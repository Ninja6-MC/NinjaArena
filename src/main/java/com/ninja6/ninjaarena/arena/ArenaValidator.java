package com.ninja6.ninjaarena.arena;

import static com.ninja6.ninjaarena.message.Messages.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether an arena is complete enough to host a match. An arena with any problem
 * is unavailable for matches; the problems are shown to the admin building it, worded by
 * the {@code arena.problem} messages.
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
        return check(arena, grid, false);
    }

    /** Only the points that lie outside bounds already set, so a mistake shows up straight away. */
    public static List<String> outside(Arena arena, ArenaGrid grid) {
        return check(arena, grid, true);
    }

    private static List<String> check(Arena arena, ArenaGrid grid, boolean outsideOnly) {
        List<String> problems = new ArrayList<>();
        List<String> missing = outsideOnly ? new ArrayList<>() : problems;
        if (!grid.insideBorder(arena.cell())) {
            missing.add(text("arena.problem.cell-outside", "cell", arena.cell().describe()));
        }

        ArenaBounds bounds = arena.bounds();
        if (bounds == null) {
            missing.add(text("arena.problem.no-bounds"));
        } else if (!grid.cellBounds(arena.cell()).contains(bounds)) {
            ArenaBounds cell = grid.cellBounds(arena.cell());
            missing.add(text("arena.problem.bounds-leave-cell", "bounds", bounds.describe(),
                    "cell", arena.cell().describe(), "cell-bounds", cell.describe()));
        }

        if (arena.waitingRoom() != null && bounds != null && !bounds.contains(arena.waitingRoom())) {
            problems.add(text("arena.problem.waiting-room-outside"));
        }

        List<ArenaPoint> teams = arena.teamSpawns();
        if (teams.size() < Arena.MIN_TEAMS) {
            missing.add(text("arena.problem.team-spawns", "min", Arena.MIN_TEAMS, "max", Arena.MAX_TEAMS,
                    "count", teams.size()));
        }
        if (bounds != null) {
            for (int i = 0; i < teams.size(); i++) {
                if (!bounds.contains(teams.get(i))) {
                    problems.add(text("arena.problem.team-spawn-outside", "team", i + 1));
                }
            }
        }

        List<ArenaPoint> enemies = arena.enemySpawns();
        if (enemies.isEmpty()) {
            missing.add(text("arena.problem.no-enemy-spawns"));
        }
        if (bounds != null) {
            for (int i = 0; i < enemies.size(); i++) {
                if (!bounds.contains(enemies.get(i))) {
                    problems.add(text("arena.problem.enemy-spawn-outside", "number", i + 1));
                }
            }
        }
        return problems;
    }
}
