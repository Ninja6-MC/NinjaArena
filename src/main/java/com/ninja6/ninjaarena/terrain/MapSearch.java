package com.ninja6.ninjaarena.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Turns a requested seed into a playable generated map, trying derived seeds when a map is
 * rejected. Pure and deterministic like the generator: the same request always tries the
 * same seeds and gives the same outcome, on any thread.
 *
 * <p>Attempt 0 uses the requested seed; attempt {@code n} uses {@link #attemptSeed}. Each
 * attempt generates a side, solves it with {@link MovementSolver} and accepts it when its
 * {@link CrossingReport} has no problems at the difficulty's {@link ChallengeMinimums}.
 * An accepted map's {@link Accepted#seed()} is the seed to remember as the room's last good
 * seed: searching from it accepts it again on attempt 0.
 *
 * <p>When every attempt is rejected, {@link #fallback} picks what the room plays instead.
 * Wiring that into rooms belongs to the generated map lifecycle, not this package.
 */
public final class MapSearch {

    /** Derived seeds tried after the requested one before the search gives up. */
    public static final int DEFAULT_RETRY_LIMIT = 8;

    /** The most retries a search accepts, so a mistaken setting cannot stall generation. */
    public static final int MAX_RETRY_LIMIT = 64;

    /** Base of the {@link SplitMix64#derive} streams for derived seeds, clear of feature streams. */
    private static final long ATTEMPT_STREAM_BASE = 1_000;

    private MapSearch() {
    }

    /** What a search gave: an accepted map or a failure. */
    public sealed interface Outcome permits Accepted, Failed {

        /** The seed the search started from. */
        long requestedSeed();
    }

    /**
     * A map that passed every check.
     *
     * @param attempt the attempt that passed, 0 for the requested seed
     * @param seed    the seed the accepted side was generated from
     * @param points  team and enemy spawns in the layout's map frame
     */
    public record Accepted(long requestedSeed, int attempt, long seed, ArenaLayout layout, MapPoints points,
            CrossingReport report) implements Outcome {

        public Accepted {
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(points, "points");
            Objects.requireNonNull(report, "report");
        }
    }

    /**
     * Every attempt was rejected.
     *
     * @param reports one per attempt, in order, each saying why it was rejected
     */
    public record Failed(long requestedSeed, List<CrossingReport> reports) implements Outcome {

        public Failed {
            reports = List.copyOf(reports);
        }

        public int attempts() {
            return reports.size();
        }
    }

    /** A search with the default size, movement, minimums and retry limit. */
    public static Outcome search(long seed, int difficulty, int teamCount) {
        return search(seed, difficulty, teamCount, SideDimensions.DEFAULT, MovementModel.DEFAULT,
                ChallengeMinimums.forDifficulty(difficulty), DEFAULT_RETRY_LIMIT);
    }

    /**
     * Tries the requested seed, then up to {@code retryLimit} derived seeds, and returns the
     * first accepted map or a failure listing every attempt.
     *
     * @param teamCount  2 or 4
     * @param retryLimit 0 to {@link #MAX_RETRY_LIMIT}; 0 tries the requested seed only
     */
    public static Outcome search(long seed, int difficulty, int teamCount, SideDimensions dimensions,
            MovementModel movement, ChallengeMinimums minimums, int retryLimit) {
        if (teamCount != 2 && teamCount != 4) {
            throw new IllegalArgumentException("team count must be 2 or 4");
        }
        if (retryLimit < 0 || retryLimit > MAX_RETRY_LIMIT) {
            throw new IllegalArgumentException("retry limit must be 0 to " + MAX_RETRY_LIMIT);
        }
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(minimums, "minimums");
        List<CrossingReport> reports = new ArrayList<>();
        for (int attempt = 0; attempt <= retryLimit; attempt++) {
            long attemptSeed = attemptSeed(seed, attempt);
            TerrainModel side = TerrainGenerator.generate(attemptSeed, difficulty, dimensions);
            MovementSolver solved = MovementSolver.solve(side, movement);
            CrossingReport report = solved.report();
            reports.add(report);
            if (report.accepted(minimums)) {
                ArenaLayout layout = new ArenaLayout(side, teamCount);
                MapPoints points = solved.points(layout).orElseThrow();
                return new Accepted(seed, attempt, attemptSeed, layout, points, report);
            }
        }
        return new Failed(seed, reports);
    }

    /** The seed attempt {@code n} of a search uses: the requested seed itself for attempt 0. */
    public static long attemptSeed(long seed, int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt cannot be negative");
        }
        return attempt == 0 ? seed : SplitMix64.derive(seed, ATTEMPT_STREAM_BASE + attempt);
    }

    /** What a room plays when a search fails, and the message that tells its players. */
    public enum Fallback {
        /**
         * Regenerate the room's last accepted seed. Message placeholders: {@code <seed>} the
         * requested seed, {@code <attempts>} the attempts made, {@code <last>} the last good seed.
         */
        LAST_GOOD_SEED("room.map-fallback-seed"),
        /**
         * No seed has been accepted yet: play a hand-built arena. Message placeholders:
         * {@code <seed>}, {@code <attempts>} and {@code <arena>} the hand-built arena's name.
         */
        HAND_BUILT("room.map-fallback-hand-built");

        private final String messageKey;

        Fallback(String messageKey) {
            this.messageKey = messageKey;
        }

        /** The {@code messages.yml} key the room sends to its players. */
        public String messageKey() {
            return messageKey;
        }
    }

    /**
     * The fallback for a failed search.
     *
     * @param seed the seed to regenerate for {@link Fallback#LAST_GOOD_SEED}, empty otherwise
     */
    public record FallbackDecision(Fallback fallback, OptionalLong seed) {

        public FallbackDecision {
            Objects.requireNonNull(fallback, "fallback");
            Objects.requireNonNull(seed, "seed");
            if (seed.isPresent() != (fallback == Fallback.LAST_GOOD_SEED)) {
                throw new IllegalArgumentException("only the last good seed fallback carries a seed");
            }
        }

        public String messageKey() {
            return fallback.messageKey();
        }
    }

    /** The room's last good seed if it has one, else a hand-built arena. */
    public static FallbackDecision fallback(OptionalLong lastGoodSeed) {
        Objects.requireNonNull(lastGoodSeed, "lastGoodSeed");
        return lastGoodSeed.isPresent()
                ? new FallbackDecision(Fallback.LAST_GOOD_SEED, lastGoodSeed)
                : new FallbackDecision(Fallback.HAND_BUILT, OptionalLong.empty());
    }
}
