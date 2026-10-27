package com.ninja6.ninjaarena.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ToolSettingsTest {

    @Test
    void bundledConfigMatchesTheRequiredDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(new ToolSettings(4, 3.0, 2, 2.0, 3, 8, 1.0, 0.3), ToolSettings.DEFAULTS);
            assertEquals(ToolSettings.DEFAULTS, ToolSettings.from(config.getConfigurationSection("tools")));
        }
    }

    @Test
    void missingSectionAndKeysFallBackToDefaults() {
        assertEquals(ToolSettings.DEFAULTS, ToolSettings.from(null));
        var config = new YamlConfiguration();
        config.set("tools.dash.cooldown-seconds", 5);
        config.set("tools.shuriken.damage", 3.5);
        config.set("tools.smoke-bomb.cloud-seconds", 6);
        assertEquals(new ToolSettings(6, 3.0, 2, 3.5, 3, 5, 1.0, 0.3),
                ToolSettings.from(config.getConfigurationSection("tools")));
    }

    @Test
    void outOfRangeValuesAreClamped() {
        var low = new ToolSettings(0, 0, -1, -4, 0, 0, 0, -1);
        assertEquals(new ToolSettings(1, 1, 1, 0, 1, 1, 0.1, 0), low);
        var high = new ToolSettings(1000, 99, 1000, 500, 1000, 1000, 50, 5);
        assertEquals(new ToolSettings(60, 8, 60, 20, 60, 60, 2, 0.6), high);
        var nan = new ToolSettings(4, Double.NaN, 2, Double.NaN, 3, 8, Double.NaN, Double.NaN);
        assertEquals(ToolSettings.DEFAULTS, nan);
    }
}
