package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import com.ninja6.ninjaarena.terrain.BlockKind;
import com.ninja6.ninjaarena.terrain.TerrainTheme;
import com.ninja6.ninjaarena.terrain.ThemePalette;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The {@link CellWriter} on the server: works its jobs once a server tick within a time
 * budget, sharing it fairly between slot cells.
 *
 * <p><b>Budget.</b> Each tick asks the budget ({@link PlacementBudget} on the server) how many
 * nanoseconds it may use and measures its work with the clock it was given. Work goes in
 * slices of at most {@link #SLICE} blocks, sized from the measured time per block so the
 * slice fits in what is left of the budget; the tick stops when no further block would fit.
 * The very first slice, before anything is measured, is {@link #PROBE} blocks. Every block a
 * job visits counts, whether or not it is written. A budget of 0 does nothing that tick.
 *
 * <p><b>Changes.</b> {@link #apply} jobs run one change per block, measured apart from
 * placing and clearing since a change may cost more than a plain block write. Changes in
 * every cell together run at most {@link CellWriter#MAX_CHANGES_PER_TICK} a tick; a cell
 * whose next job is changes and finds that limit reached waits for the next tick at the
 * back of the turns, and the other cells carry on.
 *
 * <p><b>Fairness.</b> Each cell has its own queue, worked in the order asked, so a clear
 * finishes before the placement queued after it. Cells with work take turns a slice at a
 * time, so four slots generating at once all move on together; a turn the budget cuts short
 * is finished first on the next tick, so no cell falls behind. A job's future completes
 * here, so work it starts on completion queues behind its cell's waiting jobs.
 *
 * <p>Server thread only.
 */
public final class BudgetedCellWriter implements CellWriter {

    /** The most blocks one cell works before the next cell's turn. */
    static final int SLICE = 1024;
    /** The first slice, before the time per block is known. */
    static final int PROBE = 16;

    private final BlockAccess blocks;
    private final LongSupplier budgetNanos;
    private final LongSupplier clock;
    private final Map<GridCell, Deque<Job>> queues = new HashMap<>();
    /** Cells with queued work, the next turn first. */
    private final Deque<GridCell> turns = new ArrayDeque<>();
    /** Measured nanoseconds per block visited; 0 until the first slice. */
    private double nanosPerBlock;
    /** Measured nanoseconds per change run; 0 until the first slice of changes. */
    private double nanosPerChange;
    private long visitedEver;
    /** Blocks left in the turn of the first cell in {@link #turns}, cut short by the budget; 0 for a new turn. */
    private int turnLeft;
    private BukkitTask task;

    /**
     * @param budgetNanos nanoseconds to spend in the current tick, asked once a tick with work
     * @param clock       nanoseconds, for measuring; {@link System#nanoTime()} on the server
     */
    public BudgetedCellWriter(BlockAccess blocks, LongSupplier budgetNanos, LongSupplier clock) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.budgetNanos = Objects.requireNonNull(budgetNanos, "budgetNanos");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Works the queues once a tick until {@link #close()}. */
    public void start(Plugin plugin) {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }
    }

    @Override
    public Work place(GridCell cell, ArenaLayout layout, int originX, int originY, int originZ) {
        Objects.requireNonNull(layout, "layout");
        return enqueue(cell, new Place(layout, originX, originY, originZ));
    }

    @Override
    public Work clear(GridCell cell, ArenaBounds region) {
        Objects.requireNonNull(region, "region");
        return enqueue(cell, new Clear(region));
    }

    @Override
    public Work apply(GridCell cell, List<? extends Runnable> changes) {
        Objects.requireNonNull(changes, "changes");
        return enqueue(cell, new Changes(List.copyOf(changes)));
    }

    private Work enqueue(GridCell cell, Job job) {
        Objects.requireNonNull(cell, "cell");
        Deque<Job> queue = queues.get(cell);
        if (queue == null) {
            queue = new ArrayDeque<>();
            queues.put(cell, queue);
            turns.addLast(cell);
        }
        queue.addLast(job);
        return job.work;
    }

    /** Jobs not finished yet, cancelled ones included until a tick drops them. */
    public int pending() {
        return queues.values().stream().mapToInt(Deque::size).sum();
    }

    /** Blocks visited since this writer was made, across every job. */
    public long visited() {
        return visitedEver;
    }

    /** Jobs not finished yet in one cell. */
    public int pending(GridCell cell) {
        Deque<Job> queue = queues.get(cell);
        return queue == null ? 0 : queue.size();
    }

    /**
     * Works the queues for up to this tick's budget, a slice per cell in turn.
     *
     * @return the blocks visited
     */
    public long tick() {
        if (turns.isEmpty()) {
            return 0;
        }
        long budget = budgetNanos.getAsLong();
        long start = clock.getAsLong();
        long now = start;
        long visited = 0;
        int changes = 0;
        List<GridCell> capped = new ArrayList<>();
        while (!turns.isEmpty()) {
            GridCell cell = turns.peekFirst();
            Deque<Job> queue = queues.get(cell);
            Job job = queue.peekFirst();
            if (job.work.isDone()) {
                // Cancelled: dropped without using the budget.
                turns.pollFirst();
                turnLeft = 0;
                finish(cell, queue);
                continue;
            }
            int quota = turnLeft > 0 ? turnLeft : SLICE;
            int fit = fits(job, budget - (now - start));
            int slice = Math.min(quota, fit);
            boolean change = job instanceof Changes;
            boolean cutByLimit = false;
            if (change && slice > MAX_CHANGES_PER_TICK - changes) {
                slice = MAX_CHANGES_PER_TICK - changes;
                cutByLimit = true;
            }
            if (slice < 1) {
                if (cutByLimit && fit >= 1) {
                    // No more changes this tick; the other cells may still have blocks to work.
                    turns.pollFirst();
                    turnLeft = 0;
                    capped.add(cell);
                    continue;
                }
                break;
            }
            turns.pollFirst();
            turnLeft = 0;
            long before = job.work.done();
            RuntimeException failure = null;
            try {
                job.run(slice);
            } catch (RuntimeException e) {
                failure = e;
            }
            int done = (int) (job.work.done() - before);
            visitedEver += done;
            visited += done;
            if (change) {
                changes += done;
            }
            long after = clock.getAsLong();
            if (failure != null) {
                finish(cell, queue);
                job.work.completeExceptionally(failure);
                now = after;
                continue;
            }
            measure(change, after - now, done);
            now = after;
            if (job.finished()) {
                finish(cell, queue);
                job.work.complete(null);
            } else if (done < quota && !cutByLimit) {
                // The budget ran out mid-turn: the cell finishes its turn first next time.
                turns.addFirst(cell);
                turnLeft = quota - done;
            } else {
                turns.addLast(cell);
            }
        }
        turns.addAll(capped);
        return visited;
    }

    /** Drops the cell's head job and gives the cell a later turn if it has more. */
    private void finish(GridCell cell, Deque<Job> queue) {
        queue.pollFirst();
        if (queue.isEmpty()) {
            queues.remove(cell);
        } else {
            turns.addLast(cell);
        }
    }

    /** Blocks or changes of the job that fit in what is left of the budget; at most {@link #SLICE}. */
    private int fits(Job job, long remaining) {
        if (remaining <= 0) {
            return 0;
        }
        double estimate = job instanceof Changes ? nanosPerChange : nanosPerBlock;
        if (estimate <= 0) {
            return PROBE;
        }
        return (int) Math.min(SLICE, (long) (remaining / estimate));
    }

    /** Folds a slice's time into its estimate: the newest slice counts for a quarter. */
    private void measure(boolean changes, long nanos, int blocks) {
        if (blocks <= 0 || nanos < 0) {
            return;
        }
        double sample = (double) nanos / blocks;
        if (changes) {
            nanosPerChange = nanosPerChange <= 0 ? sample : nanosPerChange * 0.75 + sample * 0.25;
        } else {
            nanosPerBlock = nanosPerBlock <= 0 ? sample : nanosPerBlock * 0.75 + sample * 0.25;
        }
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Deque<Job> queue : queues.values()) {
            for (Job job : queue) {
                job.work.cancel(false);
            }
        }
        queues.clear();
        turns.clear();
        turnLeft = 0;
    }

    /** Queued work: a number of blocks or changes, run a slice at a time. */
    private abstract static class Job {

        final Work work;

        Job(long total) {
            this.work = new Work(total);
        }

        /** Runs at most {@code budget} blocks or changes, counting them on {@link #work} as it goes. */
        abstract void run(int budget);

        abstract boolean finished();
    }

    /** Changes run one by one, in order. */
    private static final class Changes extends Job {

        private final List<? extends Runnable> changes;
        private int next;

        Changes(List<? extends Runnable> changes) {
            super(changes.size());
            this.changes = changes;
        }

        @Override
        void run(int budget) {
            int ran = 0;
            try {
                while (ran < budget && next < changes.size()) {
                    Runnable change = changes.get(next);
                    next++;
                    ran++;
                    change.run();
                }
            } finally {
                work.advance(ran);
            }
        }

        @Override
        boolean finished() {
            return next >= changes.size();
        }
    }

    /** Work over a box of blocks, column by column, bottom to top. */
    private abstract static class BoxJob extends Job {

        private final int sizeY;
        private final int sizeZ;
        private final long volume;
        private long next;

        BoxJob(int sizeX, int sizeY, int sizeZ) {
            super((long) sizeX * sizeY * sizeZ);
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.volume = (long) sizeX * sizeY * sizeZ;
        }

        @Override
        void run(int budget) {
            int visited = 0;
            try {
                while (visited < budget && next < volume) {
                    int y = (int) (next % sizeY);
                    long column = next / sizeY;
                    int z = (int) (column % sizeZ);
                    int x = (int) (column / sizeZ);
                    visit(x, y, z);
                    next++;
                    visited++;
                }
            } finally {
                work.advance(visited);
            }
        }

        @Override
        boolean finished() {
            return next >= volume;
        }

        /** One block, in coordinates from the box's minimum corner. */
        abstract void visit(int x, int y, int z);
    }

    private final class Place extends BoxJob {

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

    private final class Clear extends BoxJob {

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
