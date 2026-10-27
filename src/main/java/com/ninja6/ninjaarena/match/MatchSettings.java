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
 */
public record MatchSettings(int prepSeconds, int roundSeconds, int overrunLimitSeconds, int roundEndSeconds,
        int matchEndSeconds, int rounds, int extraRounds) {

    public static final int DEFAULT_PREP_SECONDS = 15;
    public static final int DEFAULT_ROUND_SECONDS = 240;
    public static final int DEFAULT_OVERRUN_LIMIT_SECONDS = 120;
    public static final int DEFAULT_ROUND_END_SECONDS = 5;
    public static final int DEFAULT_MATCH_END_SECONDS = 10;
    public static final int DEFAULT_ROUNDS = 5;
    public static final int DEFAULT_EXTRA_ROUNDS = 1;

    static final int MAX_SECONDS = 3600;
    static final int MAX_ROUNDS = 25;
    static final int MAX_EXTRA_ROUNDS = 10;

    public static final MatchSettings DEFAULTS = new MatchSettings(DEFAULT_PREP_SECONDS, DEFAULT_ROUND_SECONDS,
            DEFAULT_OVERRUN_LIMIT_SECONDS, DEFAULT_ROUND_END_SECONDS, DEFAULT_MATCH_END_SECONDS, DEFAULT_ROUNDS,
            DEFAULT_EXTRA_ROUNDS);

    public MatchSettings {
        prepSeconds = Math.clamp(prepSeconds, 1, MAX_SECONDS);
        roundSeconds = Math.clamp(roundSeconds, 1, MAX_SECONDS);
        overrunLimitSeconds = Math.clamp(overrunLimitSeconds, 0, MAX_SECONDS);
        roundEndSeconds = Math.clamp(roundEndSeconds, 1, MAX_SECONDS);
        matchEndSeconds = Math.clamp(matchEndSeconds, 1, MAX_SECONDS);
        rounds = Math.clamp(rounds, 1, MAX_ROUNDS);
        extraRounds = Math.clamp(extraRounds, 0, MAX_EXTRA_ROUNDS);
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
                section.getInt("extra-rounds", DEFAULT_EXTRA_ROUNDS));
    }
}
