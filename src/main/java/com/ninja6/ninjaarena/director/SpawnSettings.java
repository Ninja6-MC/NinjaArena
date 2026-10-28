package com.ninja6.ninjaarena.director;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Enemy spawn placement and its diagnostics, read from the {@code director} section of
 * {@code config.yml}: {@code spawn.view-angle} and {@code debug}. An out-of-range angle is
 * clamped and a non-finite one falls back to the default.
 *
 * @param viewAngle degrees off a player's look direction beyond which a spawn point is out of
 *                  their view, whatever lies between them; 180 leaves only cover to hide a
 *                  point
 * @param debug     whether refused and cancelled spawns are logged at INFO rather than FINE
 */
public record SpawnSettings(double viewAngle, boolean debug) {

    static final double MAX_VIEW_ANGLE = 180;

    private static final double DEFAULT_VIEW_ANGLE = 100;

    public static final SpawnSettings DEFAULTS = new SpawnSettings(DEFAULT_VIEW_ANGLE, false);

    public SpawnSettings {
        viewAngle = Double.isFinite(viewAngle) ? Math.clamp(viewAngle, 0, MAX_VIEW_ANGLE) : DEFAULT_VIEW_ANGLE;
    }

    /** Reads the settings from the {@code director} section, with defaults for anything missing. */
    public static SpawnSettings from(ConfigurationSection director) {
        if (director == null) {
            return DEFAULTS;
        }
        return new SpawnSettings(director.getDouble("spawn.view-angle", DEFAULTS.viewAngle()),
                director.getBoolean("debug", DEFAULTS.debug()));
    }
}
