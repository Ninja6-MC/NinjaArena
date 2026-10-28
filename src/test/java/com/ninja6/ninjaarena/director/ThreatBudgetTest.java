package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ThreatBudgetTest {

    @Test
    void multipliesBaseRoundMultiplierAndPlayerFactor() {
        var budget = new ThreatBudget(20);
        assertEquals(20, budget.forRound(1, 1));
        assertEquals(90, budget.forRound(1.5, 3));
        assertEquals(11.25, new ThreatBudget(7.5).forRound(1.25, 1.2));
    }

    @Test
    void zeroAndNegativeInputsProduceNoBudget() {
        assertEquals(0, new ThreatBudget(0).forRound(2, 3));
        assertEquals(0, new ThreatBudget(-20).forRound(2, 3));
        assertEquals(0, ThreatBudget.DEFAULTS.forRound(0, 3));
        assertEquals(0, ThreatBudget.DEFAULTS.forRound(2, 0));
        assertEquals(0, ThreatBudget.DEFAULTS.forRound(-1, -2));
    }

    @Test
    void rejectsNonFiniteInputsAndOverflow() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ThreatBudget(value));
            assertThrows(IllegalArgumentException.class, () -> ThreatBudget.DEFAULTS.forRound(value, 1));
            assertThrows(IllegalArgumentException.class, () -> ThreatBudget.DEFAULTS.forRound(1, value));
        }
        assertThrows(IllegalArgumentException.class, () -> new ThreatBudget(Double.MAX_VALUE).forRound(2, 1));
    }

    @Test
    void readsConfiguredBaseAndFallsBackForMissingSettings() {
        assertEquals(ThreatBudget.DEFAULTS, ThreatBudget.from(null));
        var config = new YamlConfiguration();
        assertEquals(ThreatBudget.DEFAULTS, ThreatBudget.from(config));
        config.set("base", 12.5);
        assertEquals(50, ThreatBudget.from(config).forRound(2, 2));
        for (double bad : new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
            config.set("base", bad);
            assertEquals(ThreatBudget.DEFAULTS, ThreatBudget.from(config));
        }
    }

    @Test
    void bundledConfigMatchesDefaults() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = config.getConfigurationSection("director.threat-budget");
            assertNotNull(section);
            assertEquals(20, section.getDouble("base"));
            assertEquals(ThreatBudget.DEFAULTS, ThreatBudget.from(section));
        }
    }
}
