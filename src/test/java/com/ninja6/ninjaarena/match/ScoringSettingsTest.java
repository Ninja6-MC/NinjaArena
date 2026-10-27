package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ScoringSettingsTest {

    @Test
    void bundledConfigMatchesTheRequiredDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(new ScoringSettings(5, 2), ScoringSettings.DEFAULTS);
            assertEquals(ScoringSettings.DEFAULTS, ScoringSettings.from(config.getConfigurationSection("scoring")));
        }
    }

    @Test
    void missingSectionAndKeysFallBackToDefaults() {
        assertEquals(ScoringSettings.DEFAULTS, ScoringSettings.from(null));
        var config = new YamlConfiguration();
        config.set("scoring.player-kill-points", 7);
        assertEquals(new ScoringSettings(5, 7), ScoringSettings.from(config.getConfigurationSection("scoring")));
    }

    @Test
    void zeroDisablesAwardsAndNegativeValuesAreClamped() {
        var config = new YamlConfiguration();
        config.set("scoring.round-win-points", -5);
        config.set("scoring.player-kill-points", 0);
        assertEquals(new ScoringSettings(0, 0), ScoringSettings.from(config.getConfigurationSection("scoring")));
        assertEquals(new ScoringSettings(0, 0), new ScoringSettings(Integer.MIN_VALUE, -1));
        assertEquals(Integer.MAX_VALUE, new ScoringSettings(Integer.MAX_VALUE, 1).roundWinPoints());
    }
}
