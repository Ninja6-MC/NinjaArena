package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.match.MatchResult.Reason;
import com.ninja6.ninjaarena.match.MatchResult.Standing;
import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.MatchUpdate.Closed;
import com.ninja6.ninjaarena.match.MatchUpdate.MatchEnded;
import com.ninja6.ninjaarena.match.MatchUpdate.PhaseChanged;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerEliminated;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerLeft;
import com.ninja6.ninjaarena.match.MatchUpdate.RoundEnded;
import com.ninja6.ninjaarena.match.MatchUpdate.RoundStarted;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One match's round loop: phases, timers, who is alive, round results and the match result.
 * Holds no Bukkit state, so the rules are tested on their own; {@link MatchService} turns the
 * {@link MatchUpdate}s each method returns into teleports, messages and events.
 *
 * <p><b>Phases.</b> {@link #begin()} starts round 1. Each round is PREP for
 * {@link MatchSettings#prepSeconds()}, then COMBAT for {@link MatchSettings#roundSeconds()},
 * then OVERRUN until the round is decided or {@link MatchSettings#overrunLimitSeconds()}
 * runs out (a draw). A decided round goes to ROUND_END for
 * {@link MatchSettings#roundEndSeconds()}, and then to the next round or to MATCH_END,
 * which lasts {@link MatchSettings#matchEndSeconds()} before the match closes. Each call
 * to {@link #tick()} is one second.
 *
 * <p><b>Winning a round.</b> The teams that started with players are the match's
 * <i>starting teams</i>. With two or more, a round is won by the last team with a player
 * alive, and is a draw if the last players of every remaining team go down together. With
 * one (a team against the enemies alone), the round goes on until that team is down, a
 * draw. A player is taken out of the round by dying ({@link #eliminate}) or by leaving the
 * match ({@link #leave}), in PREP too. Neither decides the round by itself: that happens in
 * {@link #settle()}, which the service calls once at the end of the server tick in which
 * players went down, so players who go down in the same tick (one explosion, say) go down
 * together. {@link #tick()} settles first as well.
 *
 * <p><b>Winning the match.</b> After {@link MatchSettings#rounds()} rounds the teams are
 * ranked by the {@link MatchStandings} given at construction, rounds won by default. If the
 * lead is shared, an extra round is played, up to {@link MatchSettings#extraRounds()} of
 * them; still tied after that, the leaders share the win. The match ends early, as a
 * forfeit, when fewer than two starting teams still have players at a round end, and is
 * abandoned and closed at once when the last player leaves.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class Match implements MatchView {

    private final String arena;
    private final MatchSettings settings;
    private final MatchStandings standings;
    private final List<List<UUID>> roster;
    private final Map<UUID, Integer> teamOf = new HashMap<>();
    private final List<Integer> startingTeams;

    /** Players still in the match. */
    private final Set<UUID> present = new HashSet<>();
    /** Players alive in the current round. */
    private final Set<UUID> alive = new HashSet<>();
    private final List<RoundResult> results = new ArrayList<>();

    private MatchPhase phase = MatchPhase.WAITING;
    private int round;
    private int secondsLeft;
    private int roundElapsed;
    private boolean reachedOverrun;
    private MatchResult result;
    private boolean closed;

    /**
     * @param arena     the arena's name
     * @param teams     the players on each team, team 1 first; a team may be empty, but not
     *                  every team, and no player may appear twice
     * @param standings ranks the teams after the last round
     */
    public Match(String arena, List<List<UUID>> teams, MatchSettings settings, MatchStandings standings) {
        this.arena = Objects.requireNonNull(arena, "arena");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.standings = Objects.requireNonNull(standings, "standings");
        if (teams.isEmpty()) {
            throw new IllegalArgumentException("a match needs at least one team");
        }
        List<List<UUID>> copy = new ArrayList<>();
        List<Integer> starting = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            List<UUID> members = List.copyOf(teams.get(i));
            for (UUID player : members) {
                if (teamOf.put(player, i + 1) != null) {
                    throw new IllegalArgumentException(player + " is on more than one team");
                }
            }
            if (!members.isEmpty()) {
                starting.add(i + 1);
            }
            copy.add(members);
        }
        if (starting.isEmpty()) {
            throw new IllegalArgumentException("a match needs at least one player");
        }
        this.roster = List.copyOf(copy);
        this.startingTeams = List.copyOf(starting);
        present.addAll(teamOf.keySet());
    }

    /**
     * Starts round 1.
     *
     * @throws IllegalStateException if the match has already begun
     */
    public List<MatchUpdate> begin() {
        if (phase != MatchPhase.WAITING || closed) {
            throw new IllegalStateException("the match has already begun");
        }
        List<MatchUpdate> updates = new ArrayList<>();
        startRound(updates);
        return updates;
    }

    /** Advances the match by one second. */
    public List<MatchUpdate> tick() {
        List<MatchUpdate> updates = new ArrayList<>();
        if (closed) {
            return updates;
        }
        if (phase.isInRound()) {
            checkRound(updates);
            if (!phase.isInRound()) {
                return updates;
            }
        }
        switch (phase) {
            case WAITING -> {
                // Nothing runs until begin().
            }
            case PREP -> {
                roundElapsed++;
                if (--secondsLeft == 0) {
                    setPhase(MatchPhase.COMBAT, settings.roundSeconds(), updates);
                }
            }
            case COMBAT -> {
                roundElapsed++;
                if (--secondsLeft == 0) {
                    reachedOverrun = true;
                    setPhase(MatchPhase.OVERRUN, settings.overrunLimitSeconds(), updates);
                }
            }
            case OVERRUN -> {
                roundElapsed++;
                if (settings.overrunLimitSeconds() > 0 && --secondsLeft == 0) {
                    endRound(0, updates);
                }
            }
            case ROUND_END -> {
                if (--secondsLeft == 0) {
                    next(updates);
                }
            }
            case MATCH_END -> {
                if (--secondsLeft == 0) {
                    close(updates);
                }
            }
        }
        return updates;
    }

    /**
     * Takes a player who died out of the current round. They stay in the match and are alive
     * again next round. Does nothing outside a round or if they are not alive in it. Whether
     * this decides the round is left to {@link #settle()}.
     *
     * @param killer the player who killed them, or {@code null}; ignored unless they are in
     *               this match
     */
    public List<MatchUpdate> eliminate(UUID player, UUID killer) {
        List<MatchUpdate> updates = new ArrayList<>();
        if (closed || !phase.isInRound() || !alive.remove(player)) {
            return updates;
        }
        UUID credited = killer != null && present.contains(killer) && !killer.equals(player) ? killer : null;
        updates.add(new PlayerEliminated(player, teamOf.get(player), Cause.DIED, credited));
        return updates;
    }

    /**
     * Takes a player out of the match, and out of the current round if they were alive in it.
     * Does nothing if they are not in the match. When they were the last player, the match is
     * abandoned and closes at once; otherwise whether this decides the round is left to
     * {@link #settle()}.
     */
    public List<MatchUpdate> leave(UUID player) {
        List<MatchUpdate> updates = new ArrayList<>();
        if (closed || !present.remove(player)) {
            return updates;
        }
        int team = teamOf.get(player);
        if (alive.remove(player)) {
            updates.add(new PlayerEliminated(player, team, Cause.LEFT, null));
        }
        updates.add(new PlayerLeft(player, team));
        if (present.isEmpty()) {
            if (phase != MatchPhase.MATCH_END) {
                endMatch(Reason.ABANDONED, updates);
            }
            close(updates);
        }
        return updates;
    }

    /**
     * Decides the round if the players still alive leave at most one team standing (or none,
     * for a match with one starting team). Call it once the eliminations of a server tick are
     * all in. Does nothing outside a round.
     */
    public List<MatchUpdate> settle() {
        List<MatchUpdate> updates = new ArrayList<>();
        if (!closed) {
            checkRound(updates);
        }
        return updates;
    }

    /**
     * Ends the match at once, for example on plugin disable: a match not yet at MATCH_END
     * ends as {@link Reason#STOPPED}, then it closes.
     */
    public List<MatchUpdate> stop() {
        List<MatchUpdate> updates = new ArrayList<>();
        if (closed) {
            return updates;
        }
        if (phase != MatchPhase.MATCH_END) {
            endMatch(Reason.STOPPED, updates);
        }
        close(updates);
        return updates;
    }

    /** Whether the match has closed; it does nothing more. */
    public boolean isClosed() {
        return closed;
    }

    private void startRound(List<MatchUpdate> updates) {
        round++;
        alive.clear();
        alive.addAll(present);
        roundElapsed = 0;
        reachedOverrun = false;
        setPhase(MatchPhase.PREP, settings.prepSeconds(), updates);
        updates.add(new RoundStarted(round, isExtraRound()));
    }

    private void checkRound(List<MatchUpdate> updates) {
        if (!phase.isInRound()) {
            return;
        }
        List<Integer> teams = aliveTeams();
        if (startingTeams.size() >= 2) {
            if (teams.size() <= 1) {
                endRound(teams.isEmpty() ? 0 : teams.getFirst(), updates);
            }
        } else if (teams.isEmpty()) {
            endRound(0, updates);
        }
    }

    private void endRound(int winner, List<MatchUpdate> updates) {
        RoundResult finished = new RoundResult(round, winner, isExtraRound(), reachedOverrun, roundElapsed,
                alivePlayers());
        results.add(finished);
        alive.clear();
        roundElapsed = 0;
        setPhase(MatchPhase.ROUND_END, settings.roundEndSeconds(), updates);
        updates.add(new RoundEnded(finished));
    }

    /** At the end of ROUND_END: the next round, an extra round, or the match end. */
    private void next(List<MatchUpdate> updates) {
        if (startingTeams.size() >= 2 && remainingTeams().size() < 2) {
            endMatch(Reason.FORFEIT, updates);
        } else if (round < settings.rounds()) {
            startRound(updates);
        } else if (leaders().size() > 1 && round < settings.rounds() + settings.extraRounds()) {
            startRound(updates);
        } else {
            endMatch(Reason.COMPLETED, updates);
        }
    }

    private void endMatch(Reason reason, List<MatchUpdate> updates) {
        List<Integer> winners = switch (reason) {
            case COMPLETED -> leaders();
            case FORFEIT -> remainingTeams();
            case ABANDONED, STOPPED -> List.of();
        };
        List<Standing> table = new ArrayList<>();
        for (int team : startingTeams) {
            table.add(new Standing(team, standings.score(this, team), roundsWon(team), roster.get(team - 1)));
        }
        table.sort(Comparator.comparingInt(Standing::score).reversed().thenComparingInt(Standing::team));
        result = new MatchResult(arena, reason, winners, table, results);
        alive.clear();
        roundElapsed = 0;
        setPhase(MatchPhase.MATCH_END, settings.matchEndSeconds(), updates);
        updates.add(new MatchEnded(result));
    }

    private void close(List<MatchUpdate> updates) {
        closed = true;
        secondsLeft = 0;
        updates.add(new Closed());
    }

    private void setPhase(MatchPhase to, int seconds, List<MatchUpdate> updates) {
        MatchPhase from = phase;
        phase = to;
        secondsLeft = seconds;
        updates.add(new PhaseChanged(from, to));
    }

    /** The starting teams with the best score; more than one on a tie. */
    private List<Integer> leaders() {
        List<Integer> best = new ArrayList<>();
        int top = Integer.MIN_VALUE;
        for (int team : startingTeams) {
            int score = standings.score(this, team);
            if (score > top) {
                top = score;
                best.clear();
                best.add(team);
            } else if (score == top) {
                best.add(team);
            }
        }
        return best;
    }

    /** Starting teams with at least one player still in the match. */
    private List<Integer> remainingTeams() {
        Set<Integer> teams = new TreeSet<>();
        for (UUID player : present) {
            teams.add(teamOf.get(player));
        }
        return List.copyOf(teams);
    }

    @Override
    public String arena() {
        return arena;
    }

    @Override
    public MatchPhase phase() {
        return phase;
    }

    @Override
    public int round() {
        return round;
    }

    @Override
    public boolean isExtraRound() {
        return round > settings.rounds();
    }

    @Override
    public int secondsLeft() {
        return secondsLeft;
    }

    @Override
    public int roundElapsedSeconds() {
        return phase.isInRound() ? roundElapsed : 0;
    }

    @Override
    public MatchSettings settings() {
        return settings;
    }

    @Override
    public int teamCount() {
        return roster.size();
    }

    @Override
    public List<List<UUID>> roster() {
        return roster;
    }

    @Override
    public List<Integer> startingTeams() {
        return startingTeams;
    }

    @Override
    public List<UUID> players() {
        return inRosterOrder(present);
    }

    @Override
    public List<UUID> playersOf(int team) {
        if (team < 1 || team > roster.size()) {
            return List.of();
        }
        return roster.get(team - 1).stream().filter(present::contains).toList();
    }

    @Override
    public boolean contains(UUID player) {
        return present.contains(player);
    }

    @Override
    public int teamOf(UUID player) {
        return present.contains(player) ? teamOf.get(player) : 0;
    }

    @Override
    public boolean isAlive(UUID player) {
        return alive.contains(player);
    }

    @Override
    public List<UUID> alivePlayers() {
        return inRosterOrder(alive);
    }

    @Override
    public List<Integer> aliveTeams() {
        Set<Integer> teams = new TreeSet<>();
        for (UUID player : alive) {
            teams.add(teamOf.get(player));
        }
        return List.copyOf(teams);
    }

    @Override
    public int roundsWon(int team) {
        int won = 0;
        for (RoundResult finished : results) {
            if (finished.winner() == team) {
                won++;
            }
        }
        return won;
    }

    @Override
    public List<RoundResult> results() {
        return List.copyOf(results);
    }

    @Override
    public Optional<MatchResult> result() {
        return Optional.ofNullable(result);
    }

    private List<UUID> inRosterOrder(Set<UUID> players) {
        List<UUID> ordered = new ArrayList<>();
        for (List<UUID> team : roster) {
            for (UUID player : team) {
                if (players.contains(player)) {
                    ordered.add(player);
                }
            }
        }
        return ordered;
    }
}
