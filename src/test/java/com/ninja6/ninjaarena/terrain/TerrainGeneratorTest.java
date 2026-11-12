package com.ninja6.ninjaarena.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TerrainGeneratorTest {

    /*
     * Golden digests pin the algorithm. They change only when the generator's output changes,
     * which must be deliberate: every seed then gives a different map. To update them on
     * purpose, run this test, copy the actual digests from the failure into these constants
     * and say in the pull request that existing seeds now generate different maps.
     */
    private static final String GOLDEN_SEED_42_DIFFICULTY_3 =
            "bdb34038419eb0b69a1e2b6dac8b907e514f83d032c4ed5dbb4625ea8079dc3b";
    private static final String GOLDEN_SEED_MINUS_7_DIFFICULTY_5 =
            "f4687f2b8d415d4a68d9badc1215e6e4fd0d78418950219c34d6664e8708afeb";

    @Test
    void goldenDigestsAreUnchanged() {
        assertEquals(GOLDEN_SEED_42_DIFFICULTY_3, TerrainGenerator.generate(42, 3).digest());
        assertEquals(GOLDEN_SEED_MINUS_7_DIFFICULTY_5, TerrainGenerator.generate(-7, 5).digest());
    }

    @Test
    void sameSeedAndDifficultyGiveTheSameModel() {
        for (int difficulty = TerrainGenerator.MIN_DIFFICULTY; difficulty <= TerrainGenerator.MAX_DIFFICULTY;
                difficulty++) {
            TerrainModel first = TerrainGenerator.generate(123_456_789L, difficulty);
            for (int run = 0; run < 3; run++) {
                TerrainModel again = TerrainGenerator.generate(123_456_789L, difficulty);
                assertEquals(first.digest(), again.digest());
                assertSameCells(first, again);
            }
        }
    }

    @Test
    void generationOnManyThreadsAtOnceGivesTheSameModel() throws Exception {
        String expected = TerrainGenerator.generate(Long.MIN_VALUE, 4).digest();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> runs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                runs.add(pool.submit(() -> TerrainGenerator.generate(Long.MIN_VALUE, 4).digest()));
            }
            for (Future<String> run : runs) {
                assertEquals(expected, run.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentSeedsAndDifficultiesGiveDifferentModels() {
        Set<String> digests = new HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            for (int difficulty = 1; difficulty <= 5; difficulty++) {
                assertTrue(digests.add(TerrainGenerator.generate(seed, difficulty).digest()),
                        "seed " + seed + " difficulty " + difficulty + " repeated an earlier model");
            }
        }
        assertNotEquals(TerrainGenerator.generate(9, 1).digest(), TerrainGenerator.generate(9, 2).digest());
    }

    @Test
    void everyDifficultyGivesAValidModel() {
        for (int difficulty = 1; difficulty <= 5; difficulty++) {
            for (long seed : new long[] {0, 1, 42, -1, Long.MAX_VALUE, 987_654_321L}) {
                assertValid(TerrainGenerator.generate(seed, difficulty));
            }
        }
    }

    @Test
    void theSmallestAndLargestGridSidesAreValid() {
        for (int cell : new int[] {32, 48, 4096}) {
            SideDimensions size = SideDimensions.forCell(cell);
            for (int difficulty = 1; difficulty <= 5; difficulty++) {
                TerrainModel model = TerrainGenerator.generate(cell, difficulty, size);
                assertEquals(size, model.dimensions());
                assertValid(model);
            }
        }
    }

    @Test
    void featuresGrowWithDifficulty() {
        long[] ravines = new long[6];
        long[] overhangs = new long[6];
        long[] platforms = new long[6];
        long[] cliffs = new long[6];
        double[] variance = new double[6];
        for (int difficulty = 1; difficulty <= 5; difficulty++) {
            for (long seed = 0; seed < 8; seed++) {
                TerrainModel model = TerrainGenerator.generate(seed * 7919, difficulty);
                ravines[difficulty] += TerrainCensus.ravineColumns(model);
                overhangs[difficulty] += TerrainCensus.overhangCells(model);
                platforms[difficulty] += model.count(BlockKind.PLATFORM);
                cliffs[difficulty] += TerrainCensus.cliffSteps(model);
                variance[difficulty] += TerrainCensus.heightVariance(model);
            }
        }
        for (int difficulty = 2; difficulty <= 5; difficulty++) {
            assertTrue(ravines[difficulty] > ravines[difficulty - 1], "ravines at " + difficulty);
            assertTrue(overhangs[difficulty] > overhangs[difficulty - 1], "overhangs at " + difficulty);
            assertTrue(platforms[difficulty] > platforms[difficulty - 1], "platforms at " + difficulty);
            assertTrue(variance[difficulty] > variance[difficulty - 1], "height variance at " + difficulty);
        }
        assertTrue(cliffs[3] > cliffs[1] && cliffs[5] > cliffs[3], "cliff steps");
    }

    @Test
    void themeComesFromTheSeedAlone() {
        Set<TerrainTheme> seen = EnumSet.noneOf(TerrainTheme.class);
        for (long seed = 0; seed < 200; seed++) {
            TerrainTheme theme = TerrainGenerator.themeFor(seed);
            assertEquals(theme, TerrainGenerator.themeFor(seed));
            seen.add(theme);
        }
        assertEquals(EnumSet.allOf(TerrainTheme.class), seen);
        for (long seed : new long[] {3, 42, -7}) {
            TerrainTheme theme = TerrainGenerator.themeFor(seed);
            for (int difficulty = 1; difficulty <= 5; difficulty++) {
                assertEquals(theme, TerrainGenerator.generate(seed, difficulty).theme());
            }
            assertEquals(theme, TerrainGenerator.generate(seed, 2, SideDimensions.forCell(64)).theme());
        }
    }

    @Test
    void themesForFixedSeedsAreUnchanged() {
        assertEquals(TerrainTheme.CRAGS, TerrainGenerator.themeFor(42));
        assertEquals(TerrainTheme.GLACIER, TerrainGenerator.themeFor(-7));
    }

    @Test
    void difficultyOutsideOneToFiveIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TerrainGenerator.generate(1, 0));
        assertThrows(IllegalArgumentException.class, () -> TerrainGenerator.generate(1, 6));
    }

    @Test
    void defaultSideIsCompact() {
        TerrainModel model = TerrainGenerator.generate(5, 3);
        assertEquals(96 * 72 * 64, model.cellBytes());
        assertEquals(0, model.spawnEdgeZ());
        assertEquals(71, model.centreEdgeZ());
        assertThrows(IndexOutOfBoundsException.class, () -> model.at(96, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> model.at(0, 64, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> model.at(0, 0, -1));
    }

    private static void assertSameCells(TerrainModel a, TerrainModel b) {
        SideDimensions size = a.dimensions();
        assertEquals(size, b.dimensions());
        assertEquals(a.theme(), b.theme());
        for (int z = 0; z < size.length(); z++) {
            for (int x = 0; x < size.width(); x++) {
                for (int y = 0; y < size.height(); y++) {
                    assertEquals(a.at(x, y, z), b.at(x, y, z));
                }
            }
        }
    }

    private static void assertValid(TerrainModel model) {
        SideDimensions size = model.dimensions();
        String where = "seed " + model.seed() + " difficulty " + model.difficulty() + ": ";
        int maxTop = size.height() - TerrainGenerator.HEADROOM - 1;
        assertEquals(model.spawnSurfaceY(), model.centreSurfaceY(), where + "flat zones share a level");
        for (int z = 0; z < size.length(); z++) {
            boolean spawnZone = z < size.spawnDepth();
            boolean centreZone = z >= size.length() - size.centreDepth();
            for (int x = 0; x < size.width(); x++) {
                for (int y = 0; y < TerrainGenerator.FLOOR; y++) {
                    assertTrue(model.at(x, y, z).solid(), where + "floor open at " + x + ", " + y + ", " + z);
                }
                int top = model.terrainTopY(x, z);
                assertTrue(top >= TerrainGenerator.FLOOR && top <= maxTop, where + "ground out of range at " + x
                        + ", " + z + ": " + top);
                assertEquals(BlockKind.SURFACE, surfaceOrPad(model.at(x, top, z)), where + "column top kind");
                if (spawnZone || centreZone) {
                    int level = spawnZone ? model.spawnSurfaceY() : model.centreSurfaceY();
                    assertEquals(level, top, where + "flat zone height at " + x + ", " + z);
                    assertEquals(spawnZone ? BlockKind.SPAWN_PAD : BlockKind.SURFACE, model.at(x, top, z));
                    for (int y = 0; y <= top; y++) {
                        assertTrue(model.at(x, y, z).solid(), where + "flat zone hollowed at " + x + ", " + z);
                    }
                    for (int y = top + 1; y < size.height(); y++) {
                        assertEquals(BlockKind.AIR, model.at(x, y, z), where + "flat zone headroom at " + x + ", " + z);
                    }
                }
                for (int y = 0; y < size.height(); y++) {
                    BlockKind kind = model.at(x, y, z);
                    if (kind == BlockKind.PLATFORM) {
                        assertTrue(y > top + TerrainGenerator.PLATFORM_GAP, where + "platform on the ground");
                    } else if (y > top) {
                        assertEquals(BlockKind.AIR, kind, where + "terrain above the column top");
                    } else if (kind == BlockKind.SPAWN_PAD) {
                        assertTrue(spawnZone && y == top, where + "spawn pad outside the spawn zone");
                    }
                }
            }
        }
    }

    private static BlockKind surfaceOrPad(BlockKind kind) {
        return kind == BlockKind.SPAWN_PAD ? BlockKind.SURFACE : kind;
    }
}
