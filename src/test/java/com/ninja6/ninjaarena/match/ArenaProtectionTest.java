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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.BlockExplodeEvent;
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
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (p, m, a) -> {
                    if (m.getName().equals("getWorld")) {
                        return world;
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
