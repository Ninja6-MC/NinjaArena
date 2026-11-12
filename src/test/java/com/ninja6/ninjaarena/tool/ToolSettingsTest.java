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
            assertEquals(new ToolSettings(4, 3.0, 2, 2.0, 3, 8, 1.0, 0.3, 0, 0, 0, 0), ToolSettings.DEFAULTS);
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
        assertEquals(new ToolSettings(6, 3.0, 2, 3.5, 3, 5, 1.0, 0.3, 0, 0, 0, 0),
                ToolSettings.from(config.getConfigurationSection("tools")));
    }

    @Test
    void throwSettingsLoadAndClampIndependently() {
        var config = new YamlConfiguration();
        config.set("shuriken.cooldown-seconds", 7);
        config.set("shuriken.uses-per-round", 3);
        config.set("smoke-bomb.cooldown-seconds", 9);
        config.set("smoke-bomb.uses-per-round", 4);
        var loaded = ToolSettings.from(config);
        assertEquals(7, loaded.shurikenCooldownSeconds());
        assertEquals(3, loaded.shurikenUsesPerRound());
        assertEquals(9, loaded.smokeCooldownSeconds());
        assertEquals(4, loaded.smokeUsesPerRound());
        var low = new ToolSettings(4, 3, 2, 2, 3, 8, 1, 0.3, -1, -1, -1, -1);
        assertEquals(ToolSettings.DEFAULTS, low);
        var high = new ToolSettings(4, 3, 2, 2, 3, 8, 1, 0.3, 99, 99, 99, 99);
        assertEquals(60, high.shurikenCooldownSeconds());
        assertEquals(60, high.smokeCooldownSeconds());
        assertEquals(64, high.shurikenUsesPerRound());
        assertEquals(64, high.smokeUsesPerRound());
    }

    @Test
    void outOfRangeValuesAreClamped() {
        var low = new ToolSettings(0, 0, -1, -4, 0, 0, 0, -1, 0, 0, 0, 0);
        assertEquals(new ToolSettings(1, 1, 1, 0, 1, 1, 0.1, 0, 0, 0, 0, 0), low);
        var high = new ToolSettings(1000, 99, 1000, 500, 1000, 1000, 50, 5, 0, 0, 0, 0);
        assertEquals(new ToolSettings(60, 8, 60, 20, 60, 60, 2, 0.6, 0, 0, 0, 0), high);
        var nan = new ToolSettings(4, Double.NaN, 2, Double.NaN, 3, 8, Double.NaN, Double.NaN, 0, 0, 0, 0);
        assertEquals(ToolSettings.DEFAULTS, nan);
    }
}
