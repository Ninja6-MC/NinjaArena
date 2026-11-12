package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PointsSettingsTest {

    @Test
    void bundledConfigMatchesTheRequiredDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(new PointsSettings(2, 1, 2, 6), PointsSettings.DEFAULTS);
            assertEquals(PointsSettings.DEFAULTS, PointsSettings.from(config.getConfigurationSection("points")));
        }
    }

    @Test
    void missingSectionAndKeysFallBackToDefaults() {
        assertEquals(PointsSettings.DEFAULTS, PointsSettings.from(null));
        var config = new YamlConfiguration();
        config.set("points.player-kill", 7);
        assertEquals(new PointsSettings(7, 1, 2, 6), PointsSettings.from(config.getConfigurationSection("points")));
    }

    @Test
    void zeroDisablesAwardsAndNegativeValuesAreClamped() {
        var config = new YamlConfiguration();
        config.set("points.player-kill", -5);
        config.set("points.enemy-kill-per-cost", 0);
        config.set("points.round-survive", -2);
        config.set("points.round-win", 0);
        assertEquals(new PointsSettings(0, 0, 0, 0), PointsSettings.from(config.getConfigurationSection("points")));
        assertEquals(new PointsSettings(0, 0, 0, 0), new PointsSettings(Integer.MIN_VALUE, -1, -5, -9));
        assertEquals(Integer.MAX_VALUE, new PointsSettings(0, 0, 0, Integer.MAX_VALUE).roundWin());
    }
}
