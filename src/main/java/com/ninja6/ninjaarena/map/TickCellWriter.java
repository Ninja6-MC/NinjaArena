package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import com.ninja6.ninjaarena.terrain.BlockKind;
import com.ninja6.ninjaarena.terrain.TerrainTheme;
import com.ninja6.ninjaarena.terrain.ThemePalette;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * A simple {@link CellWriter}: one queue of jobs, worked through on every server tick up to
 * a fixed number of blocks, the oldest job first. Every block a job visits counts towards
 * the budget, whether or not it is written. It does not watch tick times; tick-budgeted
 * placement replaces the fixed budget with one that shrinks when the server is slow.
 *
 * <p>Server thread only.
 */
public final class TickCellWriter implements CellWriter {

    /** Blocks visited per tick across every job. */
    public static final int DEFAULT_BLOCKS_PER_TICK = 32_768;

    private final BlockAccess blocks;
    private final int blocksPerTick;
    private final Deque<Job> jobs = new ArrayDeque<>();
    private BukkitTask task;

    public TickCellWriter(BlockAccess blocks) {
        this(blocks, DEFAULT_BLOCKS_PER_TICK);
    }

    /** @param blocksPerTick at least 1 */
    public TickCellWriter(BlockAccess blocks, int blocksPerTick) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        if (blocksPerTick < 1) {
            throw new IllegalArgumentException("blocksPerTick must be at least 1");
        }
        this.blocksPerTick = blocksPerTick;
    }

    /** Works the queue once a tick until {@link #close()}. */
    public void start(Plugin plugin) {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }
    }

    @Override
    public CompletableFuture<Void> place(ArenaLayout layout, int originX, int originY, int originZ) {
        Objects.requireNonNull(layout, "layout");
        return enqueue(new Place(layout, originX, originY, originZ));
    }

    @Override
    public CompletableFuture<Void> clear(ArenaBounds region) {
        Objects.requireNonNull(region, "region");
        return enqueue(new Clear(region));
    }

    private CompletableFuture<Void> enqueue(Job job) {
        jobs.addLast(job);
        return job.done;
    }

    /** Jobs not finished yet, cancelled ones included until a tick drops them. */
    public int pending() {
        return jobs.size();
    }

    /**
     * Visits up to the budget of blocks, finishing jobs in order. A job's future completes
     * here, so work it starts on completion is queued behind the jobs already waiting.
     *
     * @return the blocks visited
     */
    public int tick() {
        int budget = blocksPerTick;
        while (budget > 0 && !jobs.isEmpty()) {
            Job job = jobs.peekFirst();
            if (job.done.isDone()) {
                jobs.pollFirst();
                continue;
            }
            try {
                budget -= job.work(budget);
            } catch (RuntimeException e) {
                jobs.pollFirst();
                job.done.completeExceptionally(e);
                continue;
            }
            if (job.finished()) {
                jobs.pollFirst();
                job.done.complete(null);
            }
        }
        return blocksPerTick - budget;
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Job job : jobs) {
            job.done.cancel(false);
        }
        jobs.clear();
    }

    /** Work over a box of blocks, column by column, bottom to top. */
    private abstract static class Job {

        final CompletableFuture<Void> done = new CompletableFuture<>();
        private final int sizeY;
        private final int sizeZ;
        private final long volume;
        private long next;

        Job(int sizeX, int sizeY, int sizeZ) {
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.volume = (long) sizeX * sizeY * sizeZ;
        }

        /** @return the blocks visited, at most {@code budget} */
        int work(int budget) {
            int visited = 0;
            while (visited < budget && next < volume) {
                int y = (int) (next % sizeY);
                long column = next / sizeY;
                int z = (int) (column % sizeZ);
                int x = (int) (column / sizeZ);
                visit(x, y, z);
                next++;
                visited++;
            }
            return visited;
        }

        boolean finished() {
            return next >= volume;
        }

        /** One block, in coordinates from the box's minimum corner. */
        abstract void visit(int x, int y, int z);
    }

    private final class Place extends Job {

        private final ArenaLayout layout;
        private final TerrainTheme theme;
        private final int originX;
        private final int originY;
        private final int originZ;

        Place(ArenaLayout layout, int originX, int originY, int originZ) {
            super(layout.width(), layout.height(), layout.length());
            this.layout = layout;
            this.theme = layout.side().theme();
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }

        @Override
        void visit(int x, int y, int z) {
            BlockKind kind = layout.at(x, y, z);
            if (kind != BlockKind.AIR) {
                blocks.set(originX + x, originY + y, originZ + z, ThemePalette.material(theme, kind));
            }
        }
    }

    private final class Clear extends Job {

        private final ArenaBounds region;

        Clear(ArenaBounds region) {
            super(region.maxX() - region.minX() + 1, region.maxY() - region.minY() + 1,
                    region.maxZ() - region.minZ() + 1);
            this.region = region;
        }

        @Override
        void visit(int x, int y, int z) {
            int worldX = region.minX() + x;
            int worldY = region.minY() + y;
            int worldZ = region.minZ() + z;
            if (blocks.get(worldX, worldY, worldZ) != Material.AIR) {
                blocks.set(worldX, worldY, worldZ, Material.AIR);
            }
        }
    }
}
