package com.ninja6.ninjaarena.terrain;

/**
 * A small hand-built side for solver tests: flat ground with its top at {@code groundY},
 * a spawn pad over the first {@link SideDimensions#spawnDepth()} rows, and helpers to cut
 * and fill boxes. Coordinates are side coordinates; boxes are inclusive.
 */
final class TestSide {

    static final int WIDTH = 16;
    static final int LENGTH = 24;
    static final int HEIGHT = 32;
    static final int GROUND = 10;

    private final SideDimensions size = new SideDimensions(WIDTH, LENGTH, HEIGHT);
    private final byte[] cells = new byte[size.volume()];
    private int centreY = GROUND;

    TestSide() {
        fill(0, 0, 0, WIDTH - 1, GROUND - 1, LENGTH - 1, BlockKind.ROCK);
        fill(0, GROUND, 0, WIDTH - 1, GROUND, LENGTH - 1, BlockKind.SURFACE);
        fill(0, GROUND, 0, WIDTH - 1, GROUND, size.spawnDepth() - 1, BlockKind.SPAWN_PAD);
    }

    /** The middle of the spawn pad, where the solver puts the team spawn when it is safe. */
    static int spawnX() {
        return WIDTH / 2;
    }

    static int spawnZ() {
        return new SideDimensions(WIDTH, LENGTH, HEIGHT).spawnDepth() / 2;
    }

    TestSide fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockKind kind) {
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    cells[(z * WIDTH + x) * HEIGHT + y] = (byte) kind.ordinal();
                }
            }
        }
        return this;
    }

    /** A trench across the whole width over rows {@code z0..z1}, leaving ground up to {@code floorY}. */
    TestSide trench(int z0, int z1, int floorY) {
        return fill(0, floorY + 1, z0, WIDTH - 1, GROUND, z1, BlockKind.AIR)
                .fill(0, floorY, z0, WIDTH - 1, floorY, z1, BlockKind.SURFACE);
    }

    /** Moves the ground top of every row from {@code z0} on to {@code topY}, centre ledge included. */
    TestSide groundFrom(int z0, int topY) {
        fill(0, 0, z0, WIDTH - 1, HEIGHT - 1, LENGTH - 1, BlockKind.AIR);
        fill(0, 0, z0, WIDTH - 1, topY - 1, LENGTH - 1, BlockKind.ROCK);
        fill(0, topY, z0, WIDTH - 1, topY, LENGTH - 1, BlockKind.SURFACE);
        centreY = topY;
        return this;
    }

    TerrainModel build() {
        return new TerrainModel(1, 1, size, TerrainTheme.values()[0], GROUND, centreY, cells.clone());
    }

    MovementSolver solve() {
        return MovementSolver.solve(build(), MovementModel.DEFAULT);
    }
}
