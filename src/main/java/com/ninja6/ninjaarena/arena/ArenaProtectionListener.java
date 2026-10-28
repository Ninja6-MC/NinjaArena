package com.ninja6.ninjaarena.arena;

import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Environmental changes are blocked throughout the arena world, except during editing.
 *
 * <p>Only the blocks inside an arena's bounds are copied and put back, so nothing a match
 * sets off may change a block outside them: explosions, mobs, fire, fluids, growth, decay
 * and melting are all stopped. Match players are in adventure mode and cannot place or
 * break blocks themselves.
 */
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        cancelInArenaWorld(event.getBlock().getWorld(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        cancelInArenaWorld(event.getBlock().getWorld(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        cancelInArenaWorld(event.getBlock().getWorld(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        cancelInArenaWorld(event.getBlock().getWorld(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        cancelInArenaWorld(event.getBlock().getWorld(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        cancelInArenaWorld(event.getBlock().getWorld(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        cancelInArenaWorld(event.getBlock().getWorld(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        cancelInArenaWorld(event.getBlock().getWorld(), event::setCancelled);
    }

    private void cancelInArenaWorld(World world, Consumer<Boolean> cancel) {
        if (protectedWorld(world)) {
            cancel.accept(true);
        }
    }

    private boolean protectedWorld(World world) {
        return world != null && ArenaWorld.NAME.equals(world.getName()) && arenas.editing().isEmpty();
    }
}
