package com.ninja6.ninjaarena.waiting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaPoint;
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
