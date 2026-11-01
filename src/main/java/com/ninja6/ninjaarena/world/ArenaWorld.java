package com.ninja6.ninjaarena.world;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;

/**
 * The void world every arena is built in. It is created on first start and loaded on every
 * start after that, with auto-save off. Edit mode turns auto-save on while an arena is being
 * built and off again when it is done.
 *
 * <p>On Paper 26.3 auto-save off stops the periodic save and chunk unloading, so chunks stay
 * loaded and nothing is written while the server runs; a crash leaves the world as it was last
 * saved. It does not stop the shutdown save, which turns saving back on for every world before
 * writing all loaded chunks, nor {@code /save-all} or {@link World#save()}. The world save can
 * therefore hold what a match left behind; arenas are put back from their own saved copies
 * instead ({@code ArenaRegistry#resetAll}). {@link ArenaChunkUnloader} retires the entire
 * world without saving when nothing uses it, including the partial chunks Bukkit cannot
 * enumerate. The next use reopens it and restores the copies before publishing it.
 * Continuously busy worlds are not retired; their source coordinates are bounded by
 * the enforced arena-world extent instead.
 */
public final class ArenaWorld {

    public static final String NAME = "ninjaarena_arenas";

    /** Noon, so the sky is the same in every arena. */
    static final long FIXED_TIME = 6000;

    private enum State { READY, UNLOADED, LOADING, UNLOADING, FAILED, CLOSED }

    private World world;
    private World preparing;
    private final Supplier<World> loader;
    private final Predicate<World> unloader;
    private final AtomicInteger operations = new AtomicInteger();
    private volatile ArenaWorldSettings settings = ArenaWorldSettings.DEFAULTS;
    private Runnable restoreCopies = () -> { };
    private State state = State.READY;
    private final UUID identity;
    private boolean savingEnabled;
    private boolean freezeBlockEvents;

    ArenaWorld(World world) {
        this(world, () -> { throw new IllegalStateException("No world loader configured"); }, ignored -> false);
    }

    ArenaWorld(World world, Supplier<World> loader, Predicate<World> unloader) {
        this(world, loader, unloader, null);
    }

    ArenaWorld(World world, Supplier<World> loader, Predicate<World> unloader, UUID identity) {
        this.world = Objects.requireNonNull(world, "world");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.unloader = Objects.requireNonNull(unloader, "unloader");
        this.identity = identity;
        world.setAutoSave(false);
    }

    /**
     * Loads the arena world, creating it if it does not exist yet, and applies the settings
     * and game rules. They are reapplied on every load, so a change to {@code config.yml}
     * takes effect on the next start.
     */
    public static ArenaWorld load(ArenaWorldSettings settings, Logger logger) {
        World world = open();
        ArenaWorld arenaWorld = new ArenaWorld(world, ArenaWorld::open, current -> Bukkit.unloadWorld(current, false),
                world.getUID());
        arenaWorld.settings = settings;
        arenaWorld.freezeBlockEvents = true;
        configure(arenaWorld.world, settings);
        PaperBlockEvents.clear(world);
        logger.info("Arena world " + NAME + " loaded with saving off.");
        return arenaWorld;
    }

    private static World open() {
        World world = Bukkit.getWorld(NAME);
        if (world == null) {
            world = new WorldCreator(NAME)
                    .environment(World.Environment.NORMAL)
                    .generator(new VoidChunkGenerator())
                    .generateStructures(false)
                    .bonusChest(false)
                    .createWorld();
            if (world == null) {
                throw new IllegalStateException("The server refused to create the world " + NAME);
            }
        }
        return world;
    }

    static void configure(World world, ArenaWorldSettings settings) {
        // A death here outside a match (an admin building, say) keeps the player's items.
        // Match and waiting-room deaths already keep the inventory themselves.
        setRule(world, GameRules.KEEP_INVENTORY, true);
        setRule(world, GameRules.ADVANCE_TIME, false);
        setRule(world, GameRules.ADVANCE_WEATHER, false);
        setRule(world, GameRules.SPAWN_MOBS, false);
        setRule(world, GameRules.SPAWN_MONSTERS, false);
        setRule(world, GameRules.SPAWN_PATROLS, false);
        setRule(world, GameRules.SPAWN_PHANTOMS, false);
        setRule(world, GameRules.SPAWN_WANDERING_TRADERS, false);
        setRule(world, GameRules.SPAWN_WARDENS, false);

        world.setTime(FIXED_TIME);
        world.setStorm(false);
        world.setThundering(false);

        world.setViewDistance(settings.viewDistance());
        world.setSimulationDistance(settings.simulationDistance());

        WorldBorder border = world.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(settings.borderSize());
    }

    private static <T> void setRule(World world, GameRule<T> rule, T value) {
        if (!world.setGameRule(rule, value)) {
            throw new IllegalStateException("Could not set " + rule.getKey() + " in " + NAME);
        }
    }

    /** Changes distances without resetting the border, time, rules or saving state. */
    public void setDistances(int viewDistance, int simulationDistance) {
        settings = new ArenaWorldSettings(settings.borderSize(), viewDistance, simulationDistance);
        currentWorld().ifPresent(current -> {
            current.setViewDistance(settings.viewDistance());
            current.setSimulationDistance(settings.simulationDistance());
        });
    }

    /** Keeps a configuration reload's border when the next world generation opens. */
    public void setBorderSize(double size) {
        settings = new ArenaWorldSettings(size, settings.viewDistance(), settings.simulationDistance());
        currentWorld().ifPresent(current -> current.getWorldBorder().setSize(settings.borderSize()));
    }

    public double borderSize() {
        return settings.borderSize();
    }

    /** Reopens lazily and restores authoritative copies before publishing the new world. */
    public World world() {
        if (state == State.UNLOADED) {
            state = State.LOADING;
            World loaded = null;
            try {
                loaded = Objects.requireNonNull(loader.get(), "loaded world");
                preparing = loaded;
                if (state != State.LOADING) {
                    throw new IllegalStateException("Arena world was closed during loading");
                }
                if (identity != null && !identity.equals(preparing.getUID())) {
                    throw new IllegalStateException("Arena world identity changed while it was unloaded");
                }
                preparing.setAutoSave(false);
                configure(preparing, settings);
                restoreCopies.run();
                if (freezeBlockEvents) {
                    PaperBlockEvents.clear(preparing);
                }
                if (state != State.LOADING) {
                    throw new IllegalStateException("Arena world was closed during restoration");
                }
                world = loaded;
                preparing = null;
                state = State.READY;
            } catch (RuntimeException | Error failure) {
                // Keep a refused cleanup reachable for shutdown, but never publish it as usable.
                world = loaded;
                preparing = null;
                if (state != State.CLOSED) {
                    state = State.FAILED;
                }
                try {
                    if (world != null && unloader.test(world)) {
                        world = null;
                    }
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
        if (state != State.READY) {
            throw new IllegalStateException("Arena world is not ready: " + state);
        }
        return world;
    }

    /** Does not load a world; diagnostics and idle tasks use this instead of {@link #world()}. */
    public Optional<World> currentWorld() {
        return state == State.READY ? Optional.of(world) : Optional.empty();
    }

    /** For the registry's copy restoration only, while a new generation is being prepared. */
    public World copyWorld() {
        return state == State.LOADING && preparing != null ? preparing : world();
    }

    /** Installed after the initial registry reset; reopened worlds must never adopt dirty disk blocks. */
    public void onReopen(Runnable restoreCopies) {
        this.restoreCopies = Objects.requireNonNull(restoreCopies, "restoreCopies");
    }

    /** Holds the world through an asynchronous operation and its server-thread completion. */
    public Runnable acquire() {
        if (!acceptingOperations()) {
            throw new IllegalStateException("Arena world cannot accept an operation: " + state);
        }
        operations.incrementAndGet();
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                operations.decrementAndGet();
            }
        };
    }

    /** Rejects reentrant arena mutations while Bukkit load/unload events are being dispatched. */
    public boolean acceptingOperations() {
        return state == State.READY || state == State.UNLOADED;
    }

    /**
     * Drops the entire idle world without saving, including Paper's partial chunk holders.
     * A busy world is never retired, and a cancelled WorldUnloadEvent leaves it usable.
     */
    public boolean releaseIfIdle(BooleanSupplier registryIdle) {
        if (state != State.READY || savingEnabled || operations.get() != 0 || !registryIdle.getAsBoolean()
                || !world.getPlayers().isEmpty() || !world.getForceLoadedChunks().isEmpty()
                || !world.getPluginChunkTickets().isEmpty()) {
            return false;
        }
        state = State.UNLOADING;
        try {
            boolean released = unloader.test(world);
            if (state != State.UNLOADING) {
                return released;
            }
            if (!released) {
                return false;
            }
            world = null;
            state = State.UNLOADED;
            return true;
        } finally {
            if (state == State.UNLOADING) {
                state = State.READY;
            }
        }
    }

    public boolean isSavingEnabled() {
        return savingEnabled;
    }

    /**
     * Turns auto-save on or off. Turning it off first writes the world to disk, so the edits
     * made while it was on are kept; after that only a forced save or shutdown writes.
     */
    public void setSavingEnabled(boolean enabled) {
        if (enabled == savingEnabled) {
            return;
        }
        if (!enabled) {
            if (freezeBlockEvents) {
                PaperBlockEvents.clear(world());
            }
            world().save();
        }
        world().setAutoSave(enabled);
        savingEnabled = enabled;
    }

    /** Called on plugin disable: an edit left open is saved and closed. */
    public void close() {
        setSavingEnabled(false);
        state = State.CLOSED;
        world = null;
        preparing = null;
    }
}
