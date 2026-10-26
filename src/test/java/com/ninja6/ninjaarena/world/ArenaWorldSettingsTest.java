package com.ninja6.ninjaarena.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ArenaWorldSettingsTest {

    private static YamlConfiguration bundledConfig() throws IOException {
        try (InputStream stream = ArenaWorldSettingsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(stream, "config.yml is not on the classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    @Test
    void bundledConfigMatchesTheDefaults() throws IOException {
        assertEquals(ArenaWorldSettings.DEFAULTS, ArenaWorldSettings.from(bundledConfig().getConfigurationSection("arena-world")));
    }

    @Test
    void defaultsKeepTheWorldSmallAndCheap() {
        ArenaWorldSettings defaults = ArenaWorldSettings.DEFAULTS;
        assertEquals(2048, defaults.borderSize());
        assertEquals(6, defaults.viewDistance());
        assertEquals(4, defaults.simulationDistance());
    }

    @Test
    void missingSectionOrKeysFallBackToTheDefaults() {
        assertEquals(ArenaWorldSettings.DEFAULTS, ArenaWorldSettings.from(null));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("arena-world.view-distance", 3);
        ArenaWorldSettings settings = ArenaWorldSettings.from(yaml.getConfigurationSection("arena-world"));
        assertEquals(ArenaWorldSettings.DEFAULT_BORDER_SIZE, settings.borderSize());
        assertEquals(3, settings.viewDistance());
        assertEquals(ArenaWorldSettings.DEFAULT_SIMULATION_DISTANCE, settings.simulationDistance());
    }

    @Test
    void outOfRangeValuesAreClamped() {
        ArenaWorldSettings low = new ArenaWorldSettings(0, 0, -5);
        assertEquals(ArenaWorldSettings.MIN_BORDER_SIZE, low.borderSize());
        assertEquals(ArenaWorldSettings.MIN_DISTANCE, low.viewDistance());
        assertEquals(ArenaWorldSettings.MIN_DISTANCE, low.simulationDistance());

        ArenaWorldSettings high = new ArenaWorldSettings(1e9, 99, 99);
        assertEquals(ArenaWorldSettings.MAX_BORDER_SIZE, high.borderSize());
        assertEquals(ArenaWorldSettings.MAX_DISTANCE, high.viewDistance());
        assertEquals(ArenaWorldSettings.MAX_DISTANCE, high.simulationDistance());
    }
}
