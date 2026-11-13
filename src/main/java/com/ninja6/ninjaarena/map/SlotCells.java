package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.GridCell;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The arena grid cells reserved for generated maps, one per arena slot
 * ({@code arenas.max-active}). Hand-built arenas never use them: {@code arena create} skips
 * them, and a hand-built arena already in a cell keeps it.
 *
 * <p><b>Which cells.</b> Cells are taken farthest from the origin first: the reverse of
 * {@link ArenaGrid#cellsNearestFirst()}, the order {@code arena create} fills, so the two
 * meet only on a full grid. A cell that holds a hand-built arena is passed over and the
 * next free one is reserved instead, which is logged. The choice depends only on the grid,
 * the count and the hand-built arenas' cells, so it is the same after every restart while
 * {@code config.yml} and {@code arenas.yml} are unchanged.
 *
 * <p><b>Changing the count.</b> {@link #resize} keeps every cell already reserved, in
 * order: a higher count adds cells after them, a lower one gives up the last ones, except a
 * cell a generated map still uses, which stays reserved until it is free ({@link #trim}).
 * So a reload never moves a cell, and never takes one from under a leased slot.
 *
 * <p>Server thread only.
 */
public final class SlotCells {

    private ArenaGrid grid;
    private final Supplier<? extends Collection<GridCell>> handBuilt;
    private final Consumer<String> log;
    private final List<GridCell> reserved = new ArrayList<>();
    private int wanted;

    /**
     * Reserves no cell yet; call {@link #resize}.
     *
     * @param handBuilt the cells hand-built arenas are in, asked at each resize
     * @param log       told about passed-over and missing cells, as server log lines
     */
    public SlotCells(ArenaGrid grid, Supplier<? extends Collection<GridCell>> handBuilt, Consumer<String> log) {
        this.grid = Objects.requireNonNull(grid, "grid");
        this.handBuilt = Objects.requireNonNull(handBuilt, "handBuilt");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** The reserved cells, slot 1 first; a cell kept past a lower count comes last. */
    public List<GridCell> cells() {
        return List.copyOf(reserved);
    }

    public boolean isReserved(GridCell cell) {
        return reserved.contains(cell);
    }

    /** How many cells were asked for: the slot count. */
    public int wanted() {
        return wanted;
    }

    /**
     * Reserves {@code count} cells, keeping the ones reserved already.
     *
     * @param inUse cells a generated map uses; never given up
     */
    public void resize(int count, Predicate<GridCell> inUse) {
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative");
        }
        wanted = count;
        Set<GridCell> built = new HashSet<>(handBuilt.get());
        List<GridCell> next = new ArrayList<>();
        int kept = 0;
        for (GridCell cell : reserved) {
            if (kept < count && !built.contains(cell)) {
                next.add(cell);
                kept++;
            } else if (inUse.test(cell)) {
                next.add(cell);
            }
        }
        List<GridCell> candidates = new ArrayList<>(grid.cellsNearestFirst()).reversed();
        for (GridCell cell : candidates) {
            if (kept >= count) {
                break;
            }
            if (next.contains(cell)) {
                continue;
            }
            if (built.contains(cell)) {
                log.accept("Cell " + cell.describe() + " holds a hand-built arena, so it is not reserved for"
                        + " generated maps; the next free cell is used instead.");
                continue;
            }
            next.add(cell);
            kept++;
        }
        if (kept < count) {
            log.accept("arenas.max-active asks for " + count + " slot cell(s) for generated maps, but only " + kept
                    + " grid cell(s) are free of hand-built arenas; generated maps use " + kept + ".");
        }
        reserved.clear();
        reserved.addAll(next);
    }

    /**
     * Gives up the cells kept past a lower count once no generated map uses them. The cells
     * within the count stay.
     */
    public void trim(Predicate<GridCell> inUse) {
        List<GridCell> next = new ArrayList<>();
        Set<GridCell> built = new HashSet<>(handBuilt.get());
        int kept = 0;
        for (GridCell cell : reserved) {
            if (kept < wanted && !built.contains(cell)) {
                next.add(cell);
                kept++;
            } else if (inUse.test(cell)) {
                next.add(cell);
            }
        }
        reserved.clear();
        reserved.addAll(next);
    }

    /**
     * The grid changed: every cell is chosen again on the new grid. Only while no generated
     * map uses a cell.
     */
    public void setGrid(ArenaGrid grid) {
        this.grid = Objects.requireNonNull(grid, "grid");
        reserved.clear();
        resize(wanted, cell -> false);
    }
}
