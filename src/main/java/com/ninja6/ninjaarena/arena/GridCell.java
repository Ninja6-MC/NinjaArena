package com.ninja6.ninjaarena.arena;

/**
 * A cell of the arena grid, by index. Cell {@code (0, 0)} starts at block {@code 0, 0}; see
 * {@link ArenaGrid#cellBounds(GridCell)}.
 */
public record GridCell(int x, int z) {

    public String describe() {
        return x + ", " + z;
    }
}
