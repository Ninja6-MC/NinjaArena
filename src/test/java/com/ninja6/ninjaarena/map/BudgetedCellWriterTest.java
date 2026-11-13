package com.ninja6.ninjaarena.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import com.ninja6.ninjaarena.terrain.BlockKind;
import com.ninja6.ninjaarena.terrain.SideDimensions;
import com.ninja6.ninjaarena.terrain.TerrainGenerator;
import com.ninja6.ninjaarena.terrain.ThemePalette;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class BudgetedCellWriterTest {

    /** Fake nanoseconds each block visited takes. */
    private static final long COST = 250;
    /** One millisecond: 4,000 blocks at {@link #COST}. */
    private static final long BUDGET = 1_000_000;
    private static final GridCell A = new GridCell(0, 0);
    private static final GridCell B = new GridCell(1, 0);
    private static final GridCell C = new GridCell(0, 1);
    private static final GridCell D = new GridCell(1, 1);

    private final FakeBlocks blocks = new FakeBlocks();
    /** Fake time outside the writer's work, moved on between ticks. */
    private long idle;
    private long budget = BUDGET;
    private final BudgetedCellWriter[] holder = new BudgetedCellWriter[1];
    private final BudgetedCellWriter writer = holder[0] = new BudgetedCellWriter(blocks, () -> budget,
            () -> idle + COST * holder[0].visited());
    private final ArenaLayout layout = new ArenaLayout(TerrainGenerator.generate(7, 3, SideDimensions.forCell(64)), 4);

    private long now() {
        return idle + COST * writer.visited();
    }

    /** One tick, checking it kept to the budget; returns the fake nanoseconds it took. */
    private long tick() {
        idle += 7_000_000;
        long start = now();
        writer.tick();
        long used = now() - start;
        assertTrue(used <= budget, "a tick never takes more than the budget: " + used + " of " + budget);
        return used;
    }

    /** Ticks until the future is done; returns how many ticks it took. */
    private int run(CompletableFuture<Void> future) {
        int ticks = 0;
        while (!future.isDone()) {
            tick();
            ticks++;
            assertTrue(ticks < 100_000, "the job ends");
        }
        return ticks;
    }

    private long volume() {
        return (long) layout.width() * layout.height() * layout.length();
    }

    private static ArenaBounds box(int x, int size) {
        return new ArenaBounds(x, 0, 0, x + size - 1, size - 1, size - 1);
    }

    @Test
    void placingWritesExactlyTheLayoutsNonAirBlocksSpreadOverTicks() {
        int x0 = 100;
        int y0 = 10;
        int z0 = -300;
        CellWriter.Work placed = writer.place(A, layout, x0, y0, z0);
        assertEquals(volume(), placed.total());
        int ticks = run(placed);
        assertTrue(ticks > 1, "a placement is spread over ticks");
        assertEquals(volume(), placed.done());
        assertEquals(volume(), writer.visited(), "every block of the box is visited once");
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
        run(writer.place(A, layout, 0, 0, 0));
        blocks.set(-1, 5, 5, Material.STONE);
        blocks.set(layout.width(), 5, 5, Material.STONE);
        ArenaBounds region = new ArenaBounds(0, 0, 0, layout.width() - 1, layout.height() - 1, layout.length() - 1);
        int ticks = run(writer.clear(A, region));
        assertTrue(ticks > 1, "a clear is spread over ticks too");
        assertEquals(0, blocks.countSolid(region));
        assertEquals(2, blocks.countSolid(), "blocks outside the region stay");
    }

    @Test
    void eachTickUsesTheBudgetAndNoMore() {
        CellWriter.Work cleared = writer.clear(A, box(0, 100));
        tick();
        for (int i = 0; i < 20; i++) {
            long used = tick();
            assertEquals(BUDGET / COST * COST, used, "a full tick uses the whole budget");
        }
        budget = 300_000;
        assertEquals(300_000, tick(), "a smaller budget is kept to from the next tick");
        budget = 123_456;
        assertTrue(tick() > 123_456 - COST, "the budget is filled to the last block that fits");
        assertFalse(cleared.isDone());
    }

    @Test
    void aBudgetOfZeroDoesNothing() {
        budget = 0;
        CellWriter.Work cleared = writer.clear(A, box(0, 10));
        for (int i = 0; i < 5; i++) {
            assertEquals(0, tick());
        }
        assertEquals(0, cleared.done());
        budget = BUDGET;
        run(cleared);
    }

    @Test
    void theBudgetIsAskedOnlyOnTicksWithWork() {
        int[] asked = new int[1];
        BudgetedCellWriter[] own = new BudgetedCellWriter[1];
        own[0] = new BudgetedCellWriter(blocks, () -> {
            asked[0]++;
            return 1000;
        }, () -> own[0].visited());
        own[0].tick();
        assertEquals(0, asked[0]);
        own[0].clear(A, box(0, 2));
        own[0].tick();
        assertEquals(1, asked[0]);
    }

    @Test
    void fourCellsMoveOnTogether() {
        List<CellWriter.Work> clears = new ArrayList<>();
        int x = 0;
        for (GridCell cell : List.of(A, B, C, D)) {
            clears.add(writer.clear(cell, box(x, 64)));
            x += 1000;
        }
        for (int i = 0; i < 40; i++) {
            tick();
            long least = clears.stream().mapToLong(CellWriter.Work::done).min().orElseThrow();
            long most = clears.stream().mapToLong(CellWriter.Work::done).max().orElseThrow();
            assertTrue(least > 0, "every cell has had a turn");
            assertTrue(most - least <= BudgetedCellWriter.SLICE, "no cell is more than a slice ahead: " + least
                    + " to " + most);
        }
        int[] finishedAt = new int[4];
        for (int tick = 1; clears.stream().anyMatch(work -> !work.isDone()); tick++) {
            tick();
            for (int i = 0; i < 4; i++) {
                if (finishedAt[i] == 0 && clears.get(i).isDone()) {
                    finishedAt[i] = tick;
                }
            }
        }
        for (int at : finishedAt) {
            assertTrue(Math.abs(at - finishedAt[0]) <= 1, "the cells finish together");
        }
    }

    @Test
    void withinACellTheClearFinishesBeforeThePlacementAfterIt() {
        // Junk the clear must remove, where the placement then writes.
        for (int x = 0; x < layout.width(); x++) {
            blocks.set(x, 1, 1, Material.DIRT);
        }
        ArenaBounds region = new ArenaBounds(0, 0, 0, layout.width() - 1, layout.height() - 1, layout.length() - 1);
        CellWriter.Work cleared = writer.clear(A, region);
        CellWriter.Work placed = writer.place(A, layout, 0, 0, 0);
        CellWriter.Work other = writer.clear(B, box(5000, 40));
        while (!cleared.isDone()) {
            tick();
            if (!cleared.isDone()) {
                assertEquals(0, placed.done(), "the placement waits for the clear");
            }
        }
        assertTrue(other.done() > 0, "the other cell moves on meanwhile");
        run(placed);
        for (int x = 0; x < layout.width(); x++) {
            for (int z = 0; z < layout.length(); z++) {
                for (int y = 0; y < layout.height(); y++) {
                    BlockKind kind = layout.at(x, y, z);
                    Material expected = kind == BlockKind.AIR ? Material.AIR
                            : ThemePalette.material(layout.side().theme(), kind);
                    assertEquals(expected, blocks.get(x, y, z), "the placement is whole, with no junk left");
                }
            }
        }
    }

    @Test
    void workQueuedOnCompletionGoesBehindTheCellsWaitingJobs() {
        List<String> order = new ArrayList<>();
        CellWriter.Work first = writer.clear(A, box(0, 4));
        CellWriter.Work second = writer.clear(A, box(100, 4));
        first.thenRun(() -> {
            order.add("first");
            writer.clear(A, box(200, 4)).thenRun(() -> order.add("third"));
        });
        second.thenRun(() -> order.add("second"));
        run(second);
        while (writer.pending() > 0) {
            tick();
        }
        assertEquals(List.of("first", "second", "third"), order);
    }

    @Test
    void jobsRunInOrderAndACancelledOneIsDropped() {
        CellWriter.Work placed = writer.place(A, layout, 0, 0, 0);
        CellWriter.Work second = writer.place(A, layout, 1000, 0, 0);
        CellWriter.Work cleared = writer.clear(A, new ArenaBounds(0, 0, 0, 4, 4, 4));
        second.cancel(false);
        tick();
        assertFalse(placed.isDone());
        assertFalse(cleared.isDone(), "the clear waits for the placement before it");
        run(cleared);
        assertTrue(placed.isDone());
        assertEquals(0, second.done());
        assertEquals(0, blocks.countSolid(new ArenaBounds(1000, 0, 0, 1000 + layout.width(), layout.height(),
                layout.length())), "the cancelled placement wrote nothing");
        assertEquals(0, writer.pending());
    }

    @Test
    void aJobThatFailsCompletesExceptionallyAndTheRestCarryOn() {
        BudgetedCellWriter[] own = new BudgetedCellWriter[1];
        BlockAccess failing = new BlockAccess() {
            @Override
            public Material get(int x, int y, int z) {
                if (x == 7) {
                    throw new IllegalStateException("chunk gone");
                }
                return blocks.get(x, y, z);
            }

            @Override
            public void set(int x, int y, int z, Material material) {
                blocks.set(x, y, z, material);
            }
        };
        own[0] = new BudgetedCellWriter(failing, () -> BUDGET, () -> COST * own[0].visited());
        CellWriter.Work broken = own[0].clear(A, box(0, 10));
        CellWriter.Work after = own[0].clear(A, box(100, 10));
        CellWriter.Work elsewhere = own[0].clear(B, box(200, 10));
        for (int i = 0; i < 10 && own[0].pending() > 0; i++) {
            own[0].tick();
        }
        ExecutionException failure = assertThrows(ExecutionException.class, broken::get);
        assertSame(IllegalStateException.class, failure.getCause().getClass());
        assertTrue(after.isDone() && !after.isCompletedExceptionally());
        assertTrue(elsewhere.isDone() && !elsewhere.isCompletedExceptionally());
    }

    @Test
    void closeCancelsEveryQueuedJobInEveryCell() {
        CellWriter.Work placed = writer.place(A, layout, 0, 0, 0);
        CellWriter.Work cleared = writer.clear(B, box(1000, 64));
        tick();
        writer.close();
        assertTrue(placed.isCancelled());
        assertTrue(cleared.isCancelled());
        assertEquals(0, writer.pending());
        assertEquals(0, writer.tick());
    }
}
