package com.ninja6.ninjaarena.waiting;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Tunable values for waiting rooms, read from the {@code waiting-room} section of
 * {@code config.yml}. Out-of-range values are clamped.
 *
 * @param minPlayers       players needed in a waiting room before the start countdown begins
 * @param countdownSeconds length of the start countdown
 * @param radius           how far, in blocks along any axis, a player may be from the
 *                         waiting-room point and still be in the room
 */
public record WaitingRoomSettings(int minPlayers, int countdownSeconds, int radius) {

    public static final int DEFAULT_MIN_PLAYERS = 2;
    public static final int DEFAULT_COUNTDOWN_SECONDS = 30;
    public static final int DEFAULT_RADIUS = 16;

    static final int MAX_MIN_PLAYERS = 64;
    static final int MAX_COUNTDOWN_SECONDS = 600;
    static final int MIN_RADIUS = 2;
    static final int MAX_RADIUS = 64;

    public static final WaitingRoomSettings DEFAULTS =
            new WaitingRoomSettings(DEFAULT_MIN_PLAYERS, DEFAULT_COUNTDOWN_SECONDS, DEFAULT_RADIUS);

    public WaitingRoomSettings {
        minPlayers = Math.clamp(minPlayers, 1, MAX_MIN_PLAYERS);
        countdownSeconds = Math.clamp(countdownSeconds, 1, MAX_COUNTDOWN_SECONDS);
        radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
    }

    /**
     * Reads the settings from a config section, falling back to the defaults for a missing
     * section or key.
     */
    public static WaitingRoomSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        return new WaitingRoomSettings(
                section.getInt("min-players", DEFAULT_MIN_PLAYERS),
                section.getInt("countdown-seconds", DEFAULT_COUNTDOWN_SECONDS),
                section.getInt("radius", DEFAULT_RADIUS));
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
