package com.ninja6.ninjaarena.terrain;

/** Feature measures over a model, for checking that harshness grows with difficulty. */
final class TerrainCensus {

    /** A column whose ground is at least this far under the base level counts as a ravine. */
    static final int RAVINE_DROP = 10;

    /** Neighbouring columns this far apart in height or more form a cliff step. */
    static final int CLIFF_STEP = 3;

    private TerrainCensus() {
    }

    /** Air cells with ground above them in the same column: the space under overhangs. */
    static int overhangCells(TerrainModel model) {
        SideDimensions size = model.dimensions();
        int count = 0;
        for (int z = 0; z < size.length(); z++) {
            for (int x = 0; x < size.width(); x++) {
                int top = model.terrainTopY(x, z);
                for (int y = 0; y < top; y++) {
                    if (model.at(x, y, z) == BlockKind.AIR) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Columns cut deep below the base level. */
    static int ravineColumns(TerrainModel model) {
        SideDimensions size = model.dimensions();
        int count = 0;
        for (int z = 0; z < size.length(); z++) {
            for (int x = 0; x < size.width(); x++) {
                if (model.terrainTopY(x, z) <= model.spawnSurfaceY() - RAVINE_DROP) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Pairs of neighbouring columns whose ground differs by a cliff step or more. */
    static int cliffSteps(TerrainModel model) {
        SideDimensions size = model.dimensions();
        int count = 0;
        for (int z = 0; z < size.length(); z++) {
            for (int x = 0; x < size.width(); x++) {
                int top = model.terrainTopY(x, z);
                if (x + 1 < size.width() && Math.abs(top - model.terrainTopY(x + 1, z)) >= CLIFF_STEP) {
                    count++;
                }
                if (z + 1 < size.length() && Math.abs(top - model.terrainTopY(x, z + 1)) >= CLIFF_STEP) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Variance of the ground height over all columns. */
    static double heightVariance(TerrainModel model) {
        SideDimensions size = model.dimensions();
        double sum = 0;
        double squares = 0;
        int columns = size.width() * size.length();
        for (int z = 0; z < size.length(); z++) {
            for (int x = 0; x < size.width(); x++) {
                int top = model.terrainTopY(x, z);
                sum += top;
                squares += (double) top * top;
            }
        }
        double mean = sum / columns;
        return squares / columns - mean * mean;
    }
}
