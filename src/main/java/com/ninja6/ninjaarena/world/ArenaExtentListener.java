package com.ninja6.ninjaarena.world;

import java.util.function.DoubleSupplier;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;

/** The vanilla border does not prevent admin/spectator flight or command teleports. */
public final class ArenaExtentListener implements Listener {
    private final DoubleSupplier diameter;
    private final Location fallback;

    public ArenaExtentListener(DoubleSupplier diameter, Location fallback) {
        this.diameter = diameter;
        this.fallback = Objects.requireNonNull(fallback, "fallback").clone();
    }

    private boolean outside(Location location) {
        return location != null && location.getWorld() != null
                && ArenaWorld.NAME.equals(location.getWorld().getName())
                && !ArenaExtent.contains(location.getX(), location.getZ(), diameter.getAsDouble());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (outside(event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (outside(event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        onTeleport(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        if (outside(event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        onEntityTeleport(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(AsyncPlayerSpawnLocationEvent event) {
        if (outside(event.getSpawnLocation())) {
            event.setSpawnLocation(fallback.clone());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (outside(event.getRespawnLocation())) {
            event.setRespawnLocation(fallback.clone());
        }
    }
}
