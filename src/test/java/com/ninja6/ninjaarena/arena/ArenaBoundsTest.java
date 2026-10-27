package com.ninja6.ninjaarena.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArenaBoundsTest {

    @Test
    void chunkColumnsSplitAlongChunkBorders() {
        List<ArenaBounds> columns = new ArenaBounds(10, 60, 10, 40, 100, 20).chunkColumns();
        assertEquals(List.of(
                new ArenaBounds(10, 60, 10, 15, 100, 15),
                new ArenaBounds(10, 60, 16, 15, 100, 20),
                new ArenaBounds(16, 60, 10, 31, 100, 15),
                new ArenaBounds(16, 60, 16, 31, 100, 20),
                new ArenaBounds(32, 60, 10, 40, 100, 15),
                new ArenaBounds(32, 60, 16, 40, 100, 20)), columns);
    }

    @Test
    void chunkColumnsHandleNegativeCoordinates() {
        assertEquals(List.of(new ArenaBounds(-16, 0, -3, -1, 5, -1), new ArenaBounds(0, 0, -3, 2, 5, -1)),
                new ArenaBounds(-16, 0, -3, 2, 5, -1).chunkColumns());
    }

    @Test
    void chunkColumnsCoverEveryBlockOnce() {
        ArenaBounds box = new ArenaBounds(-21, 3, 7, 18, 4, 40);
        Set<String> seen = new HashSet<>();
        for (ArenaBounds column : box.chunkColumns()) {
            assertEquals(Math.floorDiv(column.minX(), 16), Math.floorDiv(column.maxX(), 16));
            assertEquals(Math.floorDiv(column.minZ(), 16), Math.floorDiv(column.maxZ(), 16));
            for (int x = column.minX(); x <= column.maxX(); x++) {
                for (int y = column.minY(); y <= column.maxY(); y++) {
                    for (int z = column.minZ(); z <= column.maxZ(); z++) {
                        assertTrue(box.contains(x, y, z));
                        assertTrue(seen.add(x + "," + y + "," + z), "block covered twice");
                    }
                }
            }
        }
        assertEquals(40 * 2 * 34, seen.size());
    }

    @Test
    void aSingleBlockIsOneColumn() {
        ArenaBounds block = new ArenaBounds(5, 5, 5, 5, 5, 5);
        assertEquals(List.of(block), block.chunkColumns());
    }
}
