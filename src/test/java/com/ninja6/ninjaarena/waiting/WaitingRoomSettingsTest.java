package com.ninja6.ninjaarena.waiting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.portal.LobbyMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class WaitingRoomSettingsTest {

    private static YamlConfiguration bundledConfig() throws IOException {
        try (InputStream stream = WaitingRoomSettingsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(stream, "config.yml is not on the classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    @Test
    void bundledConfigMatchesTheDefaultsAndSetsNoDeprecatedKey() throws IOException {
        YamlConfiguration bundled = bundledConfig();
        assertEquals(WaitingRoomSettings.DEFAULTS,
                WaitingRoomSettings.from(bundled.getConfigurationSection("waiting-room")));
        for (String path : WaitingRoomSettings.DEPRECATED) {
            assertFalse(bundled.contains(path), path);
        }
    }

    @Test
    void missingSectionOrKeysFallBackToTheDefaults() {
        assertEquals(WaitingRoomSettings.DEFAULTS, WaitingRoomSettings.from(null));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("waiting-room.countdown-seconds", 10);
        WaitingRoomSettings settings = WaitingRoomSettings.from(yaml.getConfigurationSection("waiting-room"));
        assertEquals(WaitingRoomSettings.DEFAULTS, settings);
    }

    @Test
    void outOfRangeValuesAreClamped() {
        WaitingRoomSettings low = new WaitingRoomSettings(0, -1, LobbyMode.DEDICATED);
        assertEquals(1, low.minPlayers());
        assertEquals(0, low.teamSize());

        WaitingRoomSettings high = new WaitingRoomSettings(1000, 1000, LobbyMode.SHARED);
        assertEquals(WaitingRoomSettings.MAX_MIN_PLAYERS, high.minPlayers());
        assertEquals(WaitingRoomSettings.MAX_TEAM_SIZE, high.teamSize());
    }

    @Test
    void theOldLobbyIsStillRead() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("waiting-room.min-players", 4);
        yaml.set("waiting-room.lobby", "SHARED");
        WaitingRoomSettings settings = WaitingRoomSettings.from(yaml.getConfigurationSection("waiting-room"));
        assertEquals(4, settings.minPlayers());
        assertEquals(LobbyMode.SHARED, settings.lobby());

        yaml.set("waiting-room.lobby", "everywhere");
        settings = WaitingRoomSettings.from(yaml.getConfigurationSection("waiting-room"));
        assertEquals(LobbyMode.DEDICATED, settings.lobby(), "an unknown lobby falls back to dedicated");
    }

    @Test
    void anOldConfigWarnsOncePerDeprecatedKeyAndPointsToTheReadme() {
        YamlConfiguration old = new YamlConfiguration();
        old.set("waiting-room.lobby", "shared");
        old.set("waiting-room.min-players", 2);
        old.set("waiting-room.max-players", 0);
        old.set("waiting-room.countdown-seconds", 30);
        old.set("waiting-room.radius", 16);
        List<String> warnings = new ArrayList<>();
        WaitingRoomSettings.warnDeprecated(old, warnings::add);
        assertEquals(4, warnings.size());
        assertTrue(warnings.getFirst().startsWith("waiting-room.lobby is deprecated"));
        assertTrue(warnings.get(1).startsWith("waiting-room.max-players is no longer used"));
        for (String warning : warnings) {
            assertTrue(warning.endsWith("See " + WaitingRoomSettings.MIGRATION_DOCS + "."), warning);
        }

        List<String> none = new ArrayList<>();
        YamlConfiguration current = new YamlConfiguration();
        current.set("waiting-room.min-players", 2);
        current.set("waiting-room.team-size", 0);
        WaitingRoomSettings.warnDeprecated(current, none::add);
        assertEquals(List.of(), none);
    }
}
