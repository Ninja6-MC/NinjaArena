package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MatchSettingsTest {

    private static YamlConfiguration bundledConfig() throws IOException {
        try (InputStream stream = MatchSettingsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(stream, "config.yml is not on the classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    @Test
    void bundledConfigMatchesTheDefaults() throws IOException {
        assertEquals(MatchSettings.DEFAULTS, MatchSettings.from(bundledConfig().getConfigurationSection("match")));
        assertEquals(15, MatchSettings.DEFAULTS.prepSeconds());
        assertEquals(5, MatchSettings.DEFAULTS.rounds());
    }

    @Test
    void missingSectionOrKeysFallBackToTheDefaults() {
        assertEquals(MatchSettings.DEFAULTS, MatchSettings.from(null));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("match.rounds", 3);
        yaml.set("match.overrun-limit-seconds", 0);
        MatchSettings settings = MatchSettings.from(yaml.getConfigurationSection("match"));
        assertEquals(3, settings.rounds());
        assertEquals(0, settings.overrunLimitSeconds());
        assertEquals(MatchSettings.DEFAULT_PREP_SECONDS, settings.prepSeconds());
        assertEquals(MatchSettings.DEFAULT_ROUND_SECONDS, settings.roundSeconds());
        assertEquals(MatchSettings.DEFAULT_EXTRA_ROUNDS, settings.extraRounds());
    }

    @Test
    void outOfRangeValuesAreClamped() {
        MatchSettings low = new MatchSettings(0, -5, -1, 0, 0, 0, -1);
        assertEquals(new MatchSettings(1, 1, 0, 1, 1, 1, 0), low);

        MatchSettings high = new MatchSettings(99999, 99999, 99999, 99999, 99999, 99, 99);
        assertEquals(MatchSettings.MAX_SECONDS, high.prepSeconds());
        assertEquals(MatchSettings.MAX_SECONDS, high.overrunLimitSeconds());
        assertEquals(MatchSettings.MAX_ROUNDS, high.rounds());
        assertEquals(MatchSettings.MAX_EXTRA_ROUNDS, high.extraRounds());
    }
}
