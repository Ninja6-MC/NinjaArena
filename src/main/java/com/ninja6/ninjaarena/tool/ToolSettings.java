package com.ninja6.ninjaarena.tool;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Tunable values for the ninja kit tools, read from the {@code tools} section of
 * {@code config.yml}. Out-of-range values are clamped. The kit in {@code kits.yml} decides
 * which tools a player gets and their initial stack amounts.
 *
 * @param smokeSeconds        how long a smoke cloud lasts
 * @param smokeRadius         smoke cloud radius in blocks
 * @param blindnessSeconds    Blindness given to a rival in the cloud, renewed while they stay in it
 * @param shurikenDamage      damage of a shuriken hit, in half hearts, before armour
 * @param glowSeconds         how long a rival hit by a shuriken glows and stays marked
 * @param dashCooldownSeconds time between dashes
 * @param dashStrength        horizontal speed of a dash, in blocks per tick
 * @param dashLift            upward speed of a dash, in blocks per tick
 * @param shurikenCooldownSeconds time between shuriken throws; zero disables the cooldown
 * @param shurikenUsesPerRound maximum throws per round; zero means unlimited
 * @param smokeCooldownSeconds time between smoke bomb throws; zero disables the cooldown
 * @param smokeUsesPerRound maximum throws per round; zero means unlimited
 */
public record ToolSettings(int smokeSeconds, double smokeRadius, int blindnessSeconds, double shurikenDamage,
        int glowSeconds, int dashCooldownSeconds, double dashStrength, double dashLift,
        int shurikenCooldownSeconds, int shurikenUsesPerRound, int smokeCooldownSeconds, int smokeUsesPerRound) {

    private static final double DEFAULT_SMOKE_RADIUS = 3.0;
    private static final double DEFAULT_SHURIKEN_DAMAGE = 2.0;
    private static final double DEFAULT_DASH_STRENGTH = 1.0;
    private static final double DEFAULT_DASH_LIFT = 0.3;

    public static final ToolSettings DEFAULTS = new ToolSettings(4, DEFAULT_SMOKE_RADIUS, 2,
            DEFAULT_SHURIKEN_DAMAGE, 3, 8, DEFAULT_DASH_STRENGTH, DEFAULT_DASH_LIFT, 0, 0, 0, 0);

    static final int MAX_SECONDS = 60;
    static final double MAX_RADIUS = 8;
    static final double MAX_DAMAGE = 20;
    static final double MAX_DASH_STRENGTH = 2;
    /** A little above a jump, so a dash cannot launch a player high into the air. */
    static final double MAX_DASH_LIFT = 0.6;

    public ToolSettings {
        shurikenCooldownSeconds = Math.clamp(shurikenCooldownSeconds, 0, MAX_SECONDS);
        smokeCooldownSeconds = Math.clamp(smokeCooldownSeconds, 0, MAX_SECONDS);
        shurikenUsesPerRound = Math.clamp(shurikenUsesPerRound, 0, 64);
        smokeUsesPerRound = Math.clamp(smokeUsesPerRound, 0, 64);
        smokeSeconds = Math.clamp(smokeSeconds, 1, MAX_SECONDS);
        smokeRadius = clamp(smokeRadius, 1, MAX_RADIUS, DEFAULT_SMOKE_RADIUS);
        blindnessSeconds = Math.clamp(blindnessSeconds, 1, MAX_SECONDS);
        shurikenDamage = clamp(shurikenDamage, 0, MAX_DAMAGE, DEFAULT_SHURIKEN_DAMAGE);
        glowSeconds = Math.clamp(glowSeconds, 1, MAX_SECONDS);
        dashCooldownSeconds = Math.clamp(dashCooldownSeconds, 1, MAX_SECONDS);
        dashStrength = clamp(dashStrength, 0.1, MAX_DASH_STRENGTH, DEFAULT_DASH_STRENGTH);
        dashLift = clamp(dashLift, 0, MAX_DASH_LIFT, DEFAULT_DASH_LIFT);
    }

    /**
     * Reads the settings from a config section, falling back to the defaults for a missing
     * section or key.
     */
    public static ToolSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        return new ToolSettings(
                section.getInt("smoke-bomb.cloud-seconds", DEFAULTS.smokeSeconds()),
                section.getDouble("smoke-bomb.radius", DEFAULTS.smokeRadius()),
                section.getInt("smoke-bomb.blindness-seconds", DEFAULTS.blindnessSeconds()),
                section.getDouble("shuriken.damage", DEFAULTS.shurikenDamage()),
                section.getInt("shuriken.glow-seconds", DEFAULTS.glowSeconds()),
                section.getInt("dash.cooldown-seconds", DEFAULTS.dashCooldownSeconds()),
                section.getDouble("dash.strength", DEFAULTS.dashStrength()),
                section.getDouble("dash.lift", DEFAULTS.dashLift()),
                section.getInt("shuriken.cooldown-seconds", 0),
                section.getInt("shuriken.uses-per-round", 0),
                section.getInt("smoke-bomb.cooldown-seconds", 0),
                section.getInt("smoke-bomb.uses-per-round", 0));
    }

    /** Cooldown for a thrown tool; dash retains its own settings and material group. */
    public int cooldownSeconds(NinjaTool tool) {
        return tool == NinjaTool.SHURIKEN ? shurikenCooldownSeconds : smokeCooldownSeconds;
    }

    /** Maximum throws per round, or zero for unlimited throws. */
    public int usesPerRound(NinjaTool tool) {
        return tool == NinjaTool.SHURIKEN ? shurikenUsesPerRound : smokeUsesPerRound;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isNaN(value) ? fallback : Math.clamp(value, min, max);
    }
}
