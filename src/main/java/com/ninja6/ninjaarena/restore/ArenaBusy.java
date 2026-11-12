package com.ninja6.ninjaarena.restore;

import com.ninja6.ninjaarena.lobby.LobbyMembers;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.spectate.Spectators;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Whether a player is in a match or the lobby, or spectating. The services are looked up on
 * each call because they are created after the restore listener and are {@code null} while
 * the plugin is disabled.
 */
public final class ArenaBusy implements Predicate<UUID> {

    private final Supplier<MatchService> matches;
    private final Supplier<Spectators> spectators;
    private final Supplier<LobbyMembers> lobby;

    public ArenaBusy(Supplier<MatchService> matches, Supplier<Spectators> spectators) {
        this(matches, spectators, () -> null);
    }

    public ArenaBusy(Supplier<MatchService> matches, Supplier<Spectators> spectators,
            Supplier<LobbyMembers> lobby) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.spectators = Objects.requireNonNull(spectators, "spectators");
        this.lobby = Objects.requireNonNull(lobby, "lobby");
    }

    @Override
    public boolean test(UUID id) {
        MatchService match = matches.get();
        Spectators viewers = spectators.get();
        LobbyMembers members = lobby.get();
        return match != null && match.isInMatch(id)
                || viewers != null && viewers.contains(id)
                || members != null && members.isMember(id);
    }
}
