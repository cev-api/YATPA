package dev.yatpa.paper.listener;

import dev.yatpa.paper.service.DataStore;
import dev.yatpa.paper.service.TeleportService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerEventListener implements Listener {
    private final TeleportService teleports;
    private final DataStore dataStore;

    public PlayerEventListener(TeleportService teleports, DataStore dataStore) {
        this.teleports = teleports;
        this.dataStore = dataStore;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        teleports.onMove(event.getPlayer());
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            teleports.onDamage(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        teleports.cancel(player.getUniqueId(), "");
        dataStore.setOfflineLocation(player.getName(), player.getLocation());
    }
}