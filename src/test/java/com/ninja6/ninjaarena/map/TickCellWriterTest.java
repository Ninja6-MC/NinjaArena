package com.ninja6.ninjaarena.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import com.ninja6.ninjaarena.terrain.BlockKind;
import com.ninja6.ninjaarena.terrain.SideDimensions;
import com.ninja6.ninjaarena.terrain.TerrainGenerator;
import com.ninja6.ninjaarena.terrain.ThemePalette;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class TickCellWriterTest {

    private static final int BUDGET = 5_000;

    private final FakeBlocks blocks = new FakeBlocks();
    private final TickCellWriter writer = new TickCellWriter(blocks, BUDGET);
    private final ArenaLayout layout = new ArenaLayout(TerrainGenerator.generate(7, 3, SideDimensions.forCell(64)), 4);

    /** Ticks until the future is done; returns how many ticks it took. */
    private int run(CompletableFuture<Void> future) {
        int ticks = 0;
        while (!future.isDone()) {
            int visited = writer.tick();
            assertTrue(visited <= BUDGET, "never more than the budget in a tick: " + visited);
            ticks++;
            assertTrue(ticks < 10_000, "the job ends");
        }
        return ticks;
    }

    private long volume() {
        return (long) layout.width() * layout.height() * layout.length();
    }

    @Test
    void placingWritesExactlyTheLayoutsNonAirBlocksSpreadOverTicks() {
        int x0 = 100;
        int y0 = 10;
        int z0 = -300;
        int ticks = run(writer.place(layout, x0, y0, z0));
        assertEquals((volume() + BUDGET - 1) / BUDGET, ticks, "every block of the box is visited once");
        long expected = 0;
        for (int x = 0; x < layout.width(); x++) {
            for (int z = 0; z < layout.length(); z++) {
                for (int y = 0; y < layout.height(); y++) {
                    BlockKind kind = layout.at(x, y, z);
                    Material material = blocks.get(x0 + x, y0 + y, z0 + z);
                    if (kind == BlockKind.AIR) {
                        assertEquals(Material.AIR, material);
                    } else {
                        expected++;
                        assertEquals(ThemePalette.material(layout.side().theme(), kind), material);
                    }
                }
            }
        }
        assertTrue(expected > 0);
        assertEquals(expected, blocks.countSolid(), "nothing is written outside the layout");
        assertEquals(expected, blocks.writes, "air is never written");
    }

    @Test
    void clearingEmptiesTheRegionFullyAndNothingElse() {
        run(writer.place(layout, 0, 0, 0));
        blocks.set(-1, 5, 5, Material.STONE);
        blocks.set(layout.width(), 5, 5, Material.STONE);
        ArenaBounds region = new ArenaBounds(0, 0, 0, layout.width() - 1, layout.height() - 1, layout.length() - 1);
        int ticks = run(writer.clear(region));
        assertTrue(ticks > 1, "a clear is spread over ticks too");
        assertEquals(0, blocks.countSolid(region));
        assertEquals(2, blocks.countSolid(), "blocks outside the region stay");
    }

    @Test
    void jobsRunInOrderAndACancelledOneIsDropped() {
        CompletableFuture<Void> placed = writer.place(layout, 0, 0, 0);
        CompletableFuture<Void> second = writer.place(layout, 1000, 0, 0);
        ArenaBounds region = new ArenaBounds(0, 0, 0, 4, 4, 4);
        CompletableFuture<Void> cleared = writer.clear(region);
        second.cancel(false);
        writer.tick();
        assertFalse(placed.isDone());
        assertFalse(cleared.isDone(), "the clear waits for the placement before it");
        run(cleared);
        assertTrue(placed.isDone());
        assertEquals(0, blocks.countSolid(new ArenaBounds(1000, 0, 0, 1000 + layout.width(), layout.height(),
                layout.length())), "the cancelled placement wrote nothing");
        assertEquals(0, writer.pending());
    }

    @Test
    void closeCancelsEveryQueuedJob() {
        CompletableFuture<Void> placed = writer.place(layout, 0, 0, 0);
        writer.tick();
        writer.close();
        assertTrue(placed.isCancelled());
        assertEquals(0, writer.pending());
        assertEquals(0, writer.tick());
    }
}
