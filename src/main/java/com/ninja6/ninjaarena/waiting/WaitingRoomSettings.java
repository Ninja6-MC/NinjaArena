package com.ninja6.ninjaarena.waiting;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.portal.LobbyMode;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Tunable values for waiting rooms and the shared hub, read from the {@code waiting-room}
 * section of {@code config.yml}. Out-of-range values are clamped; the reload validates them
 * strictly first.
 *
 * @param minPlayers       players needed in a waiting room or the hub before the start
 *                         countdown begins
 * @param maxPlayers       the most players one match takes; 0 for no limit, otherwise at
 *                         least {@code minPlayers}
 * @param teamSize         the most players on one team; 0 for no limit
 * @param countdownSeconds length of the start countdown
 * @param radius           how far, in blocks along any axis, a player may be from the
 *                         waiting-room or hub point and still be in the room
 * @param lobby            where portals that make no choice of their own send players
 */
public record WaitingRoomSettings(int minPlayers, int maxPlayers, int teamSize, int countdownSeconds, int radius,
        LobbyMode lobby) {

    public static final int DEFAULT_MIN_PLAYERS = 2;
    public static final int DEFAULT_MAX_PLAYERS = 0;
    public static final int DEFAULT_TEAM_SIZE = 0;
    public static final int DEFAULT_COUNTDOWN_SECONDS = 30;
    public static final int DEFAULT_RADIUS = 16;
    public static final LobbyMode DEFAULT_LOBBY = LobbyMode.DEDICATED;

    static final int MAX_MIN_PLAYERS = 64;
    static final int MAX_MAX_PLAYERS = 256;
    static final int MAX_TEAM_SIZE = 64;
    static final int MAX_COUNTDOWN_SECONDS = 600;
    static final int MIN_RADIUS = 2;
    static final int MAX_RADIUS = 64;

    public static final WaitingRoomSettings DEFAULTS = new WaitingRoomSettings(DEFAULT_MIN_PLAYERS,
            DEFAULT_MAX_PLAYERS, DEFAULT_TEAM_SIZE, DEFAULT_COUNTDOWN_SECONDS, DEFAULT_RADIUS, DEFAULT_LOBBY);

    public WaitingRoomSettings {
        minPlayers = Math.clamp(minPlayers, 1, MAX_MIN_PLAYERS);
        maxPlayers = maxPlayers <= 0 ? 0 : Math.clamp(maxPlayers, minPlayers, MAX_MAX_PLAYERS);
        teamSize = Math.clamp(teamSize, 0, MAX_TEAM_SIZE);
        countdownSeconds = Math.clamp(countdownSeconds, 1, MAX_COUNTDOWN_SECONDS);
        radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        Objects.requireNonNull(lobby, "lobby");
    }

    /** Dedicated waiting rooms with no player cap. */
    public WaitingRoomSettings(int minPlayers, int countdownSeconds, int radius) {
        this(minPlayers, DEFAULT_MAX_PLAYERS, DEFAULT_TEAM_SIZE, countdownSeconds, radius, DEFAULT_LOBBY);
    }

    /**
     * Reads the settings from a config section, falling back to the defaults for a missing
     * section or key, and for a lobby that is neither {@code dedicated} nor {@code shared}.
     */
    public static WaitingRoomSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        return new WaitingRoomSettings(
                section.getInt("min-players", DEFAULT_MIN_PLAYERS),
                section.getInt("max-players", DEFAULT_MAX_PLAYERS),
                section.getInt("team-size", DEFAULT_TEAM_SIZE),
                section.getInt("countdown-seconds", DEFAULT_COUNTDOWN_SECONDS),
                section.getInt("radius", DEFAULT_RADIUS),
                LobbyMode.parse(section.getString("lobby")).orElse(DEFAULT_LOBBY));
    }

    /**
     * The most players one match in an arena with this many teams takes: {@link #maxPlayers},
     * and {@link #teamSize} per team, whichever is lower. {@link Integer#MAX_VALUE} when
     * neither is set.
     */
    public int capacity(int teams) {
        long capacity = maxPlayers == 0 ? Integer.MAX_VALUE : maxPlayers;
        if (teamSize > 0) {
            capacity = Math.min(capacity, (long) Math.max(1, teams) * teamSize);
        }
        return (int) capacity;
    }

    /**
     * Whether a block position is in the room: within {@link #radius} blocks of the room's
     * block along each axis. A player falling into the void below leaves the room before
     * they reach it.
     */
    public boolean inRoom(ArenaPoint room, int blockX, int blockY, int blockZ) {
        return Math.abs(blockX - room.blockX()) <= radius
                && Math.abs(blockY - room.blockY()) <= radius
                && Math.abs(blockZ - room.blockZ()) <= radius;
    }
}
