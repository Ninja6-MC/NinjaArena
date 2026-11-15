package com.ninja6.ninjaarena.terrain;

import com.ninja6.ninjaarena.arena.ArenaGrid;

/**
 * The size of one team's generated side, in blocks.
 *
 * <p>Model axes: {@code x} runs across the side ({@code 0} to {@code width - 1}, left to right
 * for a player at the spawn facing the centre), {@code z} runs from the spawn edge
 * ({@code z = 0}) to the centre-facing edge ({@code z = length - 1}), and {@code y} is the
 * height above the model's floor ({@code 0} to {@code height - 1}). Placement chooses the
 * world height of {@code y = 0}.
 *
 * <p>A map is a centre square of {@code width} by {@code width} with one side against each of
 * its edges that a team uses: two opposite edges for 2 teams (mirrored), all four for 4 teams
 * (turned 90 degrees each). Keeping the centre at least as wide as a side stops turned sides
 * overlapping at the corners, so a map spans {@code width + 2 * length} on each axis and
 * {@link #fitsCell} checks that against an {@link ArenaGrid} cell.
 *
 * @param width  across the side, from {@link #MIN_EDGE} to {@link #MAX_EDGE}
 * @param length from the spawn edge to the centre edge, from {@link #MIN_EDGE} to {@link #MAX_EDGE}
 * @param height model height, from {@link #MIN_HEIGHT} to {@link #MAX_HEIGHT}
 */
public record SideDimensions(int width, int length, int height) {

    public static final int MIN_EDGE = 8;
    public static final int MAX_EDGE = 512;
    public static final int MIN_HEIGHT = 32;
    public static final int MAX_HEIGHT = 256;

    /** The height every grid-sized side uses: room for terrain, ravines and platforms. */
    public static final int DEFAULT_HEIGHT = 64;

    /** {@link #forCell} keeps sides at most this wide, so a large cell does not grow memory. */
    static final int CAPPED_WIDTH = 192;

    /** {@link #forCell} keeps sides at most this long. */
    static final int CAPPED_LENGTH = 144;

    /** 96 wide, 72 long and 64 high: the side for the default 256-block cell. */
    public static final SideDimensions DEFAULT = forCell(ArenaGrid.DEFAULT_CELL_SIZE);

    public SideDimensions {
        if (width < MIN_EDGE || width > MAX_EDGE || length < MIN_EDGE || length > MAX_EDGE) {
            throw new IllegalArgumentException("width and length must be " + MIN_EDGE + " to " + MAX_EDGE);
        }
        if (height < MIN_HEIGHT || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("height must be " + MIN_HEIGHT + " to " + MAX_HEIGHT);
        }
    }

    /**
     * The side for an arena grid cell: three eighths of the cell wide, and long enough that
     * the centre and two sides leave a margin of one thirty-second of the cell at each end,
     * capped at {@link #CAPPED_WIDTH} by {@link #CAPPED_LENGTH}. A 256-block cell gives
     * 96 by 72 (a 240-block map with 8 blocks spare at each end); the 32-block minimum gives
     * 8 by 11.
     */
    public static SideDimensions forCell(int cellSize) {
        if (cellSize < 32) {
            throw new IllegalArgumentException("cell size must be at least 32");
        }
        int width = Math.min(CAPPED_WIDTH, Math.max(MIN_EDGE, cellSize * 3 / 8 / 8 * 8));
        int length = Math.min(CAPPED_LENGTH, Math.max(MIN_EDGE, (cellSize - width) / 2 - cellSize / 32));
        return new SideDimensions(width, length, DEFAULT_HEIGHT);
    }

    /** Whether the centre and a side on each of its edges fit inside a cell of this size. */
    public boolean fitsCell(int cellSize) {
        return width + 2L * length <= cellSize;
    }

    /** Number of cells in the model. */
    public int volume() {
        return width * length * height;
    }

    /** Rows {@code z = 0} to {@code spawnDepth() - 1} are the flat spawn zone. */
    public int spawnDepth() {
        return Math.max(2, length / 9);
    }

    /** Rows {@code z = length - centreDepth()} to {@code length - 1} are the flat centre ledge. */
    public int centreDepth() {
        return Math.max(2, length / 12);
    }

    /** Rows over which the flat zones ease into the full terrain. */
    public int blendDepth() {
        return Math.max(1, length / 9);
    }

    /**
     * Whether a row may hold hazards: outside both flat zones and their blend rows, which
     * stay clear so the spawn and the centre are never reached by a hazard.
     */
    public boolean hazardRow(int z) {
        return z >= spawnDepth() + blendDepth() && z < length - centreDepth() - blendDepth();
    }
}
