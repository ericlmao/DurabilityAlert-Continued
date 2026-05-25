package io.shantek;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class PlayerSettingsRepository {

    private static final String DATABASE_FILE = "player-data.db";
    private static final String LEGACY_FILE = "PlayerData.yml";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DurabilityAlertContinued plugin;
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private volatile boolean available;

    public PlayerSettingsRepository(DurabilityAlertContinued plugin) {
        this.plugin = plugin;
    }

    public synchronized void init() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Failed to create DurabilityAlert data folder.");
        }

        File databaseFile = new File(plugin.getDataFolder(), DATABASE_FILE);
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setPoolName("DurabilityAlert-SQLite");

        dataSource = new HikariDataSource(hikariConfig);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "DurabilityAlert-SQLite");
            thread.setDaemon(true);
            return thread;
        });

        try {
            createTables();
            available = true;
            migrateLegacyYaml();
            plugin.getLogger().info("Connected to DurabilityAlert SQLite database.");
        } catch (SQLException exception) {
            available = false;
            close();
            throw new IllegalStateException("Failed to initialize DurabilityAlert SQLite database.", exception);
        }
    }

    public CompletableFuture<Optional<PlayerSettings>> loadAsync(UUID playerId) {
        return supplyAsync(() -> load(playerId));
    }

    public CompletableFuture<Void> saveAsync(UUID playerId, PlayerSettings settings) {
        PlayerSettings snapshot = settings.copy();
        return runAsync(() -> save(playerId, snapshot));
    }

    public synchronized void close() {
        available = false;

        ExecutorService currentExecutor = executor;
        executor = null;
        if (currentExecutor != null) {
            currentExecutor.shutdown();
            try {
                if (!currentExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    currentExecutor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                currentExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        HikariDataSource currentDataSource = dataSource;
        dataSource = null;
        if (currentDataSource != null) {
            currentDataSource.close();
        }
    }

    private void createTables() throws SQLException {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_settings (
                        player_uuid TEXT PRIMARY KEY,
                        warnings_enabled INTEGER NOT NULL,
                        armor_threshold INTEGER NOT NULL,
                        tools_threshold INTEGER NOT NULL,
                        alert_type TEXT NOT NULL,
                        enchanted_items_only INTEGER NOT NULL,
                        sound_enabled INTEGER NOT NULL
                    )
                    """);
        }
    }

    private void migrateLegacyYaml() throws SQLException {
        File legacyFile = new File(plugin.getDataFolder(), LEGACY_FILE);
        if (!legacyFile.isFile()) {
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(legacyFile);
        ConfigurationSection players = config.getConfigurationSection("player");
        int migrated = 0;
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID playerId;
                try {
                    playerId = UUID.fromString(key);
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Skipping invalid UUID in PlayerData.yml: " + key);
                    continue;
                }

                String path = "player." + key;
                PlayerSettings settings = readLegacySettings(config, path);
                save(playerId, settings);
                if (plugin.isPlayerOnline(playerId)) {
                    plugin.setPlayerData(playerId, settings);
                }
                migrated++;
            }
        }

        File backupFile = legacyBackupFile();
        if (!legacyFile.renameTo(backupFile)) {
            throw new SQLException("Migrated PlayerData.yml but failed to rename it to " + backupFile.getName());
        }
        plugin.getLogger().info("Migrated " + migrated + " player setting(s) from PlayerData.yml to SQLite.");
    }

    private PlayerSettings readLegacySettings(FileConfiguration config, String path) {
        boolean warningsEnabled = config.getBoolean(path + ".warningsEnabled", DurabilityAlertContinued.isEnableByDefault());
        int armorThreshold = config.getInt(path + ".armorThreshold", DurabilityAlertContinued.getDefaultValue());
        int toolsThreshold = config.getInt(path + ".toolsThreshold", DurabilityAlertContinued.getDefaultValue());
        PlayerSettings.AlertType alertType = parseAlertType(config.getString(path + ".alertType"));
        boolean enchantedItemsOnly = config.getBoolean(path + ".enchantedItemsOnly", DurabilityAlertContinued.isDefaultEnchanted());
        boolean soundEnabled = config.getBoolean(path + ".soundEnabled", true);
        return new PlayerSettings(warningsEnabled, armorThreshold, toolsThreshold, alertType, enchantedItemsOnly, soundEnabled);
    }

    private PlayerSettings.AlertType parseAlertType(String value) {
        if (value == null) {
            return DurabilityAlertContinued.getDefaultType();
        }

        try {
            return PlayerSettings.AlertType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid alert type in PlayerData.yml: " + value + ". Using configured default.");
            return DurabilityAlertContinued.getDefaultType();
        }
    }

    private File legacyBackupFile() {
        File backupFile = new File(plugin.getDataFolder(), LEGACY_FILE + ".migrated");
        if (!backupFile.exists()) {
            return backupFile;
        }
        return new File(plugin.getDataFolder(), LEGACY_FILE + ".migrated-" + LocalDateTime.now().format(BACKUP_TIMESTAMP));
    }

    private Optional<PlayerSettings> load(UUID playerId) throws SQLException {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT warnings_enabled, armor_threshold, tools_threshold, alert_type, enchanted_items_only, sound_enabled
                     FROM player_settings
                     WHERE player_uuid = ?
                     """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }

                PlayerSettings.AlertType alertType;
                try {
                    alertType = PlayerSettings.AlertType.valueOf(result.getString("alert_type"));
                } catch (IllegalArgumentException exception) {
                    alertType = DurabilityAlertContinued.getDefaultType();
                }

                return Optional.of(new PlayerSettings(
                        result.getInt("warnings_enabled") != 0,
                        result.getInt("armor_threshold"),
                        result.getInt("tools_threshold"),
                        alertType,
                        result.getInt("enchanted_items_only") != 0,
                        result.getInt("sound_enabled") != 0
                ));
            }
        }
    }

    private void save(UUID playerId, PlayerSettings settings) throws SQLException {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO player_settings (
                         player_uuid,
                         warnings_enabled,
                         armor_threshold,
                         tools_threshold,
                         alert_type,
                         enchanted_items_only,
                         sound_enabled
                     ) VALUES (?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(player_uuid) DO UPDATE SET
                         warnings_enabled = excluded.warnings_enabled,
                         armor_threshold = excluded.armor_threshold,
                         tools_threshold = excluded.tools_threshold,
                         alert_type = excluded.alert_type,
                         enchanted_items_only = excluded.enchanted_items_only,
                         sound_enabled = excluded.sound_enabled
                     """)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, settings.isWarningsEnabled() ? 1 : 0);
            statement.setInt(3, settings.getArmorThreshold());
            statement.setInt(4, settings.getToolsThreshold());
            statement.setString(5, settings.getAlertType().name());
            statement.setInt(6, settings.isEnchantedItemsOnly() ? 1 : 0);
            statement.setInt(7, settings.isSoundEnabled() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private Connection getConnection() throws SQLException {
        HikariDataSource current = dataSource;
        if (current == null || current.isClosed()) {
            throw new SQLException("SQLite data source is not available");
        }
        return current.getConnection();
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        ExecutorService currentExecutor = executor;
        if (!available || currentExecutor == null || currentExecutor.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("SQLite executor is not available"));
        }

        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return supplier.get();
                } catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            }, currentExecutor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(new IllegalStateException("SQLite executor is not available", exception));
        }
    }

    private CompletableFuture<Void> runAsync(SqlRunnable runnable) {
        ExecutorService currentExecutor = executor;
        if (!available || currentExecutor == null || currentExecutor.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("SQLite executor is not available"));
        }

        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    runnable.run();
                } catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            }, currentExecutor).exceptionally(exception -> {
                plugin.getLogger().log(Level.SEVERE, "Failed to save player settings.", exception);
                return null;
            });
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(new IllegalStateException("SQLite executor is not available", exception));
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }
}
