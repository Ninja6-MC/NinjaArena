package com.ninja6.ninjaarena.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArenaLayoutTest {

    @Test
    void everyTeamContainsEveryGeneratedCellForSeveralSeedsAndAllDifficulties() {
        for (long seed : new long[] {0, 42, -7}) {
            for (int difficulty = 1; difficulty <= 5; difficulty++) {
                TerrainModel side = TerrainGenerator.generate(seed, difficulty);
                for (int teams : new int[] {2, 4}) {
                    ArenaLayout layout = new ArenaLayout(side, teams);
                    assertSame(side, layout.side());
                    assertEquals(teams, layout.teamCount());
                    assertCopiesAndRoundTrips(layout);
                }
            }
        }
    }

    @Test
    void transformsPreserveEveryKindWithoutInterpretingIt() {
        SideDimensions dimensions = new SideDimensions(8, 11, 32);
        byte[] cells = new byte[dimensions.volume()];
        BlockKind[] kinds = BlockKind.values();
        for (int i = 0; i < cells.length; i++) {
            cells[i] = (byte) (i % kinds.length);
        }
        TerrainModel side = new TerrainModel(1, 1, dimensions, TerrainTheme.values()[0], 8, 8, cells);
        assertCopiesAndRoundTrips(new ArenaLayout(side, 2));
        assertCopiesAndRoundTrips(new ArenaLayout(side, 4));
    }

    @Test
    void edgesMeetTheFlatCentreAndSpawnAreasFaceInwards() {
        TerrainModel side = TerrainGenerator.generate(42, 3);
        for (int teams : new int[] {2, 4}) {
            ArenaLayout layout = new ArenaLayout(side, teams);
            for (int team = 1; team <= teams; team++) {
                ArenaLayout.Facing facing = layout.facing(team);
                for (int x = 0; x < side.dimensions().width(); x++) {
                    ArenaLayout.Position edge = layout.toMap(team, x, side.centreSurfaceY(), side.centreEdgeZ());
                    assertTrue(layout.centreBounds().contains(edge.x() + facing.dx(), edge.y(),
                            edge.z() + facing.dz()));
                    assertEquals(BlockKind.SURFACE, layout.at(edge.x(), edge.y(), edge.z()));
                    assertEquals(BlockKind.SURFACE, layout.at(edge.x() + facing.dx(), edge.y(),
                            edge.z() + facing.dz()));
                    ArenaLayout.Position spawn = layout.toMap(team, x, side.spawnSurfaceY(), side.spawnEdgeZ());
                    assertFalse(layout.sideBounds(team).contains(spawn.x() - facing.dx(), spawn.y(),
                            spawn.z() - facing.dz()));
                }
                ArenaLayout.Bounds spawn = layout.spawnArea(team);
                int count = 0;
                for (int z = spawn.minZ(); z < spawn.maxZ(); z++) {
                    for (int x = spawn.minX(); x < spawn.maxX(); x++) {
                        assertEquals(BlockKind.SPAWN_PAD, layout.at(x, spawn.minY(), z));
                        count++;
                    }
                }
                assertEquals(side.dimensions().width() * side.dimensions().spawnDepth(), count);
            }
            ArenaLayout.Bounds centre = layout.centreBounds();
            for (int z = centre.minZ(); z < centre.maxZ(); z++) {
                for (int x = centre.minX(); x < centre.maxX(); x++) {
                    for (int y = 0; y < layout.height(); y++) {
                        BlockKind kind = layout.at(x, y, z);
                        assertEquals(side.at(0, y, side.centreEdgeZ()), kind);
                        if (y < side.centreSurfaceY()) {
                            assertTrue(kind.solid());
                        } else {
                            assertEquals(y == side.centreSurfaceY() ? BlockKind.SURFACE : BlockKind.AIR, kind);
                        }
                    }
                }
            }
        }
    }

    @Test
    void partsAreDisjointAndFitEveryGridSize() {
        for (int cell = 32; cell <= 4096; cell += 16) {
            SideDimensions dimensions = SideDimensions.forCell(cell);
            TerrainModel side = new TerrainModel(0, 1, dimensions, TerrainTheme.values()[0], 8, 8,
                    new byte[dimensions.volume()]);
            for (int teams : new int[] {2, 4}) {
                ArenaLayout layout = new ArenaLayout(side, teams);
                assertTrue(layout.width() <= cell && layout.length() <= cell);
                assertEquals(dimensions.height(), layout.height());
                for (int team = 1; team <= teams; team++) {
                    ArenaLayout.Bounds bounds = layout.sideBounds(team);
                    assertTrue(layout.bounds().contains(bounds.minX(), bounds.minY(), bounds.minZ()));
                    assertTrue(layout.bounds().contains(bounds.maxX() - 1, bounds.maxY() - 1, bounds.maxZ() - 1));
                    assertFalse(overlaps(bounds, layout.centreBounds()));
                    for (int other = team + 1; other <= teams; other++) {
                        assertFalse(overlaps(bounds, layout.sideBounds(other)));
                    }
                }
            }
        }
    }

    @Test
    void orientationsAreQuarterTurnsAndTwoTeamsUseOppositeSides() {
        TerrainModel side = TerrainGenerator.generate(0, 1, new SideDimensions(8, 11, 32));
        ArenaLayout four = new ArenaLayout(side, 4);
        int span = four.width();
        assertEquals(new ArenaLayout.Facing(0, 1), four.facing(1));
        assertEquals(new ArenaLayout.Facing(-1, 0), four.facing(2));
        assertEquals(new ArenaLayout.Facing(0, -1), four.facing(3));
        assertEquals(new ArenaLayout.Facing(1, 0), four.facing(4));
        for (int z = 0; z < side.dimensions().length(); z++) {
            for (int x = 0; x < side.dimensions().width(); x++) {
                ArenaLayout.Position original = four.toMap(1, x, 7, z);
                ArenaLayout.Position rotated = original;
                for (int turn = 1; turn <= 4; turn++) {
                    rotated = new ArenaLayout.Position(span - 1 - rotated.z(), rotated.y(), rotated.x());
                    assertEquals(four.toMap(turn % 4 + 1, x, 7, z), rotated);
                }
                assertEquals(original, rotated);
            }
        }
        ArenaLayout two = new ArenaLayout(side, 2);
        assertEquals(new ArenaLayout.Facing(0, -1), two.facing(2));
        assertEquals(new ArenaLayout.Position(7, 4, 29), two.toMap(2, 0, 4, 0));
        assertEquals(new ArenaLayout.Position(0, 4, 29), two.toMap(2, 7, 4, 0));
    }

    @Test
    void repeatedLayoutsMatchAcrossTheWholeMapIncludingAir() {
        for (int teams : new int[] {2, 4}) {
            SideDimensions dimensions = new SideDimensions(8, 11, 32);
            ArenaLayout a = new ArenaLayout(TerrainGenerator.generate(42, 5, dimensions), teams);
            ArenaLayout b = new ArenaLayout(TerrainGenerator.generate(42, 5, dimensions), teams);
            for (int z = -1; z <= a.length(); z++) {
                for (int x = -1; x <= a.width(); x++) {
                    for (int y = -1; y <= a.height(); y++) {
                        assertEquals(a.at(x, y, z), b.at(x, y, z));
                        boolean occupied = a.centreBounds().contains(x, y, z);
                        for (int team = 1; team <= teams; team++) {
                            occupied |= a.sideBounds(team).contains(x, y, z);
                        }
                        if (!occupied) {
                            assertEquals(BlockKind.AIR, a.at(x, y, z));
                        }
                    }
                }
            }
        }
    }

    @Test
    void invalidTeamCountsAndTeamNumbersAreRefused() {
        TerrainModel side = TerrainGenerator.generate(0, 1, new SideDimensions(8, 8, 32));
        for (int teams : new int[] {-1, 0, 1, 3, 5, Integer.MAX_VALUE}) {
            assertEquals("team count must be 2 or 4",
                    assertThrows(IllegalArgumentException.class, () -> new ArenaLayout(side, teams)).getMessage());
        }
        assertThrows(NullPointerException.class, () -> new ArenaLayout(null, 2));
        ArenaLayout layout = new ArenaLayout(side, 2);
        for (int team : new int[] {0, 3}) {
            assertThrows(IllegalArgumentException.class, () -> layout.toMap(team, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> layout.toSide(team, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> layout.sideBounds(team));
            assertThrows(IllegalArgumentException.class, () -> layout.spawnArea(team));
            assertThrows(IllegalArgumentException.class, () -> layout.facing(team));
        }
    }

    private static void assertCopiesAndRoundTrips(ArenaLayout layout) {
        TerrainModel side = layout.side();
        for (int team = 1; team <= layout.teamCount(); team++) {
            for (int z = 0; z < side.dimensions().length(); z++) {
                for (int x = 0; x < side.dimensions().width(); x++) {
                    for (int y = 0; y < side.dimensions().height(); y++) {
                        ArenaLayout.Position map = layout.toMap(team, x, y, z);
                        assertEquals(new ArenaLayout.Position(x, y, z), layout.toSide(team, map.x(), y, map.z()));
                        assertEquals(side.at(x, y, z), layout.at(map.x(), map.y(), map.z()));
                    }
                }
            }
        }
    }

    private static boolean overlaps(ArenaLayout.Bounds a, ArenaLayout.Bounds b) {
        return a.minX() < b.maxX() && b.minX() < a.maxX()
                && a.minY() < b.maxY() && b.minY() < a.maxY()
                && a.minZ() < b.maxZ() && b.minZ() < a.maxZ();
    }
}
