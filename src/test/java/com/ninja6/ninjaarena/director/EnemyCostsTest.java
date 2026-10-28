package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class EnemyCostsTest {

    @Test
    void bundledConfigContainsTheRequiredCosts() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = config.getConfigurationSection("director.enemy-costs");
            assertNotNull(section);
            assertEquals(1, section.getInt("zombie"));
            assertEquals(2, section.getInt("skeleton"));
            assertEquals(2, section.getInt("spider"));
            assertEquals(8, section.getInt("shadow-stalker"));
            assertEquals(new EnemyCosts(1, 2, 2, 8), EnemyCosts.DEFAULTS);
            assertEquals(EnemyCosts.DEFAULTS, EnemyCosts.from(section));
        }
    }

    @Test
    void configuredCostsOverrideDefaultsIndependently() {
        assertEquals(EnemyCosts.DEFAULTS, EnemyCosts.from(null));
        var config = new YamlConfiguration();
        assertEquals(EnemyCosts.DEFAULTS, EnemyCosts.from(config));
        config.set("skeleton", 5);
        assertEquals(new EnemyCosts(1, 5, 2, 8), EnemyCosts.from(config));
        config.set("zombie", 3);
        config.set("spider", 4);
        config.set("shadow-stalker", 12);
        assertEquals(new EnemyCosts(3, 5, 4, 12), EnemyCosts.from(config));
    }

    @Test
    void costsStayPositive() {
        var config = new YamlConfiguration();
        config.set("zombie", 0);
        config.set("skeleton", -2);
        config.set("spider", Integer.MIN_VALUE);
        config.set("shadow-stalker", 0);
        assertEquals(new EnemyCosts(1, 1, 1, 1), EnemyCosts.from(config));
        assertEquals(Integer.MAX_VALUE, new EnemyCosts(Integer.MAX_VALUE, 2, 2, 8).zombie());
    }
}
