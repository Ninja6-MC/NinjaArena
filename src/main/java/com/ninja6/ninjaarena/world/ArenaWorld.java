package com.ninja6.ninjaarena.world;

import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;

/**
 * The void world every arena is built in. It is created on first start and loaded on every
 * start after that, with auto-save off, so the periodic save never runs during a match. Chunk
 * unloads and the shutdown save can still write; that gap is tracked in #43. Edit mode turns
 * auto-save on while an arena is being built and off again when it is done.
 */
public final class ArenaWorld {

    public static final String NAME = "ninjaarena_arenas";

    /** Noon, so the sky is the same in every arena. */
    static final long FIXED_TIME = 6000;

    private final World world;
    private boolean savingEnabled;

    ArenaWorld(World world) {
        this.world = Objects.requireNonNull(world, "world");
        world.setAutoSave(false);
    }

    /**
     * Loads the arena world, creating it if it does not exist yet, and applies the settings
     * and game rules. They are reapplied on every load, so a change to {@code config.yml}
     * takes effect on the next start.
     */
    public static ArenaWorld load(ArenaWorldSettings settings, Logger logger) {
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
        ArenaWorld arenaWorld = new ArenaWorld(world);
        configure(world, settings);
        logger.info("Arena world " + NAME + " loaded with saving off.");
        return arenaWorld;
    }

    private static void configure(World world, ArenaWorldSettings settings) {
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

    public World world() {
        return world;
    }

    public boolean isSavingEnabled() {
        return savingEnabled;
    }

    /**
     * Turns auto-save on or off. Turning it off first writes the world to disk, so the edits
     * made while it was on are kept; after that only chunk unloads and shutdown can write.
     */
    public void setSavingEnabled(boolean enabled) {
        if (enabled == savingEnabled) {
            return;
        }
        if (!enabled) {
            world.save();
        }
        world.setAutoSave(enabled);
        savingEnabled = enabled;
    }

    /** Called on plugin disable: an edit left open is saved and closed. */
    public void close() {
        setSavingEnabled(false);
    }
}
