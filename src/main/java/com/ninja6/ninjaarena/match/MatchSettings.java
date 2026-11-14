package com.ninja6.ninjaarena.match;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Tunable values for matches, read from the {@code match} section of {@code config.yml}.
 * Out-of-range values are clamped. Every duration is in seconds.
 *
 * @param prepSeconds         length of PREP at the start of each round
 * @param roundSeconds        length of COMBAT, the round timer; when it runs out the round
 *                            goes to OVERRUN
 * @param overrunLimitSeconds how long OVERRUN may last before the round ends as a draw; 0
 *                            for no limit, so OVERRUN lasts until one team is left
 * @param roundEndSeconds     pause after a round is decided
 * @param matchEndSeconds     how long the result is shown before players are sent home
 * @param rounds              rounds in a match
 * @param extraRounds         most extra rounds played while the leading teams are tied
 *                            after the last scheduled round; still tied after them, the
 *                            leaders share the win
 * @param disconnectKillWindowSeconds how recently an enemy player must have damaged a player
 *                            who disconnects during COMBAT or OVERRUN to be credited with the
 *                            kill; 0 for the whole round so far
 * @param disconnectPenaltyPoints team points lost when a player disconnects during COMBAT or
 *                            OVERRUN with no enemy player to credit; 0 for none
 */
public record MatchSettings(int prepSeconds, int roundSeconds, int overrunLimitSeconds, int roundEndSeconds,
        int matchEndSeconds, int rounds, int extraRounds, int disconnectKillWindowSeconds,
        int disconnectPenaltyPoints) {

    public static final int DEFAULT_PREP_SECONDS = 15;
    public static final int DEFAULT_ROUND_SECONDS = 240;
    public static final int DEFAULT_OVERRUN_LIMIT_SECONDS = 120;
    public static final int DEFAULT_ROUND_END_SECONDS = 5;
    public static final int DEFAULT_MATCH_END_SECONDS = 10;
    public static final int DEFAULT_ROUNDS = 5;
    public static final int DEFAULT_EXTRA_ROUNDS = 1;
    /** The whole round. */
    public static final int DEFAULT_DISCONNECT_KILL_WINDOW_SECONDS = 0;
    public static final int DEFAULT_DISCONNECT_PENALTY_POINTS = 2;

    static final int MAX_SECONDS = 3600;
    static final int MAX_ROUNDS = 25;
    static final int MAX_EXTRA_ROUNDS = 10;

    public static final MatchSettings DEFAULTS = new MatchSettings(DEFAULT_PREP_SECONDS, DEFAULT_ROUND_SECONDS,
            DEFAULT_OVERRUN_LIMIT_SECONDS, DEFAULT_ROUND_END_SECONDS, DEFAULT_MATCH_END_SECONDS, DEFAULT_ROUNDS,
            DEFAULT_EXTRA_ROUNDS, DEFAULT_DISCONNECT_KILL_WINDOW_SECONDS, DEFAULT_DISCONNECT_PENALTY_POINTS);

    public MatchSettings {
        prepSeconds = Math.clamp(prepSeconds, 1, MAX_SECONDS);
        roundSeconds = Math.clamp(roundSeconds, 1, MAX_SECONDS);
        overrunLimitSeconds = Math.clamp(overrunLimitSeconds, 0, MAX_SECONDS);
        roundEndSeconds = Math.clamp(roundEndSeconds, 1, MAX_SECONDS);
        matchEndSeconds = Math.clamp(matchEndSeconds, 1, MAX_SECONDS);
        rounds = Math.clamp(rounds, 1, MAX_ROUNDS);
        extraRounds = Math.clamp(extraRounds, 0, MAX_EXTRA_ROUNDS);
        disconnectKillWindowSeconds = Math.clamp(disconnectKillWindowSeconds, 0, MAX_SECONDS);
        disconnectPenaltyPoints = Math.max(0, disconnectPenaltyPoints);
    }

    /** With the default disconnect scoring. */
    public MatchSettings(int prepSeconds, int roundSeconds, int overrunLimitSeconds, int roundEndSeconds,
            int matchEndSeconds, int rounds, int extraRounds) {
        this(prepSeconds, roundSeconds, overrunLimitSeconds, roundEndSeconds, matchEndSeconds, rounds, extraRounds,
                DEFAULT_DISCONNECT_KILL_WINDOW_SECONDS, DEFAULT_DISCONNECT_PENALTY_POINTS);
    }

    /** Seconds a disconnected player is held in their match by default. */
    public static final int DEFAULT_REJOIN_GRACE_SECONDS = 60;

    /**
     * Reads {@code rejoin-grace-seconds} from the {@code match} section, the default for a
     * missing section or key, clamped to 0 to {@link MatchService#MAX_REJOIN_GRACE_SECONDS}. It
     * is not part of the record: it applies to disconnects from now on, not per match.
     */
    public static int rejoinGraceSeconds(ConfigurationSection section) {
        int seconds = section == null ? DEFAULT_REJOIN_GRACE_SECONDS
                : section.getInt("rejoin-grace-seconds", DEFAULT_REJOIN_GRACE_SECONDS);
        return Math.clamp(seconds, 0, MatchService.MAX_REJOIN_GRACE_SECONDS);
    }

    /**
     * Reads the settings from a config section, falling back to the defaults for a missing
     * section or key.
     */
    public static MatchSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        return new MatchSettings(
                section.getInt("prep-seconds", DEFAULT_PREP_SECONDS),
                section.getInt("round-seconds", DEFAULT_ROUND_SECONDS),
                section.getInt("overrun-limit-seconds", DEFAULT_OVERRUN_LIMIT_SECONDS),
                section.getInt("round-end-seconds", DEFAULT_ROUND_END_SECONDS),
                section.getInt("match-end-seconds", DEFAULT_MATCH_END_SECONDS),
                section.getInt("rounds", DEFAULT_ROUNDS),
                section.getInt("extra-rounds", DEFAULT_EXTRA_ROUNDS),
                section.getInt("disconnect-kill-window-seconds", DEFAULT_DISCONNECT_KILL_WINDOW_SECONDS),
                section.getInt("disconnect-penalty-points", DEFAULT_DISCONNECT_PENALTY_POINTS));
    }
}
