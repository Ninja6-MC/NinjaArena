package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class StalkerSettingsTest {

    @Test
    void bundledConfigMatchesDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = config.getConfigurationSection("director.shadow-stalker");
            assertNotNull(section);
            assertEquals(new StalkerSettings(1, 40, 6, 0.3, 1.1, 8, 20, 8, 24, 2, 3), StalkerSettings.DEFAULTS);
            assertEquals(StalkerSettings.DEFAULTS, StalkerSettings.from(section));
            for (String key : section.getKeys(true)) {
                var without = new YamlConfiguration();
                section.getValues(true).forEach((path, value) -> {
                    if (!(value instanceof ConfigurationSection)) {
                        without.set(path, value);
                    }
                });
                without.set(key, null);
                assertEquals(StalkerSettings.DEFAULTS, StalkerSettings.from(without), key);
            }
        }
    }

    @Test
    void missingSectionAndKeysUseDefaults() {
        assertEquals(StalkerSettings.DEFAULTS, StalkerSettings.from(null));
        assertEquals(StalkerSettings.DEFAULTS, StalkerSettings.from(new YamlConfiguration()));
    }

    @Test
    void readsEveryKey() {
        var config = new YamlConfiguration();
        config.set("max-alive", 2);
        config.set("health", 80.0);
        config.set("damage", 10.0);
        config.set("speed", 0.4);
        config.set("scale", 1.5);
        config.set("blink.cooldown-seconds", 12);
        config.set("blink.tell-ticks", 30);
        config.set("blink.distance", 6.0);
        config.set("blink.range", 30.0);
        config.set("blink.behind", 3.0);
        config.set("glow-seconds", 5);
        var settings = StalkerSettings.from(config);
        assertEquals(new StalkerSettings(2, 80, 10, 0.4, 1.5, 12, 30, 6, 30, 3, 5), settings);
        assertEquals(240, settings.cooldownTicks());
        assertEquals(100, settings.glowTicks());
    }

    @Test
    void clampsOutOfRangeValues() {
        assertEquals(new StalkerSettings(0, 1, 0, 0.1, 0.5, 1, 5, 2, 4, 1, 1),
                new StalkerSettings(-1, 0, -5, 0, 0.1, 0, 1, 0, 0, 0, 0));
        assertEquals(new StalkerSettings(5, 1024, 100, 0.5, 2, 120, 100, 32, 64, 6, 60),
                new StalkerSettings(9, 5000, 500, 3, 10, 999, 999, 99, 999, 99, 999));
    }

    @Test
    void theBlinkRangeNeverFallsShortOfTheBlinkDistance() {
        var settings = new StalkerSettings(1, 40, 6, 0.3, 1.1, 8, 20, 20, 10, 2, 3);
        assertEquals(20, settings.blinkRange());
    }

    @Test
    void nonFiniteValuesUseTheDefaults() {
        double nan = Double.NaN;
        var settings = new StalkerSettings(1, nan, nan, nan, Double.POSITIVE_INFINITY, 8, 20, nan, nan, nan, 3);
        assertEquals(StalkerSettings.DEFAULTS, settings);
    }
}
