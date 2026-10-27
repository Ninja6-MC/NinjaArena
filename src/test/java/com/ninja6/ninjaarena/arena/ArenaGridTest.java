package com.ninja6.ninjaarena.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArenaGridTest {

    private final ArenaGrid grid = Arenas.GRID;

    @Test
    void cellCoversItsBlocksAndTheFullHeight() {
        assertEquals(new ArenaBounds(0, -64, 0, 255, 319, 255), grid.cellBounds(new GridCell(0, 0)));
        assertEquals(new ArenaBounds(-256, -64, 512, -1, 319, 767), grid.cellBounds(new GridCell(-1, 2)));
    }

    @Test
    void cellAtFloorsNegativeCoordinates() {
        assertEquals(new GridCell(0, 0), grid.cellAt(0, 255));
        assertEquals(new GridCell(-1, -1), grid.cellAt(-1, -256));
        assertEquals(new GridCell(-2, 1), grid.cellAt(-257, 256));
    }

    @Test
    void onlyWholeCellsInsideTheBorderCount() {
        assertTrue(grid.insideBorder(new GridCell(-4, -4)));
        assertTrue(grid.insideBorder(new GridCell(3, 3)));
        assertFalse(grid.insideBorder(new GridCell(4, 0)));
        assertFalse(grid.insideBorder(new GridCell(0, -5)));
    }

    @Test
    void cellsNearestFirstListsEveryCellInsideTheBorderOnce() {
        List<GridCell> cells = grid.cellsNearestFirst();
        assertEquals(64, cells.size());
        assertEquals(64, new HashSet<>(cells).size());
        assertEquals(new GridCell(0, 0), cells.get(0));
        assertTrue(cells.stream().allMatch(grid::insideBorder));
    }

    @Test
    void cellSizeIsClampedAndChunkAligned() {
        assertEquals(32, new ArenaGrid(1, 2048, 0, 256).cellSize());
        assertEquals(4096, new ArenaGrid(100_000, 2048, 0, 256).cellSize());
        assertEquals(96, new ArenaGrid(100, 2048, 0, 256).cellSize());
    }

    @Test
    void boundsChunkRangeCoversNegativeBlocks() {
        ArenaBounds bounds = new ArenaBounds(-17, 0, 15, 16, 10, 16);
        assertEquals(-2, bounds.minChunkX());
        assertEquals(1, bounds.maxChunkX());
        assertEquals(0, bounds.minChunkZ());
        assertEquals(1, bounds.maxChunkZ());
    }

    @Test
    void boundsOrderTheirCorners() {
        assertEquals(new ArenaBounds(1, 2, 3, 4, 5, 6), new ArenaBounds(4, 2, 6, 1, 5, 3));
    }
}
