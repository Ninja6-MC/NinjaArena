package com.ninja6.ninjaarena.portal;

import java.util.Locale;
import java.util.Optional;

/**
 * A play portal's old lobby choice. Every play portal now leads to the lobby; this only
 * decides which open rooms a player who walks in joins there. Deprecated, but still read.
 *
 * <p>The admin chooses one per portal with {@code /narena portal lobby}; a portal that does
 * not choose, such as a player-built one, uses {@code waiting-room.lobby} from
 * {@code config.yml}.
 */
public enum LobbyMode {

    /** Once the waiting room of the portal's own arena; now an open room on that arena. */
    DEDICATED,
    /** Once the one shared hub; now any open room, whatever its arena. */
    SHARED;

    /** The name used in {@code config.yml}, {@code portals.yml} and commands. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Reads {@code dedicated} or {@code shared}, in any case; empty for anything else. */
    public static Optional<LobbyMode> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        for (LobbyMode mode : values()) {
            if (mode.id().equals(text.toLowerCase(Locale.ROOT))) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
