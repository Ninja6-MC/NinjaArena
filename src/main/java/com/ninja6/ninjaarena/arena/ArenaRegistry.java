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
 * them. The match engine holds an arena for exactly as long as a match runs. A held arena
 * cannot be deleted.
 *
 * <p><b>Saved copies.</b> Auto-save being off does not keep a match out of the world save:
 * the server's shutdown save writes every loaded chunk of every world, the arena world
 * included, and {@code /save-all} does the same. So each arena's blocks are copied to
 * {@link ArenaCopies} whenever an edit finishes, and the copy, not the world save, is what
 * the arena is meant to look like. {@link #resetAll()} puts every copy back when the
 * registry opens on startup, so an arena is as it was built on every boot, whether the
 * last run ended cleanly, mid-match or in a crash. {@link #reset(String)} does the same for
 * one arena, for the match engine at match end. An arena with no copy is not available.
 *
 * <p><b>Change listeners.</b> {@link #onEditOrDelete(Consumer)} is told an arena's name when
 * it enters edit mode or is deleted, the two ways its definition can change, so whatever
 * relies on it (such as players waiting in its waiting room) can let go first.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class ArenaRegistry {

    private final ArenaStore store;
    private final ArenaGrid grid;
    private final Consumer<Boolean> worldSaving;
    private final ChunkTickets tickets;
    private final ArenaCopies copies;

    private final Map<String, Arena> arenas = new TreeMap<>();
    private final Set<String> editing = new TreeSet<>();
    private final Set<String> held = new HashSet<>();

    /** Set when the file could not be read, so a later write cannot replace what is in it. */
    private boolean unreadable;

    private final List<Consumer<String>> editOrDeleteListeners = new ArrayList<>();

    /**
     * @param worldSaving turns the arena world's saving on or off
     *                    ({@code ArenaWorld#setSavingEnabled})
     */
    public ArenaRegistry(ArenaStore store, ArenaGrid grid, Consumer<Boolean> worldSaving, ChunkTickets tickets,
            ArenaCopies copies) {
        this.store = Objects.requireNonNull(store, "store");
        this.grid = Objects.requireNonNull(grid, "grid");
        this.worldSaving = Objects.requireNonNull(worldSaving, "worldSaving");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.copies = Objects.requireNonNull(copies, "copies");
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
     * height, chunk tickets owned by the plugin, and copies in {@code arena-copies}. Once
     * loaded, every arena is reset from its copy ({@link #resetAll()}). A file that cannot be
     * read is logged and left as it is.
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
        ArenaCopies copies = new StructureArenaCopies(plugin.getDataFolder().toPath().resolve("arena-copies"), world,
                plugin.getServer().getStructureManager());
        ArenaRegistry registry = new ArenaRegistry(store, grid, arenaWorld::setSavingEnabled, tickets, copies);
        try {
            registry.load();
            plugin.getLogger().info("Loaded " + registry.all().size() + " arena(s) on a " + grid.cellSize()
                    + "-block grid.");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read " + store.file()
                    + "; arenas cannot be changed until it is fixed and the server restarted.", e);
            return registry;
        }
        ResetReport report = registry.resetAll();
        if (!report.reset().isEmpty()) {
            plugin.getLogger().info("Reset " + String.join(", ", report.reset()) + " from the saved copy.");
        }
        if (!report.copied().isEmpty()) {
            plugin.getLogger().info("Saved a first copy of " + String.join(", ", report.copied()) + ".");
        }
        report.failed().forEach(problem -> plugin.getLogger().warning(problem));
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
        // A copy left by an earlier arena of this name must never be restored over this one.
        deleteCopy(key);
        Arena arena = Arena.create(key, cell);
        Map<String, Arena> next = new TreeMap<>(arenas);
        next.put(key, arena);
        write(next);
        enterEdit(key);
        return arena;
    }

    /** Removes an arena and its saved copy from the registry. Its blocks stay in the world. */
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
        try {
            copies.delete(arena.name());
        } catch (IOException e) {
            // Harmless: nothing restores a copy without an arena, and create removes it first.
        }
        notifyEditOrDelete(arena.name());
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
        notifyEditOrDelete(arena.name());
    }

    /**
     * Registers a listener told an arena's name after it enters edit mode through
     * {@link #startEdit(String)} or is deleted. A new arena starts in edit mode too, but
     * nothing can rely on it yet, so {@link #create} does not notify.
     */
    public void onEditOrDelete(Consumer<String> listener) {
        editOrDeleteListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void notifyEditOrDelete(String name) {
        for (Consumer<String> listener : editOrDeleteListeners) {
            listener.accept(name);
        }
    }

    /**
     * Saves the arena's copy and takes it out of edit mode. When it was the last one, world
     * saving is turned off, which saves the world.
     *
     * @return what still stops the arena from hosting a match; empty if it is ready
     * @throws ArenaException if the copy cannot be saved; the arena then stays in edit mode
     */
    public List<String> finishEdit(String name) throws ArenaException {
        Arena arena = require(name);
        if (!editing.contains(arena.name())) {
            throw new ArenaException("Arena " + arena.name() + " is not in edit mode.");
        }
        if (arena.bounds() != null) {
            try {
                copies.save(arena.name(), arena.bounds());
            } catch (IOException e) {
                throw new ArenaException("Could not save the copy of " + arena.name() + ", so it stays in edit"
                        + " mode: " + e.getMessage(), e);
            }
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
     * Whether a match may start in the arena: complete, saved as a copy it can be reset from,
     * not in use, and no arena at all in edit mode, since edit mode turns saving on for the
     * whole world.
     */
    public boolean isAvailable(String name) {
        String key = Arena.normalizeName(name);
        Arena arena = arenas.get(key);
        return arena != null
                && editing.isEmpty()
                && !held.contains(key)
                && ArenaValidator.problems(arena, grid).isEmpty()
                && copies.exists(key);
    }

    /**
     * Puts the arena's blocks back as they were when its last edit finished. For the match
     * engine, at match end; it may be called while the arena is held.
     *
     * @throws ArenaException if the arena is in edit mode, which would undo the edit, or has
     *                        no copy, or the copy cannot be read
     */
    public void reset(String name) throws ArenaException {
        Arena arena = require(name);
        if (editing.contains(arena.name())) {
            throw new ArenaException("Arena " + arena.name() + " is in edit mode; a reset would undo the edit.");
        }
        try {
            copies.restore(arena.name());
        } catch (IOException e) {
            throw new ArenaException("Could not reset " + arena.name() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resets every arena that is not in edit mode from its copy. An arena with bounds but no
     * copy, such as one built before copies existed, gets its first copy from the world as
     * it is. Called when the registry opens on startup, before any match can run, so whatever
     * the last run wrote to the world save is undone.
     */
    public ResetReport resetAll() {
        List<String> reset = new ArrayList<>();
        List<String> copied = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.bounds() == null || editing.contains(arena.name())) {
                continue;
            }
            try {
                if (copies.exists(arena.name())) {
                    copies.restore(arena.name());
                    reset.add(arena.name());
                } else {
                    copies.save(arena.name(), arena.bounds());
                    copied.add(arena.name());
                }
            } catch (IOException e) {
                failed.add("Could not reset " + arena.name() + " from its saved copy: " + e.getMessage());
            }
        }
        return new ResetReport(reset, copied, failed);
    }

    /**
     * What {@link #resetAll()} did.
     *
     * @param reset  arenas put back from their copy
     * @param copied arenas that had no copy and were copied as they are
     * @param failed one message per arena that could not be reset or copied
     */
    public record ResetReport(List<String> reset, List<String> copied, List<String> failed) {

        public ResetReport {
            reset = List.copyOf(reset);
            copied = List.copyOf(copied);
            failed = List.copyOf(failed);
        }
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

    /**
     * Releases every held arena and ends every edit, saving the copy of each arena that was
     * being edited. Called on plugin disable.
     *
     * @return one message per copy that could not be saved
     */
    public List<String> close() {
        for (String name : new ArrayList<>(held)) {
            release(name);
        }
        List<String> failed = new ArrayList<>();
        if (!editing.isEmpty()) {
            for (String name : editing) {
                Arena arena = arenas.get(name);
                if (arena == null || arena.bounds() == null) {
                    continue;
                }
                try {
                    copies.save(name, arena.bounds());
                } catch (IOException e) {
                    failed.add("Could not save the copy of " + name + " while closing its edit: " + e.getMessage());
                }
            }
            editing.clear();
            worldSaving.accept(false);
        }
        return failed;
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

    private void deleteCopy(String key) throws ArenaException {
        try {
            copies.delete(key);
        } catch (IOException e) {
            throw new ArenaException("Could not delete the saved copy of " + key + ": " + e.getMessage(), e);
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
