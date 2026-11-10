package com.ninja6.ninjaarena.restore;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * Restores a player on join if they left, or the server stopped, while they were in an
 * arena, and on respawn if they joined dead.
 */
public final class RestoreListener implements Listener {

    private final Plugin plugin;
    private final RestoreService restores;
    private final JoinRestore joinRestore;

    public RestoreListener(Plugin plugin, RestoreService restores) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.restores = Objects.requireNonNull(restores, "restores");
        this.joinRestore = new JoinRestore(restores,
                (ticks, task) -> plugin.getServer().getScheduler().runTaskLater(plugin, task, ticks),
                id -> plugin.getServer().getPlayer(id));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // One tick later, once the join has finished and the client has the world; see
        // JoinRestore for the retries and the ordering against other plugins.
        joinRestore.onJoin(event.getPlayer().getUniqueId());
    }

    /** Another plugin moving a player right after their restore; see {@link JoinRestore}. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        joinRestore.onForeignTeleport(event.getPlayer(), event.getTo(), event.getCause());
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
        joinRestore.onQuit(event.getPlayer().getUniqueId());
        restores.markIfPending(event.getPlayer().getUniqueId());
    }
}
