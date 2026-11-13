package com.ninja6.ninjaarena.map;

import org.bukkit.configuration.ConfigurationSection;

/**
 * How generated maps are placed, read from the {@code stability.placement} section of
 * {@code config.yml}. Out-of-range values are clamped and non-finite ones fall back to the
 * default; {@code slowBudgetMillis} is lowered to {@code budgetMillis} when it is above it.
 *
 * @param budgetMillis     server time per tick, in milliseconds, spent placing and clearing
 *                         generated maps across every slot cell
 * @param slowBudgetMillis the time per tick while the tick-time guard holds enemy spawning
 *                         back; 0 pauses placement then
 * @param recoverySeconds  how long the budget takes to climb back from the slow budget to the
 *                         full one once the guard lets go
 * @param maxMapSize       the largest grid cell a generated map is sized for: in a larger
 *                         cell the map is the one a cell of this size gets, centred
 */
public record PlacementSettings(double budgetMillis, double slowBudgetMillis, int recoverySeconds,
        int maxMapSize) {

    static final double MIN_BUDGET_MILLIS = 0.1;
    static final double MAX_BUDGET_MILLIS = 50;
    static final int MAX_RECOVERY_SECONDS = 600;
    /** The smallest grid cell, and so the smallest map size there is. */
    public static final int MIN_MAP_SIZE = 32;
    public static final int MAX_MAP_SIZE = 4096;

    public static final PlacementSettings DEFAULTS = new PlacementSettings(10, 1, 10, 256);

    public PlacementSettings {
        budgetMillis = clamp(budgetMillis, MIN_BUDGET_MILLIS, MAX_BUDGET_MILLIS);
        slowBudgetMillis = Math.min(budgetMillis, clamp(slowBudgetMillis, 0, MAX_BUDGET_MILLIS));
        recoverySeconds = Math.clamp(recoverySeconds, 0, MAX_RECOVERY_SECONDS);
        maxMapSize = Math.clamp(maxMapSize, MIN_MAP_SIZE, MAX_MAP_SIZE);
    }

    /** Reads the settings, falling back to the defaults for a missing section, key or non-finite value. */
    public static PlacementSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        PlacementSettings d = DEFAULTS;
        return new PlacementSettings(
                finite(section, "budget-ms", d.budgetMillis()),
                finite(section, "slow-budget-ms", d.slowBudgetMillis()),
                section.getInt("recovery-seconds", d.recoverySeconds()),
                section.getInt("max-map-size", d.maxMapSize()));
    }

    /** The full budget in nanoseconds. */
    public long budgetNanos() {
        return Math.round(budgetMillis * 1_000_000);
    }

    /** The slow budget in nanoseconds. */
    public long slowBudgetNanos() {
        return Math.round(slowBudgetMillis * 1_000_000);
    }

    private static double finite(ConfigurationSection section, String key, double fallback) {
        double value = section.getDouble(key, fallback);
        return Double.isFinite(value) ? value : fallback;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("placement settings must be finite");
        }
        return Math.clamp(value, min, max);
    }
}
