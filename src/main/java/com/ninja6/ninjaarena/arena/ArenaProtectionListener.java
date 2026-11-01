package com.ninja6.ninjaarena.arena;

import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.bukkit.World;
import org.bukkit.Material;
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
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Environmental changes are blocked throughout the arena world, except during editing.
 *
 * <p>Only the blocks inside an arena's bounds are copied and put back, so nothing a match
 * sets off may change a block outside them: explosions, mobs, fire, fluids, growth, decay
 * and melting are all stopped. Piston physics and redstone current changes are frozen
 * before a sticky retract can mutate its head or base. Match players are in adventure mode and cannot place or
 * break blocks themselves.
 */
public final class ArenaProtectionListener implements Listener {

    private final BooleanSupplier protectionEnabled;

    public ArenaProtectionListener(ArenaRegistry arenas) {
        Objects.requireNonNull(arenas, "arenas");
        this.protectionEnabled = () -> arenas.editing().isEmpty();
    }

    /** Can be registered before initial registry restoration starts. */
    public ArenaProtectionListener(BooleanSupplier protectionEnabled) {
        this.protectionEnabled = Objects.requireNonNull(protectionEnabled, "protectionEnabled");
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

    /** Prevents queuing a retract: sticky pistons change their base/head before their event. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonPhysics(BlockPhysicsEvent event) {
        if (protectedWorld(event.getBlock().getWorld()) && piston(event.getBlock().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRedstone(BlockRedstoneEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.setNewCurrent(event.getOldCurrent());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        cancelInArenaWorld(event.getBlock().getWorld(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        cancelInArenaWorld(event.getBlock().getWorld(), event::setCancelled);
    }

    private static boolean piston(Material material) {
        return material == Material.PISTON || material == Material.STICKY_PISTON
                || material == Material.PISTON_HEAD || material == Material.MOVING_PISTON;
    }

    private void cancelInArenaWorld(World world, Consumer<Boolean> cancel) {
        if (protectedWorld(world)) {
            cancel.accept(true);
        }
    }

    private boolean protectedWorld(World world) {
        return world != null && ArenaWorld.NAME.equals(world.getName()) && protectionEnabled.getAsBoolean();
    }
}
