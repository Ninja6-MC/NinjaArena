package com.ninja6.ninjaarena.waiting;

import com.ninja6.ninjaarena.portal.LobbyMode;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.bukkit.configuration.ConfigurationSection;

/**
 * What a new room starts from, read from the {@code waiting-room} section of
 * {@code config.yml}, which kept its name from the waiting rooms that rooms replaced.
 * Out-of-range values are clamped; the reload validates them strictly first.
 *
 * @param minPlayers players a new room needs before its host can start it
 * @param teamSize   the most players on one team of a new room; 0 for no limit
 * @param lobby      for portals that make no choice of their own, which open rooms a player
 *                   who walks in joins: those on the portal's arena, or any; deprecated, see
 *                   {@link #DEPRECATED}
 */
public record WaitingRoomSettings(int minPlayers, int teamSize, LobbyMode lobby) {

    public static final int DEFAULT_MIN_PLAYERS = 2;
    public static final int DEFAULT_TEAM_SIZE = 0;
    public static final LobbyMode DEFAULT_LOBBY = LobbyMode.DEDICATED;

    static final int MAX_MIN_PLAYERS = 64;
    static final int MAX_TEAM_SIZE = 64;

    /** {@code waiting-room.lobby}: still read, with a startup warning, for portal routing. */
    public static final String LOBBY = "waiting-room.lobby";

    /**
     * Waiting-room settings that only the automatic countdown read. They are ignored, with a
     * warning, and no longer in the bundled config.
     */
    public static final List<String> REMOVED = List.of("waiting-room.max-players",
            "waiting-room.countdown-seconds", "waiting-room.radius");

    /** Every old waiting-room setting a server may still have: {@link #LOBBY} and {@link #REMOVED}. */
    public static final List<String> DEPRECATED = Stream.concat(Stream.of(LOBBY), REMOVED.stream()).toList();

    /** The README section the warnings point to. */
    public static final String MIGRATION_DOCS = "README.md, \"Moving from waiting rooms to rooms\"";

    public static final WaitingRoomSettings DEFAULTS = new WaitingRoomSettings(DEFAULT_MIN_PLAYERS,
            DEFAULT_TEAM_SIZE, DEFAULT_LOBBY);

    public WaitingRoomSettings {
        minPlayers = Math.clamp(minPlayers, 1, MAX_MIN_PLAYERS);
        teamSize = Math.clamp(teamSize, 0, MAX_TEAM_SIZE);
        Objects.requireNonNull(lobby, "lobby");
    }

    /** A room needing this many players, with no team-size limit. */
    public WaitingRoomSettings(int minPlayers) {
        this(minPlayers, DEFAULT_TEAM_SIZE, DEFAULT_LOBBY);
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
                section.getInt("team-size", DEFAULT_TEAM_SIZE),
                LobbyMode.parse(section.getString("lobby")).orElse(DEFAULT_LOBBY));
    }

    /**
     * The server log line for an old waiting-room setting that {@code config.yml} still sets,
     * such as {@link #LOBBY} or one of {@link #REMOVED}.
     */
    public static String warning(String path) {
        String effect = path.equals(LOBBY)
                ? "is deprecated: no match starts by itself any more and portals lead to the lobby."
                        + " It now only chooses which open rooms a portal joins (dedicated: its own arena,"
                        + " shared: any)."
                : "is no longer used: it belonged to the automatic waiting-room countdown, which rooms"
                        + " replaced. Remove it.";
        return path + " " + effect + " See " + MIGRATION_DOCS + ".";
    }

    /** Logs {@link #warning} for each deprecated setting the server's own config sets. */
    public static void warnDeprecated(ConfigurationSection config, Consumer<String> warnings) {
        for (String path : DEPRECATED) {
            if (config.contains(path, true)) {
                warnings.accept(warning(path));
            }
        }
    }
}
