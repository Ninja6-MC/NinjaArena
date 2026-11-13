package com.ninja6.ninjaarena.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaValidator;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.message.Messages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MapSearchTest {

    /** Seeds searched per difficulty and team count in the acceptance test. */
    private static final int SEEDS = 200;

    private static final ArenaGrid GRID = new ArenaGrid(256, 2048, -64, 320);

    @Test
    void everyAcceptedMapIsCrossableFromEverySpawnAndMeetsItsMinimums() {
        List<String> rates = new ArrayList<>();
        for (int difficulty = TerrainGenerator.MIN_DIFFICULTY; difficulty <= TerrainGenerator.MAX_DIFFICULTY;
                difficulty++) {
            ChallengeMinimums minimums = ChallengeMinimums.forDifficulty(difficulty);
            for (int teams : new int[] {2, 4}) {
                int firstTry = 0;
                int accepted = 0;
                int attempts = 0;
                for (int i = 0; i < SEEDS; i++) {
                    long seed = i * 104_729L + difficulty;
                    MapSearch.Outcome outcome = MapSearch.search(seed, difficulty, teams);
                    if (outcome instanceof MapSearch.Accepted map) {
                        accepted++;
                        attempts += map.attempt() + 1;
                        if (map.attempt() == 0) {
                            firstTry++;
                        }
                        assertAcceptedMap(map, difficulty, teams, minimums, i == 0);
                    } else {
                        attempts += ((MapSearch.Failed) outcome).attempts();
                    }
                }
                rates.add(String.format(Locale.ROOT, "difficulty %d, %d teams: %.1f%% accepted on the first seed, "
                        + "%.1f%% within the retry limit, %.2f attempts per search", difficulty, teams,
                        100.0 * firstTry / SEEDS, 100.0 * accepted / SEEDS, (double) attempts / SEEDS));
                assertTrue(firstTry * 2 > SEEDS, "most seeds should pass at difficulty " + difficulty);
                assertTrue(accepted >= SEEDS * 99 / 100, "the search should almost never fail");
            }
        }
        rates.forEach(System.out::println);
    }

    private static void assertAcceptedMap(MapSearch.Accepted map, int difficulty, int teams,
            ChallengeMinimums minimums, boolean checkArena) {
        assertEquals(MapSearch.attemptSeed(map.requestedSeed(), map.attempt()), map.seed());
        CrossingReport report = map.report();
        assertTrue(report.crossable());
        assertTrue(report.crossableAfterCollapse());
        assertTrue(report.forcedJumps() >= minimums.forcedJumps());
        assertTrue(report.climb() >= minimums.climb());
        assertTrue(report.hazardExposure() >= minimums.hazardExposure());

        // Solve the accepted seed again from scratch: the verdict must not depend on the search.
        TerrainModel side = TerrainGenerator.generate(map.seed(), difficulty);
        MovementSolver solved = MovementSolver.solve(side, MovementModel.DEFAULT);
        assertEquals(report, solved.report());
        assertEquals(List.of(), solved.report().problems(minimums));

        ArenaLayout layout = map.layout();
        assertEquals(teams, layout.teamCount());
        MapPoints points = map.points();
        assertEquals(teams, points.teamSpawns().size());
        assertEquals(teams * report.enemySpawns(), points.enemySpawns().size());
        for (int team = 1; team <= teams; team++) {
            ArenaLayout.Position spawn = points.teamSpawns().get(team - 1).block();
            assertEquals(BlockKind.SPAWN_PAD, layout.at(spawn.x(), spawn.y() - 1, spawn.z()));
            assertEquals(BlockKind.AIR, layout.at(spawn.x(), spawn.y(), spawn.z()));
            assertEquals(BlockKind.AIR, layout.at(spawn.x(), spawn.y() + 1, spawn.z()));
            assertTrue(layout.spawnArea(team).contains(spawn.x(), spawn.y() - 1, spawn.z()));
        }
        for (int i = 0; i < points.enemySpawns().size(); i++) {
            int team = i / report.enemySpawns() + 1;
            ArenaLayout.Position enemy = points.enemySpawns().get(i).block();
            BlockKind floor = layout.at(enemy.x(), enemy.y() - 1, enemy.z());
            assertTrue(floor.solid() && !MovementSolver.isHazard(floor));
            assertEquals(BlockKind.AIR, layout.at(enemy.x(), enemy.y(), enemy.z()));
            assertEquals(BlockKind.AIR, layout.at(enemy.x(), enemy.y() + 1, enemy.z()));
            ArenaLayout.Position local = layout.toSide(team, enemy.x(), enemy.y(), enemy.z());
            assertTrue(solved.reachable(local.x(), local.y(), local.z()));
            assertEquals(0, solved.hazardsAround(local.x(), local.y(), local.z()));
            for (MapPoints.Point teamSpawn : points.teamSpawns()) {
                double dx = teamSpawn.x() - points.enemySpawns().get(i).x();
                double dz = teamSpawn.z() - points.enemySpawns().get(i).z();
                int min = MovementSolver.ENEMY_SPAWN_DISTANCE;
                assertTrue(dx * dx + dz * dz >= min * min);
            }
        }
        if (checkArena) {
            assertArenaWouldValidate(map);
        }
    }

    /**
     * An arena made from the map, placed in the middle of grid cell 0, 0, has no problems
     * except the waiting room, which generated maps leave unset.
     */
    private static void assertArenaWouldValidate(MapSearch.Accepted map) {
        ArenaLayout layout = map.layout();
        int originX = (GRID.cellSize() - layout.width()) / 2;
        int originZ = (GRID.cellSize() - layout.length()) / 2;
        int originY = 0;
        ArenaBounds bounds = new ArenaBounds(originX, originY, originZ, originX + layout.width() - 1,
                originY + layout.height() - 1, originZ + layout.length() - 1);
        List<ArenaPoint> teams = map.points().teamSpawns().stream()
                .map(point -> point.toArenaPoint(originX, originY, originZ)).toList();
        List<ArenaPoint> enemies = map.points().enemySpawns().stream()
                .map(point -> point.toArenaPoint(originX, originY, originZ)).toList();
        Arena arena = new Arena("generated", new GridCell(0, 0), bounds, null, teams, enemies);
        List<String> problems = ArenaValidator.problems(arena, GRID);
        assertTrue(problems.size() <= 1, problems.toString());
        assertEquals(List.of(), ArenaValidator.problems(arena.withWaitingRoom(teams.get(0)), GRID));
    }

    @Test
    void theSameRequestGivesTheSameMap() {
        MapSearch.Accepted first = assertInstanceOf(MapSearch.Accepted.class, MapSearch.search(42, 5, 4));
        MapSearch.Accepted again = assertInstanceOf(MapSearch.Accepted.class, MapSearch.search(42, 5, 4));
        assertEquals(first.attempt(), again.attempt());
        assertEquals(first.seed(), again.seed());
        assertEquals(first.report(), again.report());
        assertEquals(first.points(), again.points());
        assertEquals(first.layout().side().digest(), again.layout().side().digest());
    }

    @Test
    void anAcceptedSeedIsAcceptedAgainOnTheFirstTry() {
        for (long seed = 0; seed < 20; seed++) {
            MapSearch.Accepted map = assertInstanceOf(MapSearch.Accepted.class, MapSearch.search(seed, 5, 2));
            MapSearch.Accepted again = assertInstanceOf(MapSearch.Accepted.class, MapSearch.search(map.seed(), 5, 2));
            assertEquals(0, again.attempt());
            assertEquals(map.seed(), again.seed());
        }
    }

    @Test
    void anImpossibleSetupFailsAfterTheRetryLimit() {
        ChallengeMinimums impossible = new ChallengeMinimums(1_000, 0, 0);
        MapSearch.Failed failed = assertInstanceOf(MapSearch.Failed.class, MapSearch.search(7, 3, 2,
                SideDimensions.DEFAULT, MovementModel.DEFAULT, impossible, 3));
        assertEquals(7, failed.requestedSeed());
        assertEquals(4, failed.attempts());
        for (int attempt = 0; attempt < failed.attempts(); attempt++) {
            CrossingReport report = failed.reports().get(attempt);
            assertEquals(MapSearch.attemptSeed(7, attempt), report.seed());
            assertFalse(report.accepted(impossible));
        }
        assertEquals(failed, MapSearch.search(7, 3, 2, SideDimensions.DEFAULT, MovementModel.DEFAULT, impossible, 3));
        MapSearch.Failed once = assertInstanceOf(MapSearch.Failed.class, MapSearch.search(7, 3, 2,
                SideDimensions.DEFAULT, MovementModel.DEFAULT, impossible, 0));
        assertEquals(1, once.attempts());
    }

    @Test
    void derivedSeedsDifferFromTheRequestAndEachOther() {
        long[] seeds = new long[MapSearch.MAX_RETRY_LIMIT + 1];
        for (int attempt = 0; attempt < seeds.length; attempt++) {
            seeds[attempt] = MapSearch.attemptSeed(123, attempt);
        }
        assertEquals(123, seeds[0]);
        assertEquals(seeds.length, Arrays.stream(seeds).distinct().count());
    }

    @Test
    void badRequestsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> MapSearch.search(1, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> MapSearch.search(1, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> MapSearch.search(1, 3, 2, SideDimensions.DEFAULT,
                MovementModel.DEFAULT, ChallengeMinimums.NONE, -1));
        assertThrows(IllegalArgumentException.class, () -> MapSearch.search(1, 3, 2, SideDimensions.DEFAULT,
                MovementModel.DEFAULT, ChallengeMinimums.NONE, MapSearch.MAX_RETRY_LIMIT + 1));
        assertThrows(IllegalArgumentException.class, () -> ChallengeMinimums.forDifficulty(6));
    }

    @Test
    void theFallbackIsTheLastGoodSeedElseAHandBuiltArena() {
        MapSearch.FallbackDecision last = MapSearch.fallback(OptionalLong.of(-5));
        assertEquals(MapSearch.Fallback.LAST_GOOD_SEED, last.fallback());
        assertEquals(OptionalLong.of(-5), last.seed());
        assertEquals("room.map-fallback-seed", last.messageKey());

        MapSearch.FallbackDecision handBuilt = MapSearch.fallback(OptionalLong.empty());
        assertEquals(MapSearch.Fallback.HAND_BUILT, handBuilt.fallback());
        assertEquals(OptionalLong.empty(), handBuilt.seed());
        assertEquals("room.map-fallback-hand-built", handBuilt.messageKey());

        for (MapSearch.Fallback fallback : MapSearch.Fallback.values()) {
            assertTrue(Messages.defaults().containsKey(fallback.messageKey()), fallback.messageKey());
        }
        assertEquals("No playable map came from seed 9 in 9 tries, so the room plays the last good seed, -5.",
                Messages.bundled().plain(last.messageKey(), "seed", 9, "attempts", 9, "last", -5));
        assertThrows(IllegalArgumentException.class,
                () -> new MapSearch.FallbackDecision(MapSearch.Fallback.HAND_BUILT, OptionalLong.of(1)));
    }

    @Test
    void reportsTheTimeToGenerateAndCheckAMap() {
        for (int teams : new int[] {2, 4}) {
            for (int warm = 0; warm < 10; warm++) {
                checkOnce(warm, teams);
            }
            long[] times = new long[30];
            for (int i = 0; i < times.length; i++) {
                long start = System.nanoTime();
                checkOnce(1_000 + i, teams);
                times[i] = System.nanoTime() - start;
            }
            Arrays.sort(times);
            System.out.printf(Locale.ROOT, "%d teams: median %.1f ms, slowest %.1f ms to generate and check one "
                    + "default side%n", teams, times[times.length / 2] / 1e6, times[times.length - 1] / 1e6);
        }
    }

    private static void checkOnce(long seed, int teams) {
        TerrainModel side = TerrainGenerator.generate(seed, 5);
        MovementSolver solved = MovementSolver.solve(side, MovementModel.DEFAULT);
        solved.points(new ArenaLayout(side, teams));
    }
}
