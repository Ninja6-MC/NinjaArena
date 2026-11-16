package com.ninja6.ninjaarena.lobby;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.lobby.GeneratedLobby.Blueprint;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Places a generated lobby over server ticks within a time budget, the way generated maps
 * are placed ({@code BudgetedCellWriter}): each tick asks the budget for nanoseconds, works
 * in slices sized from the measured time per step, and stops when no further step fits.
 *
 * <p>The steps, in order: the entities in the swept box other than players are removed,
 * the new floor is laid, so anyone arriving meanwhile has ground under them, then every
 * other block of the swept box is turned into the lobby's block or air, and last the
 * cushions are spawned. A block that already matches is left alone but counts as a step.
 * Server thread only.
 */
public final class LobbyPlacement {

    /** The most steps in one slice. */
    static final int SLICE = 1024;
    /** The first slice, before the time per step is known. */
    static final int PROBE = 16;

    /** Block reads and writes in the lobby world; tests keep the blocks in memory. */
    public interface Blocks {

        /** Whether the block is that state; null means air. */
        boolean is(int x, int y, int z, String state);

        /** Sets the block without physics; null means air. */
        void set(int x, int y, int z, String state);

        /** Removes every entity in the box except players. */
        void removeEntities(ArenaBounds box);

        void spawn(LobbyPiece.Cushion cushion);

        /** Keeps the box's chunks loaded until {@link #release}, so no slice loads one. */
        void hold(ArenaBounds box);

        /** Lets go of the chunks {@link #hold} kept loaded, and only those. */
        void release(ArenaBounds box);
    }

    private final Blocks blocks;
    private final Blueprint print;
    private final ArenaBounds floor;
    private final ArenaBounds sweep;
    private final List<LobbyPiece.Cushion> cushions;
    private final long floorSteps;
    private final long sweepSteps;
    private final long total;
    private long done;
    private double nanosPerStep;

    /**
     * @param floor the lobby's floor, laid first; one block high
     * @param sweep every block of this box ends as the lobby's block or air; it holds the floor
     */
    public LobbyPlacement(Blocks blocks, Blueprint print, ArenaBounds floor, ArenaBounds sweep) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.print = Objects.requireNonNull(print, "print");
        this.floor = Objects.requireNonNull(floor, "floor");
        this.sweep = Objects.requireNonNull(sweep, "sweep");
        if (floor.minY() != floor.maxY() || !sweep.contains(floor)) {
            throw new IllegalArgumentException("the floor must be one block high and inside the swept box");
        }
        this.cushions = print.cushions();
        this.floorSteps = volume(floor);
        this.sweepSteps = volume(sweep);
        this.total = 1 + floorSteps + sweepSteps + cushions.size();
    }

    private static long volume(ArenaBounds box) {
        return (long) (box.maxX() - box.minX() + 1) * (box.maxY() - box.minY() + 1) * (box.maxZ() - box.minZ() + 1);
    }

    /** Steps in all: one for the entities, one per block of the floor and of the swept box, one per cushion. */
    public long total() {
        return total;
    }

    public long done() {
        return done;
    }

    public boolean finished() {
        return done >= total;
    }

    /**
     * Works up to this tick's budget.
     *
     * @param budget nanoseconds this tick may use; 0 or less does nothing
     * @param clock  nanoseconds, for measuring; {@link System#nanoTime()} on the server
     * @return the steps worked
     */
    public long tick(long budget, LongSupplier clock) {
        long start = clock.getAsLong();
        long now = start;
        long worked = 0;
        while (!finished()) {
            long remaining = budget - (now - start);
            if (remaining <= 0) {
                break;
            }
            int slice = nanosPerStep <= 0 ? PROBE : (int) Math.min(SLICE, (long) (remaining / nanosPerStep));
            if (slice < 1) {
                break;
            }
            int stepped = run(slice);
            long after = clock.getAsLong();
            if (stepped > 0 && after >= now) {
                double measured = (double) (after - now) / stepped;
                nanosPerStep = nanosPerStep <= 0 ? measured : nanosPerStep * 0.75 + measured * 0.25;
            }
            now = after;
            worked += stepped;
        }
        return worked;
    }

    /** Works up to {@code steps} steps, whatever the time; for tests and the budgeted tick. */
    public int run(int steps) {
        int stepped = 0;
        while (stepped < steps && !finished()) {
            step(done);
            done++;
            stepped++;
        }
        return stepped;
    }

    private void step(long index) {
        if (index == 0) {
            blocks.removeEntities(sweep);
            return;
        }
        index--;
        if (index < floorSteps) {
            int[] at = position(floor, index);
            write(at[0], at[1], at[2]);
            return;
        }
        index -= floorSteps;
        if (index < sweepSteps) {
            int[] at = position(sweep, index);
            if (!floor.contains(at[0], at[1], at[2])) {
                write(at[0], at[1], at[2]);
            }
            return;
        }
        index -= sweepSteps;
        blocks.spawn(cushions.get((int) index));
    }

    private void write(int x, int y, int z) {
        String state = print.at(x, y, z);
        if (!blocks.is(x, y, z, state)) {
            blocks.set(x, y, z, state);
        }
    }

    /** The block at an index of a box, going up a column at a time, north to south then west to east. */
    private static int[] position(ArenaBounds box, long index) {
        int height = box.maxY() - box.minY() + 1;
        int depth = box.maxZ() - box.minZ() + 1;
        int y = (int) (index % height);
        long column = index / height;
        int z = (int) (column % depth);
        int x = (int) (column / depth);
        return new int[] {box.minX() + x, box.minY() + y, box.minZ() + z};
    }
}
