package com.ninja6.ninjaarena.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * What {@link MovementSolver} found on one side. Route measures are those of the direct
 * route from the team spawn to the centre (see {@link MovementSolver}), or {@code -1} when
 * there is no route.
 *
 * @param seed                   the model's seed
 * @param difficulty             the model's difficulty
 * @param spawnSafe              whether a spawn pad cell has a safe team spawn
 * @param crossable              whether the centre can be reached from the team spawn
 * @param crossableAfterCollapse whether it still can with every crumbling block gone
 * @param routeLength            the direct route's horizontal length in blocks
 * @param forcedJumps            jumps over a gap the direct route cannot avoid
 * @param climb                  blocks the direct route climbs, summed over its climbs and
 *                               rising jumps
 * @param hazardExposure         hazard blocks next to the direct route: lava or crumbling
 *                               blocks in the 3 by 3 columns around each standing cell, from
 *                               the floor to the block above the head, summed over the route
 * @param standingCells          standing cells on the side
 * @param reachableCells         standing cells reachable from the team spawn
 * @param enemySpawns            enemy spawn points found on the side
 */
public record CrossingReport(long seed, int difficulty, boolean spawnSafe, boolean crossable,
        boolean crossableAfterCollapse, int routeLength, int forcedJumps, int climb, int hazardExposure,
        int standingCells, int reachableCells, int enemySpawns) {

    /** A side with no safe team spawn: nothing else is measured. */
    static CrossingReport unsafeSpawn(long seed, int difficulty, int standingCells) {
        return new CrossingReport(seed, difficulty, false, false, false, -1, -1, -1, -1, standingCells, 0, 0);
    }

    /** Why a map is rejected, in the order they are checked. */
    public enum Problem {
        SPAWN_UNSAFE, NOT_CROSSABLE, NOT_CROSSABLE_AFTER_COLLAPSE, NO_ENEMY_SPAWNS, TOO_FEW_JUMPS,
        TOO_LITTLE_CLIMB, TOO_FEW_HAZARDS
    }

    /** Everything that stops this side being accepted at these minimums; empty if accepted. */
    public List<Problem> problems(ChallengeMinimums minimums) {
        List<Problem> problems = new ArrayList<>();
        if (!spawnSafe) {
            problems.add(Problem.SPAWN_UNSAFE);
            return problems;
        }
        if (!crossable) {
            problems.add(Problem.NOT_CROSSABLE);
            return problems;
        }
        if (!crossableAfterCollapse) {
            problems.add(Problem.NOT_CROSSABLE_AFTER_COLLAPSE);
        }
        if (enemySpawns == 0) {
            problems.add(Problem.NO_ENEMY_SPAWNS);
        }
        if (forcedJumps < minimums.forcedJumps()) {
            problems.add(Problem.TOO_FEW_JUMPS);
        }
        if (climb < minimums.climb()) {
            problems.add(Problem.TOO_LITTLE_CLIMB);
        }
        if (hazardExposure < minimums.hazardExposure()) {
            problems.add(Problem.TOO_FEW_HAZARDS);
        }
        return problems;
    }

    public boolean accepted(ChallengeMinimums minimums) {
        return problems(minimums).isEmpty();
    }
}
