package com.ninja6.ninjaarena.restore;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Restores a player on join if they left, or the server stopped, while they were in an
 * arena, and on respawn if they joined dead.
 */
public final class RestoreListener implements Listener {

    private final Plugin plugin;
    private final RestoreService restores;

    public RestoreListener(Plugin plugin, RestoreService restores) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.restores = Objects.requireNonNull(restores, "restores");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!restores.shouldRestoreOnJoin(playerId)) {
            return;
        }
        // One tick later, once the join has finished and the client has the world.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                restores.restore(player);
            }
        });
    }

    /** Finishes a restore that was put off because the player had joined dead. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        if (restores.shouldRestoreOnRespawn(player.getUniqueId())) {
            restores.restore(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        restores.markIfPending(event.getPlayer().getUniqueId());
    }
}
