package com.ninja6.ninjaarena.director;

import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Tunable values for stealth and aggro steering, read from the {@code director.stealth}
 * section of {@code config.yml}. Out-of-range values are clamped and non-finite ones fall back
 * to the default.
 *
 * @param hiddenWeight         targeting weight multiplier for a hidden player; 1 turns the
 *                             cut off
 * @param lowLight             light level at or below which a crouching player is hidden
 *                             whoever can see them; -1 turns it off
 * @param loseTrackSeconds     how long an enemy chases a player it cannot see before it
 *                             loses track of them
 * @param markedWeight         targeting weight multiplier for a shuriken-marked player; 1
 *                             turns it off
 * @param zombieIgnores        whether zombies skip the stealth cut and the lose-track timer
 * @param skeletonIgnores      the same for skeletons
 * @param spiderIgnores        the same for spiders
 * @param shadowStalkerIgnores the same for the Shadow Stalker, once it is spawned
 */
public record StealthSettings(double hiddenWeight, int lowLight, int loseTrackSeconds, double markedWeight,
        boolean zombieIgnores, boolean skeletonIgnores, boolean spiderIgnores, boolean shadowStalkerIgnores) {

    static final int MAX_LIGHT = 15;
    static final int MAX_LOSE_TRACK_SECONDS = 60;
    static final double MAX_MARKED_WEIGHT = 10;

    private static final double DEFAULT_HIDDEN_WEIGHT = 0.25;
    private static final double DEFAULT_MARKED_WEIGHT = 2;

    public static final StealthSettings DEFAULTS = new StealthSettings(DEFAULT_HIDDEN_WEIGHT, 7, 3,
            DEFAULT_MARKED_WEIGHT, false, false, false, false);

    public StealthSettings {
        hiddenWeight = clamp(hiddenWeight, 0, 1, DEFAULT_HIDDEN_WEIGHT);
        lowLight = Math.clamp(lowLight, -1, MAX_LIGHT);
        loseTrackSeconds = Math.clamp(loseTrackSeconds, 1, MAX_LOSE_TRACK_SECONDS);
        markedWeight = clamp(markedWeight, 1, MAX_MARKED_WEIGHT, DEFAULT_MARKED_WEIGHT);
    }

    /** Reads the settings, falling back to the defaults for a missing section or key. */
    public static StealthSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        StealthSettings d = DEFAULTS;
        return new StealthSettings(
                section.getDouble("hidden-weight", d.hiddenWeight()),
                section.getInt("low-light", d.lowLight()),
                section.getInt("lose-track-seconds", d.loseTrackSeconds()),
                section.getDouble("marked-weight", d.markedWeight()),
                section.getBoolean("ignores-stealth.zombie", d.zombieIgnores()),
                section.getBoolean("ignores-stealth.skeleton", d.skeletonIgnores()),
                section.getBoolean("ignores-stealth.spider", d.spiderIgnores()),
                section.getBoolean("ignores-stealth.shadow-stalker", d.shadowStalkerIgnores()));
    }

    /** Whether enemies of the kind skip the stealth cut and the lose-track timer. */
    public boolean ignoresStealth(EnemyKind kind) {
        Objects.requireNonNull(kind, "kind");
        return switch (kind) {
            case ZOMBIE -> zombieIgnores;
            case SKELETON -> skeletonIgnores;
            case SPIDER -> spiderIgnores;
        };
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
