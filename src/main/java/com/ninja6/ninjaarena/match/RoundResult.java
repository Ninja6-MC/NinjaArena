package com.ninja6.ninjaarena.match;

import java.util.List;
import java.util.UUID;

/**
 * How one round ended.
 *
 * @param round          the round's number, from 1; extra rounds continue the count
 * @param winner         the winning team, numbered from 1, or 0 for a draw: every team was
 *                       down, or the overrun limit ran out
 * @param extra          whether it was an extra round played to break a tie
 * @param reachedOverrun whether the round timer ran out and the round went to OVERRUN
 * @param seconds        how long the round lasted, PREP included
 * @param survivors      the players still alive when it ended, in roster order
 */
public record RoundResult(int round, int winner, boolean extra, boolean reachedOverrun, int seconds,
        List<UUID> survivors) {

    public RoundResult {
        survivors = List.copyOf(survivors);
    }

    public boolean isDraw() {
        return winner == 0;
    }
}
