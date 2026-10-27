package com.ninja6.ninjaarena.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A registered arena: the grid cell it is built in, its bounds inside that cell, the
 * waiting room, one spawn per team and the spawn points the enemy director picks from.
 * Instances are immutable; the {@code with} methods return a changed copy. Whether a
 * definition is complete enough for a match is decided by {@link ArenaValidator}, not here,
 * so an arena can be saved half built.
 *
 * @param bounds      {@code null} until set
 * @param waitingRoom {@code null} until set
 * @param teamSpawns  team 1 first; at most {@link #MAX_TEAMS}
 */
public record Arena(
        String name,
        GridCell cell,
        ArenaBounds bounds,
        ArenaPoint waitingRoom,
        List<ArenaPoint> teamSpawns,
        List<ArenaPoint> enemySpawns) {

    public static final int MIN_TEAMS = 2;
    public static final int MAX_TEAMS = 4;

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    public Arena {
        Objects.requireNonNull(name, "name");
        if (!isValidName(name)) {
            throw new IllegalArgumentException("invalid arena name " + name);
        }
        Objects.requireNonNull(cell, "cell");
        teamSpawns = List.copyOf(teamSpawns);
        enemySpawns = List.copyOf(enemySpawns);
        if (teamSpawns.size() > MAX_TEAMS) {
            throw new IllegalArgumentException("at most " + MAX_TEAMS + " team spawns");
        }
    }

    /** A new, empty arena in the given cell. */
    public static Arena create(String name, GridCell cell) {
        return new Arena(name, cell, null, null, List.of(), List.of());
    }

    /** Lower case letters, digits, {@code _} and {@code -}, 1 to 32 characters. */
    public static boolean isValidName(String name) {
        return NAME.matcher(name).matches();
    }

    /** Arena names are case-insensitive; this is the stored form. */
    public static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Arena withBounds(ArenaBounds newBounds) {
        return new Arena(name, cell, newBounds, waitingRoom, teamSpawns, enemySpawns);
    }

    public Arena withWaitingRoom(ArenaPoint point) {
        return new Arena(name, cell, bounds, point, teamSpawns, enemySpawns);
    }

    /**
     * Sets the spawn of a team, numbered from 1. A team can only be added directly after the
     * last one, so the numbers stay contiguous.
     */
    public Arena withTeamSpawn(int team, ArenaPoint point) {
        if (team < 1 || team > Math.min(teamSpawns.size() + 1, MAX_TEAMS)) {
            throw new IllegalArgumentException("team must be 1 to " + Math.min(teamSpawns.size() + 1, MAX_TEAMS));
        }
        List<ArenaPoint> spawns = new ArrayList<>(teamSpawns);
        if (team <= spawns.size()) {
            spawns.set(team - 1, point);
        } else {
            spawns.add(point);
        }
        return new Arena(name, cell, bounds, waitingRoom, spawns, enemySpawns);
    }

    /** Removes the last team's spawn. Only the last can go, so team numbers never shift. */
    public Arena withoutLastTeamSpawn() {
        if (teamSpawns.isEmpty()) {
            throw new IllegalArgumentException("no team spawns to remove");
        }
        return new Arena(name, cell, bounds, waitingRoom, teamSpawns.subList(0, teamSpawns.size() - 1), enemySpawns);
    }

    public Arena withEnemySpawn(ArenaPoint point) {
        List<ArenaPoint> spawns = new ArrayList<>(enemySpawns);
        spawns.add(point);
        return new Arena(name, cell, bounds, waitingRoom, teamSpawns, spawns);
    }

    /** Removes an enemy spawn point, numbered from 1 as {@code info} lists them. */
    public Arena withoutEnemySpawn(int number) {
        if (number < 1 || number > enemySpawns.size()) {
            throw new IllegalArgumentException("no enemy spawn " + number);
        }
        List<ArenaPoint> spawns = new ArrayList<>(enemySpawns);
        spawns.remove(number - 1);
        return new Arena(name, cell, bounds, waitingRoom, teamSpawns, spawns);
    }
}
