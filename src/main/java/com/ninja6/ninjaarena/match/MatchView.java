package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.event.EventSettings.Bonus;
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
 * from its start until they leave it or it closes, including while they are disconnected
 * within the rejoin grace period; they are <i>alive</i> while in a round that they have not
 * been eliminated from.
 */
public interface MatchView {

    /** The arena's name. */
    String arena();

    default Bonus eventBonus() {
        return Bonus.NONE;
    }

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

    /** The shop settings the match started with; {@link ShopSettings#OFF} when it has no shop. */
    default ShopSettings shopSettings() {
        return ShopSettings.OFF;
    }

    /** Players in the match who are ready in the current SHOP; empty in any other phase. */
    default List<UUID> readyPlayers() {
        return List.of();
    }

    /**
     * Whether the SHOP has run out of time and waits for the next map; {@link #secondsLeft()}
     * is then the wait left before the round is played on the current map.
     */
    default boolean isWaitingForMap() {
        return false;
    }

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

    /**
     * Whether the player is in the match but disconnected, within the grace period in which
     * they may rejoin. They stay on their team and in {@link #players()}, but are alive in no
     * round until they are back.
     */
    default boolean isDisconnected(UUID player) {
        return false;
    }

    /** The players in the match who are disconnected and may rejoin, team 1 first. */
    default List<UUID> disconnectedPlayers() {
        return List.of();
    }

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

    /**
     * Every map played so far, in round order: one for the whole match, or one per map when
     * a generated map is generated again between rounds. Empty before the first round.
     */
    default List<PlayedMap> maps() {
        return List.of();
    }

    /** The match result, once the match has ended. */
    Optional<MatchResult> result();
}
