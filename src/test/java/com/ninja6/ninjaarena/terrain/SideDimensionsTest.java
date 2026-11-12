package com.ninja6.ninjaarena.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaGrid;
import org.junit.jupiter.api.Test;

class SideDimensionsTest {

    @Test
    void defaultSideFitsTheDefaultCellWithAMargin() {
        assertEquals(new SideDimensions(96, 72, 64), SideDimensions.DEFAULT);
        assertEquals(SideDimensions.DEFAULT, SideDimensions.forCell(ArenaGrid.DEFAULT_CELL_SIZE));
        assertTrue(SideDimensions.DEFAULT.fitsCell(240));
        assertFalse(SideDimensions.DEFAULT.fitsCell(224));
        assertEquals(8, SideDimensions.DEFAULT.spawnDepth());
        assertEquals(6, SideDimensions.DEFAULT.centreDepth());
        assertEquals(8, SideDimensions.DEFAULT.blendDepth());
    }

    @Test
    void everyGridCellSizeGetsASideThatFits() {
        for (int cell = 32; cell <= 4096; cell += 16) {
            SideDimensions side = SideDimensions.forCell(cell);
            assertTrue(side.fitsCell(cell), "cell " + cell);
            assertTrue(side.width() <= SideDimensions.CAPPED_WIDTH && side.length() <= SideDimensions.CAPPED_LENGTH);
        }
        assertEquals(new SideDimensions(8, 11, 64), SideDimensions.forCell(32));
        assertEquals(new SideDimensions(192, 144, 64), SideDimensions.forCell(4096));
    }

    @Test
    void outOfRangeSizesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SideDimensions(7, 72, 64));
        assertThrows(IllegalArgumentException.class, () -> new SideDimensions(96, 513, 64));
        assertThrows(IllegalArgumentException.class, () -> new SideDimensions(96, 72, 31));
        assertThrows(IllegalArgumentException.class, () -> new SideDimensions(96, 72, 257));
        assertThrows(IllegalArgumentException.class, () -> SideDimensions.forCell(16));
    }
}
