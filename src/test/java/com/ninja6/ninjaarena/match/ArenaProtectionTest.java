package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaProtectionListener;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
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
import org.junit.jupiter.api.Test;

/** Uses a real registry to exercise protection before, during and after an edit. */
class ArenaProtectionTest extends MatchFixture {

    @SuppressWarnings("unchecked")
    private static <T> T inert(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            throw new UnsupportedOperationException(m.getName());
        });
    }

    private Block block(World world) {
        return block(world, Material.STONE);
    }

    private Block block(World world, Material material) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (p, m, a) -> {
                    if (m.getName().equals("getWorld")) {
                        return world;
                    }
                    if (m.getName().equals("getType")) {
                        return material;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private void assertProtection(World world, boolean protectedWorld) {
        var listener = new ArenaProtectionListener(arenas);
        Block block = block(world);
        var entityExplosion = new EntityExplodeEvent(inert(Entity.class), new Location(world, 1000, 70, 1000),
                new ArrayList<>(List.of(block)), 0.5f, ExplosionResult.DESTROY);
        listener.onEntityExplosion(entityExplosion);
        assertEquals(protectedWorld ? 0 : 1, entityExplosion.blockList().size());
        assertFalse(entityExplosion.isCancelled());
        assertEquals(0.5f, entityExplosion.getYield());

        var blockExplosion = new BlockExplodeEvent(block, inert(BlockState.class),
                new ArrayList<>(List.of(block)), 0.75f, ExplosionResult.DESTROY);
        listener.onBlockExplosion(blockExplosion);
        assertEquals(protectedWorld ? 0 : 1, blockExplosion.blockList().size());
        assertFalse(blockExplosion.isCancelled());
        assertEquals(0.75f, blockExplosion.getYield());

        var change = new EntityChangeBlockEvent(inert(Entity.class), block, inert(BlockData.class));
        listener.onEntityChangeBlock(change);
        assertEquals(protectedWorld, change.isCancelled());

        BlockState state = inert(BlockState.class);
        List<Cancellable> blockChanges = List.of(new BlockBurnEvent(block), new BlockIgniteEvent(block,
                BlockIgniteEvent.IgniteCause.SPREAD, (Entity) null), new BlockSpreadEvent(block, block, state),
                new BlockFormEvent(block, state), new BlockGrowEvent(block, state), new BlockFadeEvent(block, state),
                new BlockFromToEvent(block, BlockFace.DOWN), new LeavesDecayEvent(block));
        listener.onBurn((BlockBurnEvent) blockChanges.get(0));
        listener.onIgnite((BlockIgniteEvent) blockChanges.get(1));
        listener.onSpread((BlockSpreadEvent) blockChanges.get(2));
        listener.onForm((BlockFormEvent) blockChanges.get(3));
        listener.onGrow((BlockGrowEvent) blockChanges.get(4));
        listener.onFade((BlockFadeEvent) blockChanges.get(5));
        listener.onFlow((BlockFromToEvent) blockChanges.get(6));
        listener.onLeavesDecay((LeavesDecayEvent) blockChanges.get(7));
        for (Cancellable event : blockChanges) {
            assertEquals(protectedWorld, event.isCancelled(), event.getClass().getSimpleName());
        }

        var extend = new BlockPistonExtendEvent(block, List.of(), BlockFace.EAST);
        listener.onPistonExtend(extend);
        assertEquals(protectedWorld, extend.isCancelled());
        var retract = new BlockPistonRetractEvent(block, List.of(), BlockFace.WEST);
        listener.onPistonRetract(retract);
        assertEquals(protectedWorld, retract.isCancelled());
        for (Material material : List.of(Material.PISTON, Material.STICKY_PISTON,
                Material.PISTON_HEAD, Material.MOVING_PISTON, Material.STONE)) {
            var physics = new BlockPhysicsEvent(block(world, material), inert(BlockData.class));
            listener.onPistonPhysics(physics);
            assertEquals(protectedWorld && material != Material.STONE, physics.isCancelled(), material.toString());
        }
        for (int[] currents : List.of(new int[] {0, 15}, new int[] {15, 0}, new int[] {15, 7})) {
            var redstone = new BlockRedstoneEvent(block, currents[0], currents[1]);
            listener.onRedstone(redstone);
            assertEquals(protectedWorld ? currents[0] : currents[1], redstone.getNewCurrent());
        }
    }

    @Test
    void protectsTheEntireArenaWorldWithoutAMatchAndLeavesOtherWorldsAlone() {
        assertTrue(matches.matches().isEmpty());
        assertProtection(arenaWorld, true);
        assertProtection(overworld, false);
    }

    @Test
    void allEnvironmentalProtectionStopsDuringEditingAndResumesWhenItFinishes() throws Exception {
        assertProtection(arenaWorld, true);
        arenas.startEdit("dojo");
        assertFalse(arenas.editing().isEmpty());
        assertProtection(arenaWorld, false);
        arenas.finishEdit("dojo");
        assertTrue(arenas.editing().isEmpty());
        assertProtection(arenaWorld, true);
    }

    @Test
    void theLastEditMustFinishBeforeProtectionResumes() throws Exception {
        arenas.startEdit("dojo");
        arenas.create("building", null);
        arenas.finishEdit("dojo");
        assertProtection(arenaWorld, false);
        arenas.delete("building");
        assertProtection(arenaWorld, true);
    }
}
