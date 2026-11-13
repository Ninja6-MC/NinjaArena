package com.ninja6.ninjaarena.room;

import com.ninja6.ninjaarena.terrain.TerrainGenerator;

/**
 * A seed and the difficulty to generate a map from it at.
 *
 * @param difficulty {@link TerrainGenerator#MIN_DIFFICULTY} to
 *                   {@link TerrainGenerator#MAX_DIFFICULTY}
 */
public record SeedChoice(long seed, int difficulty) {

    public SeedChoice {
        if (difficulty < TerrainGenerator.MIN_DIFFICULTY || difficulty > TerrainGenerator.MAX_DIFFICULTY) {
            throw new IllegalArgumentException("difficulty must be " + TerrainGenerator.MIN_DIFFICULTY + " to "
                    + TerrainGenerator.MAX_DIFFICULTY);
        }
    }
}
