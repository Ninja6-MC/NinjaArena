package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.portal.PortalFrame.Cell;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PortalFrameTest {

    /** Blocks that are not set are solid ground of some other kind. */
    static final class Grid implements PortalFrame.Blocks {

        final Map<String, Cell> cells = new HashMap<>();

        @Override
        public Cell at(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, Cell.OTHER);
        }

        void set(int x, int y, int z, Cell cell) {
            cells.put(x + "," + y + "," + z, cell);
        }

        /**
         * Builds a frame around an opening whose lower corner is at {@code a, y} along the plane,
         * with the plane at {@code fixed} on the other axis, and leaves the opening empty.
         */
        Grid frame(boolean alongX, int a, int y, int fixed, int width, int height, boolean corners) {
            for (int i = -1; i <= width; i++) {
                for (int j = -1; j <= height; j++) {
                    boolean edgeA = i == -1 || i == width;
                    boolean edgeY = j == -1 || j == height;
                    Cell cell;
                    if (!edgeA && !edgeY) {
                        cell = Cell.EMPTY;
                    } else if (edgeA && edgeY && !corners) {
                        continue;
                    } else {
                        cell = Cell.FRAME;
                    }
                    put(alongX, a + i, y + j, fixed, cell);
                }
            }
            return this;
        }

        void put(boolean alongX, int a, int y, int fixed, Cell cell) {
            if (alongX) {
                set(a, y, fixed, cell);
            } else {
                set(fixed, y, a, cell);
            }
        }
    }

    private static Optional<ArenaBounds> find(Grid grid, int x, int y, int z) {
        return PortalFrame.find(grid, x, y, z);
    }

    @Test
    void theSmallestFrameIsFoundFromAnyBlockOfItsOpeningAlongX() {
        Grid grid = new Grid().frame(true, 10, 64, 5, 2, 3, false);
        ArenaBounds opening = new ArenaBounds(10, 64, 5, 11, 66, 5);
        for (int x = 10; x <= 11; x++) {
            for (int y = 64; y <= 66; y++) {
                assertEquals(Optional.of(opening), find(grid, x, y, 5), x + "," + y);
            }
        }
        assertTrue(PortalFrame.alongX(opening));
    }

    @Test
    void aFrameAlongZIsFoundToo() {
        Grid grid = new Grid().frame(false, -20, 70, 3, 4, 5, true);
        ArenaBounds opening = new ArenaBounds(3, 70, -20, 3, 74, -17);
        assertEquals(Optional.of(opening), find(grid, 3, 72, -18));
        assertFalse(PortalFrame.alongX(opening));
    }

    @Test
    void theLargestFrameIsFoundAndOneBlockMoreIsNot() {
        Grid largest = new Grid().frame(true, 0, 64, 0, 21, 21, true);
        assertEquals(Optional.of(new ArenaBounds(0, 64, 0, 20, 84, 0)), find(largest, 0, 84, 0));
        assertEquals(Optional.of(new ArenaBounds(0, 64, 0, 20, 84, 0)), find(largest, 20, 64, 0));

        assertEquals(Optional.empty(), find(new Grid().frame(true, 0, 64, 0, 22, 3, true), 5, 65, 0));
        assertEquals(Optional.empty(), find(new Grid().frame(true, 0, 64, 0, 3, 22, true), 1, 70, 0));
        assertEquals(Optional.empty(), find(new Grid().frame(false, 0, 64, 0, 22, 3, true), 0, 65, 5));
    }

    @Test
    void openingsTooNarrowOrTooShortAreNotFrames() {
        assertEquals(Optional.empty(), find(new Grid().frame(true, 0, 64, 0, 1, 3, true), 0, 65, 0));
        assertEquals(Optional.empty(), find(new Grid().frame(true, 0, 64, 0, 2, 2, true), 0, 64, 0));
        assertEquals(Optional.empty(), find(new Grid().frame(false, 0, 64, 0, 1, 5, true), 0, 65, 0));
    }

    @Test
    void cornersAreOptional() {
        assertTrue(find(new Grid().frame(true, 0, 64, 0, 3, 4, false), 1, 65, 0).isPresent());
        assertTrue(find(new Grid().frame(true, 0, 64, 0, 3, 4, true), 1, 65, 0).isPresent());
    }

    @Test
    void aMissingOrWrongFrameBlockIsNotAFrame() {
        int[][] missing = {{-1, 65}, {3, 66}, {1, 63}, {2, 68}};
        for (int[] spot : missing) {
            Grid grid = new Grid().frame(true, 0, 64, 0, 3, 4, false);
            grid.set(spot[0], spot[1], 0, Cell.OTHER);
            assertEquals(Optional.empty(), find(grid, 1, 65, 0), spot[0] + "," + spot[1]);
            grid.set(spot[0], spot[1], 0, Cell.EMPTY);
            assertEquals(Optional.empty(), find(grid, 1, 65, 0), spot[0] + "," + spot[1] + " empty");
        }
    }

    @Test
    void aBlockedOpeningOrAnUnloadedBlockIsNotAFrame() {
        Grid blocked = new Grid().frame(true, 0, 64, 0, 3, 4, false);
        blocked.set(2, 67, 0, Cell.OTHER);
        assertEquals(Optional.empty(), find(blocked, 0, 64, 0));

        Grid unloaded = new Grid().frame(true, 0, 64, 0, 3, 4, false);
        unloaded.set(3, 64, 0, Cell.UNKNOWN);
        assertEquals(Optional.empty(), find(unloaded, 0, 64, 0));
    }

    @Test
    void aStartThatIsNotEmptyOrNotInAFrameFindsNothing() {
        Grid grid = new Grid().frame(true, 0, 64, 0, 3, 4, false);
        assertEquals(Optional.empty(), find(grid, -1, 65, 0));
        assertEquals(Optional.empty(), find(grid, 1, 65, 1));
        Grid open = new Grid();
        for (int y = 64; y < 100; y++) {
            open.set(0, y, 0, Cell.EMPTY);
        }
        assertEquals(Optional.empty(), find(open, 0, 99, 0));
    }

    @Test
    void aFrameIsIntactUntilAFrameBlockGoesAndUnloadedBlocksCountAsThere() {
        ArenaBounds opening = new ArenaBounds(0, 64, 0, 2, 67, 0);
        Grid grid = new Grid().frame(true, 0, 64, 0, 3, 4, false);
        assertTrue(PortalFrame.intact(grid, opening));
        grid.set(-1, 64, 0, Cell.FRAME);
        grid.set(-1, 63, 0, Cell.EMPTY);
        assertTrue(PortalFrame.intact(grid, opening), "a missing corner changes nothing");
        grid.set(2, 63, 0, Cell.UNKNOWN);
        assertTrue(PortalFrame.intact(grid, opening), "an unloaded frame block counts as there");
        grid.set(3, 66, 0, Cell.EMPTY);
        assertFalse(PortalFrame.intact(grid, opening));

        ArenaBounds alongZ = new ArenaBounds(7, 70, 3, 7, 72, 4);
        Grid z = new Grid().frame(false, 3, 70, 7, 2, 3, false);
        assertTrue(PortalFrame.intact(z, alongZ));
        z.set(7, 73, 4, Cell.OTHER);
        assertFalse(PortalFrame.intact(z, alongZ));
    }

    @Test
    void onlyTheFrameBlocksBesideTheOpeningAreFrameBlocks() {
        ArenaBounds opening = new ArenaBounds(0, 64, 0, 2, 67, 0);
        assertTrue(PortalFrame.isFrame(opening, -1, 64, 0));
        assertTrue(PortalFrame.isFrame(opening, 3, 67, 0));
        assertTrue(PortalFrame.isFrame(opening, 0, 63, 0));
        assertTrue(PortalFrame.isFrame(opening, 2, 68, 0));
        assertFalse(PortalFrame.isFrame(opening, -1, 63, 0), "corner");
        assertFalse(PortalFrame.isFrame(opening, 3, 68, 0), "corner");
        assertFalse(PortalFrame.isFrame(opening, 1, 65, 0), "opening");
        assertFalse(PortalFrame.isFrame(opening, 0, 63, 1), "beside the frame");
        assertFalse(PortalFrame.isFrame(opening, -2, 64, 0), "beside the frame");

        ArenaBounds alongZ = new ArenaBounds(7, 70, 3, 7, 72, 4);
        assertTrue(PortalFrame.isFrame(alongZ, 7, 71, 2));
        assertTrue(PortalFrame.isFrame(alongZ, 7, 69, 4));
        assertFalse(PortalFrame.isFrame(alongZ, 6, 71, 2));
    }
}
