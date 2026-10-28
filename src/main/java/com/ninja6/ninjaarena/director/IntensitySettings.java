package com.ninja6.ninjaarena.director;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Tunable values for the intensity cycle, the round budget's factors and the leading-team
 * bias, read from the {@code director.intensity} section of {@code config.yml}. Out-of-range
 * values are clamped and non-finite ones fall back to the default.
 *
 * @param buildUpSeconds      length of the build-up from the relax level to the peak
 * @param peakSeconds         how long the peak is held
 * @param relaxSeconds        length of the relax before the next build-up
 * @param firstRoundPeak      peak intensity of round 1
 * @param roundStep           peak intensity added by each later round, up to 1
 * @param relaxLevel          relax intensity as a fraction of the round's peak
 * @param timeRamp            intensity added by the end of the round timer, in proportion to
 *                            the combat time passed
 * @param teamsWeight         share of the intensity that scales with the fraction of starting
 *                            teams still alive; 0 ignores eliminations
 * @param damageWindowSeconds how far back damage taken by players counts
 * @param damageFullStress    damage per alive player in the window that counts as full stress
 * @param damageRelief        share of the intensity removed at full stress
 * @param budgetRoundStep     round budget multiplier added by each round after the first
 * @param budgetPerPlayer     player factor of the round budget per player alive at its start
 * @param leaderBias          targeting weight of players on the team with the most points;
 *                            1 means no bias
 */
public record IntensitySettings(int buildUpSeconds, int peakSeconds, int relaxSeconds, double firstRoundPeak,
        double roundStep, double relaxLevel, double timeRamp, double teamsWeight, int damageWindowSeconds,
        double damageFullStress, double damageRelief, double budgetRoundStep, double budgetPerPlayer,
        double leaderBias) {

    static final int MAX_PHASE_SECONDS = 600;
    static final int MAX_WINDOW_SECONDS = 60;

    public static final IntensitySettings DEFAULTS = new IntensitySettings(40, 20, 10, 0.6, 0.1, 0.25, 0.2, 0.5,
            10, 20, 0.5, 0.25, 0.25, 2);

    public IntensitySettings {
        buildUpSeconds = Math.clamp(buildUpSeconds, 1, MAX_PHASE_SECONDS);
        peakSeconds = Math.clamp(peakSeconds, 1, MAX_PHASE_SECONDS);
        relaxSeconds = Math.clamp(relaxSeconds, 1, MAX_PHASE_SECONDS);
        firstRoundPeak = fraction(firstRoundPeak);
        roundStep = fraction(roundStep);
        relaxLevel = fraction(relaxLevel);
        timeRamp = fraction(timeRamp);
        teamsWeight = fraction(teamsWeight);
        damageWindowSeconds = Math.clamp(damageWindowSeconds, 1, MAX_WINDOW_SECONDS);
        damageFullStress = clamp(damageFullStress, 1, 1000);
        damageRelief = fraction(damageRelief);
        budgetRoundStep = clamp(budgetRoundStep, 0, 10);
        budgetPerPlayer = clamp(budgetPerPlayer, 0, 10);
        leaderBias = clamp(leaderBias, 1, 10);
    }

    /** Reads the settings, falling back to the defaults for a missing section, key or non-finite value. */
    public static IntensitySettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        IntensitySettings d = DEFAULTS;
        return new IntensitySettings(
                section.getInt("build-up-seconds", d.buildUpSeconds()),
                section.getInt("peak-seconds", d.peakSeconds()),
                section.getInt("relax-seconds", d.relaxSeconds()),
                finite(section, "first-round-peak", d.firstRoundPeak()),
                finite(section, "round-step", d.roundStep()),
                finite(section, "relax-level", d.relaxLevel()),
                finite(section, "time-ramp", d.timeRamp()),
                finite(section, "teams-weight", d.teamsWeight()),
                section.getInt("damage-window-seconds", d.damageWindowSeconds()),
                finite(section, "damage-full-stress", d.damageFullStress()),
                finite(section, "damage-relief", d.damageRelief()),
                finite(section, "budget-round-step", d.budgetRoundStep()),
                finite(section, "budget-per-player", d.budgetPerPlayer()),
                finite(section, "leader-bias", d.leaderBias()));
    }

    /** One build-up, peak and relax, in seconds. */
    public int cycleSeconds() {
        return buildUpSeconds + peakSeconds + relaxSeconds;
    }

    /** The peak intensity of a round, from 1: the first round's peak plus a step per later round, up to 1. */
    public double peak(int round) {
        return Math.min(1, firstRoundPeak + roundStep * Math.max(0, round - 1));
    }

    /** The {@link ThreatBudget} round multiplier: 1 for round 1, plus a step per later round. */
    public double roundMultiplier(int round) {
        return 1 + budgetRoundStep * Math.max(0, round - 1);
    }

    /** The {@link ThreatBudget} player factor for the players alive when a round's fight starts. */
    public double playerFactor(int players) {
        return budgetPerPlayer * Math.max(0, players);
    }

    private static double finite(ConfigurationSection section, String key, double fallback) {
        double value = section.getDouble(key, fallback);
        return Double.isFinite(value) ? value : fallback;
    }

    private static double fraction(double value) {
        return clamp(value, 0, 1);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("intensity settings must be finite");
        }
        return Math.clamp(value, min, max);
    }
}
