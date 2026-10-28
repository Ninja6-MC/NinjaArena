package com.ninja6.ninjaarena.director;

import org.bukkit.configuration.ConfigurationSection;

/** Pure round-budget arithmetic; callers supply the round multiplier and player factor. */
public record ThreatBudget(double base) {

    public static final ThreatBudget DEFAULTS = new ThreatBudget(20);

    public ThreatBudget {
        base = nonNegative(base);
    }

    /** Reads the {@code director.threat-budget} section; zero disables the budget. */
    public static ThreatBudget from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        double base = section.getDouble("base", DEFAULTS.base());
        return Double.isFinite(base) ? new ThreatBudget(base) : DEFAULTS;
    }

    /** Returns base x round multiplier x player factor without rounding fractional points. */
    public double forRound(double roundMultiplier, double playerFactor) {
        double budget = base * nonNegative(roundMultiplier) * nonNegative(playerFactor);
        if (!Double.isFinite(budget)) {
            throw new IllegalArgumentException("Threat budget must be finite");
        }
        return budget;
    }

    private static double nonNegative(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Threat budget factors must be finite");
        }
        return Math.max(0, value);
    }
}
