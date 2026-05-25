package io.shantek;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class JoinListener implements Listener {

    private final DurabilityAlertContinued main;

    public JoinListener(DurabilityAlertContinued plugin) {
        main = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        main.loadPlayerSettings(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        playerSave(player);
        main.removePlayerSettings(player);
    }

    public void onServerStop() {
        main.saveOnlinePlayerSettings();
    }

    public void onServerStart() {
        main.getServer().getOnlinePlayers().forEach(main::loadPlayerSettings);
    }

    void playerSave(Player player) {
        main.savePlayerSettings(player);
    }
}
