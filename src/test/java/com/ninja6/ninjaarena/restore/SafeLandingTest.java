package com.ninja6.ninjaarena.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.restore.SafeLanding.Cell;
import com.ninja6.ninjaarena.restore.SafeLanding.Spot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SafeLandingTest {

    /** Open air by default, with blocks set one by one. */
    private static final class Grid implements SafeLanding.Terrain {

        private final Map<List<Integer>, Cell> cells = new HashMap<>();

        Grid set(int x, int y, int z, Cell cell) {
            cells.put(List.of(x, y, z), cell);
            return this;
        }

        /** A solid floor at y = 63 across the whole search area and beyond. */
        Grid floor() {
            for (int x = -20; x <= 20; x++) {
                for (int z = -20; z <= 20; z++) {
                    set(x, 63, z, Cell.SOLID);
                }
            }
            return this;
        }

        /** Fills a box, both corners included. */
        Grid fill(int x1, int y1, int z1, int x2, int y2, int z2, Cell cell) {
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        set(x, y, z, cell);
                    }
                }
            }
            return this;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 128;
        }

        @Override
        public Cell at(int x, int y, int z) {
            if (y < minY() || y >= maxY()) {
                throw new AssertionError("read outside the world at y=" + y);
            }
            return cells.getOrDefault(List.of(x, y, z), Cell.OPEN);
        }
    }

    @Test
    void aSafeSavedSpotIsKeptExactly() {
        Grid grid = new Grid().floor();

        assertEquals(Optional.of(new Spot(0.3, 64.0, -0.7, false)), SafeLanding.find(grid, 0.3, 64.0, -0.7));
    }

    @Test
    void solidAtTheFeetIsUnsafe() {
        Grid grid = new Grid().floor().set(0, 64, 0, Cell.SOLID);

        assertFalse(SafeLanding.isSafe(grid, 0.5, 64, 0.5));
    }

    @Test
    void solidAtTheHeadIsUnsafe() {
        Grid grid = new Grid().floor().set(0, 65, 0, Cell.SOLID);

        assertFalse(SafeLanding.isSafe(grid, 0.5, 64, 0.5));
    }

    @Test
    void lavaOrFireAtTheFeetIsUnsafe() {
        assertFalse(SafeLanding.isSafe(new Grid().floor().set(0, 64, 0, Cell.LAVA), 0.5, 64, 0.5));
        assertFalse(SafeLanding.isSafe(new Grid().floor().set(0, 64, 0, Cell.FIRE), 0.5, 64, 0.5));
    }

    @Test
    void lavaOrFireBelowIsUnsafe() {
        assertFalse(SafeLanding.isSafe(new Grid().floor().set(0, 63, 0, Cell.LAVA), 0.5, 64, 0.5));
        assertFalse(SafeLanding.isSafe(new Grid().floor().set(0, 63, 0, Cell.FIRE), 0.5, 64, 0.5));
    }

    @Test
    void lavaUnderAGapIsStillUnsafe() {
        Grid grid = new Grid().set(0, 60, 0, Cell.LAVA);

        assertFalse(SafeLanding.isSafe(grid, 0.5, 64, 0.5));
    }

    @Test
    void theVoidBelowIsUnsafe() {
        assertFalse(SafeLanding.isSafe(new Grid(), 0.5, 64, 0.5));
    }

    @Test
    void groundAFewBlocksDownIsSafe() {
        assertTrue(SafeLanding.isSafe(new Grid().floor(), 0.5, 66, 0.5));
    }

    @Test
    void standingOnASlabIsNotMistakenForBeingInsideIt() {
        // A bottom slab at y = 63 puts the feet at 63.5, inside the slab's block.
        Grid grid = new Grid().set(0, 62, 0, Cell.SOLID).set(0, 63, 0, Cell.SOLID);

        assertTrue(SafeLanding.isSafe(grid, 0.5, 63.5, 0.5));
    }

    @Test
    void aFilledInSpotMovesToTheNearestStandableSpot() {
        // The entry spot and its column are buried; the open floor starts two blocks east.
        Grid grid = new Grid().floor().fill(-1, 64, -1, 1, 70, 1, Cell.SOLID);

        Optional<Spot> spot = SafeLanding.find(grid, 0.5, 64, 0.5);

        assertTrue(spot.isPresent());
        assertTrue(spot.get().moved());
        assertTrue(SafeLanding.isStandable(grid, (int) Math.floor(spot.get().x()), (int) spot.get().y(),
                (int) Math.floor(spot.get().z())));
        assertEquals(64.0, spot.get().y());
        assertEquals(2, Math.abs((int) Math.floor(spot.get().x())) + Math.abs((int) Math.floor(spot.get().z())));
    }

    @Test
    void aSpotFilledWithLavaMovesOutOfIt() {
        Grid grid = new Grid().floor().fill(-3, 63, -3, 3, 64, 3, Cell.LAVA);

        Spot spot = SafeLanding.find(grid, 0.5, 64, 0.5).orElseThrow();

        int x = (int) Math.floor(spot.x());
        int z = (int) Math.floor(spot.z());
        assertTrue(Math.abs(x) > 3 || Math.abs(z) > 3);
        assertEquals(Cell.SOLID, grid.at(x, 63, z));
    }

    @Test
    void aTieGoesToTheSameHeight() {
        // Buried at y = 64; one block up and one block east are equally close.
        Grid grid = new Grid().set(0, 64, 0, Cell.SOLID).set(1, 63, 0, Cell.SOLID);

        assertEquals(new Spot(1.5, 64, 0.5, true), SafeLanding.find(grid, 0.5, 64, 0.5).orElseThrow());
    }

    @Test
    void aTieBetweenHigherAndLowerGoesHigher() {
        // Lava at the feet; the nearest standable spots are one up to the east and one down to the west.
        Grid grid = new Grid()
                .set(0, 64, 0, Cell.LAVA)
                .set(1, 64, 0, Cell.SOLID)
                .set(-1, 62, 0, Cell.SOLID);

        assertEquals(new Spot(1.5, 65, 0.5, true), SafeLanding.find(grid, 0.5, 64, 0.5).orElseThrow());
    }

    @Test
    void nothingWithinEightBlocksGivesEmpty() {
        // Ground only at nine blocks out, beyond the search.
        Grid grid = new Grid().set(9, 63, 0, Cell.SOLID);

        assertEquals(Optional.empty(), SafeLanding.find(grid, 0.5, 64, 0.5));
    }

    @Test
    void groundExactlyEightBlocksOutIsFound() {
        Grid grid = new Grid().set(8, 63, 0, Cell.SOLID);

        assertEquals(Optional.of(new Spot(8.5, 64, 0.5, true)), SafeLanding.find(grid, 0.5, 64, 0.5));
    }

    @Test
    void theSearchStaysInsideTheWorld() {
        // Saved at the very bottom over the void; the grid throws on any read outside it.
        Grid grid = new Grid().set(3, 0, 0, Cell.SOLID);

        assertEquals(Optional.of(new Spot(3.5, 1, 0.5, true)), SafeLanding.find(grid, 0.5, 0, 0.5));
        assertTrue(SafeLanding.find(new Grid(), 0.5, 127, 0.5).isEmpty());
    }
}
