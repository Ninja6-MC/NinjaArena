package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ChunkTickets;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.arena.MapSeed;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import com.ninja6.ninjaarena.terrain.ChallengeMinimums;
import com.ninja6.ninjaarena.terrain.MapPoints;
import com.ninja6.ninjaarena.terrain.MapSearch;
import com.ninja6.ninjaarena.terrain.MovementModel;
import com.ninja6.ninjaarena.terrain.SideDimensions;
import com.ninja6.ninjaarena.terrain.TerrainGenerator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generated maps and the slot cells they are played in.
 *
 * <p><b>Slot cells.</b> {@code arenas.max-active} grid cells are reserved for generated
 * maps ({@link SlotCells}). A generated map is played in the middle of its cell, in a
 * fixed region as wide as the four-team map for the cell, or for a cell of
 * {@link PlacementSettings#maxMapSize()} when that is smaller ({@link #setMaxMapSize}), and
 * {@link SideDimensions#DEFAULT_HEIGHT} high from {@link #FLOOR_Y}; nothing is written
 * outside it.
 *
 * <p><b>Lifecycle.</b> A room that holds an arena slot asks for a map with
 * {@link #prepare}: the map takes a free cell, chunk tickets go on the cell's region, the
 * region is cleared, and meanwhile {@link MapSearch} runs off the server thread. An
 * accepted map is placed by the {@link CellWriter} and is then an ordinary {@link Arena}:
 * its cell, its bounds and its points in world coordinates, under a name no hand-built
 * arena can have ({@link Arena#isGeneratedName}). A match holds it like any arena. When the
 * room is done with it, {@link #free} clears the region and takes the tickets off, and the
 * cell is free again. A map is never reset from a copy: the next match clears the cell and
 * generates again.
 *
 * <p><b>Nothing left behind.</b> Generated maps are held in memory only and never reach
 * {@code arenas.yml}. Every slot cell is cleared when it is reserved ({@link #resize}, on
 * startup and on reload) and when the arena world reopens after a retire
 * ({@link #reopened}), so a map the world save kept from an earlier run is gone before
 * any cell is used, and every cell is cleared again before each map is placed. Those two
 * sweeps clear the {@link #sweepRegion} for the cell's full size, so a map placed before
 * the size limit was lowered across a restart goes too. A cell being cleared counts as in
 * use. While the hand-built arenas cannot be read, no cell is
 * reserved or cleared, since one might hold an arena nobody knows about.
 *
 * <p><b>Progress.</b> {@link #progress} tells how far a map being prepared has got: the
 * blocks its clear and its placement have visited out of both together.
 *
 * <p><b>Replacing a map.</b> A map can be generated again while it is placed, for the next
 * round of its match ({@link #prepareReplacement}): the new one is placed in another free
 * cell while the old one stays as it is. Only once it is fully placed can it take the old
 * one's place ({@link #commitReplacement}): the name then finds the new map, which takes the
 * old one's slot number, and the old cell is cleared. Until then, or when it is dropped
 * ({@link #dropReplacement}), the old map is untouched.
 *
 * <p>Server thread only, apart from the searches, which run on the executor given.
 */
public final class GeneratedMaps {

    /** The world height of a generated map's {@code y = 0}, within the world's height. */
    public static final int FLOOR_Y = 32;

    /** One seed search: {@link MapSearch} with the default movement and minimums. */
    @FunctionalInterface
    public interface Search {

        MapSearch.Outcome search(long seed, int difficulty, int teams, SideDimensions size);

        Search DEFAULT = (seed, difficulty, teams, size) -> MapSearch.search(seed, difficulty, teams, size,
                MovementModel.DEFAULT, ChallengeMinimums.forDifficulty(difficulty), MapSearch.DEFAULT_RETRY_LIMIT);
    }

    /**
     * What a room asks for.
     *
     * @param difficulty {@link TerrainGenerator#MIN_DIFFICULTY} to
     *                   {@link TerrainGenerator#MAX_DIFFICULTY}
     * @param teams      2 or 4: the layout's sides
     */
    public record Request(long seed, int difficulty, int teams) {

        public Request {
            if (difficulty < TerrainGenerator.MIN_DIFFICULTY || difficulty > TerrainGenerator.MAX_DIFFICULTY) {
                throw new IllegalArgumentException("difficulty must be " + TerrainGenerator.MIN_DIFFICULTY + " to "
                        + TerrainGenerator.MAX_DIFFICULTY);
            }
            if (teams != 2 && teams != 4) {
                throw new IllegalArgumentException("teams must be 2 or 4");
            }
        }
    }

    /** What {@link #prepare} gave, on the server thread. */
    public sealed interface Prepared permits Ready, Failed {
    }

    /**
     * The map is placed and available.
     *
     * @param seed the seed it was generated from: the room's last good seed
     */
    public record Ready(Arena arena, long seed, int attempt) implements Prepared {
    }

    /**
     * No map came from the seed: every attempt of the search was rejected, or generating
     * or placing it failed (then {@code attempts} counts the tries made). The cell stays
     * the map's, so {@link #prepare} can try another seed in it; {@link #free} gives it up.
     */
    public record Failed(long requestedSeed, int attempts) implements Prepared {
    }

    /**
     * How far a map being prepared has got: the blocks the {@link CellWriter} has visited,
     * clearing the region and then placing the map, out of both together. Before the
     * search is done, the placement counts with the size of the layout asked for.
     *
     * @param done  from 0 to {@code total}
     * @param total the region's blocks and the layout's blocks
     */
    public record Progress(long done, long total) {

        public Progress {
            if (total < 0 || done < 0 || done > total) {
                throw new IllegalArgumentException("done must be 0 to total");
            }
        }

        /** From 0 to 1; 1 for nothing to do. */
        public double fraction() {
            return total == 0 ? 1 : (double) done / total;
        }

        public boolean isComplete() {
            return done == total;
        }
    }

    private enum State { IDLE, CLEARING, PREPARING, FAILED, READY, HELD }

    private static final class Slot {

        final GridCell cell;
        State state = State.IDLE;
        String map;
        Arena arena;
        /** What the placed map was generated from, while it is placed. */
        MapSeed seed;
        /** The box whose chunks have tickets on; null for none. */
        ArenaBounds ticketed;
        /** Bumped by every change, so a completion that comes too late is dropped. */
        int generation;
        CompletableFuture<?> work = CompletableFuture.completedFuture(null);
        /** The clear before the map in progress, its placement once queued, and its size until then. */
        CellWriter.Work clearing;
        CellWriter.Work placing;
        long placeTotal;

        Slot(GridCell cell) {
            this.cell = cell;
        }
    }

    private ArenaGrid grid;
    private final SlotCells cells;
    private final Map<GridCell, Slot> slots = new LinkedHashMap<>();
    /** Maps being generated again, by the name of the map each will replace. */
    private final Map<String, Slot> replacements = new HashMap<>();
    private final CellWriter writer;
    private final ChunkTickets tickets;
    private final Executor searches;
    private final Executor server;
    private final Logger logger;
    private Search search = Search.DEFAULT;
    private Runnable freed = () -> { };
    private final BooleanSupplier handBuiltKnown;
    private int names;
    private int maxMapSize = PlacementSettings.DEFAULTS.maxMapSize();
    private boolean closed;

    /**
     * Reserves no cell yet: {@link #resize} reserves and clears them.
     *
     * @param handBuilt      the cells of the hand-built arenas, which are never reserved
     * @param handBuiltKnown whether {@code handBuilt} is complete: false while
     *                       {@code arenas.yml} could not be read
     * @param searches       runs seed searches off the server thread
     * @param server         runs a task on the server thread
     */
    public GeneratedMaps(ArenaGrid grid, Supplier<? extends Collection<GridCell>> handBuilt,
            BooleanSupplier handBuiltKnown, CellWriter writer, ChunkTickets tickets, Executor searches,
            Executor server, Logger logger) {
        this.handBuiltKnown = Objects.requireNonNull(handBuiltKnown, "handBuiltKnown");
        this.grid = Objects.requireNonNull(grid, "grid");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.searches = Objects.requireNonNull(searches, "searches");
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.cells = new SlotCells(grid, handBuilt, logger::warning);
    }

    /**
     * Sizes maps as if no cell were larger than {@code size} blocks, which caps the blocks
     * each map clears and places on shared hosts ({@link PlacementSettings#maxMapSize()}).
     * Set before any cell is in use; the regions follow it.
     *
     * @param size {@link PlacementSettings#MIN_MAP_SIZE} to {@link PlacementSettings#MAX_MAP_SIZE}
     * @throws IllegalStateException if a cell is in use
     */
    public void setMaxMapSize(int size) {
        if (size < PlacementSettings.MIN_MAP_SIZE || size > PlacementSettings.MAX_MAP_SIZE) {
            throw new IllegalArgumentException("map size must be " + PlacementSettings.MIN_MAP_SIZE + " to "
                    + PlacementSettings.MAX_MAP_SIZE);
        }
        if (!inUse().isEmpty()) {
            throw new IllegalStateException("slot cells are in use");
        }
        maxMapSize = size;
    }

    /**
     * The size of each side of a generated map: {@link SideDimensions#forCell} for the cell
     * size, or for {@link #setMaxMapSize the size limit} when that is smaller.
     */
    public SideDimensions mapSize() {
        return SideDimensions.forCell(Math.min(grid.cellSize(), maxMapSize));
    }

    /** Replaces the seed search; for tests. */
    public void setSearch(Search search) {
        this.search = Objects.requireNonNull(search, "search");
    }

    /** Told each time a cell becomes free, after its map is cleared. */
    public void onFree(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        Runnable earlier = freed;
        freed = () -> {
            earlier.run();
            listener.run();
        };
    }

    // ---- Cells ----

    /**
     * The arena world reopened after a retire: the world save may hold maps from before, so
     * every free slot cell is cleared again. Never throws; a failure is logged.
     */
    public void reopened() {
        try {
            sweep("after the arena world reopened");
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not clear the slot cells for generated maps after the arena world"
                    + " reopened.", e);
        }
    }

    private void sweep(String when) {
        List<Slot> free = slots.values().stream().filter(slot -> slot.state == State.IDLE).toList();
        if (!free.isEmpty()) {
            logger.info("Clearing " + free.size() + " slot cell(s) for generated maps " + when + ": "
                    + String.join("; ", free.stream().map(slot -> slot.cell.describe()).toList()) + ".");
        }
        free.forEach(this::sweep);
    }

    /** The reserved cells, slot 1 first. */
    public List<GridCell> cells() {
        return cells.cells();
    }

    public boolean isReserved(GridCell cell) {
        return cells.isReserved(cell);
    }

    /**
     * The slot number of the cell a map uses: its place among the reserved cells, from 1.
     * Empty for a map without a cell.
     */
    public OptionalInt slotNumber(String name) {
        Slot slot = slotOf(Arena.normalizeName(name));
        return slot == null ? OptionalInt.empty() : OptionalInt.of(cells.cells().indexOf(slot.cell) + 1);
    }

    /** The slot number {@link #prepare} gives a new map now: the first free cell's. Empty if none is free. */
    public OptionalInt nextFreeSlot() {
        List<GridCell> reserved = cells.cells();
        for (int i = 0; i < reserved.size(); i++) {
            Slot slot = slots.get(reserved.get(i));
            if (slot != null && slot.state == State.IDLE) {
                return OptionalInt.of(i + 1);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The slot cells in use now: being cleared, generated, placed or played. Arena edits
     * are refused while any is.
     */
    public List<GridCell> inUse() {
        return slots.values().stream().filter(slot -> slot.state != State.IDLE).map(slot -> slot.cell).toList();
    }

    private boolean inUse(GridCell cell) {
        Slot slot = slots.get(cell);
        return slot != null && slot.state != State.IDLE;
    }

    /** Cells no map uses and nothing is clearing. */
    public int freeCells() {
        return (int) slots.values().stream().filter(slot -> slot.state == State.IDLE).count();
    }

    /**
     * Applies a new {@code arenas.max-active}. Reserved cells stay where they are; a cell a
     * map still uses is never given up, only once it is free. New cells are cleared.
     */
    public void resize(int count) {
        if (!handBuiltKnown.getAsBoolean()) {
            logger.warning("The hand-built arenas could not be read, so no slot cell is reserved for generated"
                    + " maps and none is cleared until they can be.");
            return;
        }
        cells.resize(count, this::inUse);
        syncSlots().forEach(this::sweep);
    }

    /**
     * The arena grid changed on reload: the cells are chosen again on the new grid and
     * cleared.
     *
     * @throws IllegalStateException if a cell is in use
     */
    public void setGrid(ArenaGrid grid) {
        if (!inUse().isEmpty()) {
            throw new IllegalStateException("slot cells are in use");
        }
        this.grid = Objects.requireNonNull(grid, "grid");
        slots.clear();
        cells.setGrid(grid);
        syncSlots().forEach(this::sweep);
    }

    /** Adds a slot for every newly reserved cell and drops those given up. */
    private List<Slot> syncSlots() {
        List<GridCell> reserved = cells.cells();
        slots.keySet().retainAll(reserved);
        List<Slot> added = new ArrayList<>();
        Map<GridCell, Slot> ordered = new LinkedHashMap<>();
        for (GridCell cell : reserved) {
            Slot slot = slots.get(cell);
            if (slot == null) {
                slot = new Slot(cell);
                added.add(slot);
            }
            ordered.put(cell, slot);
        }
        slots.clear();
        slots.putAll(ordered);
        return added;
    }

    /**
     * The region of a slot cell a generated map may use: centred in the cell, as wide and
     * long as a four-team map of {@link #mapSize()}, from {@link #FLOOR_Y} up the map's
     * height. Both corners inclusive.
     */
    public ArenaBounds region(GridCell cell) {
        return region(cell, mapSize());
    }

    /**
     * The most of a slot cell any generated map can have used: the {@link #region} for the
     * cell's full size, whatever the size limit. A size limit lowered across a restart
     * leaves an older, larger map in it, so a cell is swept this wide when it is reserved
     * and when the arena world reopens. It contains {@link #region}.
     */
    public ArenaBounds sweepRegion(GridCell cell) {
        return region(cell, SideDimensions.forCell(grid.cellSize()));
    }

    private ArenaBounds region(GridCell cell, SideDimensions size) {
        int span = size.width() + 2 * size.length();
        ArenaBounds bounds = grid.cellBounds(cell);
        int minX = bounds.minX() + (grid.cellSize() - span) / 2;
        int minZ = bounds.minZ() + (grid.cellSize() - span) / 2;
        int floor = floorY(size);
        return new ArenaBounds(minX, floor, minZ, minX + span - 1, floor + size.height() - 1, minZ + span - 1);
    }

    private int floorY(SideDimensions size) {
        return Math.clamp(FLOOR_Y, grid.minY(), Math.max(grid.minY(), grid.maxY() - size.height()));
    }

    // ---- Maps ----

    /** A name for a new generated map, never used before in this run. */
    public String newName() {
        return Arena.GENERATED_PREFIX + ++names;
    }

    /** The cell a generated map uses, if it has one. */
    public Optional<GridCell> cellOf(String name) {
        return Optional.ofNullable(slotOf(name)).map(slot -> slot.cell);
    }

    /**
     * Generates and places a map in a free cell, or, after {@link Failed}, in the cell the
     * map already has. {@code done} is told on the server thread, unless the map is freed
     * first.
     *
     * @throws IllegalStateException if the map already has a map in progress or placed, or
     *                               no cell is free
     */
    public void prepare(String name, Request request, Consumer<Prepared> done) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(done, "done");
        if (closed) {
            throw new IllegalStateException("generated maps are closed");
        }
        String key = Arena.normalizeName(name);
        if (!Arena.isGeneratedName(key)) {
            throw new IllegalArgumentException(name + " is not a generated map's name");
        }
        Slot slot = slotOf(key);
        if (slot != null && slot.state != State.FAILED) {
            throw new IllegalStateException(key + " is already being prepared or placed");
        }
        if (slot == null) {
            slot = freeSlot();
            slot.map = key;
        }
        start(slot, request, done);
    }

    private Slot freeSlot() {
        return slots.values().stream().filter(free -> free.state == State.IDLE).findFirst()
                .orElseThrow(() -> new IllegalStateException("no slot cell is free"));
    }

    /** Clears the slot's region, searches the seed and places the map, for {@link #prepare}. */
    private void start(Slot target, Request request, Consumer<Prepared> done) {
        target.state = State.PREPARING;
        target.arena = null;
        target.seed = null;
        int generation = ++target.generation;
        // Tickets before any block is written, so no chunk unloads and drops the work.
        takeTickets(target, region(target.cell));
        // A chunk unloaded since its last use is back as the world save had it, so clear first.
        CellWriter.Work cleared = writer.clear(target.cell, region(target.cell));
        target.work = cleared;
        SideDimensions size = mapSize();
        target.clearing = cleared;
        target.placing = null;
        target.placeTotal = layoutVolume(size, request.teams());
        CompletableFuture<MapSearch.Outcome> searched = CompletableFuture
                .supplyAsync(() -> search.search(request.seed(), request.difficulty(), request.teams(), size), searches);
        // Back on the server thread for every outcome, a search that threw included.
        cleared.thenCombine(searched, (ignored, outcome) -> outcome)
                .whenCompleteAsync((outcome, error) -> searched(target, generation, request, outcome, error, done),
                        server);
    }

    private void searched(Slot slot, int generation, Request request, MapSearch.Outcome outcome, Throwable error,
            Consumer<Prepared> done) {
        if (slot.generation != generation) {
            return;
        }
        if (error != null) {
            logger.log(Level.SEVERE, "Could not generate map " + slot.map + " from seed " + request.seed()
                    + " in slot cell " + slot.cell.describe() + ".", unwrap(error));
            slot.state = State.FAILED;
            done.accept(new Failed(request.seed(), 0));
            return;
        }
        switch (outcome) {
            case MapSearch.Failed failed -> {
                slot.state = State.FAILED;
                logger.info("No playable map came from seed " + request.seed() + " at difficulty "
                        + request.difficulty() + " in " + failed.attempts() + " tries, for " + slot.map + ".");
                done.accept(new Failed(failed.requestedSeed(), failed.attempts()));
            }
            case MapSearch.Accepted accepted -> place(slot, generation, request, accepted, done);
        }
    }

    private void place(Slot slot, int generation, Request request, MapSearch.Accepted accepted,
            Consumer<Prepared> done) {
        ArenaLayout layout = accepted.layout();
        ArenaBounds cell = grid.cellBounds(slot.cell);
        int originX = cell.minX() + (grid.cellSize() - layout.width()) / 2;
        int originZ = cell.minZ() + (grid.cellSize() - layout.length()) / 2;
        int originY = floorY(layout.side().dimensions());
        CellWriter.Work placed = writer.place(slot.cell, layout, originX, originY, originZ);
        slot.work = placed;
        slot.placing = placed;
        placed.whenComplete((ignored, error) -> {
            if (slot.generation != generation) {
                return;
            }
            if (error != null) {
                logger.log(Level.SEVERE, "Could not place map " + slot.map + " in slot cell " + slot.cell.describe()
                        + ".", unwrap(error));
                slot.state = State.FAILED;
                done.accept(new Failed(request.seed(), accepted.attempt() + 1));
                return;
            }
            slot.arena = arena(slot, layout, accepted.points(), originX, originY, originZ);
            slot.seed = new MapSeed(accepted.seed(), request.difficulty());
            slot.state = State.READY;
            logger.info("Generated map " + slot.map + " (seed " + accepted.seed() + ", difficulty "
                    + request.difficulty() + ", " + request.teams() + " teams) is ready in slot cell "
                    + slot.cell.describe() + ".");
            done.accept(new Ready(slot.arena, accepted.seed(), accepted.attempt()));
        });
    }

    private static Arena arena(Slot slot, ArenaLayout layout, MapPoints points, int x, int y, int z) {
        ArenaBounds bounds = new ArenaBounds(x, y, z, x + layout.width() - 1, y + layout.height() - 1,
                z + layout.length() - 1);
        List<ArenaPoint> teams = points.teamSpawns().stream().map(point -> point.toArenaPoint(x, y, z)).toList();
        List<ArenaPoint> enemies = points.enemySpawns().stream().map(point -> point.toArenaPoint(x, y, z)).toList();
        return new Arena(slot.map, slot.cell, bounds, points.waitingRoom().toArenaPoint(x, y, z), teams, enemies,
                false);
    }

    /**
     * Gives up a map: what is left of its work is dropped, its region cleared and its
     * tickets taken off, and the cell is free again. Does nothing for a map without a cell.
     */
    public void free(String name) {
        dropReplacement(name);
        Slot slot = slotOf(Arena.normalizeName(name));
        if (slot == null) {
            return;
        }
        CompletableFuture<?> work = slot.work;
        // Bumped before the cancel, so the cancelled work's completion is ignored.
        slot.generation++;
        work.cancel(false);
        clear(slot, region(slot.cell));
    }

    /** Clears all a map could ever have used of the slot's cell, then frees it. */
    private void sweep(Slot slot) {
        clear(slot, sweepRegion(slot.cell));
    }

    /** Clears the box with tickets on, then frees the cell. */
    private void clear(Slot slot, ArenaBounds region) {
        slot.state = State.CLEARING;
        slot.map = null;
        slot.arena = null;
        slot.seed = null;
        int generation = ++slot.generation;
        takeTickets(slot, region);
        slot.clearing = null;
        slot.placing = null;
        CompletableFuture<Void> cleared = writer.clear(slot.cell, region);
        slot.work = cleared;
        cleared.whenComplete((ignored, error) -> {
            if (slot.generation != generation) {
                return;
            }
            if (error != null) {
                // The cell is cleared again before its next map is placed.
                logger.log(Level.WARNING, "Could not clear slot cell " + slot.cell.describe() + ".", unwrap(error));
            }
            releaseTickets(slot);
            slot.state = State.IDLE;
            cells.trim(this::inUse);
            syncSlots();
            freed.run();
        });
    }

    /** The map, once placed: hand-built and generated maps are both found by name. */
    public Optional<Arena> get(String name) {
        Slot slot = slotOf(Arena.normalizeName(name));
        return slot != null && (slot.state == State.READY || slot.state == State.HELD)
                ? Optional.of(slot.arena) : Optional.empty();
    }

    /**
     * The seed the placed map was accepted from and its difficulty: what reproduces it.
     * Empty unless the map is placed. A committed replacement answers with its own seed.
     */
    public Optional<MapSeed> seed(String name) {
        Slot slot = slotOf(Arena.normalizeName(name));
        return slot != null && (slot.state == State.READY || slot.state == State.HELD)
                ? Optional.ofNullable(slot.seed) : Optional.empty();
    }

    /**
     * How far the map has got while it is prepared, and complete once it is placed. Empty
     * for a map with no cell, or whose last try failed until it tries again.
     */
    public Optional<Progress> progress(String name) {
        return progress(slotOf(Arena.normalizeName(name)));
    }

    /**
     * How far the map that will replace this one has got ({@link #prepareReplacement}), and
     * complete once it is placed. Empty when none is being generated.
     */
    public Optional<Progress> replacementProgress(String name) {
        return progress(replacements.get(Arena.normalizeName(name)));
    }

    private static Optional<Progress> progress(Slot slot) {
        if (slot == null || slot.clearing == null
                || (slot.state != State.PREPARING && slot.state != State.READY && slot.state != State.HELD)) {
            return Optional.empty();
        }
        long placeTotal = slot.placing != null ? slot.placing.total() : slot.placeTotal;
        long total = slot.clearing.total() + placeTotal;
        if (slot.state != State.PREPARING) {
            return Optional.of(new Progress(total, total));
        }
        long done = slot.clearing.done() + (slot.placing != null ? slot.placing.done() : 0);
        return Optional.of(new Progress(done, total));
    }

    /** The blocks in the box of a layout of this size for 2 or 4 teams. */
    static long layoutVolume(SideDimensions size, int teams) {
        long span = size.width() + 2L * size.length();
        long across = teams == 4 ? span : size.width();
        return across * span * size.height();
    }

    /** Placed and not held by a match. */
    public boolean isAvailable(String name) {
        Slot slot = slotOf(Arena.normalizeName(name));
        return slot != null && slot.state == State.READY;
    }

    public boolean isHeld(String name) {
        Slot slot = slotOf(Arena.normalizeName(name));
        return slot != null && slot.state == State.HELD;
    }

    /**
     * A match starts on the map. Its tickets are on already, since before it was placed.
     *
     * @throws ArenaException if the map is not placed or is held already
     */
    public void hold(String name) throws ArenaException {
        String key = Arena.normalizeName(name);
        Slot slot = slotOf(key);
        if (slot != null && slot.state == State.HELD) {
            throw new ArenaException("arena.error.already-in-use", "arena", key);
        }
        if (slot == null || slot.state != State.READY) {
            throw new ArenaException("arena.error.unavailable", "arena", key);
        }
        slot.state = State.HELD;
    }

    /** The match is over; the map stays placed until the room frees it. */
    public void release(String name) {
        Slot slot = slotOf(Arena.normalizeName(name));
        if (slot != null && slot.state == State.HELD) {
            slot.state = State.READY;
        }
    }

    /**
     * Nothing to do: a generated map is never restored from a copy. It is placed fresh for
     * each match, and the room clears and generates its cell again after the match.
     *
     * @throws ArenaException if the map is not placed
     */
    public void reset(String name) throws ArenaException {
        if (get(name).isEmpty()) {
            throw new ArenaException("arena.error.unknown", "arena", name);
        }
    }

    // ---- Replacing a placed map ----

    /**
     * Generates a map to replace a placed one, in a free cell, while the placed one stays as
     * it is. {@code done} is told on the server thread, unless the replacement is dropped or
     * the map freed first. A {@link Ready} replacement waits for {@link #commitReplacement};
     * after {@link Failed}, drop it.
     *
     * @throws IllegalStateException if the map is not placed, already has a replacement, or
     *                               no cell is free
     */
    public void prepareReplacement(String name, Request request, Consumer<Prepared> done) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(done, "done");
        if (closed) {
            throw new IllegalStateException("generated maps are closed");
        }
        String key = Arena.normalizeName(name);
        Slot current = slotOf(key);
        if (current == null || current.state != State.READY && current.state != State.HELD) {
            throw new IllegalStateException(key + " is not placed");
        }
        if (replacements.containsKey(key)) {
            throw new IllegalStateException(key + " already has a replacement");
        }
        Slot slot = freeSlot();
        // A name of its own until it takes the old map's place, so nothing finds it before.
        slot.map = newName();
        replacements.put(key, slot);
        start(slot, request, done);
    }

    public boolean hasReplacement(String name) {
        return replacements.containsKey(Arena.normalizeName(name));
    }

    /**
     * The placed replacement takes the map's place: the name finds it from now on, held by
     * the match as the old map was, and it takes the old map's slot number. The old map's
     * cell is cleared and freed.
     *
     * @throws IllegalStateException if no replacement is placed for the map
     */
    public void commitReplacement(String name) {
        String key = Arena.normalizeName(name);
        Slot next = replacements.get(key);
        Slot old = slotOf(key);
        if (next == null || next.state != State.READY || old == null) {
            throw new IllegalStateException(key + " has no placed replacement");
        }
        replacements.remove(key);
        State state = old.state;
        cells.swap(old.cell, next.cell);
        syncSlots();
        old.map = null;
        next.map = key;
        Arena placed = next.arena;
        next.arena = new Arena(key, placed.cell(), placed.bounds(), placed.waitingRoom(), placed.teamSpawns(),
                placed.enemySpawns(), false);
        next.state = state;
        logger.info("Map " + key + " is replaced by the one generated in slot cell " + next.cell.describe()
                + "; slot cell " + old.cell.describe() + " is cleared.");
        clear(old, region(old.cell));
    }

    /**
     * Gives up the map's replacement, if it has one: its work is dropped and its cell
     * cleared. The map itself is untouched.
     */
    public void dropReplacement(String name) {
        Slot slot = replacements.remove(Arena.normalizeName(name));
        if (slot == null) {
            return;
        }
        CompletableFuture<?> work = slot.work;
        slot.generation++;
        work.cancel(false);
        clear(slot, region(slot.cell));
    }

    /**
     * Drops every map and all queued work and takes every ticket off. Called on plugin
     * disable; the cells are cleared on the next start.
     */
    public void close() {
        closed = true;
        replacements.clear();
        for (Slot slot : slots.values()) {
            slot.generation++;
            releaseTickets(slot);
            slot.state = State.IDLE;
            slot.map = null;
            slot.arena = null;
            slot.seed = null;
        }
        writer.close();
    }

    private Slot slotOf(String key) {
        for (Slot slot : slots.values()) {
            if (key.equals(slot.map)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Puts tickets on the chunks of the box, keeping those already on: the slot then holds
     * the box around both, so a sweep's wider tickets cover a map placed after it.
     */
    private void takeTickets(Slot slot, ArenaBounds region) {
        ArenaBounds held = slot.ticketed;
        if (held != null && held.contains(region)) {
            return;
        }
        ArenaBounds box = held == null ? region : new ArenaBounds(Math.min(held.minX(), region.minX()),
                Math.min(held.minY(), region.minY()), Math.min(held.minZ(), region.minZ()),
                Math.max(held.maxX(), region.maxX()), Math.max(held.maxY(), region.maxY()),
                Math.max(held.maxZ(), region.maxZ()));
        for (int x = box.minChunkX(); x <= box.maxChunkX(); x++) {
            for (int z = box.minChunkZ(); z <= box.maxChunkZ(); z++) {
                tickets.add(x, z);
            }
        }
        slot.ticketed = box;
    }

    private void releaseTickets(Slot slot) {
        ArenaBounds held = slot.ticketed;
        if (held == null) {
            return;
        }
        for (int x = held.minChunkX(); x <= held.maxChunkX(); x++) {
            for (int z = held.minChunkZ(); z <= held.maxChunkZ(); z++) {
                tickets.remove(x, z);
            }
        }
        slot.ticketed = null;
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
