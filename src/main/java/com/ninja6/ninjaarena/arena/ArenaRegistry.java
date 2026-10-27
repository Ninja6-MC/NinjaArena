package com.ninja6.ninjaarena.arena;

import com.ninja6.ninjaarena.world.ArenaWorld;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Every registered arena, kept in memory and in {@code arenas.yml}. Each change is written
 * to disk before it is accepted; a failed write leaves the registry as it was.
 *
 * <p><b>Edit mode.</b> An arena is changed only while it is in edit mode. While any arena
 * is in edit mode the arena world saves, so what is built is kept; when the last edit
 * finishes, saving is turned off again, which writes the world once. An arena in edit
 * mode is unavailable for matches. Saving is one switch for the whole world, so no arena
 * can be held while any arena is in edit mode, and no edit can start while any arena is
 * held: auto-save is never on during a match. Edit mode is not stored: a restart ends every edit, and
 * the world is saved on the way down.
 *
 * <p><b>Chunk tickets.</b> {@link #hold(String)} adds a plugin chunk ticket to every chunk
 * the arena's bounds touch, so the arena stays loaded, and {@link #release(String)} removes
 * them. The match engine holds an arena for exactly as long as a match runs; nothing else
 * keeps arena chunks loaded. A held arena cannot be deleted.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class ArenaRegistry {

    private final ArenaStore store;
    private final ArenaGrid grid;
    private final Consumer<Boolean> worldSaving;
    private final ChunkTickets tickets;

    private final Map<String, Arena> arenas = new TreeMap<>();
    private final Set<String> editing = new TreeSet<>();
    private final Set<String> held = new HashSet<>();

    /** Set when the file could not be read, so a later write cannot replace what is in it. */
    private boolean unreadable;

    /**
     * @param worldSaving turns the arena world's saving on or off
     *                    ({@code ArenaWorld#setSavingEnabled})
     */
    public ArenaRegistry(ArenaStore store, ArenaGrid grid, Consumer<Boolean> worldSaving, ChunkTickets tickets) {
        this.store = Objects.requireNonNull(store, "store");
        this.grid = Objects.requireNonNull(grid, "grid");
        this.worldSaving = Objects.requireNonNull(worldSaving, "worldSaving");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
    }

    /**
     * Reads {@code arenas.yml}, replacing what is in memory.
     *
     * @throws IOException if the file cannot be read; the registry is then left empty and
     *                     refuses every change until a load succeeds, so the file is never
     *                     overwritten with less than it holds
     */
    public void load() throws IOException {
        arenas.clear();
        List<Arena> loaded;
        try {
            loaded = store.load();
        } catch (IOException e) {
            unreadable = true;
            throw e;
        }
        unreadable = false;
        for (Arena arena : loaded) {
            arenas.put(arena.name(), arena);
        }
    }

    /**
     * The registry for the running server: {@code arenas.yml} in the plugin's data folder,
     * the grid from the {@code arena-grid} config section and the arena world's border and
     * height, and chunk tickets owned by the plugin. A file that cannot be read is logged and
     * left as it is.
     */
    public static ArenaRegistry open(Plugin plugin, ArenaWorld arenaWorld) {
        World world = arenaWorld.world();
        ArenaGrid grid = new ArenaGrid(
                ArenaGrid.cellSizeFrom(plugin.getConfig().getConfigurationSection("arena-grid")),
                world.getWorldBorder().getSize(), world.getMinHeight(), world.getMaxHeight());
        ChunkTickets tickets = new ChunkTickets() {
            @Override
            public void add(int chunkX, int chunkZ) {
                world.addPluginChunkTicket(chunkX, chunkZ, plugin);
            }

            @Override
            public void remove(int chunkX, int chunkZ) {
                world.removePluginChunkTicket(chunkX, chunkZ, plugin);
            }
        };
        ArenaStore store = new ArenaStore(plugin.getDataFolder().toPath().resolve("arenas.yml"));
        ArenaRegistry registry = new ArenaRegistry(store, grid, arenaWorld::setSavingEnabled, tickets);
        try {
            registry.load();
            plugin.getLogger().info("Loaded " + registry.all().size() + " arena(s) on a " + grid.cellSize()
                    + "-block grid.");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read " + store.file()
                    + "; arenas cannot be changed until it is fixed and the server restarted.", e);
        }
        return registry;
    }

    public ArenaGrid grid() {
        return grid;
    }

    public Optional<Arena> get(String name) {
        return Optional.ofNullable(arenas.get(Arena.normalizeName(name)));
    }

    /** Every arena, by name. */
    public Collection<Arena> all() {
        return Collections.unmodifiableCollection(arenas.values());
    }

    /**
     * Registers a new, empty arena and puts it in edit mode.
     *
     * @param cell the cell to build it in, or {@code null} for the free cell nearest the origin
     */
    public Arena create(String name, GridCell cell) throws ArenaException {
        String key = Arena.normalizeName(name);
        if (!Arena.isValidName(key)) {
            throw new ArenaException("Arena names are 1 to 32 letters, digits, _ or -.");
        }
        if (arenas.containsKey(key)) {
            throw new ArenaException("An arena called " + key + " already exists.");
        }
        Set<GridCell> taken = new HashSet<>();
        for (Arena arena : arenas.values()) {
            taken.add(arena.cell());
        }
        if (cell == null) {
            cell = grid.cellsNearestFirst().stream()
                    .filter(candidate -> !taken.contains(candidate))
                    .findFirst()
                    .orElseThrow(() -> new ArenaException("Every grid cell inside the world border is taken."));
        } else if (!grid.insideBorder(cell)) {
            throw new ArenaException("Cell " + cell.describe() + " is not inside the world border.");
        } else if (taken.contains(cell)) {
            throw new ArenaException("Cell " + cell.describe() + " already holds an arena.");
        }
        refuseEditWhileHeld();
        Arena arena = Arena.create(key, cell);
        Map<String, Arena> next = new TreeMap<>(arenas);
        next.put(key, arena);
        write(next);
        enterEdit(key);
        return arena;
    }

    /** Removes an arena from the registry. Its blocks stay in the world. */
    public void delete(String name) throws ArenaException {
        Arena arena = require(name);
        if (held.contains(arena.name())) {
            throw new ArenaException("Arena " + arena.name() + " is in use by a match.");
        }
        Map<String, Arena> next = new TreeMap<>(arenas);
        next.remove(arena.name());
        write(next);
        if (editing.contains(arena.name())) {
            leaveEdit(arena.name());
        }
    }

    /** Puts an arena in edit mode and turns world saving on. */
    public void startEdit(String name) throws ArenaException {
        Arena arena = require(name);
        if (held.contains(arena.name())) {
            throw new ArenaException("Arena " + arena.name() + " is in use by a match.");
        }
        if (editing.contains(arena.name())) {
            throw new ArenaException("Arena " + arena.name() + " is already in edit mode.");
        }
        refuseEditWhileHeld();
        enterEdit(arena.name());
    }

    /**
     * Takes an arena out of edit mode. When it was the last one, world saving is turned off,
     * which saves the world.
     *
     * @return what still stops the arena from hosting a match; empty if it is ready
     */
    public List<String> finishEdit(String name) throws ArenaException {
        Arena arena = require(name);
        if (!editing.contains(arena.name())) {
            throw new ArenaException("Arena " + arena.name() + " is not in edit mode.");
        }
        leaveEdit(arena.name());
        return ArenaValidator.problems(arena, grid);
    }

    public boolean isEditing(String name) {
        return editing.contains(Arena.normalizeName(name));
    }

    /** Arenas currently in edit mode, by name. */
    public Set<String> editing() {
        return Collections.unmodifiableSet(editing);
    }

    /**
     * Applies a change to an arena in edit mode and writes it to disk.
     *
     * @throws ArenaException if the arena is not in edit mode, the change is refused, or the
     *                        write fails; the arena is then unchanged
     */
    public Arena update(String name, UnaryOperator<Arena> change) throws ArenaException {
        Arena arena = require(name);
        if (!editing.contains(arena.name())) {
            throw new ArenaException("Arena " + arena.name() + " is not in edit mode. Start it with edit "
                    + arena.name() + " on.");
        }
        Arena changed;
        try {
            changed = change.apply(arena);
        } catch (IllegalArgumentException e) {
            throw new ArenaException(capitalize(e.getMessage()) + ".", e);
        }
        Map<String, Arena> next = new TreeMap<>(arenas);
        next.put(arena.name(), changed);
        write(next);
        return changed;
    }

    /** What stops an arena from hosting a match, besides edit mode. Empty if nothing does. */
    public List<String> problems(String name) throws ArenaException {
        return ArenaValidator.problems(require(name), grid);
    }

    /**
     * Whether a match may start in the arena: complete, not in use, and no arena at all in
     * edit mode, since edit mode turns saving on for the whole world.
     */
    public boolean isAvailable(String name) {
        String key = Arena.normalizeName(name);
        Arena arena = arenas.get(key);
        return arena != null
                && editing.isEmpty()
                && !held.contains(key)
                && ArenaValidator.problems(arena, grid).isEmpty();
    }

    /**
     * Adds a chunk ticket to every chunk the arena's bounds touch. For the match engine, at
     * match start.
     *
     * @throws ArenaException if the arena is not available, including while any arena is in
     *                        edit mode
     */
    public void hold(String name) throws ArenaException {
        Arena arena = require(name);
        if (held.contains(arena.name())) {
            throw new ArenaException("Arena " + arena.name() + " is already in use by a match.");
        }
        if (!editing.isEmpty()) {
            throw new ArenaException("No match can start while " + String.join(", ", editing)
                    + " is in edit mode: edit mode turns on saving for the whole arena world.");
        }
        if (!isAvailable(arena.name())) {
            throw new ArenaException("Arena " + arena.name() + " is not available for a match.");
        }
        ArenaBounds bounds = arena.bounds();
        for (int x = bounds.minChunkX(); x <= bounds.maxChunkX(); x++) {
            for (int z = bounds.minChunkZ(); z <= bounds.maxChunkZ(); z++) {
                tickets.add(x, z);
            }
        }
        held.add(arena.name());
    }

    /**
     * Removes the chunk tickets {@link #hold(String)} added. For the match engine, at match
     * end. Does nothing if the arena is not held.
     */
    public void release(String name) {
        String key = Arena.normalizeName(name);
        if (!held.remove(key)) {
            return;
        }
        // Deleting a held arena is refused, so its bounds are the ones the tickets went on.
        ArenaBounds bounds = arenas.get(key).bounds();
        for (int x = bounds.minChunkX(); x <= bounds.maxChunkX(); x++) {
            for (int z = bounds.minChunkZ(); z <= bounds.maxChunkZ(); z++) {
                tickets.remove(x, z);
            }
        }
    }

    public boolean isHeld(String name) {
        return held.contains(Arena.normalizeName(name));
    }

    /** Releases every held arena and ends every edit. Called on plugin disable. */
    public void close() {
        for (String name : new ArrayList<>(held)) {
            release(name);
        }
        if (!editing.isEmpty()) {
            editing.clear();
            worldSaving.accept(false);
        }
    }

    /** Edit mode turns saving on for the whole world, which must not happen during a match. */
    private void refuseEditWhileHeld() throws ArenaException {
        if (!held.isEmpty()) {
            throw new ArenaException("Arenas cannot be edited while a match is running in " + String.join(", ",
                    new TreeSet<>(held)) + ": edit mode turns on saving for the whole arena world.");
        }
    }

    private void enterEdit(String key) {
        editing.add(key);
        if (editing.size() == 1) {
            worldSaving.accept(true);
        }
    }

    private void leaveEdit(String key) {
        editing.remove(key);
        if (editing.isEmpty()) {
            worldSaving.accept(false);
        }
    }

    private Arena require(String name) throws ArenaException {
        Arena arena = arenas.get(Arena.normalizeName(name));
        if (arena == null) {
            throw new ArenaException("No arena called " + name + ".");
        }
        return arena;
    }

    private void write(Map<String, Arena> next) throws ArenaException {
        if (unreadable) {
            throw new ArenaException(store.file().getFileName() + " could not be read at startup. Fix or remove it"
                    + " and restart before changing arenas.");
        }
        try {
            store.save(next.values());
        } catch (IOException e) {
            throw new ArenaException("Could not save " + store.file().getFileName() + ": " + e.getMessage(), e);
        }
        arenas.clear();
        arenas.putAll(next);
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "Refused";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
