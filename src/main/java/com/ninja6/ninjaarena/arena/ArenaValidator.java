package com.ninja6.ninjaarena.arena;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether an arena is complete enough to host a match. An arena with any problem
 * is unavailable for matches; the problems are shown to the admin building it.
 */
public final class ArenaValidator {

    private ArenaValidator() {
    }

    /**
     * Lists what is missing or out of place, in the order an admin would fix it.
     *
     * @return empty if the arena can host a match
     */
    public static List<String> problems(Arena arena, ArenaGrid grid) {
        List<String> problems = new ArrayList<>();
        if (!grid.insideBorder(arena.cell())) {
            problems.add("Cell " + arena.cell().describe() + " is not inside the world border.");
        }

        ArenaBounds bounds = arena.bounds();
        if (bounds == null) {
            problems.add("Bounds are not set.");
        } else if (!grid.cellBounds(arena.cell()).contains(bounds)) {
            ArenaBounds cell = grid.cellBounds(arena.cell());
            problems.add("Bounds " + bounds.describe() + " leave cell " + arena.cell().describe()
                    + " (" + cell.describe() + ").");
        }

        if (arena.waitingRoom() == null) {
            problems.add("The waiting room is not set.");
        } else if (bounds != null && !bounds.contains(arena.waitingRoom())) {
            problems.add("The waiting room is outside the bounds.");
        }

        List<ArenaPoint> teams = arena.teamSpawns();
        if (teams.size() < Arena.MIN_TEAMS) {
            problems.add("Needs " + Arena.MIN_TEAMS + " to " + Arena.MAX_TEAMS + " team spawns, has "
                    + teams.size() + ".");
        }
        if (bounds != null) {
            for (int i = 0; i < teams.size(); i++) {
                if (!bounds.contains(teams.get(i))) {
                    problems.add("Team " + (i + 1) + " spawn is outside the bounds.");
                }
            }
        }

        List<ArenaPoint> enemies = arena.enemySpawns();
        if (enemies.isEmpty()) {
            problems.add("Needs at least one enemy spawn point.");
        }
        if (bounds != null) {
            for (int i = 0; i < enemies.size(); i++) {
                if (!bounds.contains(enemies.get(i))) {
                    problems.add("Enemy spawn " + (i + 1) + " is outside the bounds.");
                }
            }
        }
        return problems;
    }
}
