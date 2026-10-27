package com.ninja6.ninjaarena.match;

/**
 * Ranks the teams of a match. It decides who leads after the last scheduled round, so
 * whether an extra round is played, and orders {@link MatchResult#standings()}.
 *
 * <p>The default, {@link #ROUND_WINS}, ranks by rounds won. Team scoring replaces it with
 * points through {@code MatchService#setStandings}; matches already running keep the one
 * they started with.
 */
@FunctionalInterface
public interface MatchStandings {

    /** Ranks teams by rounds won. */
    MatchStandings ROUND_WINS = MatchView::roundsWon;

    /**
     * @param match the match being ranked
     * @param team  a team that started with players, numbered from 1
     * @return the team's score; higher is better
     */
    int score(MatchView match, int team);
}
