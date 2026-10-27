package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortalRegistryTest {

    @TempDir
    Path dir;

    private PortalRegistry open() throws IOException {
        PortalRegistry registry = new PortalRegistry(new PortalStore(dir.resolve("portals.yml")));
        registry.load();
        return registry;
    }

    @Test
    void createdPortalsAreFoundByBlockAndSurviveARestart() throws Exception {
        PortalRegistry registry = open();
        Portal lobby = registry.create("Lobby", "world", new ArenaBounds(12, 67, 10, 10, 64, 10), "Dojo");

        assertEquals(new Portal("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo"), lobby);
        PortalRegistry reopened = open();
        assertEquals(List.of(lobby), List.copyOf(reopened.all()));
        assertEquals(lobby, reopened.at("world", 11, 65, 10));
        assertEquals(lobby, reopened.at("world", 10, 64, 10));
        assertEquals(lobby, reopened.at("world", 12, 67, 10));
        assertNull(reopened.at("world", 13, 65, 10));
        assertNull(reopened.at("world", 11, 68, 10));
        assertNull(reopened.at("world_nether", 11, 65, 10));
    }

    @Test
    void indexCoversEveryChunkARegionTouchesIncludingNegativeOnes() throws Exception {
        PortalRegistry registry = open();
        Portal edge = registry.create("edge", "world", new ArenaBounds(-2, 64, -2, 1, 66, 1), "dojo");

        for (int x = -2; x <= 1; x++) {
            for (int z = -2; z <= 1; z++) {
                assertEquals(edge, registry.at("world", x, 65, z), x + ", " + z);
            }
        }
        assertNull(registry.at("world", -3, 65, 0));
        assertNull(registry.at("world", 0, 65, 2));
        assertEquals(4, new PortalIndex(registry.all()).chunkEntries());
    }

    @Test
    void indexHoldsOnlyTheChunksARegionTouches() {
        PortalIndex index = new PortalIndex(List.of(
                new Portal("a", "world", new ArenaBounds(0, 64, 0, 3, 66, 0), "dojo"),
                new Portal("b", "world", new ArenaBounds(4, 64, 0, 6, 66, 0), "dojo"),
                new Portal("c", "world", new ArenaBounds(1000, 64, 1000, 1001, 66, 1000), "dojo")));
        assertEquals(2, index.chunkEntries());
        assertEquals("b", index.at("world", 5, 65, 0).name());
        assertEquals("c", index.at("world", 1001, 66, 1000).name());
    }

    @Test
    void overlappingNamesAndOversizedRegionsAreRefused() throws Exception {
        PortalRegistry registry = open();
        registry.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");

        assertEquals("That region overlaps portal lobby.", assertThrows(ArenaException.class,
                () -> registry.create("other", "world", new ArenaBounds(12, 60, 10, 20, 64, 10), "dojo")).getMessage());
        assertEquals("A portal called lobby already exists.", assertThrows(ArenaException.class,
                () -> registry.create("LOBBY", "world", new ArenaBounds(0, 0, 0, 0, 0, 0), "dojo")).getMessage());
        assertEquals("Portals cannot be in the arena world.", assertThrows(ArenaException.class,
                () -> registry.create("x", "ninjaarena_arenas", new ArenaBounds(0, 0, 0, 0, 0, 0), "dojo"))
                .getMessage());
        assertThrows(ArenaException.class,
                () -> registry.create("big", "world", new ArenaBounds(0, 0, 0, 128, 0, 0), "dojo"));
        assertThrows(ArenaException.class,
                () -> registry.create("bad name", "world", new ArenaBounds(0, 0, 0, 0, 0, 0), "dojo"));

        // The same blocks in another world are a different region.
        registry.create("nether", "world_nether", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
        registry.create("wide", "world", new ArenaBounds(0, 0, 0, 127, 0, 0), "dojo");
        assertEquals(3, registry.all().size());
    }

    @Test
    void deleteRemovesThePortalFromTheIndex() throws Exception {
        PortalRegistry registry = open();
        registry.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
        registry.delete("LOBBY");

        assertNull(registry.at("world", 11, 65, 10));
        assertTrue(open().all().isEmpty());
        assertEquals("No portal called lobby.",
                assertThrows(ArenaException.class, () -> registry.delete("lobby")).getMessage());
    }

    @Test
    void anUnreadableFileIsNeverOverwritten() throws Exception {
        Path file = dir.resolve("portals.yml");
        Files.writeString(file, "format: 1\nportals:\n  lobby: 5\n");
        PortalRegistry registry = new PortalRegistry(new PortalStore(file));

        assertThrows(IOException.class, registry::load);
        assertTrue(registry.all().isEmpty());
        assertThrows(ArenaException.class,
                () -> registry.create("other", "world", new ArenaBounds(0, 0, 0, 0, 0, 0), "dojo"));
        assertEquals("format: 1\nportals:\n  lobby: 5\n", Files.readString(file));
    }
}
