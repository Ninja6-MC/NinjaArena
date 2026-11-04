package com.ninja6.ninjaarena.spectate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.match.MatchPhase;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SpectatorRulesTest {

    @Test
    void confinementIncludesEntireEdgeBlocksOnAllAxes() {
        ArenaBounds bounds = new ArenaBounds(-10, 60, -20, -5, 80, -12);
        assertTrue(Spectators.contains(bounds, -10, 60, -20));
        assertTrue(Spectators.contains(bounds, -4.001, 80.999, -11.001));
        assertFalse(Spectators.contains(bounds, -4, 64, -16));
        assertFalse(Spectators.contains(bounds, -10.001, 64, -16));
        assertFalse(Spectators.contains(bounds, -8, 81, -16));
        assertFalse(Spectators.contains(bounds, -8, 59.999, -16));
        assertFalse(Spectators.contains(bounds, -8, 64, -11));
        assertFalse(Spectators.contains(bounds, -8, 64, -20.001));
        assertFalse(Spectators.contains(bounds, Double.NaN, 64, -16));
    }

    @Test
    void onlyAnInProgressMatchCanAdmitViewers() {
        assertFalse(Spectators.watchable(MatchPhase.WAITING));
        assertFalse(Spectators.watchable(MatchPhase.MATCH_END));
        assertTrue(Spectators.watchable(MatchPhase.PREP));
        assertTrue(Spectators.watchable(MatchPhase.COMBAT));
        assertTrue(Spectators.watchable(MatchPhase.OVERRUN));
        assertTrue(Spectators.watchable(MatchPhase.ROUND_END));
    }

    @Test
    void settingsValidateAndApplyTheLimitOnlyToAdmission() {
        assertEquals(new SpectatorSettings(true, 16), SpectatorSettings.from(null));
        var yaml = new YamlConfiguration();
        yaml.set("max-per-match", 2);
        var limited = SpectatorSettings.from(yaml);
        assertTrue(limited.allows(1));
        assertFalse(limited.allows(2));
        yaml.set("enabled", false);
        assertFalse(SpectatorSettings.from(yaml).allows(0));
        yaml.set("enabled", "true");
        assertThrows(IllegalArgumentException.class, () -> SpectatorSettings.from(yaml));
        assertThrows(IllegalArgumentException.class, () -> new SpectatorSettings(true, 0));
        assertThrows(IllegalArgumentException.class, () -> new SpectatorSettings(true, 65));
    }
}
