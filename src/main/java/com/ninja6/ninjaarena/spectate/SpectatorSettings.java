package com.ninja6.ninjaarena.spectate;

import org.bukkit.configuration.ConfigurationSection;

/** Admission settings for new spectators. Existing viewers may finish watching. */
public record SpectatorSettings(boolean enabled, int maxPerMatch) {

    private static final int DEFAULT_MAX = 16;

    public SpectatorSettings {
        if (maxPerMatch < 1 || maxPerMatch > 64) {
            throw new IllegalArgumentException("spectating.max-per-match must be from 1 to 64");
        }
    }

    public static SpectatorSettings from(ConfigurationSection section) {
        if (section == null) {
            return new SpectatorSettings(true, DEFAULT_MAX);
        }
        Object enabled = section.get("enabled", true);
        Object max = section.get("max-per-match", DEFAULT_MAX);
        if (!(enabled instanceof Boolean allow) || !(max instanceof Integer limit)) {
            throw new IllegalArgumentException("spectating.enabled must be a boolean and max-per-match an integer");
        }
        return new SpectatorSettings(allow, limit);
    }

    boolean allows(int spectators) {
        return enabled && spectators < maxPerMatch;
    }
}
