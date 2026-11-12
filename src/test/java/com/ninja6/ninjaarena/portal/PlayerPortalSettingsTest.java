package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.LobbyWorld;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PlayerPortalSettingsTest {

    private static PlayerPortalSettings read(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return PlayerPortalSettings.from(config.getConfigurationSection("player-portals"));
    }

    @Test
    void missingValuesTakeTheirDefaults() throws Exception {
        assertEquals(PlayerPortalSettings.DEFAULTS, PlayerPortalSettings.from(null));
        assertEquals(PlayerPortalSettings.DEFAULTS, read("player-portals: {}\n"));
        assertEquals(Set.of("world"), PlayerPortalSettings.DEFAULTS.worlds());
        assertEquals(Material.CRYING_OBSIDIAN, PlayerPortalSettings.DEFAULTS.frame());
        assertEquals("#40E0D0", PlayerPortalSettings.hex(PlayerPortalSettings.DEFAULTS.color()));
    }

    @Test
    void everySettingIsRead() throws Exception {
        PlayerPortalSettings settings = read("""
                player-portals:
                  worlds: [survival, survival_nether]
                  frame-material: GILDED_BLACKSTONE
                  max-per-player: 0
                  min-distance: 100
                  color: '#ff8800'
                """);
        assertEquals(new PlayerPortalSettings(Set.of("survival", "survival_nether"), Material.GILDED_BLACKSTONE, 0, 100,
                0xFF8800), settings);
        assertTrue(settings.allows("survival"));
        assertFalse(settings.allows("world"));
        assertEquals("gilded_blackstone", settings.frameName());
    }

    @Test
    void badValuesNameTheirSetting() {
        List<String> bad = List.of(
                "worlds: world",
                "worlds: [world, " + ArenaWorld.NAME + "]",
                "worlds: [world, " + LobbyWorld.NAME + "]",
                "worlds: [world, 5]",
                "frame-material: obsidian",
                "frame-material: water",
                "frame-material: diamond",
                "frame-material: not_a_block",
                "frame-material: 5",
                "color: turquoise",
                "color: '#12345'",
                "max-per-player: -1",
                "max-per-player: 1001",
                "max-per-player: 1.5",
                "min-distance: 1025");
        for (String line : bad) {
            String key = line.substring(0, line.indexOf(':'));
            var failure = assertThrows(IllegalArgumentException.class, () -> read("player-portals:\n  " + line + "\n"),
                    line);
            assertTrue(failure.getMessage().contains(key), failure.getMessage());
        }
    }

    @Test
    void neitherPluginWorldIsEverAllowed() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerPortalSettings(Set.of("world"),
                Material.CRYING_OBSIDIAN, 2, 32, 0x1000000));
        PlayerPortalSettings settings = new PlayerPortalSettings(Set.of(ArenaWorld.NAME), Material.CRYING_OBSIDIAN, 2,
                32, 0);
        assertFalse(settings.allows(ArenaWorld.NAME));
        assertFalse(new PlayerPortalSettings(Set.of(LobbyWorld.NAME), Material.CRYING_OBSIDIAN, 2, 32, 0)
                .allows(LobbyWorld.NAME));
        assertFalse(PlayerPortalSettings.OFF.allows("world"));
    }
}
