package com.ninja6.ninjaarena.waiting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.UUID;

/**
 * The players waiting in one arena's waiting room, the team each is on, and the start
 * countdown. Holds no Bukkit state, so the balancing and countdown rules are tested on their
 * own; {@link WaitingRoomService} turns its results into messages and teleports.
 *
 * <p><b>Balancing.</b> A player who arrives joins the smallest team, the lowest-numbered
 * one on a tie. When someone leaves and the largest team is then two or more ahead of the
 * smallest, the player who joined the largest team last moves to the smallest. Arrivals and
 * departures come one at a time, so team sizes never differ by more than one. A hosted
 * party is kept together up to one balanced team's capacity, host first, and reunites as
 * later arrivals make room. Unrelated players move before those kept together.
 *
 * <p><b>Countdown.</b> It starts as soon as {@code minPlayers} are waiting and runs for
 * {@code countdownSeconds} calls to {@link #tick()}, one a second. A departure that takes
 * the count below the minimum cancels it; the next arrival that reaches the minimum starts
 * it again from the full length. When it reaches zero, {@link #tick()} reports
 * {@link Phase#START} once and the room is emptied: from then on the players belong to the
 * match.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class WaitingRoom {

    /** What a call to {@link #tick()} found. */
    public enum Phase {
        /** Fewer than the minimum are waiting; no countdown runs. */
        IDLE,
        /** The countdown is running; {@link Tick#secondsLeft()} says how long is left. */
        COUNTING,
        /** The countdown reached zero. {@link Tick#teams()} holds who starts, by team. */
        START
    }

    /**
     * @param secondsLeft seconds until the start while {@link Phase#COUNTING}, otherwise 0
     * @param teams       the players on each team, team 1 first, for {@link Phase#START};
     *                    otherwise empty
     */
    public record Tick(Phase phase, int secondsLeft, List<List<UUID>> teams) {

        public Tick {
            teams = teams.stream().map(List::copyOf).toList();
        }

        static Tick idle() {
            return new Tick(Phase.IDLE, 0, List.of());
        }
    }

    /**
     * @param team             the team the player was put on, numbered from 1
     * @param countdownStarted whether this arrival reached the minimum and started the countdown
     */
    public record Joined(int team, boolean countdownStarted, List<Move> moves) {
        public Joined {
            moves = List.copyOf(moves);
        }
    }

    /** A player moved from one team to another to keep the teams even. */
    public record Move(UUID player, int from, int to) {
    }

    /**
     * @param team               the team the player was on, numbered from 1
     * @param moves              the players moved to rebalance the teams
     * @param countdownCancelled whether this departure took the count below the minimum
     *                           while the countdown ran
     */
    public record Left(int team, List<Move> moves, boolean countdownCancelled) {
        public Left {
            moves = List.copyOf(moves);
        }

        public Optional<Move> move() {
            return moves.stream().findFirst();
        }
    }

    private int minPlayers;
    private int countdownSeconds;
    /** Each team's players in the order they joined it. */
    private final List<SequencedSet<UUID>> teams = new ArrayList<>();
    private List<UUID> party = List.of();

    /** Hosted players, host first; missing members may arrive later. */
    public void setParty(List<UUID> party) {
        this.party = List.copyOf(party);
    }

    public List<Move> refreshParty() {
        Map<UUID, Integer> before = assignments();
        balanceParty();
        return moves(before);
    }

    /** Seconds left while counting; 0 when no countdown runs. */
    private int secondsLeft;

    /**
     * @param teamCount        how many teams the arena has; at least 1
     * @param minPlayers       players needed to start the countdown; at least 1
     * @param countdownSeconds length of the countdown; at least 1
     */
    public WaitingRoom(int teamCount, int minPlayers, int countdownSeconds) {
        if (teamCount < 1) {
            throw new IllegalArgumentException("teamCount must be at least 1");
        }
        if (minPlayers < 1) {
            throw new IllegalArgumentException("minPlayers must be at least 1");
        }
        if (countdownSeconds < 1) {
            throw new IllegalArgumentException("countdownSeconds must be at least 1");
        }
        this.minPlayers = minPlayers;
        this.countdownSeconds = countdownSeconds;
        for (int i = 0; i < teamCount; i++) {
            teams.add(new LinkedHashSet<>());
        }
    }

    /** Keeps a viable countdown's remaining time while changing future countdowns. */
    public void setSettings(int minPlayers, int countdownSeconds) {
        if (minPlayers < 1 || countdownSeconds < 1) {
            throw new IllegalArgumentException("waiting settings must be positive");
        }
        this.minPlayers = minPlayers;
        this.countdownSeconds = countdownSeconds;
        if (size() < minPlayers) {
            secondsLeft = 0;
        } else if (secondsLeft == 0) {
            secondsLeft = countdownSeconds;
        }
    }

    /**
     * Puts a player on the smallest team and starts the countdown if they bring the count to
     * the minimum.
     *
     * @throws IllegalStateException if the player is already waiting here
     */
    public Joined join(UUID player) {
        Objects.requireNonNull(player, "player");
        if (contains(player)) {
            throw new IllegalStateException(player + " is already waiting");
        }
        Map<UUID, Integer> before = assignments();
        int team = smallest();
        teams.get(team).add(player);
        balanceParty();
        boolean started = false;
        if (secondsLeft == 0 && size() >= minPlayers) {
            secondsLeft = countdownSeconds;
            started = true;
        }
        return new Joined(teamOf(player), started, moves(before));
    }

    /**
     * Takes a player out, rebalances the teams and cancels the countdown if the count drops
     * below the minimum.
     *
     * @return empty if the player was not waiting here
     */
    public Optional<Left> leave(UUID player) {
        int team = indexOf(player);
        if (team < 0) {
            return Optional.empty();
        }
        teams.get(team).remove(player);
        Map<UUID, Integer> before = assignments();
        if (party.isEmpty()) {
            rebalance();
        } else {
            balanceParty();
        }
        boolean cancelled = false;
        if (secondsLeft > 0 && size() < minPlayers) {
            secondsLeft = 0;
            cancelled = true;
        }
        return Optional.of(new Left(team + 1, moves(before), cancelled));
    }

    /** Advances the countdown by one second. */
    public Tick tick() {
        if (secondsLeft == 0) {
            return Tick.idle();
        }
        secondsLeft--;
        if (secondsLeft > 0) {
            return new Tick(Phase.COUNTING, secondsLeft, List.of());
        }
        List<List<UUID>> starting = teams();
        for (SequencedSet<UUID> members : teams) {
            members.clear();
        }
        return new Tick(Phase.START, 0, starting);
    }

    public boolean contains(UUID player) {
        return indexOf(player) >= 0;
    }

    /** The player's team, numbered from 1, or 0 if they are not waiting here. */
    public int teamOf(UUID player) {
        return indexOf(player) + 1;
    }

    public int size() {
        int size = 0;
        for (SequencedSet<UUID> members : teams) {
            size += members.size();
        }
        return size;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public int minPlayers() {
        return minPlayers;
    }

    public boolean isCounting() {
        return secondsLeft > 0;
    }

    /** Seconds until the start while the countdown runs, otherwise 0. */
    public int secondsLeft() {
        return secondsLeft;
    }

    /** Every waiting player, team 1 first, each team in the order its players joined. */
    public List<UUID> players() {
        List<UUID> all = new ArrayList<>();
        for (SequencedSet<UUID> members : teams) {
            all.addAll(members);
        }
        return all;
    }

    /** A copy of each team's players, team 1 first. */
    public List<List<UUID>> teams() {
        List<List<UUID>> copy = new ArrayList<>();
        for (SequencedSet<UUID> members : teams) {
            copy.add(List.copyOf(members));
        }
        return copy;
    }

    private int indexOf(UUID player) {
        for (int i = 0; i < teams.size(); i++) {
            if (teams.get(i).contains(player)) {
                return i;
            }
        }
        return -1;
    }

    /** The smallest team's index, the lowest on a tie. */
    private int smallest() {
        int best = 0;
        for (int i = 1; i < teams.size(); i++) {
            if (teams.get(i).size() < teams.get(best).size()) {
                best = i;
            }
        }
        return best;
    }

    /** The largest team's index, the highest on a tie, so low-numbered teams stay put. */
    private int largest() {
        int best = teams.size() - 1;
        for (int i = teams.size() - 2; i >= 0; i--) {
            if (teams.get(i).size() > teams.get(best).size()) {
                best = i;
            }
        }
        return best;
    }

    private Optional<Move> rebalance() {
        int from = largest();
        int to = smallest();
        if (teams.get(from).size() - teams.get(to).size() < 2) {
            return Optional.empty();
        }
        UUID moved = teams.get(from).removeLast();
        teams.get(to).add(moved);
        return Optional.of(new Move(moved, from + 1, to + 1));
    }

    private Map<UUID, Integer> assignments() {
        Map<UUID, Integer> out = new HashMap<>();
        for (UUID id : players()) {
            out.put(id, teamOf(id));
        }
        return out;
    }

    private List<Move> moves(Map<UUID, Integer> before) {
        List<Move> moved = new ArrayList<>();
        for (UUID id : players()) {
            Integer from = before.get(id);
            if (from != null && from != teamOf(id)) {
                moved.add(new Move(id, from, teamOf(id)));
            }
        }
        return List.copyOf(moved);
    }

    /** Reserve one balanced team's capacity for the host and accepted players, in that order. */
    private void balanceParty() {
        List<UUID> present = party.stream().filter(this::contains).toList();
        if (present.isEmpty()) {
            rebalance();
            return;
        }
        int capacity = (size() + teams.size() - 1) / teams.size();
        List<UUID> together = present.subList(0, Math.min(capacity, present.size()));
        int target = indexOf(together.getFirst());
        for (UUID id : together) {
            int from = indexOf(id);
            if (from != target) {
                teams.get(from).remove(id);
                teams.get(target).add(id);
            }
        }
        while (teams.get(largest()).size() - teams.get(smallest()).size() >= 2) {
            int to = smallest();
            int from = -1;
            UUID candidate = null;
            for (int i = teams.size() - 1; i >= 0; i--) {
                if (teams.get(i).size() <= teams.get(to).size() + 1
                        || (from >= 0 && teams.get(i).size() < teams.get(from).size())) {
                    continue;
                }
                for (UUID id : teams.get(i).reversed()) {
                    if (!together.contains(id)) {
                        from = i;
                        candidate = id;
                        break;
                    }
                }
            }
            if (candidate == null) {
                throw new IllegalStateException("No movable player in unbalanced waiting room");
            }
            teams.get(from).remove(candidate);
            teams.get(to).add(candidate);
        }
    }
}
