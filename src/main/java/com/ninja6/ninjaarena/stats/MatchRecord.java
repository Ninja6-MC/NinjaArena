package com.ninja6.ninjaarena.stats;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What one finished match adds to its players' stats. It is written to the all-time totals
 * and to the season that is current when the write happens.
 *
 * @param endedAt when the match ended
 * @param players each player's contribution, at most one entry per player
 */
public record MatchRecord(Instant endedAt, List<PlayerStats> players) {

    public MatchRecord {
        Objects.requireNonNull(endedAt, "endedAt");
        players = List.copyOf(players);
        if (players.stream().map(PlayerStats::player).distinct().count() != players.size()) {
            throw new IllegalArgumentException("one entry per player");
        }
    }
}
