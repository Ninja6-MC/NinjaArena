package com.ninja6.ninjaarena.hazard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HazardSettingsTest {

    @Test
    void bundledConfigMatchesDefaultsAndTheMeasuredCaps() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = config.getConfigurationSection("stability.hazards");
            assertNotNull(section);
            assertEquals(256, section.getInt("max-lava-sources-per-map"));
            assertEquals(1024, section.getInt("max-lava-sources"));
            assertEquals(300, section.getInt("lava-flow-seconds"));
            assertEquals(new HazardSettings(256, 1024, 300), HazardSettings.DEFAULTS);
            assertEquals(HazardSettings.DEFAULTS, HazardSettings.from(section));
            for (String key : section.getKeys(false)) {
                var without = new YamlConfiguration();
                section.getValues(false).forEach(without::set);
                without.set(key, null);
                assertEquals(HazardSettings.DEFAULTS, HazardSettings.from(without), key);
            }
        }
    }

    @Test
    void missingSectionAndKeysUseDefaults() {
        assertEquals(HazardSettings.DEFAULTS, HazardSettings.from(null));
        assertEquals(HazardSettings.DEFAULTS, HazardSettings.from(new YamlConfiguration()));
    }

    @Test
    void valuesAreClampedToTheMeasuredCaps() {
        assertEquals(new HazardSettings(256, 1024, 3600), new HazardSettings(10_000, 10_000, 10_000));
        assertEquals(new HazardSettings(0, 0, 1), new HazardSettings(-1, -1, 0));
    }
}
