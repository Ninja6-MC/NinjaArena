package com.ninja6.ninjaarena.stats;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stored match played on a generated map from one seed: the rounds it played the seed for.
 *
 * @param match      the match's identity
 * @param endedAt    when the match ended
 * @param season     the season current when it was written; {@code null} for none
 * @param difficulty the difficulty the seed was played at
 * @param firstRound the first round played on the map
 * @param rounds     the rounds played on the map
 * @param teams      the teams the match was set up with
 * @param winners    the winning teams of the match, none when nobody won
 * @param reason     why the match ended
 */
public record MatchOnSeed(UUID match, Instant endedAt, Integer season, int difficulty, int firstRound, int rounds,
        int teams, List<Integer> winners, String reason) {

    public MatchOnSeed {
        winners = List.copyOf(winners);
    }
}
