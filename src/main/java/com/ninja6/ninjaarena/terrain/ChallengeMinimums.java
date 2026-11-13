package com.ninja6.ninjaarena.terrain;

/**
 * The least challenge a side's direct route must hold at a difficulty, compared against a
 * {@link CrossingReport}. The values from {@link #forDifficulty} are initial ones, set from
 * what the generator produces so that most seeds pass, and are tuned by play testing.
 *
 * @param forcedJumps    jumps over a gap the direct route cannot avoid
 * @param climb          blocks the direct route climbs in total
 * @param hazardExposure hazard blocks next to the direct route
 */
public record ChallengeMinimums(int forcedJumps, int climb, int hazardExposure) {

    /** No minimums: only crossability, a safe spawn and enemy spawns are checked. */
    public static final ChallengeMinimums NONE = new ChallengeMinimums(0, 0, 0);

    // Initial values, about the 10th to 20th percentile of what the generator gives at each
    // difficulty, so most seeds pass. Jumps stay at 0: about 4 in 10 direct routes at every
    // difficulty need none.
    private static final ChallengeMinimums[] BY_DIFFICULTY = {
        new ChallengeMinimums(0, 1, 0),
        new ChallengeMinimums(0, 2, 0),
        new ChallengeMinimums(0, 2, 0),
        new ChallengeMinimums(0, 2, 1),
        new ChallengeMinimums(0, 2, 2),
    };

    public ChallengeMinimums {
        if (forcedJumps < 0 || climb < 0 || hazardExposure < 0) {
            throw new IllegalArgumentException("minimums cannot be negative");
        }
    }

    /** The initial minimums for a difficulty from 1 to 5. */
    public static ChallengeMinimums forDifficulty(int difficulty) {
        if (difficulty < TerrainGenerator.MIN_DIFFICULTY || difficulty > TerrainGenerator.MAX_DIFFICULTY) {
            throw new IllegalArgumentException("difficulty must be " + TerrainGenerator.MIN_DIFFICULTY + " to "
                    + TerrainGenerator.MAX_DIFFICULTY);
        }
        return BY_DIFFICULTY[difficulty - 1];
    }
}
