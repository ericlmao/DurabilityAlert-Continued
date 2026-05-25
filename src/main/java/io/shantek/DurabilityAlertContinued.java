package io.shantek;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class DurabilityAlertContinued extends JavaPlugin {

    private static DurabilityAlertContinued plugin;

    // Default settings
    private static boolean enableByDefault;
    private static int defaultValue;
    private static PlayerSettings.AlertType defaultType;
    private static boolean defaultEnchanted;
    private static int displayTime;

    // Prefix used in messages
    public static String prefix = "&f&l[&9&lDurabilityAlert&f&l] ";

    // Cache to store player settings
    private final Cache<UUID, PlayerSettings> playerData = Caffeine.newBuilder()
            .maximumSize(10_000)
            .build();
    private final Set<UUID> dirtyPlayerData = ConcurrentHashMap.newKeySet();

    // Listeners
    JoinListener joinListener;
    ConfigHandler configHandler;
    private PlayerSettingsRepository playerSettingsRepository;

    @Override
    public void onEnable() {
        plugin = this;

        // Load default config if not present
        saveDefaultConfig();

        enableByDefault = getConfig().getBoolean("enabled-by-default", false);
        defaultValue = getConfig().getInt("defaultvalue", 20);
        defaultType = PlayerSettings.AlertType.valueOf(getConfig().getString("defaulttype", "PERCENT").toUpperCase());
        defaultEnchanted = getConfig().getBoolean("defaultenchanted", false);
        displayTime = getConfig().getInt("displaytime", 10); // Set default if not in config

        playerSettingsRepository = new PlayerSettingsRepository(this);
        try {
            playerSettingsRepository.init();
        } catch (IllegalStateException exception) {
            getLogger().log(Level.SEVERE, "Failed to initialize player settings storage.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize listeners and config handler
        joinListener = new JoinListener(this);
        configHandler = new ConfigHandler(this);

        // Register events
        getServer().getPluginManager().registerEvents(joinListener, this);
        // Register the DurabilityListener
        getServer().getPluginManager().registerEvents(new DurabilityListener(this), this);

        registerDurabilityAlertCommand();

        // Perform any additional setup when the server starts
        joinListener.onServerStart();
    }

    @Override
    public void onDisable() {
        if (joinListener != null) {
            joinListener.onServerStop();
        }

        if (playerSettingsRepository != null) {
            playerSettingsRepository.close();
        }
        playerData.invalidateAll();
        playerData.cleanUp();
    }

    public static DurabilityAlertContinued getInstance() {
        return plugin;
    }

    // Method to retrieve player settings or create default settings if not present
    public PlayerSettings getPlayerSettings(Player player) {
        return playerData.get(player.getUniqueId(), uuid -> new PlayerSettings());
    }

    // Method to store player settings
    public void setPlayerData(Player player, PlayerSettings settings) {
        setPlayerData(player.getUniqueId(), settings);
    }

    public void setPlayerData(UUID playerId, PlayerSettings settings) {
        playerData.put(playerId, settings);
    }

    // Method to remove player settings from the playerData map when they quit
    public void removePlayerSettings(Player player) {
        UUID playerId = player.getUniqueId();
        playerData.invalidate(playerId);
        dirtyPlayerData.remove(playerId);
    }

    public boolean isPlayerOnline(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline();
    }

    public void loadPlayerSettings(Player player) {
        UUID playerId = player.getUniqueId();
        playerSettingsRepository.loadAsync(playerId).thenAccept(optionalSettings ->
                Bukkit.getGlobalRegionScheduler().run(this, task -> {
                    Player onlinePlayer = Bukkit.getPlayer(playerId);
                    if (onlinePlayer != null && onlinePlayer.isOnline() && !dirtyPlayerData.contains(playerId)) {
                        setPlayerData(playerId, optionalSettings.orElseGet(PlayerSettings::new));
                    }
                })
        ).exceptionally(exception -> {
            getLogger().log(Level.SEVERE, "Failed to load player settings for " + playerId + ".", exception);
            return null;
        });
    }

    public CompletableFuture<Void> savePlayerSettings(Player player) {
        return savePlayerSettings(player.getUniqueId(), getPlayerSettings(player));
    }

    public CompletableFuture<Void> savePlayerSettings(UUID playerId, PlayerSettings settings) {
        return playerSettingsRepository.saveAsync(playerId, settings);
    }

    public void saveOnlinePlayerSettings() {
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            saves.add(savePlayerSettings(player));
        }

        CompletableFuture<Void> combined = CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
        try {
            combined.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "Timed out while saving player settings during shutdown.", exception);
        }
    }

    private void registerDurabilityAlertCommand() {
        CommandHandler commandHandler = new CommandHandler();
        DurabilityTabCompleter tabCompleter = new DurabilityTabCompleter();
        Command command = new Command(
                "durabilityalert",
                "the base durabilityalert command",
                "/<command>",
                List.of("da")
        ) {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return commandHandler.onCommand(sender, this, commandLabel, args);
            }

            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                List<String> completions = tabCompleter.onTabComplete(sender, this, alias, args);
                return completions == null ? List.of() : completions;
            }
        };
        command.setPermission("shantek.durabilityalert.use");
        command.setPermissionMessage("You do not have permission!");
        getServer().getCommandMap().register("durabilityalert", command);
    }

    // Method to set the alert type for a player (e.g., percent or durability)
    public void setPlayerAlertType(Player player, PlayerSettings.AlertType alertType) {
        PlayerSettings settings = getPlayerSettings(player);
        settings.setAlertType(alertType);
        markPlayerSettingsDirty(player);
        setPlayerData(player, settings);
    }

    // Method to set the armor threshold for a player
    public void setPlayerArmorThreshold(Player player, int threshold) {
        PlayerSettings settings = getPlayerSettings(player);
        settings.setArmorThreshold(threshold);
        markPlayerSettingsDirty(player);
        setPlayerData(player, settings);
    }

    // Method to set the tools threshold for a player
    public void setPlayerToolsThreshold(Player player, int threshold) {
        PlayerSettings settings = getPlayerSettings(player);
        settings.setToolsThreshold(threshold);
        markPlayerSettingsDirty(player);
        setPlayerData(player, settings);
    }

    // Toggle specific player settings (warnings enabled, enchanted items only, sound enabled)
    public void togglePlayerSetting(Player player, Setting setting) {
        PlayerSettings settings = getPlayerSettings(player);

        switch (setting) {
            case WARNINGS_ENABLED:
                settings.setWarningsEnabled(!settings.isWarningsEnabled());
                break;
            case ENCHANTED_ITEMS_ONLY:
                settings.setEnchantedItemsOnly(!settings.isEnchantedItemsOnly());
                break;
            case SOUND_ENABLED:
                settings.setSoundEnabled(!settings.isSoundEnabled());
                break;
        }

        markPlayerSettingsDirty(player);
        setPlayerData(player, settings);
    }

    private void markPlayerSettingsDirty(Player player) {
        dirtyPlayerData.add(player.getUniqueId());
    }

    // Enum to represent different toggleable settings
    public enum Setting {
        WARNINGS_ENABLED,
        ENCHANTED_ITEMS_ONLY,
        SOUND_ENABLED
    }

    // Getter for default settings
    public static boolean isEnableByDefault() {
        return enableByDefault;
    }

    public static int getDefaultValue() {
        return defaultValue;
    }

    public static PlayerSettings.AlertType getDefaultType() {
        return defaultType;
    }

    public static boolean isDefaultEnchanted() {
        return defaultEnchanted;
    }

    public static int getDisplayTime() {
        return displayTime;
    }
}
