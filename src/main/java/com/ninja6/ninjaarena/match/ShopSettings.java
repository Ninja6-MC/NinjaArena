package com.ninja6.ninjaarena.match;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The shop phase between rounds, read from the {@code shop} section of {@code config.yml}.
 * Out-of-range values are clamped. Every duration is in seconds. A match keeps the values
 * it started with.
 *
 * @param enabled             whether a SHOP phase comes between rounds; off, a round end
 *                            leads straight to the next PREP
 * @param maxSeconds          the shop's time limit
 * @param quickStartSeconds   once every player in the match is ready, the time left is cut to
 *                            this, if it is shorter
 * @param mapWaitLimitSeconds how long past the time limit the shop waits for the next map;
 *                            after it the round is played on the current map, reset first
 */
public record ShopSettings(boolean enabled, int maxSeconds, int quickStartSeconds, int mapWaitLimitSeconds) {

    public static final int DEFAULT_MAX_SECONDS = 45;
    public static final int DEFAULT_QUICK_START_SECONDS = 5;
    public static final int DEFAULT_MAP_WAIT_LIMIT_SECONDS = 30;

    /** The bundled defaults: the shop is on. */
    public static final ShopSettings DEFAULTS = new ShopSettings(true, DEFAULT_MAX_SECONDS,
            DEFAULT_QUICK_START_SECONDS, DEFAULT_MAP_WAIT_LIMIT_SECONDS);
    /** No shop: each round end leads straight to the next round, as before the shop existed. */
    public static final ShopSettings OFF = new ShopSettings(false, DEFAULT_MAX_SECONDS,
            DEFAULT_QUICK_START_SECONDS, DEFAULT_MAP_WAIT_LIMIT_SECONDS);

    public ShopSettings {
        maxSeconds = Math.clamp(maxSeconds, 1, MatchSettings.MAX_SECONDS);
        quickStartSeconds = Math.clamp(quickStartSeconds, 1, MatchSettings.MAX_SECONDS);
        mapWaitLimitSeconds = Math.clamp(mapWaitLimitSeconds, 0, MatchSettings.MAX_SECONDS);
    }

    /**
     * Reads the settings from a config section, falling back to the defaults for a missing
     * section or key.
     */
    public static ShopSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        return new ShopSettings(
                section.getBoolean("enabled", true),
                section.getInt("max-seconds", DEFAULT_MAX_SECONDS),
                section.getInt("quick-start-seconds", DEFAULT_QUICK_START_SECONDS),
                section.getInt("map-wait-limit-seconds", DEFAULT_MAP_WAIT_LIMIT_SECONDS));
    }
}
