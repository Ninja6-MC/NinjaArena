package com.ninja6.ninjaarena.director;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Limits that keep the server stable under load, read from the {@code stability} section of
 * {@code config.yml}: how many matches run at once, the enemy caps, the spawn rate and the
 * tick-time guard. Out-of-range values are clamped and non-finite ones fall back to the
 * default.
 *
 * <p>The tick-time thresholds are kept in order: {@code resumeBelowMspt} is lowered to
 * {@code pauseAboveMspt} and {@code dropTierAboveMspt} raised to it when they cross it.
 *
 * @param maxMatches        matches running at once; a waiting room holds its start until one ends
 * @param matchMobCap       enemies alive at once in one match
 * @param globalMobCap      enemies alive at once across every match
 * @param spawnsPerTick     enemies spawned in one server tick across every match
 * @param pauseAboveMspt    average tick time, in milliseconds, above which spawning pauses
 * @param dropTierAboveMspt average tick time above which intensity also drops one tier
 * @param resumeBelowMspt   average tick time below which spawning and intensity return to normal
 * @param intensityTier     intensity removed by a tier drop, from 0 to 1
 */
public record StabilitySettings(int maxMatches, int matchMobCap, int globalMobCap, int spawnsPerTick,
        double pauseAboveMspt, double dropTierAboveMspt, double resumeBelowMspt, double intensityTier) {

    static final int MAX_MATCHES = 100;
    static final int MAX_MOBS = 1000;
    static final int MAX_SPAWNS_PER_TICK = 100;
    static final double MAX_MSPT = 1000;

    public static final StabilitySettings DEFAULTS = new StabilitySettings(4, 25, 80, 4, 40, 45, 35, 0.25);

    public StabilitySettings {
        maxMatches = Math.clamp(maxMatches, 1, MAX_MATCHES);
        matchMobCap = Math.clamp(matchMobCap, 1, MAX_MOBS);
        globalMobCap = Math.clamp(globalMobCap, 1, MAX_MOBS);
        spawnsPerTick = Math.clamp(spawnsPerTick, 1, MAX_SPAWNS_PER_TICK);
        pauseAboveMspt = clamp(pauseAboveMspt, 1, MAX_MSPT);
        dropTierAboveMspt = Math.max(pauseAboveMspt, clamp(dropTierAboveMspt, 1, MAX_MSPT));
        resumeBelowMspt = Math.min(pauseAboveMspt, clamp(resumeBelowMspt, 1, MAX_MSPT));
        intensityTier = clamp(intensityTier, 0, 1);
    }

    /** Reads the settings, falling back to the defaults for a missing section, key or non-finite value. */
    public static StabilitySettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        StabilitySettings d = DEFAULTS;
        return new StabilitySettings(
                section.getInt("max-concurrent-matches", d.maxMatches()),
                section.getInt("match-mob-cap", d.matchMobCap()),
                section.getInt("global-mob-cap", d.globalMobCap()),
                section.getInt("spawns-per-tick", d.spawnsPerTick()),
                finite(section, "pause-spawning-above-mspt", d.pauseAboveMspt()),
                finite(section, "drop-tier-above-mspt", d.dropTierAboveMspt()),
                finite(section, "resume-below-mspt", d.resumeBelowMspt()),
                finite(section, "intensity-tier", d.intensityTier()));
    }

    private static double finite(ConfigurationSection section, String key, double fallback) {
        double value = section.getDouble(key, fallback);
        return Double.isFinite(value) ? value : fallback;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("stability settings must be finite");
        }
        return Math.clamp(value, min, max);
    }
}
