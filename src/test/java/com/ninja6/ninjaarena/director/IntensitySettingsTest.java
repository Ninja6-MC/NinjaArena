package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class IntensitySettingsTest {

    @Test
    void bundledConfigMatchesDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = config.getConfigurationSection("director.intensity");
            assertNotNull(section);
            assertEquals(40, section.getInt("build-up-seconds"));
            assertEquals(20, section.getInt("peak-seconds"));
            assertEquals(10, section.getInt("relax-seconds"));
            assertEquals(0.6, section.getDouble("first-round-peak"));
            assertEquals(2.0, section.getDouble("leader-bias"));
            assertEquals(new IntensitySettings(40, 20, 10, 0.6, 0.1, 0.25, 0.2, 0.5, 10, 20, 0.5, 0.25, 0.25, 2),
                    IntensitySettings.DEFAULTS);
            assertEquals(IntensitySettings.DEFAULTS, IntensitySettings.from(section));
            for (String key : section.getKeys(false)) {
                var without = new YamlConfiguration();
                section.getValues(false).forEach(without::set);
                without.set(key, null);
                assertEquals(IntensitySettings.DEFAULTS, IntensitySettings.from(without), key);
            }
        }
    }

    @Test
    void missingSectionAndKeysUseDefaults() {
        assertEquals(IntensitySettings.DEFAULTS, IntensitySettings.from(null));
        assertEquals(IntensitySettings.DEFAULTS, IntensitySettings.from(new YamlConfiguration()));
    }

    @Test
    void readsEveryKey() {
        var config = new YamlConfiguration();
        config.set("build-up-seconds", 30);
        config.set("peak-seconds", 15);
        config.set("relax-seconds", 5);
        config.set("first-round-peak", 0.5);
        config.set("round-step", 0.2);
        config.set("relax-level", 0.1);
        config.set("time-ramp", 0.3);
        config.set("teams-weight", 0.4);
        config.set("damage-window-seconds", 8);
        config.set("damage-full-stress", 30.0);
        config.set("damage-relief", 0.6);
        config.set("budget-round-step", 0.5);
        config.set("budget-per-player", 0.3);
        config.set("leader-bias", 3.0);
        assertEquals(new IntensitySettings(30, 15, 5, 0.5, 0.2, 0.1, 0.3, 0.4, 8, 30, 0.6, 0.5, 0.3, 3),
                IntensitySettings.from(config));
    }

    @Test
    void clampsOutOfRangeValues() {
        var low = new IntensitySettings(0, -5, 0, -1, -1, -1, -1, -1, 0, 0, -1, -1, -1, 0);
        assertEquals(new IntensitySettings(1, 1, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 1), low);
        var high = new IntensitySettings(10_000, 10_000, 10_000, 2, 2, 2, 2, 2, 1000, 1e9, 2, 100, 100, 100);
        assertEquals(new IntensitySettings(600, 600, 600, 1, 1, 1, 1, 1, 60, 1000, 1, 10, 10, 10), high);
    }

    @Test
    void nonFiniteConfigValuesFallBackAndTheConstructorRejectsThem() {
        var config = new YamlConfiguration();
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            config.set("first-round-peak", bad);
            config.set("leader-bias", bad);
            assertEquals(IntensitySettings.DEFAULTS, IntensitySettings.from(config));
            assertThrows(IllegalArgumentException.class,
                    () -> new IntensitySettings(40, 20, 10, bad, 0.1, 0.25, 0.2, 0.5, 10, 20, 0.5, 0.25, 0.25, 2));
        }
    }

    @Test
    void peakEscalatesEachRoundUpToOne() {
        var settings = IntensitySettings.DEFAULTS;
        assertEquals(0.6, settings.peak(1), 1e-9);
        assertEquals(0.7, settings.peak(2), 1e-9);
        assertEquals(1.0, settings.peak(5), 1e-9);
        assertEquals(1.0, settings.peak(12), 1e-9);
        assertEquals(0.6, settings.peak(0), 1e-9);
    }

    @Test
    void budgetFactorsFollowRoundAndPlayers() {
        var settings = IntensitySettings.DEFAULTS;
        assertEquals(1, settings.roundMultiplier(1));
        assertEquals(1.25, settings.roundMultiplier(2));
        assertEquals(2, settings.roundMultiplier(5));
        assertEquals(1, settings.playerFactor(4));
        assertEquals(0.5, settings.playerFactor(2));
        assertEquals(0, settings.playerFactor(-1));
        assertEquals(70, settings.cycleSeconds());
    }
}
