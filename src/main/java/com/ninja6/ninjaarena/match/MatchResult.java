package com.ninja6.ninjaarena.match;

import java.util.List;
import java.util.UUID;

/**
 * How a match ended: why, who won, the final standings and every round's result.
 *
 * @param arena     the arena's name
 * @param reason    why the match ended
 * @param winners   the winning teams, numbered from 1: one team, several that share the win
 *                  on a tie that extra rounds did not break, or none when the match was
 *                  abandoned or stopped
 * @param standings every team that started with players, best first: by score, then by
 *                  team number
 * @param rounds    each round's result, in order
 */
public record MatchResult(String arena, Reason reason, List<Integer> winners, List<Standing> standings,
        List<RoundResult> rounds) {

    /** Why a match ended. */
    public enum Reason {
        /** Every scheduled round, and any extra rounds, were played. */
        COMPLETED,
        /**
         * Fewer than two teams still had players in the match, so the team left wins. Only
         * for matches that started with two or more teams.
         */
        FORFEIT,
        /** Every player left. Nobody wins. */
        ABANDONED,
        /** The match was stopped, for example by the plugin being disabled. Nobody wins. */
        STOPPED
    }

    /**
     * One team's place in the final standings.
     *
     * @param team      the team, numbered from 1
     * @param score     the team's score from the {@link MatchStandings} the match used
     * @param roundsWon rounds the team won
     * @param players   everyone who started on the team, whether or not they finished
     */
    public record Standing(int team, int score, int roundsWon, List<UUID> players) {

        public Standing {
            players = List.copyOf(players);
        }
    }

    public MatchResult {
        winners = List.copyOf(winners);
        standings = List.copyOf(standings);
        rounds = List.copyOf(rounds);
    }

    /** Whether more than one team shares the win. */
    public boolean isSharedWin() {
        return winners.size() > 1;
    }
}
