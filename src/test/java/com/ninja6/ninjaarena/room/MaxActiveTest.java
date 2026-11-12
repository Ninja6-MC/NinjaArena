package com.ninja6.ninjaarena.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.config.ReloadFiles;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MaxActiveTest {

    private final List<String> warnings = new ArrayList<>();

    /** As at startup: the server's file over the bundled defaults. */
    private YamlConfiguration config(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        config.setDefaults(ReloadFiles.defaults());
        return config;
    }

    @Test
    void theBundledDefaultIsFourUnderTheNewName() throws Exception {
        assertEquals(4, ReloadFiles.defaults().getInt(MaxActive.KEY));
        assertFalse(ReloadFiles.defaults().contains(MaxActive.OLD_KEY));
        assertEquals(4, MaxActive.read(config(""), warnings::add));
        assertEquals(List.of(), warnings);
    }

    @Test
    void readsTheNewName() throws Exception {
        assertEquals(2, MaxActive.read(config("arenas:\n  max-active: 2\n"), warnings::add));
        assertEquals(List.of(), warnings);
    }

    @Test
    void theOldNameStillWorksWithAWarning() throws Exception {
        assertEquals(3, MaxActive.read(config("stability:\n  max-concurrent-matches: 3\n"), warnings::add));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("stability.max-concurrent-matches has been renamed arenas.max-active"));
    }

    @Test
    void theOldNameBesideTheNewOneIsIgnoredWithAWarning() throws Exception {
        assertEquals(6, MaxActive.read(config("arenas:\n  max-active: 6\nstability:\n  max-concurrent-matches: 3\n"),
                warnings::add));
        assertTrue(warnings.getFirst().contains("ignored"), warnings.toString());
    }

    @Test
    void outOfRangeValuesAreClamped() throws Exception {
        assertEquals(1, MaxActive.read(config("arenas:\n  max-active: 0\n"), warnings::add));
        assertEquals(100, MaxActive.read(config("arenas:\n  max-active: 500\n"), warnings::add));
    }
}
