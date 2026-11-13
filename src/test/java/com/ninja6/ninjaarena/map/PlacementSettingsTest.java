package com.ninja6.ninjaarena.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PlacementSettingsTest {

    @Test
    void bundledConfigMatchesDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = config.getConfigurationSection("stability.placement");
            assertNotNull(section);
            assertEquals(10.0, section.getDouble("budget-ms"));
            assertEquals(1.0, section.getDouble("slow-budget-ms"));
            assertEquals(10, section.getInt("recovery-seconds"));
            assertEquals(256, section.getInt("max-map-size"));
            assertEquals(new PlacementSettings(10, 1, 10, 256), PlacementSettings.DEFAULTS);
            assertEquals(PlacementSettings.DEFAULTS, PlacementSettings.from(section));
            for (String key : section.getKeys(false)) {
                var without = new YamlConfiguration();
                section.getValues(false).forEach(without::set);
                without.set(key, null);
                assertEquals(PlacementSettings.DEFAULTS, PlacementSettings.from(without), key);
            }
        }
    }

    @Test
    void missingSectionAndKeysUseDefaults() {
        assertEquals(PlacementSettings.DEFAULTS, PlacementSettings.from(null));
        assertEquals(PlacementSettings.DEFAULTS, PlacementSettings.from(new YamlConfiguration()));
    }

    @Test
    void readsEveryKey() {
        var config = new YamlConfiguration();
        config.set("budget-ms", 4.5);
        config.set("slow-budget-ms", 0.0);
        config.set("recovery-seconds", 30);
        config.set("max-map-size", 128);
        PlacementSettings settings = PlacementSettings.from(config);
        assertEquals(new PlacementSettings(4.5, 0, 30, 128), settings);
        assertEquals(4_500_000, settings.budgetNanos());
        assertEquals(0, settings.slowBudgetNanos());
    }

    @Test
    void clampsOutOfRangeValuesAndKeepsTheSlowBudgetWithinTheFullOne() {
        assertEquals(new PlacementSettings(0.1, 0, 0, 32), new PlacementSettings(-5, -1, -3, 0));
        assertEquals(new PlacementSettings(50, 50, 600, 4096), new PlacementSettings(500, 900, 9999, 99_999));
        assertEquals(3.0, new PlacementSettings(3, 8, 10, 256).slowBudgetMillis(), "lowered to the full budget");
    }

    @Test
    void nonFiniteValuesFallBackOrAreRefused() {
        var config = new YamlConfiguration();
        config.set("budget-ms", Double.NaN);
        config.set("slow-budget-ms", Double.POSITIVE_INFINITY);
        assertEquals(PlacementSettings.DEFAULTS, PlacementSettings.from(config));
        assertThrows(IllegalArgumentException.class, () -> new PlacementSettings(Double.NaN, 1, 10, 256));
    }
}
