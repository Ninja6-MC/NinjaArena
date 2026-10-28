package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class StabilitySettingsTest {

    @Test
    void bundledConfigMatchesDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = config.getConfigurationSection("stability");
            assertNotNull(section);
            assertEquals(4, section.getInt("max-concurrent-matches"));
            assertEquals(25, section.getInt("match-mob-cap"));
            assertEquals(80, section.getInt("global-mob-cap"));
            assertEquals(4, section.getInt("spawns-per-tick"));
            assertEquals(40.0, section.getDouble("pause-spawning-above-mspt"));
            assertEquals(45.0, section.getDouble("drop-tier-above-mspt"));
            assertEquals(35.0, section.getDouble("resume-below-mspt"));
            assertEquals(0.25, section.getDouble("intensity-tier"));
            assertEquals(new StabilitySettings(4, 25, 80, 4, 40, 45, 35, 0.25), StabilitySettings.DEFAULTS);
            assertEquals(StabilitySettings.DEFAULTS, StabilitySettings.from(section));
            for (String key : section.getKeys(false)) {
                var without = new YamlConfiguration();
                section.getValues(false).forEach(without::set);
                without.set(key, null);
                assertEquals(StabilitySettings.DEFAULTS, StabilitySettings.from(without), key);
            }
        }
    }

    @Test
    void missingSectionAndKeysUseDefaults() {
        assertEquals(StabilitySettings.DEFAULTS, StabilitySettings.from(null));
        assertEquals(StabilitySettings.DEFAULTS, StabilitySettings.from(new YamlConfiguration()));
    }

    @Test
    void readsEveryKey() {
        var config = new YamlConfiguration();
        config.set("max-concurrent-matches", 2);
        config.set("match-mob-cap", 10);
        config.set("global-mob-cap", 30);
        config.set("spawns-per-tick", 2);
        config.set("pause-spawning-above-mspt", 30.0);
        config.set("drop-tier-above-mspt", 40.0);
        config.set("resume-below-mspt", 25.0);
        config.set("intensity-tier", 0.5);
        assertEquals(new StabilitySettings(2, 10, 30, 2, 30, 40, 25, 0.5), StabilitySettings.from(config));
    }

    @Test
    void clampsOutOfRangeValues() {
        assertEquals(new StabilitySettings(1, 1, 1, 1, 1, 1, 1, 0),
                new StabilitySettings(0, -5, 0, -1, -10, 0, -3, -1));
        assertEquals(new StabilitySettings(100, 1000, 1000, 100, 1000, 1000, 1000, 1),
                new StabilitySettings(1000, 5000, 5000, 1000, 1e9, 1e9, 1e9, 2));
    }

    @Test
    void keepsTheTickTimeThresholdsInOrder() {
        var crossed = new StabilitySettings(4, 25, 80, 4, 40, 30, 50, 0.25);
        assertEquals(40, crossed.pauseAboveMspt());
        assertEquals(40, crossed.dropTierAboveMspt());
        assertEquals(40, crossed.resumeBelowMspt());
    }

    @Test
    void nonFiniteConfigValuesFallBackAndTheConstructorRejectsThem() {
        var config = new YamlConfiguration();
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            config.set("pause-spawning-above-mspt", bad);
            config.set("intensity-tier", bad);
            assertEquals(StabilitySettings.DEFAULTS, StabilitySettings.from(config));
            assertThrows(IllegalArgumentException.class, () -> new StabilitySettings(4, 25, 80, 4, bad, 45, 35, 0.25));
            assertThrows(IllegalArgumentException.class, () -> new StabilitySettings(4, 25, 80, 4, 40, 45, 35, bad));
        }
    }
}
