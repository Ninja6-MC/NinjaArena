package com.ninja6.ninjaarena.match;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Team points for each live match, used as its {@link MatchStandings}. Call on the server
 * thread. Obtain through {@code NinjaArenaPlugin#scores()} and award points from any
 * source with {@link #addPoints}, including future enemy kills.
 *
 * <p>Scores start at zero, survive round changes and player departures, and are frozen in
 * {@link MatchResult} at match end. Each new match starts with a separate table, even when
 * it reuses the same arena. Live tables are discarded on {@code MatchEndEvent}.
 */
public final class TeamScores implements MatchStandings {

    private final Map<MatchView, Map<Integer, Integer>> points = new IdentityHashMap<>();

    /** Returns a live score, or the frozen result's score after the match has ended. */
    @Override
    public int score(MatchView match, int team) {
        Objects.requireNonNull(match, "match");
        if (match.result().isPresent()) {
            return match.result().orElseThrow().standings().stream()
                    .filter(standing -> standing.team() == team)
                    .mapToInt(MatchResult.Standing::score).findFirst().orElse(0);
        }
        Map<Integer, Integer> table = points.get(match);
        return table == null ? 0 : table.getOrDefault(team, 0);
    }

    /**
     * Adds a non-negative award to a starting team. Returns false before a match begins,
     * after it ends, or for a team that did not start with players. ROUND_END awards are
     * accepted before the engine decides the next round or match result. Totals saturate
     * at {@link Integer#MAX_VALUE} rather than overflowing.
     *
     * @throws IllegalArgumentException if points are negative
     */
    public boolean addPoints(MatchView match, int team, int award) {
        Objects.requireNonNull(match, "match");
        if (award < 0) {
            throw new IllegalArgumentException("points must be non-negative");
        }
        if (match.phase() == MatchPhase.WAITING || match.phase() == MatchPhase.MATCH_END
                || match.result().isPresent() || !match.startingTeams().contains(team)) {
            return false;
        }
        if (award > 0) {
            Map<Integer, Integer> table = points.computeIfAbsent(match, ignored -> new HashMap<>());
            int total = (int) Math.min(Integer.MAX_VALUE, (long) table.getOrDefault(team, 0) + award);
            table.put(team, total);
        }
        return true;
    }

    /** Releases the live table after the engine has saved the scores in its result. */
    void finish(MatchView match) {
        points.remove(match);
    }
}
