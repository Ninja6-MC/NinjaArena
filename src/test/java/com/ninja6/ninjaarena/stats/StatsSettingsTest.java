package com.ninja6.ninjaarena.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.config.ReloadFiles;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class StatsSettingsTest {

    @Test
    void theBundledConfigMatchesTheDefaults() {
        assertEquals(StatsSettings.DEFAULTS, StatsSettings.from(ReloadFiles.defaults().getConfigurationSection("stats")));
        assertEquals(StatsSettings.DEFAULTS, StatsSettings.from(null));
        assertEquals(StatsSettings.Storage.SQLITE, StatsSettings.DEFAULTS.storage());
    }

    @Test
    void mysqlSettingsAreReadAndThePasswordNeverPrints() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                enabled: false
                storage: mysql
                season: Winter 2026
                table-prefix: arena_
                mysql:
                  host: db.internal
                  port: 3307
                  database: shared
                  username: arena
                  password: hunter2
                """);
        StatsSettings settings = StatsSettings.from(config);
        assertFalse(settings.enabled());
        assertEquals(StatsSettings.Storage.MYSQL, settings.storage());
        assertEquals("Winter 2026", settings.season());
        assertEquals(new StatsSettings.MySql("db.internal", 3307, "shared", "arena", "hunter2"), settings.mysql());
        assertFalse(settings.toString().contains("hunter2"), settings.toString());
    }

    @Test
    void invalidValuesAreRejectedRatherThanClamped() throws Exception {
        for (String yaml : List.of("storage: postgres", "season: ''", "season: ' padded'", "season: 2026",
                "table-prefix: Bad-Prefix", "sqlite:\n  file: ../escape.db", "sqlite:\n  file: '..'",
                "mysql:\n  port: 0", "mysql:\n  host: 'bad host'", "mysql:\n  database: 'no-dash'",
                "mysql:\n  username: ''")) {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(yaml);
            var failure = assertThrows(IllegalArgumentException.class, () -> StatsSettings.from(config), yaml);
            assertTrue(failure.getMessage().startsWith("stats."), failure.getMessage());
        }
    }

    @Test
    void problemsNameWhatIsExpected() {
        assertNull(StatsSettings.problem("stats.storage", "mysql"));
        assertEquals("expected sqlite or mysql", StatsSettings.problem("stats.storage", "MySQL"));
        assertNull(StatsSettings.problem("stats.season", "S".repeat(64)));
        assertTrue(StatsSettings.problem("stats.season", "S".repeat(65)).startsWith("expected 1 to 64"));
        assertTrue(StatsSettings.problem("stats.season", "tab\there").startsWith("expected 1 to 64"));
        assertNull(StatsSettings.problem("stats.table-prefix", ""));
        assertNull(StatsSettings.problem("stats.mysql.host", "::1"));
        assertNull(StatsSettings.problem("stats.mysql.password", ""));
        assertThrows(IllegalStateException.class, () -> StatsSettings.problem("stats.unknown", "x"));
    }
}
