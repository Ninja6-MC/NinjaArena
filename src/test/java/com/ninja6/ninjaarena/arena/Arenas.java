package com.ninja6.ninjaarena.arena;

import java.util.List;

/** Arenas for tests, on a 256-block grid inside a 2048-block border. */
final class Arenas {

    static final ArenaGrid GRID = new ArenaGrid(256, 2048, -64, 320);

    private Arenas() {
    }

    static ArenaPoint point(double x, double y, double z) {
        return new ArenaPoint(x, y, z, 90, 10);
    }

    /** A complete arena in cell 0, 0 with two teams and two enemy spawns. */
    static Arena complete(String name) {
        return new Arena(name, new GridCell(0, 0), new ArenaBounds(10, 60, 10, 90, 100, 90),
                point(50.5, 64, 50.5),
                List.of(point(20.5, 64, 20.5), point(80.5, 64, 80.5)),
                List.of(point(50.5, 64, 20.5), point(50.5, 64, 80.5)));
    }
}
