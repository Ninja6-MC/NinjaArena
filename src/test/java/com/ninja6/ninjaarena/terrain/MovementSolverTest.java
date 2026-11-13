package com.ninja6.ninjaarena.terrain;

import static com.ninja6.ninjaarena.terrain.TestSide.GROUND;
import static com.ninja6.ninjaarena.terrain.TestSide.LENGTH;
import static com.ninja6.ninjaarena.terrain.TestSide.WIDTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MovementSolverTest {

    private static final int FEET = GROUND + 1;
    private static final ChallengeMinimums NONE = ChallengeMinimums.NONE;

    @Test
    void aFlatSideIsCrossableWithoutChallenge() {
        CrossingReport report = new TestSide().solve().report();
        assertTrue(report.spawnSafe());
        assertTrue(report.crossable());
        assertTrue(report.crossableAfterCollapse());
        assertEquals(LENGTH - 2 - TestSide.spawnZ(), report.routeLength());
        assertEquals(0, report.forcedJumps());
        assertEquals(0, report.climb());
        assertEquals(0, report.hazardExposure());
        assertEquals(MovementSolver.ENEMY_SPAWNS_PER_SIDE, report.enemySpawns());
        assertEquals(List.of(), report.problems(NONE));
        assertEquals(List.of(CrossingReport.Problem.TOO_FEW_JUMPS, CrossingReport.Problem.TOO_LITTLE_CLIMB,
                CrossingReport.Problem.TOO_FEW_HAZARDS), report.problems(new ChallengeMinimums(1, 1, 1)));
    }

    @Test
    void levelGapsUpToTheLimitAreJumpedAndOneWiderIsRejected() {
        for (int gap = 1; gap <= MovementModel.DEFAULT.maxGap(); gap++) {
            CrossingReport report = new TestSide().trench(10, 10 + gap - 1, 2).solve().report();
            assertTrue(report.crossable(), "gap " + gap);
            assertEquals(1, report.forcedJumps(), "gap " + gap);
        }
        CrossingReport wide = new TestSide().trench(10, 10 + MovementModel.DEFAULT.maxGap(), 2).solve().report();
        assertFalse(wide.crossable());
        assertEquals(List.of(CrossingReport.Problem.NOT_CROSSABLE), wide.problems(NONE));
    }

    @Test
    void risingJumpsClearOneColumnLessThanLevelOnes() {
        int rising = MovementModel.DEFAULT.maxRisingGap();
        CrossingReport ok = new TestSide().trench(10, 10 + rising - 1, 2).groundFrom(10 + rising, GROUND + 1)
                .solve().report();
        assertTrue(ok.crossable());
        assertEquals(1, ok.forcedJumps());
        assertEquals(1, ok.climb());
        assertFalse(new TestSide().trench(10, 10 + rising, 2).groundFrom(10 + rising + 1, GROUND + 1)
                .solve().report().crossable());
    }

    @Test
    void climbsAreLimitedToTheJumpHeight() {
        CrossingReport step = new TestSide().groundFrom(10, GROUND + 1).solve().report();
        assertTrue(step.crossable());
        assertEquals(0, step.forcedJumps());
        assertEquals(1, step.climb());
        assertFalse(new TestSide().groundFrom(10, GROUND + 2).solve().report().crossable());
    }

    @Test
    void dropsAreLimitedToTheSafeFall() {
        int fall = MovementModel.DEFAULT.safeFall();
        CrossingReport drop = new TestSide().groundFrom(10, GROUND - fall).solve().report();
        assertTrue(drop.crossable());
        assertEquals(0, drop.climb());
        assertFalse(new TestSide().groundFrom(10, GROUND - fall - 1).solve().report().crossable());
    }

    @Test
    void aJumpNeedsThreeBlocksOfAirOverTheGap() {
        TestSide low = new TestSide().trench(10, 11, 2)
                .fill(0, FEET + 2, 10, WIDTH - 1, FEET + 2, 11, BlockKind.ROCK);
        assertFalse(low.solve().report().crossable());
        TestSide high = new TestSide().trench(10, 11, 2)
                .fill(0, FEET + 3, 10, WIDTH - 1, FEET + 3, 11, BlockKind.ROCK);
        assertTrue(high.solve().report().crossable());
    }

    @Test
    void lavaIsNeverStoodOnOrPassedThrough() {
        // A pool level with the ground: no floor to stand on, but a jump clears it.
        CrossingReport pool = new TestSide().trench(10, 11, 2).fill(0, 3, 10, WIDTH - 1, GROUND, 11, BlockKind.LAVA)
                .solve().report();
        assertTrue(pool.crossable());
        assertEquals(1, pool.forcedJumps());
        assertTrue(pool.hazardExposure() > 0);
        // A one-block wall of lava across the side cannot be walked, climbed or jumped through.
        CrossingReport wall = new TestSide().fill(0, FEET, 10, WIDTH - 1, FEET, 10, BlockKind.LAVA).solve().report();
        assertFalse(wall.crossable());
        MovementSolver solved = new TestSide().fill(0, GROUND, 10, WIDTH - 1, GROUND, 10, BlockKind.LAVA).solve();
        assertFalse(solved.standable(5, FEET, 10));
    }

    @Test
    void crumblingGroundIsStoodOnAndCounted() {
        TestSide side = new TestSide().fill(0, GROUND, 10, WIDTH - 1, GROUND, 10, BlockKind.CRUMBLE);
        MovementSolver solved = side.solve();
        assertTrue(solved.standable(5, FEET, 10));
        CrossingReport report = solved.report();
        assertTrue(report.crossable());
        // Rows 9, 10 and 11 each see three crumbling blocks at floor level; after the
        // collapse the row is a one-column gap.
        assertEquals(9, report.hazardExposure());
        assertEquals(0, report.forcedJumps());
        assertTrue(report.crossableAfterCollapse());
    }

    @Test
    void aCrossingThatOnlyACrumblingBridgeHoldsIsRejected() {
        int wide = MovementModel.DEFAULT.maxGap() + 1;
        CrossingReport report = new TestSide().trench(10, 10 + wide - 1, 2)
                .fill(6, GROUND, 10, 9, GROUND, 10 + wide - 1, BlockKind.CRUMBLE).solve().report();
        assertTrue(report.crossable());
        assertFalse(report.crossableAfterCollapse());
        assertEquals(List.of(CrossingReport.Problem.NOT_CROSSABLE_AFTER_COLLAPSE), report.problems(NONE));
    }

    @Test
    void aSealedHollowIsStandableButNotReachable() {
        MovementSolver solved = new TestSide().fill(4, 4, 12, 6, 6, 14, BlockKind.AIR).solve();
        assertTrue(solved.standable(5, 4, 13));
        assertFalse(solved.reachable(5, 4, 13));
        assertTrue(solved.reachable(5, FEET, 13));
        for (var enemy : solved.enemySpawns()) {
            assertEquals(FEET, enemy.y());
        }
    }

    @Test
    void spawnsInLavaOrAWallAreRejected() {
        int x = TestSide.spawnX();
        int z = TestSide.spawnZ();
        MovementSolver flat = new TestSide().solve();
        assertEquals(MovementSolver.SpawnCheck.SAFE, flat.checkSpawn(x, FEET, z));
        assertEquals(MovementSolver.SpawnCheck.IN_WALL, flat.checkSpawn(x, GROUND, z));
        assertEquals(MovementSolver.SpawnCheck.NOT_ON_SPAWN_PAD, flat.checkSpawn(x, FEET, 12));
        assertEquals(MovementSolver.SpawnCheck.NOT_ON_SPAWN_PAD, flat.checkSpawn(x, FEET + 1, z));
        MovementSolver lava = new TestSide().fill(x, FEET, z, x, FEET, z, BlockKind.LAVA).solve();
        assertEquals(MovementSolver.SpawnCheck.IN_LAVA, lava.checkSpawn(x, FEET, z));
        MovementSolver lavaFloor = new TestSide().fill(x, GROUND, z, x, GROUND, z, BlockKind.LAVA).solve();
        assertEquals(MovementSolver.SpawnCheck.IN_LAVA, lavaFloor.checkSpawn(x, FEET, z));
        MovementSolver head = new TestSide().fill(x, FEET + 1, z, x, FEET + 1, z, BlockKind.ROCK).solve();
        assertEquals(MovementSolver.SpawnCheck.IN_WALL, head.checkSpawn(x, FEET, z));
        // Lava on the preferred cell moves the spawn clear of it.
        assertEquals(MovementSolver.SpawnCheck.SAFE, lava.checkSpawn(lava.spawn().x(), lava.spawn().y(),
                lava.spawn().z()));
        assertNotEquals(x, lava.spawn().x());

        int depth = new SideDimensions(WIDTH, LENGTH, TestSide.HEIGHT).spawnDepth() - 1;
        for (BlockKind cover : new BlockKind[] {BlockKind.LAVA, BlockKind.ROCK}) {
            MovementSolver covered = new TestSide().fill(0, FEET, 0, WIDTH - 1, FEET, depth, cover).solve();
            assertNull(covered.spawn());
            assertFalse(covered.report().spawnSafe());
            assertEquals(List.of(CrossingReport.Problem.SPAWN_UNSAFE), covered.report().problems(NONE));
            assertTrue(covered.points(new ArenaLayout(covered.side(), 2)).isEmpty());
        }
    }

    @Test
    void enemySpawnsAreReachableHazardFreeAndSpreadOut() {
        MovementSolver solved = new TestSide().fill(0, GROUND, 14, WIDTH - 1, GROUND, 14, BlockKind.CRUMBLE).solve();
        List<ArenaLayout.Position> enemies = solved.enemySpawns();
        assertEquals(MovementSolver.ENEMY_SPAWNS_PER_SIDE, enemies.size());
        ArenaLayout.Position spawn = solved.spawn();
        for (ArenaLayout.Position enemy : enemies) {
            assertTrue(solved.reachable(enemy.x(), enemy.y(), enemy.z()));
            assertEquals(0, solved.hazardsAround(enemy.x(), enemy.y(), enemy.z()));
            int dx = enemy.x() - spawn.x();
            int dz = enemy.z() - spawn.z();
            assertTrue(dx * dx + dz * dz >= LENGTH / 2 * (LENGTH / 2));
        }
        for (int i = 0; i < enemies.size(); i++) {
            for (int j = i + 1; j < enemies.size(); j++) {
                assertNotEquals(enemies.get(i), enemies.get(j));
            }
        }
    }

    @Test
    void theSameSideGivesTheSameAnswer() {
        TerrainModel side = TerrainGenerator.generate(99, 4);
        MovementSolver first = MovementSolver.solve(side, MovementModel.DEFAULT);
        MovementSolver again = MovementSolver.solve(TerrainGenerator.generate(99, 4), MovementModel.DEFAULT);
        assertEquals(first.report(), again.report());
        assertEquals(first.spawn(), again.spawn());
        assertEquals(first.enemySpawns(), again.enemySpawns());
        assertEquals(first.points(new ArenaLayout(side, 4)), again.points(new ArenaLayout(again.side(), 4)));
    }

    @Test
    void oneSideAnswersForEveryTeamOfTheFullMap() {
        // Difficulty 5 seeds until two crossable sides and one that is not have been compared.
        int crossable = 0;
        int blocked = 0;
        for (long seed = 0; seed < 40 && (crossable < 2 || blocked < 1); seed++) {
            TerrainModel side = TerrainGenerator.generate(seed, 5);
            CrossingReport alone = MovementSolver.solve(side, MovementModel.DEFAULT).report();
            if (alone.crossable() ? crossable++ >= 2 : blocked++ >= 1) {
                continue;
            }
            for (int teams : new int[] {2, 4}) {
                ArenaLayout layout = new ArenaLayout(side, teams);
                for (int team = 1; team <= teams; team++) {
                    CrossingReport full = MovementSolver.crossLayout(layout, team, MovementModel.DEFAULT);
                    String where = "seed " + seed + ", " + teams + " teams, team " + team;
                    assertEquals(alone.crossable(), full.crossable(), where);
                    assertEquals(alone.crossableAfterCollapse(), full.crossableAfterCollapse(), where);
                    if (alone.crossable()) {
                        // The full map's route walks on over the centre ledge into the square.
                        assertEquals(alone.routeLength() + side.dimensions().centreDepth(), full.routeLength(), where);
                        assertEquals(alone.forcedJumps(), full.forcedJumps(), where);
                        assertEquals(alone.climb(), full.climb(), where);
                        assertEquals(alone.hazardExposure(), full.hazardExposure(), where);
                    }
                }
            }
        }
        assertTrue(crossable >= 2 && blocked >= 1, "the seeds should include both kinds of side");
    }

    @Test
    void movementLimitsAreChecked() {
        assertThrows(IllegalArgumentException.class,
                () -> new MovementModel(0, 3, 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new MovementModel(1, 3, 4, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new MovementModel(1, 3, 2, 0));
    }
}
