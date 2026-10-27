package com.ninja6.ninjaarena.match;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A read-only view of one running match: its phase and timers, who is on which team, who is
 * still alive this round, and the results so far. Get one from {@code MatchService#matchOf},
 * {@code #match} or {@code #matches}, or from any match event. It is live: it reflects the
 * match as it is when read, so read it on the server thread and do not keep it past the
 * match end.
 *
 * <p>Team numbers start at 1, as in the arena's team spawns. A player is <i>in the match</i>
 * from its start until they leave it or it closes; they are <i>alive</i> while in a round
 * that they have not been eliminated from.
 */
public interface MatchView {

    /** The arena's name. */
    String arena();

    MatchPhase phase();

    /** Whether the enemy director should run at maximum intensity, which is during OVERRUN. */
    default boolean isMaxIntensity() {
        return phase().isMaxIntensity();
    }

    /** The current round, from 1; 0 before the first round starts. */
    int round();

    /** Whether the current round is an extra round played to break a tie. */
    boolean isExtraRound();

    /** Seconds left in the current phase; 0 when it has no time limit, such as OVERRUN with no limit. */
    int secondsLeft();

    /** Seconds since the current round started, PREP included; 0 outside a round. */
    int roundElapsedSeconds();

    MatchSettings settings();

    /** The number of teams the match was set up with, including any that started empty. */
    int teamCount();

    /** Everyone who started, by team, team 1 first, whether or not they are still in the match. */
    List<List<UUID>> roster();

    /** Teams that started with at least one player. */
    List<Integer> startingTeams();

    /** Everyone still in the match, team 1 first. */
    List<UUID> players();

    /** The team's players still in the match. */
    List<UUID> playersOf(int team);

    boolean contains(UUID player);

    /** The player's team while they are in the match, otherwise 0. */
    int teamOf(UUID player);

    /** Whether the player is in the current round and has not been eliminated from it. */
    boolean isAlive(UUID player);

    /** The players alive in the current round, team 1 first; empty outside a round. */
    List<UUID> alivePlayers();

    /** Teams with at least one player alive in the current round, in order; empty outside a round. */
    List<Integer> aliveTeams();

    /** Rounds the team has won so far. */
    int roundsWon(int team);

    /** Each finished round's result, in order. */
    List<RoundResult> results();

    /** The match result, once the match has ended. */
    Optional<MatchResult> result();
}
