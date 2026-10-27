package com.ninja6.ninjaarena.arena;

import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.Objects;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/** Environmental changes are blocked throughout the arena world, except during editing. */
public final class ArenaProtectionListener implements Listener {

    private final ArenaRegistry arenas;

    public ArenaProtectionListener(ArenaRegistry arenas) {
        this.arenas = Objects.requireNonNull(arenas, "arenas");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (protectedWorld(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    private boolean protectedWorld(World world) {
        return world != null && ArenaWorld.NAME.equals(world.getName()) && arenas.editing().isEmpty();
    }
}
