package com.ninja6.ninjaarena.room;

import com.ninja6.ninjaarena.waiting.WaitingRoom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * One host's room: who is in it, in the order they joined, the team each is on, the host's
 * settings and the hand-built arena it plays on. Holds no Bukkit state; {@link RoomService}
 * turns its answers into messages and teleports.
 *
 * <p>Teams are kept even by {@link WaitingRoom}, the same balancing the waiting rooms use: a
 * player who joins goes to the smallest team, and a departure that leaves one team two
 * ahead moves a player across. Nothing here counts down: the match starts only when the
 * host or an admin presses start, and only once {@link #canStart()}.
 *
 * <p>Compared by identity, so a room keeps its arena lease when its host changes.
 * Server thread only.
 */
public final class Room {

    private UUID host;
    private RoomSettings settings;
    private String arena;
    private final SequencedSet<UUID> members = new LinkedHashSet<>();
    private final Set<UUID> invited = new HashSet<>();
    private final Set<UUID> kicked = new HashSet<>();
    private WaitingRoom teams;
    private boolean playing;

    /** A room with only its host in it. */
    public Room(UUID host, String arena, RoomSettings settings) {
        this.host = Objects.requireNonNull(host, "host");
        this.arena = Objects.requireNonNull(arena, "arena");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.teams = teams(settings, List.of());
        members.add(host);
        this.teams.join(host);
    }

    public UUID host() {
        return host;
    }

    public RoomSettings settings() {
        return settings;
    }

    public String arena() {
        return arena;
    }

    /** Changes the arena the room plays on; the caller moves its lease. */
    public void setArena(String arena) {
        this.arena = Objects.requireNonNull(arena, "arena");
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
    }

    /** Members in the order they joined, the longest waiting first. */
    public List<UUID> members() {
        return List.copyOf(members);
    }

    public boolean contains(UUID player) {
        return members.contains(player);
    }

    public int size() {
        return members.size();
    }

    public boolean isFull() {
        return size() >= settings.capacity();
    }

    /** Whether there are enough players to start: the required count, or a full room. */
    public boolean canStart() {
        return size() >= Math.min(settings.players(), settings.capacity());
    }

    /** The player's team, numbered from 1, or 0 if they are not in the room. */
    public int teamOf(UUID player) {
        return teams.teamOf(player);
    }

    /** Each team's players, team 1 first. */
    public List<List<UUID>> teams() {
        return teams.teams();
    }

    public boolean isInvited(UUID player) {
        return invited.contains(player);
    }

    public void invite(UUID player) {
        invited.add(player);
        kicked.remove(player);
    }

    /**
     * Whether the player may join: a public room takes anyone it has not removed, an
     * invite-only room only those invited.
     */
    public boolean admits(UUID player) {
        return invited.contains(player) || !settings.inviteOnly() && !kicked.contains(player);
    }

    /**
     * Adds a player to the smallest team.
     *
     * @throws IllegalStateException if they are already in the room or it is full
     */
    public WaitingRoom.Joined join(UUID player) {
        if (isFull()) {
            throw new IllegalStateException("the room is full");
        }
        WaitingRoom.Joined joined = teams.join(player);
        members.add(player);
        invited.remove(player);
        return joined;
    }

    /**
     * Takes a player out and rebalances the teams. A host who leaves hands the room to the
     * longest-waiting member that {@code canHost} accepts.
     *
     * @return the team moves, or empty if they were not in the room
     */
    public Optional<List<WaitingRoom.Move>> leave(UUID player, Predicate<UUID> canHost) {
        if (!members.remove(player)) {
            return Optional.empty();
        }
        List<WaitingRoom.Move> moves = teams.leave(player).map(WaitingRoom.Left::moves).orElse(List.of());
        if (player.equals(host)) {
            members.stream().filter(canHost).findFirst().ifPresent(next -> host = next);
        }
        return Optional.of(moves);
    }

    /** Takes a player out, as {@link #leave}, and keeps them out of a public room until invited. */
    public Optional<List<WaitingRoom.Move>> kick(UUID player) {
        Optional<List<WaitingRoom.Move>> moves = leave(player, id -> false);
        moves.ifPresent(ignored -> kicked.add(player));
        return moves;
    }

    /**
     * Whether the room has no host any more: the host left and nobody in it may host.
     * Such a room closes.
     */
    public boolean isOrphaned() {
        return !members.contains(host);
    }

    /**
     * Changes the settings. Players stay on their teams when the team count and size stay
     * the same; otherwise the teams are dealt again in join order.
     *
     * @throws IllegalArgumentException if the room holds more players than the new capacity
     */
    public void setSettings(RoomSettings next) {
        if (next.capacity() < size()) {
            throw new IllegalArgumentException("the room holds more players than that");
        }
        if (next.teams() != settings.teams() || next.teamSize() != settings.teamSize()) {
            teams = teams(next, members());
        }
        settings = next;
    }

    private static WaitingRoom teams(RoomSettings settings, List<UUID> players) {
        WaitingRoom room = new WaitingRoom(settings.teams(), 1, 1, settings.capacity());
        for (UUID player : players) {
            room.join(player);
        }
        return room;
    }

    /** Everyone in the room except one player. */
    List<UUID> others(UUID except) {
        List<UUID> others = new ArrayList<>(members);
        others.remove(except);
        return others;
    }
}
