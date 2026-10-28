package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class StealthSettingsTest {

    @Test
    void bundledConfigMatchesDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = config.getConfigurationSection("director.stealth");
            assertNotNull(section);
            assertEquals(new StealthSettings(0.25, 7, 3, 2, false, false, false, false), StealthSettings.DEFAULTS);
            assertEquals(StealthSettings.DEFAULTS, StealthSettings.from(section));
            for (String key : section.getKeys(true)) {
                var without = new YamlConfiguration();
                section.getValues(true).forEach((path, value) -> {
                    if (!(value instanceof ConfigurationSection)) {
                        without.set(path, value);
                    }
                });
                without.set(key, null);
                assertEquals(StealthSettings.DEFAULTS, StealthSettings.from(without), key);
            }
        }
    }

    @Test
    void missingSectionAndKeysUseDefaults() {
        assertEquals(StealthSettings.DEFAULTS, StealthSettings.from(null));
        assertEquals(StealthSettings.DEFAULTS, StealthSettings.from(new YamlConfiguration()));
    }

    @Test
    void readsEveryKey() {
        var config = new YamlConfiguration();
        config.set("hidden-weight", 0.5);
        config.set("low-light", 4);
        config.set("lose-track-seconds", 5);
        config.set("marked-weight", 3.0);
        config.set("ignores-stealth.zombie", true);
        config.set("ignores-stealth.skeleton", false);
        config.set("ignores-stealth.spider", true);
        config.set("ignores-stealth.shadow-stalker", true);
        var settings = StealthSettings.from(config);
        assertEquals(new StealthSettings(0.5, 4, 5, 3, true, false, true, true), settings);
        assertTrue(settings.ignoresStealth(EnemyKind.ZOMBIE));
        assertFalse(settings.ignoresStealth(EnemyKind.SKELETON));
        assertTrue(settings.ignoresStealth(EnemyKind.SPIDER));
    }

    @Test
    void noKindIgnoresStealthByDefault() {
        for (EnemyKind kind : EnemyKind.values()) {
            assertFalse(StealthSettings.DEFAULTS.ignoresStealth(kind), kind.name());
        }
        assertFalse(StealthSettings.DEFAULTS.shadowStalkerIgnores());
    }

    @Test
    void clampsOutOfRangeValues() {
        assertEquals(new StealthSettings(0, -1, 1, 1, false, false, false, false),
                new StealthSettings(-2, -9, 0, 0.5, false, false, false, false));
        assertEquals(new StealthSettings(1, 15, 60, 10, false, false, false, false),
                new StealthSettings(4, 99, 600, 50, false, false, false, false));
    }

    @Test
    void nonFiniteWeightsUseTheDefaults() {
        var settings = new StealthSettings(Double.NaN, 7, 3, Double.POSITIVE_INFINITY, false, false, false, false);
        assertEquals(StealthSettings.DEFAULTS, settings);
        var config = new YamlConfiguration();
        config.set("hidden-weight", Double.NaN);
        assertEquals(StealthSettings.DEFAULTS, StealthSettings.from(config));
    }
}
