package com.ninja6.ninjaarena.waiting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.portal.LobbyMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    void bundledConfigMatchesTheDefaults() throws IOException {
        assertEquals(WaitingRoomSettings.DEFAULTS,
                WaitingRoomSettings.from(bundledConfig().getConfigurationSection("waiting-room")));
    }

    @Test
    void missingSectionOrKeysFallBackToTheDefaults() {
        assertEquals(WaitingRoomSettings.DEFAULTS, WaitingRoomSettings.from(null));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("waiting-room.countdown-seconds", 10);
        WaitingRoomSettings settings = WaitingRoomSettings.from(yaml.getConfigurationSection("waiting-room"));
        assertEquals(WaitingRoomSettings.DEFAULT_MIN_PLAYERS, settings.minPlayers());
        assertEquals(10, settings.countdownSeconds());
        assertEquals(WaitingRoomSettings.DEFAULT_RADIUS, settings.radius());
    }

    @Test
    void outOfRangeValuesAreClamped() {
        WaitingRoomSettings low = new WaitingRoomSettings(0, -1, 0);
        assertEquals(1, low.minPlayers());
        assertEquals(1, low.countdownSeconds());
        assertEquals(WaitingRoomSettings.MIN_RADIUS, low.radius());

        WaitingRoomSettings high = new WaitingRoomSettings(1000, 100_000, 1000);
        assertEquals(WaitingRoomSettings.MAX_MIN_PLAYERS, high.minPlayers());
        assertEquals(WaitingRoomSettings.MAX_COUNTDOWN_SECONDS, high.countdownSeconds());
        assertEquals(WaitingRoomSettings.MAX_RADIUS, high.radius());
    }

    @Test
    void capsAndTheLobbyAreReadAndClamped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("waiting-room.min-players", 4);
        yaml.set("waiting-room.max-players", 2);
        yaml.set("waiting-room.team-size", 1000);
        yaml.set("waiting-room.lobby", "SHARED");
        WaitingRoomSettings settings = WaitingRoomSettings.from(yaml.getConfigurationSection("waiting-room"));
        assertEquals(4, settings.maxPlayers(), "a cap below the minimum is raised to it");
        assertEquals(WaitingRoomSettings.MAX_TEAM_SIZE, settings.teamSize());
        assertEquals(LobbyMode.SHARED, settings.lobby());

        yaml.set("waiting-room.max-players", -3);
        yaml.set("waiting-room.lobby", "everywhere");
        settings = WaitingRoomSettings.from(yaml.getConfigurationSection("waiting-room"));
        assertEquals(0, settings.maxPlayers());
        assertEquals(LobbyMode.DEDICATED, settings.lobby(), "an unknown lobby falls back to dedicated");
    }

    @Test
    void capacityIsTheLowerOfTheMatchCapAndTeamSizeTimesTeams() {
        assertEquals(Integer.MAX_VALUE, WaitingRoomSettings.DEFAULTS.capacity(4));
        WaitingRoomSettings capped = new WaitingRoomSettings(2, 6, 0, 30, 16, LobbyMode.DEDICATED);
        assertEquals(6, capped.capacity(4));
        WaitingRoomSettings teams = new WaitingRoomSettings(2, 6, 2, 30, 16, LobbyMode.DEDICATED);
        assertEquals(4, teams.capacity(2));
        assertEquals(6, teams.capacity(4));
        assertEquals(8, new WaitingRoomSettings(2, 0, 2, 30, 16, LobbyMode.DEDICATED).capacity(4));
    }

    @Test
    void theRoomIsACubeAroundThePointsBlock() {
        WaitingRoomSettings settings = new WaitingRoomSettings(2, 30, 4);
        ArenaPoint room = new ArenaPoint(50.5, 64, -10.5, 0, 0);
        assertTrue(settings.inRoom(room, 50, 64, -11));
        assertTrue(settings.inRoom(room, 54, 60, -15));
        assertTrue(settings.inRoom(room, 46, 68, -7));
        assertFalse(settings.inRoom(room, 55, 64, -11));
        assertFalse(settings.inRoom(room, 50, 59, -11));
        assertFalse(settings.inRoom(room, 50, 64, -6));
    }
}
