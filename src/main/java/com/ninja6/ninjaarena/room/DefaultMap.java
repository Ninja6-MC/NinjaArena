package com.ninja6.ninjaarena.room;

import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** The map preference for rooms opened after the setting is read. */
public enum DefaultMap {
    GENERATED, ARENA;

    public static final String KEY = "rooms.default-map";

    /** Unknown values warn once per read and use generated maps. */
    public static DefaultMap read(ConfigurationSection config, Consumer<String> warning) {
        String value = config.getString(KEY, "generated");
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "generated" -> GENERATED;
            case "arena" -> ARENA;
            default -> {
                warning.accept("Unknown " + KEY + " value '" + value + "'; using generated (expected generated or arena).");
                yield GENERATED;
            }
        };
    }
}
