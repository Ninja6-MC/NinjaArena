package com.ninja6.ninjaarena.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaValidator;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import com.ninja6.ninjaarena.terrain.BlockKind;
import com.ninja6.ninjaarena.terrain.MapSearch;
import com.ninja6.ninjaarena.terrain.SideDimensions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class GeneratedMapsTest {

    private static final ArenaGrid GRID = new ArenaGrid(256, 2048, -64, 320);

    private final FakeBlocks blocks = new FakeBlocks();
    private final TickCellWriter writer = new TickCellWriter(blocks, 1 << 20);
    private final Set<GridCell> handBuilt = new HashSet<>();
    private final List<GeneratedMaps.Prepared> outcomes = new ArrayList<>();

    private static Logger quiet() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }

    /** No cell reserved yet. */
    private GeneratedMaps fresh() {
        return new GeneratedMaps(GRID, () -> handBuilt, () -> true, writer, blocks.ticketsView(), Runnable::run,
                Runnable::run, quiet());
    }

    /** Cells reserved and cleared, as on startup. */
    private GeneratedMaps maps(int count) {
        GeneratedMaps maps = fresh();
        maps.resize(count);
        drain();
        return maps;
    }

    private void drain() {
        for (int i = 0; i < 1000 && writer.pending() > 0; i++) {
            writer.tick();
        }
        assertEquals(0, writer.pending());
    }

    private static long nonAir(ArenaLayout layout) {
        long count = 0;
        for (int x = 0; x < layout.width(); x++) {
            for (int z = 0; z < layout.length(); z++) {
                for (int y = 0; y < layout.height(); y++) {
                    if (layout.at(x, y, z) != BlockKind.AIR) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private void junk(GeneratedMaps maps) {
        for (GridCell cell : maps.cells()) {
            ArenaBounds region = maps.region(cell);
            blocks.set(region.minX(), region.minY(), region.minZ(), Material.STONE);
            blocks.set(region.maxX(), region.maxY(), region.maxZ(), Material.LAVA);
        }
    }

    private GeneratedMaps.Ready ready(GeneratedMaps maps, String name, long seed) {
        maps.prepare(name, new GeneratedMaps.Request(seed, 1, 2), outcomes::add);
        drain();
        return assertInstanceOf(GeneratedMaps.Ready.class, outcomes.getLast());
    }

    @Test
    void startupClearsEverySlotCellAndTakesItsTicketsOffAfterwards() {
        GeneratedMaps maps = fresh();
        assertEquals(List.of(), maps.cells(), "nothing is reserved until the slot count is applied");
        maps.resize(2);
        junk(maps);
        blocks.set(0, 64, 0, Material.STONE);
        assertEquals(maps.cells(), maps.inUse(), "a cell being cleared is in use");
        assertFalse(blocks.tickets.isEmpty(), "tickets are on while the cells are cleared");
        drain();
        for (GridCell cell : maps.cells()) {
            assertEquals(0, blocks.countSolid(maps.region(cell)));
        }
        assertEquals(1, blocks.countSolid(), "nothing outside the slot cells is touched");
        assertTrue(blocks.tickets.isEmpty());
        assertEquals(List.of(), maps.inUse());
        assertEquals(2, maps.freeCells());
    }

    @Test
    void aPreparedMapIsAnArenaInItsCellWithTicketsTakenBeforeAnyBlock() {
        GeneratedMaps maps = maps(2);
        maps.prepare("generated:1", new GeneratedMaps.Request(42, 1, 2), outcomes::add);
        assertFalse(blocks.tickets.isEmpty());
        assertEquals(0, blocks.writes, "no block is written before the tickets are on");
        assertTrue(maps.get("generated:1").isEmpty(), "not placed yet");
        assertFalse(maps.isAvailable("generated:1"));
        drain();

        GeneratedMaps.Ready ready = assertInstanceOf(GeneratedMaps.Ready.class, outcomes.getLast());
        Arena arena = ready.arena();
        GridCell cell = maps.cellOf("generated:1").orElseThrow();
        assertEquals("generated:1", arena.name());
        assertEquals(cell, arena.cell());
        assertTrue(maps.isReserved(cell));
        assertEquals(List.of(), ArenaValidator.problems(arena, GRID));
        assertTrue(maps.region(cell).contains(arena.bounds()));
        assertEquals(2, arena.teamSpawns().size());
        assertFalse(arena.enemySpawns().isEmpty());
        arena.enemySpawns().forEach(point -> assertTrue(arena.bounds().contains(point)));
        arena.teamSpawns().forEach(point -> assertTrue(arena.bounds().contains(point)));
        assertTrue(arena.bounds().contains(arena.waitingRoom()));
        assertEquals(42, ready.seed());

        MapSearch.Accepted accepted = assertInstanceOf(MapSearch.Accepted.class, GeneratedMaps.Search.DEFAULT
                .search(42, 1, 2, SideDimensions.forCell(GRID.cellSize())));
        assertEquals(nonAir(accepted.layout()), blocks.countSolid(maps.region(cell)));
        assertEquals(nonAir(accepted.layout()), blocks.countSolid(), "nothing is placed outside the cell");
        assertEquals(arena, maps.get("generated:1").orElseThrow());
        assertTrue(maps.isAvailable("generated:1"));
        assertEquals(List.of(cell), maps.inUse());
    }

    @Test
    void aMatchHoldsTheMapAndFreeingItClearsTheCellAndTakesTheTicketsOff() throws ArenaException {
        GeneratedMaps maps = maps(2);
        ready(maps, "generated:1", 42);
        GridCell cell = maps.cellOf("generated:1").orElseThrow();
        maps.hold("generated:1");
        assertTrue(maps.isHeld("generated:1"));
        assertFalse(maps.isAvailable("generated:1"));
        assertThrows(ArenaException.class, () -> maps.hold("generated:1"));
        maps.reset("generated:1");
        maps.release("generated:1");
        assertTrue(maps.isAvailable("generated:1"));

        maps.free("generated:1");
        assertTrue(maps.get("generated:1").isEmpty());
        assertEquals(List.of(cell), maps.inUse(), "in use until it is cleared");
        drain();
        assertEquals(0, blocks.countSolid());
        assertTrue(blocks.tickets.isEmpty());
        assertEquals(List.of(), maps.inUse());
        assertEquals(2, maps.freeCells());
        assertThrows(ArenaException.class, () -> maps.hold("generated:1"));
    }

    @Test
    void freeingAMapMidPlacementDropsTheRestAndClearsTheCell() {
        GeneratedMaps maps = maps(1);
        maps.prepare("generated:1", new GeneratedMaps.Request(42, 1, 2), outcomes::add);
        // The cleared region is empty, so the first block written is the placement's.
        for (int i = 0; i < 100 && blocks.writes == 0; i++) {
            writer.tick();
        }
        assertTrue(blocks.writes > 0 && writer.pending() > 0, "placement has started and is not done");
        maps.free("generated:1");
        drain();
        assertEquals(List.of(), outcomes, "a freed map is never reported");
        assertEquals(0, blocks.countSolid());
        assertTrue(blocks.tickets.isEmpty());
        assertEquals(1, maps.freeCells());
    }

    @Test
    void generatedMapsLeaveNothingBehindAfterARestart() {
        GeneratedMaps before = maps(2);
        ready(before, "generated:1", 42);
        ready(before, "generated:2", 43);
        // Disable: queued work stops and the tickets come off; the world save keeps the blocks.
        before.close();
        assertTrue(blocks.tickets.isEmpty());
        assertTrue(blocks.countSolid() > 0);

        GeneratedMaps after = fresh();
        after.resize(2);
        assertEquals(before.cells(), after.cells(), "the same cells after a restart");
        drain();
        assertEquals(0, blocks.countSolid());
        assertTrue(blocks.tickets.isEmpty());
        assertTrue(after.get("generated:1").isEmpty());
    }

    @Test
    void generatedMapsLeaveNothingBehindAfterTheWorldIsRetiredAndReopened() {
        GeneratedMaps maps = maps(2);
        ready(maps, "generated:1", 42);
        maps.free("generated:1");
        drain();
        // Retired without saving: the chunks come back as an earlier world save had them.
        junk(maps);
        maps.reopened();
        drain();
        assertEquals(0, blocks.countSolid());
        assertTrue(blocks.tickets.isEmpty());
    }

    @Test
    void aLowerCountNeverTakesTheCellOfAMapAndAHigherOneAddsClearedCells() {
        GeneratedMaps maps = maps(3);
        List<GridCell> before = maps.cells();
        ready(maps, "generated:1", 42);
        ready(maps, "generated:2", 43);
        GridCell second = maps.cellOf("generated:2").orElseThrow();
        assertEquals(before.get(1), second);

        maps.resize(3);
        assertEquals(before, maps.cells(), "an unchanged count moves nothing");
        maps.resize(1);
        assertEquals(List.of(before.get(0), second), maps.cells());
        assertTrue(maps.get("generated:2").isPresent(), "the map keeps its cell");
        maps.free("generated:2");
        drain();
        assertEquals(List.of(before.get(0)), maps.cells(), "given up once free");

        junk(maps);
        blocks.set(maps.region(before.get(2)).minX(), maps.region(before.get(2)).minY(),
                maps.region(before.get(2)).minZ(), Material.STONE);
        maps.resize(3);
        assertEquals(before, maps.cells());
        drain();
        assertEquals(0, blocks.countSolid(maps.region(before.get(2))), "a newly reserved cell is cleared");
    }

    @Test
    void aFailedSearchKeepsTheCellForAnotherSeed() {
        GeneratedMaps maps = maps(1);
        maps.setSearch((seed, difficulty, teams, size) -> seed == 13
                ? new MapSearch.Failed(13, List.of()) : GeneratedMaps.Search.DEFAULT.search(seed, difficulty, teams, size));
        maps.prepare("generated:1", new GeneratedMaps.Request(13, 2, 2), outcomes::add);
        drain();
        GeneratedMaps.Failed failed = assertInstanceOf(GeneratedMaps.Failed.class, outcomes.getLast());
        assertEquals(13, failed.requestedSeed());
        GridCell cell = maps.cellOf("generated:1").orElseThrow();
        assertEquals(0, maps.freeCells());
        assertFalse(maps.isAvailable("generated:1"));
        assertThrows(IllegalStateException.class,
                () -> maps.prepare("generated:2", new GeneratedMaps.Request(1, 1, 2), outcomes::add));

        maps.prepare("generated:1", new GeneratedMaps.Request(42, 1, 2), outcomes::add);
        drain();
        assertInstanceOf(GeneratedMaps.Ready.class, outcomes.getLast());
        assertEquals(cell, maps.cellOf("generated:1").orElseThrow());
    }

    @Test
    void namesCannotBeHandBuiltNamesAndAMapCannotBePreparedTwice() {
        GeneratedMaps maps = maps(2);
        String name = maps.newName();
        assertTrue(Arena.isGeneratedName(name));
        assertFalse(Arena.isValidName(name), "no hand-built arena can have it");
        assertFalse(maps.newName().equals(name));
        assertThrows(IllegalArgumentException.class,
                () -> maps.prepare("dojo", new GeneratedMaps.Request(1, 1, 2), outcomes::add));
        maps.prepare(name, new GeneratedMaps.Request(42, 1, 4), outcomes::add);
        assertThrows(IllegalStateException.class,
                () -> maps.prepare(name, new GeneratedMaps.Request(42, 1, 4), outcomes::add));
        drain();
        Arena arena = assertInstanceOf(GeneratedMaps.Ready.class, outcomes.getLast()).arena();
        assertEquals(4, arena.teamSpawns().size());
        assertEquals(maps.region(arena.cell()), arena.bounds(), "a four-team map fills its region");
    }

    @Test
    void noCellIsReservedOrClearedWhileTheHandBuiltArenasCannotBeRead() {
        boolean[] readable = {false};
        GeneratedMaps maps = new GeneratedMaps(GRID, () -> handBuilt, () -> readable[0], writer,
                blocks.ticketsView(), Runnable::run, Runnable::run, quiet());
        GridCell far = new ArrayList<>(GRID.cellsNearestFirst()).reversed().getFirst();
        // An arena nobody could read sits in the cell that would be reserved first.
        blocks.set(GRID.cellBounds(far).minX() + 100, 40, GRID.cellBounds(far).minZ() + 100, Material.STONE);
        maps.resize(2);
        drain();
        assertEquals(List.of(), maps.cells());
        assertEquals(0, maps.freeCells());
        assertEquals(1, blocks.countSolid(), "nothing is cleared");
        assertTrue(blocks.tickets.isEmpty());
        readable[0] = true;
        maps.resize(2);
        assertEquals(2, maps.cells().size());
    }

    @Test
    void aSearchThatThrowsIsReportedOnTheServerThread() throws Exception {
        ExecutorService async = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "async-search"));
        LinkedBlockingQueue<Runnable> server = new LinkedBlockingQueue<>();
        try {
            GeneratedMaps maps = new GeneratedMaps(GRID, () -> handBuilt, () -> true, writer, blocks.ticketsView(),
                    async, server::add, quiet());
            maps.resize(1);
            drain();
            maps.setSearch((seed, difficulty, teams, size) -> {
                throw new IllegalStateException("search failed");
            });
            List<String> threads = new ArrayList<>();
            maps.prepare("generated:1", new GeneratedMaps.Request(5, 1, 2), outcome -> {
                threads.add(Thread.currentThread().getName());
                outcomes.add(outcome);
            });
            drain();
            Runnable task = server.poll(10, TimeUnit.SECONDS);
            assertTrue(task != null, "the outcome is handed to the server thread");
            assertEquals(List.of(), threads, "nothing runs before the server thread does");
            task.run();
            assertEquals(List.of(Thread.currentThread().getName()), threads);
            GeneratedMaps.Failed failed = assertInstanceOf(GeneratedMaps.Failed.class, outcomes.getLast());
            assertEquals(5, failed.requestedSeed());
            assertTrue(maps.cellOf("generated:1").isPresent(), "the cell stays the map's for another seed");
        } finally {
            async.shutdownNow();
        }
    }
}
