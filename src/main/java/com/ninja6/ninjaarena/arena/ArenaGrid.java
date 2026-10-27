package com.ninja6.ninjaarena.arena;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The fixed grid arenas are built on. The arena world is cut into square cells of
 * {@code cellSize} blocks, cell {@code (cx, cz)} covering blocks {@code cx * cellSize} to
 * {@code cx * cellSize + cellSize - 1} on each axis and the full world height. Each arena
 * owns one cell and must stay inside it, so arenas never overlap and each one's chunks are
 * its own.
 *
 * @param cellSize   cell edge in blocks, a multiple of 16 so cells line up with chunks
 * @param borderSize world border diameter, centred on 0, 0; a cell must lie wholly inside it
 * @param minY       lowest block height of the world, inclusive
 * @param maxY       highest block height of the world, exclusive
 */
public record ArenaGrid(int cellSize, double borderSize, int minY, int maxY) {

    public static final int DEFAULT_CELL_SIZE = 256;
    static final int MIN_CELL_SIZE = 32;
    static final int MAX_CELL_SIZE = 4096;

    public ArenaGrid {
        cellSize = Math.clamp(cellSize, MIN_CELL_SIZE, MAX_CELL_SIZE) / 16 * 16;
        if (maxY <= minY) {
            throw new IllegalArgumentException("maxY must be above minY");
        }
    }

    /** Reads the cell size from the {@code arena-grid} section, falling back to the default. */
    public static int cellSizeFrom(ConfigurationSection section) {
        return section == null ? DEFAULT_CELL_SIZE : section.getInt("cell-size", DEFAULT_CELL_SIZE);
    }

    public ArenaBounds cellBounds(GridCell cell) {
        int x = cell.x() * cellSize;
        int z = cell.z() * cellSize;
        return new ArenaBounds(x, minY, z, x + cellSize - 1, maxY - 1, z + cellSize - 1);
    }

    /** The cell holding the given block column. */
    public GridCell cellAt(int blockX, int blockZ) {
        return new GridCell(Math.floorDiv(blockX, cellSize), Math.floorDiv(blockZ, cellSize));
    }

    /** Whether the whole cell lies inside the world border. */
    public boolean insideBorder(GridCell cell) {
        double half = borderSize / 2;
        double low = Math.min(cell.x(), cell.z()) * (double) cellSize;
        double high = (Math.max(cell.x(), cell.z()) + 1) * (double) cellSize;
        return low >= -half && high <= half;
    }

    /**
     * Every cell inside the border, nearest to the origin first: ring by ring outwards, and
     * within a ring by x then z, so the order is stable.
     */
    public List<GridCell> cellsNearestFirst() {
        List<GridCell> cells = new ArrayList<>();
        int rings = (int) Math.ceil(borderSize / 2 / cellSize) + 1;
        for (int ring = 0; ring <= rings; ring++) {
            for (int x = -ring; x <= ring; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != ring) {
                        continue;
                    }
                    GridCell cell = new GridCell(x, z);
                    if (insideBorder(cell)) {
                        cells.add(cell);
                    }
                }
            }
        }
        return cells;
    }
}
