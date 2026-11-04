package com.ninja6.ninjaarena.portal;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a portal sends the players who walk into it before their match.
 *
 * <p>The admin chooses one per portal; a portal that does not choose, such as a player-built
 * one, uses {@code waiting-room.lobby} from {@code config.yml}.
 */
public enum LobbyMode {

    /** The waiting room of the portal's own arena, which starts a match there once enough players arrive. */
    DEDICATED,
    /** The one shared hub, which sends a group to whichever arena is free once enough players are there. */
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
