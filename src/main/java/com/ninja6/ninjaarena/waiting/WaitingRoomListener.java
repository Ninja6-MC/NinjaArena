package com.ninja6.ninjaarena.waiting;

import com.ninja6.ninjaarena.waiting.WaitingRoomService.Exit;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Notices a player leaving their waiting room. Waiting players also take no damage, and one
 * who dies anyway keeps their inventory and experience, since the restore puts back what
 * they had before they came in and a drop would duplicate it.
 */
public final class WaitingRoomListener implements Listener {

    private final WaitingRoomService rooms;

    public WaitingRoomListener(WaitingRoomService rooms) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        if (rooms.isWaiting(player.getUniqueId()) && !rooms.inRoom(player.getUniqueId(), event.getTo())) {
            rooms.leave(player, Exit.WALKED_OUT);
        }
    }

    /** Also sees vanilla portal travel, whose event is a teleport event. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (rooms.isWaiting(player.getUniqueId()) && !rooms.inRoom(player.getUniqueId(), event.getTo())) {
            rooms.leave(player, Exit.TELEPORTED);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && rooms.isWaiting(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!rooms.isWaiting(player.getUniqueId())) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        rooms.leave(player, Exit.DIED);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        rooms.leave(event.getPlayer(), Exit.QUIT);
    }
}
