package com.ninja6.ninjaarena.restore;

import com.ninja6.ninjaarena.lobby.LobbyMembers;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.spectate.Spectators;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Whether a player is in a match, a waiting room, the hub or the lobby, or spectating. The
 * services are looked up on each call because they are created after the restore listener
 * and are {@code null} while the plugin is disabled.
 */
public final class ArenaBusy implements Predicate<UUID> {

    private final Supplier<MatchService> matches;
    private final Supplier<WaitingRoomService> waitingRooms;
    private final Supplier<Spectators> spectators;
    private final Supplier<LobbyMembers> lobby;

    public ArenaBusy(Supplier<MatchService> matches, Supplier<WaitingRoomService> waitingRooms,
            Supplier<Spectators> spectators) {
        this(matches, waitingRooms, spectators, () -> null);
    }

    public ArenaBusy(Supplier<MatchService> matches, Supplier<WaitingRoomService> waitingRooms,
            Supplier<Spectators> spectators, Supplier<LobbyMembers> lobby) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.waitingRooms = Objects.requireNonNull(waitingRooms, "waitingRooms");
        this.spectators = Objects.requireNonNull(spectators, "spectators");
        this.lobby = Objects.requireNonNull(lobby, "lobby");
    }

    @Override
    public boolean test(UUID id) {
        MatchService match = matches.get();
        WaitingRoomService rooms = waitingRooms.get();
        Spectators viewers = spectators.get();
        LobbyMembers members = lobby.get();
        return match != null && match.isInMatch(id)
                || rooms != null && (rooms.isWaiting(id) || rooms.inHub(id))
                || viewers != null && viewers.contains(id)
                || members != null && members.isMember(id);
    }
}
