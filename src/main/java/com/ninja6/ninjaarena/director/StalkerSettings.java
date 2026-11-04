package com.ninja6.ninjaarena.director;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Tunable values for the Shadow Stalker, read from the {@code director.shadow-stalker} section
 * of {@code config.yml}. Out-of-range values are clamped, non-finite ones fall back to the
 * default, and the blink range is kept at or above the blink distance.
 *
 * @param maxAlive             Stalkers alive at once in one match; 0 turns the Stalker off
 * @param health               maximum health in half hearts
 * @param damage               melee damage per hit in half hearts, before armour
 * @param speed                movement speed attribute; vanilla wither skeletons use 0.25
 * @param scale                size multiplier, applied through the scale attribute
 * @param blinkCooldownSeconds time between blinks, and before the first one after spawning
 * @param tellTicks            how long the Stalker stands still with its particle tell before
 *                             it blinks, in server ticks
 * @param blinkDistance        distance from the target at or beyond which the target is out
 *                             of reach; a nearer target is out of reach only when no walking
 *                             path gets there
 * @param blinkRange           farthest distance from which the Stalker blinks to its target
 * @param behindDistance       how far behind the target it lands
 * @param glowSeconds          how long the target glows after a blink
 */
public record StalkerSettings(int maxAlive, double health, double damage, double speed, double scale,
        int blinkCooldownSeconds, int tellTicks, double blinkDistance, double blinkRange, double behindDistance,
        int glowSeconds) {

    static final int MAX_ALIVE = 5;
    static final double MAX_HEALTH = 1024;
    static final double MAX_DAMAGE = 100;
    static final double MIN_SPEED = 0.1;
    static final double MAX_SPEED = 0.5;
    static final double MIN_SCALE = 0.5;
    static final double MAX_SCALE = 2;
    static final int MAX_COOLDOWN_SECONDS = 120;
    static final int MIN_TELL_TICKS = 5;
    static final int MAX_TELL_TICKS = 100;
    static final double MIN_BLINK_DISTANCE = 2;
    static final double MAX_BLINK_DISTANCE = 32;
    static final double MIN_BLINK_RANGE = 4;
    static final double MAX_BLINK_RANGE = 64;
    static final double MIN_BEHIND = 1;
    static final double MAX_BEHIND = 6;
    static final int MAX_GLOW_SECONDS = 60;

    private static final double DEFAULT_HEALTH = 40;
    private static final double DEFAULT_DAMAGE = 6;
    private static final double DEFAULT_SPEED = 0.3;
    private static final double DEFAULT_SCALE = 1.1;
    private static final double DEFAULT_BLINK_DISTANCE = 8;
    private static final double DEFAULT_BLINK_RANGE = 24;
    private static final double DEFAULT_BEHIND = 2;

    public static final StalkerSettings DEFAULTS = new StalkerSettings(1, DEFAULT_HEALTH, DEFAULT_DAMAGE,
            DEFAULT_SPEED, DEFAULT_SCALE, 8, 20, DEFAULT_BLINK_DISTANCE, DEFAULT_BLINK_RANGE, DEFAULT_BEHIND, 3);

    public StalkerSettings {
        maxAlive = Math.clamp(maxAlive, 0, MAX_ALIVE);
        health = clamp(health, 1, MAX_HEALTH, DEFAULT_HEALTH);
        damage = clamp(damage, 0, MAX_DAMAGE, DEFAULT_DAMAGE);
        speed = clamp(speed, MIN_SPEED, MAX_SPEED, DEFAULT_SPEED);
        scale = clamp(scale, MIN_SCALE, MAX_SCALE, DEFAULT_SCALE);
        blinkCooldownSeconds = Math.clamp(blinkCooldownSeconds, 1, MAX_COOLDOWN_SECONDS);
        tellTicks = Math.clamp(tellTicks, MIN_TELL_TICKS, MAX_TELL_TICKS);
        blinkDistance = clamp(blinkDistance, MIN_BLINK_DISTANCE, MAX_BLINK_DISTANCE, DEFAULT_BLINK_DISTANCE);
        blinkRange = Math.max(blinkDistance, clamp(blinkRange, MIN_BLINK_RANGE, MAX_BLINK_RANGE, DEFAULT_BLINK_RANGE));
        behindDistance = clamp(behindDistance, MIN_BEHIND, MAX_BEHIND, DEFAULT_BEHIND);
        glowSeconds = Math.clamp(glowSeconds, 1, MAX_GLOW_SECONDS);
    }

    /** Reads the settings, falling back to the defaults for a missing section or key. */
    public static StalkerSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        StalkerSettings d = DEFAULTS;
        return new StalkerSettings(
                section.getInt("max-alive", d.maxAlive()),
                section.getDouble("health", d.health()),
                section.getDouble("damage", d.damage()),
                section.getDouble("speed", d.speed()),
                section.getDouble("scale", d.scale()),
                section.getInt("blink.cooldown-seconds", d.blinkCooldownSeconds()),
                section.getInt("blink.tell-ticks", d.tellTicks()),
                section.getDouble("blink.distance", d.blinkDistance()),
                section.getDouble("blink.range", d.blinkRange()),
                section.getDouble("blink.behind", d.behindDistance()),
                section.getInt("glow-seconds", d.glowSeconds()));
    }

    /** The blink cooldown in server ticks. */
    public long cooldownTicks() {
        return (long) blinkCooldownSeconds * DirectorService.TICKS_PER_SECOND;
    }

    /** The glow in server ticks. */
    public int glowTicks() {
        return glowSeconds * (int) DirectorService.TICKS_PER_SECOND;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
