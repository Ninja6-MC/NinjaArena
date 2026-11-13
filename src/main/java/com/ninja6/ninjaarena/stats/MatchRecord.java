package com.ninja6.ninjaarena.stats;

import com.ninja6.ninjaarena.arena.Arena;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What one finished match adds to its players' stats, and the match itself. Both are written
 * to the season that is current when the write happens; the players' stats also to the
 * all-time totals.
 *
 * @param endedAt when the match ended
 * @param players each player's contribution, at most one entry per player
 * @param match   the match's own row, or {@code null} to record the players' stats only
 */
public record MatchRecord(Instant endedAt, List<PlayerStats> players, Details match) {

    /** What {@code map} holds for a generated map, which is never stored by its slot name. */
    public static final String GENERATED = "*generated";
    /** Longest stored reason. */
    static final int REASON_LENGTH = 16;

    public MatchRecord {
        Objects.requireNonNull(endedAt, "endedAt");
        players = List.copyOf(players);
        if (players.stream().map(PlayerStats::player).distinct().count() != players.size()) {
            throw new IllegalArgumentException("one entry per player");
        }
    }

    public MatchRecord(Instant endedAt, List<PlayerStats> players) {
        this(endedAt, players, null);
    }

    /**
     * One match.
     *
     * @param id      the match's identity, as in its result and in the payout ledger
     * @param reason  why it ended: {@code COMPLETED}, {@code FORFEIT}, {@code ABANDONED} or
     *                {@code STOPPED}
     * @param teams   the teams the match was set up with
     * @param winners the winning teams, none when nobody won
     * @param maps    every map played, in round order; at least one
     */
    public record Details(UUID id, String reason, int teams, List<Integer> winners, List<Played> maps) {

        public Details {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(reason, "reason");
            if (reason.isEmpty() || reason.length() > REASON_LENGTH) {
                throw new IllegalArgumentException("reason must be 1 to " + REASON_LENGTH + " characters");
            }
            if (teams < 1) {
                throw new IllegalArgumentException("a match has at least one team");
            }
            winners = List.copyOf(winners);
            if (winners.stream().anyMatch(team -> team < 1 || team > teams)) {
                throw new IllegalArgumentException("a winner is not one of the teams");
            }
            maps = List.copyOf(maps);
            if (maps.isEmpty() || maps.stream().map(Played::firstRound).distinct().count() != maps.size()) {
                throw new IllegalArgumentException("at least one map, each from its own round");
            }
        }

        /** The winning teams as stored: team numbers joined by commas, empty for none. */
        String winnersText() {
            return String.join(",", winners.stream().map(String::valueOf).toList());
        }
    }

    /**
     * A map the match was played on.
     *
     * @param map        a hand-built arena's name, or {@link #GENERATED}
     * @param seed       a generated map's seed; {@code null} for a hand-built arena, and for
     *                   a generated map whose seed was not known
     * @param difficulty with the seed; {@code null} without it
     * @param firstRound the first round played on it, from 1
     * @param rounds     the rounds played on it
     */
    public record Played(String map, Long seed, Integer difficulty, int firstRound, int rounds) {

        public Played {
            Objects.requireNonNull(map, "map");
            if ((seed == null) != (difficulty == null)) {
                throw new IllegalArgumentException("a seed and a difficulty, or neither");
            }
            if (seed != null ? !map.equals(GENERATED) : !map.equals(GENERATED) && !Arena.isValidName(map)) {
                throw new IllegalArgumentException("a hand-built arena's name, or " + GENERATED + " for a seed");
            }
            if (firstRound < 1 || rounds < 1) {
                throw new IllegalArgumentException("firstRound and rounds start at 1");
            }
        }

        public static Played handBuilt(String arena, int firstRound, int rounds) {
            return new Played(arena, null, null, firstRound, rounds);
        }

        public static Played generated(long seed, int difficulty, int firstRound, int rounds) {
            return new Played(GENERATED, seed, difficulty, firstRound, rounds);
        }
    }
}
