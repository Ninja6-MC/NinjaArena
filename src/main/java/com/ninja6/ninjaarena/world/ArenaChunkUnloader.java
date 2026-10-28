package com.ninja6.ninjaarena.world;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Unloads arena world chunks that nothing holds, without saving them.
 *
 * <p>With auto-save off the server never unloads a chunk in the arena world, so every chunk
 * loaded there (by a visit, a match or the startup reset) stays loaded until shutdown, and
 * the shutdown save then writes all of them. Every few seconds this unloads, with saving
 * off, the loaded chunks that have no ticket left: no player near, no held arena, no force
 * load. Their load level is then {@link Chunk.LoadLevel#INACCESSIBLE}; any ticket raises it.
 * Nothing in such a chunk is meant to be kept: arenas are put back from their saved copies
 * before every match and on every start, and outside the arenas only edit mode builds.
 *
 * <p>Nothing is unloaded while world saving is on (an arena is in edit mode): the server
 * then unloads chunks itself and saves them, which keeps the edit. If a ticketless chunk
 * refuses to unload, the sweep asks for it again with saving allowed, so it is not left
 * marked as never to be saved, and goes on to the next chunk; a later sweep tries it again.
 * The first refusal of a run is logged.
 *
 * <p>Call it from the server thread.
 */
public final class ArenaChunkUnloader {

    /** Ticks between sweeps. */
    static final long PERIOD_TICKS = 100;

    /** At most this many chunks are unloaded in one sweep, so a backlog is spread over ticks. */
    static final int MAX_PER_SWEEP = 64;

    private final World world;
    private final BooleanSupplier saving;
    private final Logger logger;

    private long unloaded;
    private long refused;
    private BukkitTask task;

    /**
     * @param saving whether the arena world's saving is on ({@code ArenaWorld#isSavingEnabled})
     */
    public ArenaChunkUnloader(World world, BooleanSupplier saving, Logger logger) {
        this.world = Objects.requireNonNull(world, "world");
        this.saving = Objects.requireNonNull(saving, "saving");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Sweeps every {@link #PERIOD_TICKS} ticks until {@link #close()}. */
    public void start(Plugin plugin) {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, PERIOD_TICKS, PERIOD_TICKS);
        }
    }

    /**
     * Unloads up to {@link #MAX_PER_SWEEP} ticketless chunks without saving them.
     *
     * @return how many were unloaded
     */
    public int sweep() {
        if (saving.getAsBoolean()) {
            return 0;
        }
        int count = 0;
        for (Chunk chunk : world.getLoadedChunks()) {
            if (count >= MAX_PER_SWEEP) {
                break;
            }
            if (chunk.getLoadLevel() != Chunk.LoadLevel.INACCESSIBLE) {
                continue;
            }
            int x = chunk.getX();
            int z = chunk.getZ();
            if (world.unloadChunk(x, z, false)) {
                count++;
                continue;
            }
            // A refused unload still marks the chunk as never to be saved; asking again with
            // saving allowed clears the mark, so a later edit in it is kept.
            world.unloadChunk(x, z, true);
            refused++;
            if (refused == 1) {
                logger.warning("Chunk " + x + ", " + z + " in " + world.getName() + " has no ticket but would not"
                        + " unload; it is tried again on the next sweep.");
            }
        }
        unloaded += count;
        return count;
    }

    /** Chunks unloaded since the start. */
    public long unloaded() {
        return unloaded;
    }

    /** Ticketless chunks that refused to unload since the start, counted once per attempt. */
    public long refused() {
        return refused;
    }

    /** Stops sweeping. Called on plugin disable. */
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
