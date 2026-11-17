package com.ninja6.ninjaarena.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DefaultMapTest {
    @Test
    void missingSettingUsesGeneratedAndKnownValuesIgnoreCaseAndWhitespace() {
        var config = new YamlConfiguration();
        var warnings = new ArrayList<String>();
        assertEquals(DefaultMap.GENERATED, DefaultMap.read(config, warnings::add));
        config.set(DefaultMap.KEY, " ARENA ");
        assertEquals(DefaultMap.ARENA, DefaultMap.read(config, warnings::add));
        config.set(DefaultMap.KEY, " Generated ");
        assertEquals(DefaultMap.GENERATED, DefaultMap.read(config, warnings::add));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void unknownSettingWarnsOnceAndUsesGenerated() {
        var config = new YamlConfiguration();
        config.set(DefaultMap.KEY, "unknown");
        var warnings = new ArrayList<String>();
        assertEquals(DefaultMap.GENERATED, DefaultMap.read(config, warnings::add));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains(DefaultMap.KEY));
        assertTrue(warnings.getFirst().contains("using generated"));
    }
}
