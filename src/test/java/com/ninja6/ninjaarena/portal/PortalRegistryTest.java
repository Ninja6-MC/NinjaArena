package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    void theHubAndEachPortalsLobbySurviveARestartAndOtherChanges() throws Exception {
        PortalRegistry registry = open();
        ArenaPoint hub = new ArenaPoint(-300.5, 64, -300.5, 90, 10);
        registry.setHub(hub);
        registry.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
        Portal shared = registry.setLobby("lobby", LobbyMode.SHARED);
        registry.create("watch", "world", new ArenaBounds(20, 64, 10, 22, 67, 10), "dojo", true);
        assertThrows(ArenaException.class, () -> registry.setLobby("watch", LobbyMode.SHARED));
        assertThrows(ArenaException.class, () -> registry.setLobby("nowhere", LobbyMode.SHARED));

        PortalRegistry reopened = open();
        assertEquals(Optional.of(hub), reopened.hub());
        assertEquals(shared, reopened.get("lobby").orElseThrow());
        assertEquals(LobbyMode.SHARED, shared.lobby());

        reopened.setLobby("lobby", null);
        reopened.setHub(null);
        PortalRegistry cleared = open();
        assertEquals(Optional.empty(), cleared.hub());
        assertNull(cleared.get("lobby").orElseThrow().lobby());
        assertEquals(LobbyMode.DEDICATED, cleared.get("lobby").orElseThrow().lobby(LobbyMode.DEDICATED));
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
    void playerBuiltPortalsAreNumberedCountedAndSurviveARestart() throws Exception {
        UUID alice = new UUID(0, 1);
        UUID bob = new UUID(0, 2);
        PortalRegistry registry = open();
        registry.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
        Portal first = registry.createBuilt("world", new ArenaBounds(40, 64, 8, 41, 66, 8),
                new Portal.Built(alice, "crying_obsidian"));
        Portal second = registry.createBuilt("world_nether", new ArenaBounds(0, 40, 0, 0, 42, 1),
                new Portal.Built(alice, "crying_obsidian"));
        Portal third = registry.createBuilt("world", new ArenaBounds(80, 64, 8, 81, 66, 8),
                new Portal.Built(bob, "gilded_blackstone"));
        assertEquals(List.of("built-1", "built-2", "built-3"), List.of(first.name(), second.name(), third.name()));
        assertEquals(2, registry.ownedBy(alice));
        assertEquals(1, registry.ownedBy(bob));
        assertThrows(ArenaException.class, () -> registry.createBuilt("world", new ArenaBounds(11, 64, 10, 11, 66, 10),
                new Portal.Built(bob, "crying_obsidian")), "overlaps the admin portal");
        assertThrows(ArenaException.class, () -> registry.createBuilt(ArenaWorld.NAME,
                new ArenaBounds(0, 64, 0, 1, 66, 0), new Portal.Built(bob, "crying_obsidian")));
        assertThrows(ArenaException.class, () -> registry.setLobby("built-1", LobbyMode.SHARED));

        PortalRegistry reopened = open();
        assertEquals(first, reopened.get("built-1").orElseThrow());
        assertEquals(third, reopened.at("world", 81, 66, 8));
        assertEquals(new Portal.Built(bob, "gilded_blackstone"), reopened.get("built-3").orElseThrow().built());
        assertEquals(2, reopened.ownedBy(alice));

        List<Portal> removed = new ArrayList<>();
        reopened.onRemoved(removed::add);
        reopened.delete("built-1");
        assertEquals(List.of(first), removed);
        // The lowest free number is used again.
        assertEquals("built-1", reopened.createBuilt("world", new ArenaBounds(40, 64, 8, 41, 66, 8),
                new Portal.Built(bob, "crying_obsidian")).name());
    }

    @Test
    void aPlayerBuiltPortalTakesNoLobbyChoiceNotEvenTheDefault() throws Exception {
        PortalRegistry registry = open();
        Portal built = registry.createBuilt("world", new ArenaBounds(40, 64, 8, 41, 66, 8),
                new Portal.Built(new UUID(0, 1), "crying_obsidian"));
        Path file = dir.resolve("portals.yml");
        String before = Files.readString(file);
        for (LobbyMode lobby : new LobbyMode[] {null, LobbyMode.DEDICATED, LobbyMode.SHARED}) {
            assertEquals("Portal built-1 is player-built and always follows waiting-room.lobby in config.yml.",
                    assertThrows(ArenaException.class, () -> registry.setLobby("built-1", lobby)).getMessage());
        }
        assertEquals(built, registry.get("built-1").orElseThrow());
        assertEquals(before, Files.readString(file));
    }

    @Test
    void nearHandsOverThePortalsInTheChunksAround() throws Exception {
        PortalRegistry registry = open();
        Portal home = registry.createBuilt("world", new ArenaBounds(40, 64, 8, 41, 66, 8),
                new Portal.Built(new UUID(0, 1), "crying_obsidian"));
        registry.createBuilt("world", new ArenaBounds(400, 64, 8, 401, 66, 8),
                new Portal.Built(new UUID(0, 1), "crying_obsidian"));
        Set<Portal> found = new HashSet<>();
        registry.near("world", 0, 0, 2, found::add);
        assertEquals(Set.of(home), found);
        found.clear();
        registry.near("world", 5, 0, 2, found::add);
        assertTrue(found.isEmpty());
        registry.near("world_nether", 2, 0, 2, found::add);
        assertTrue(found.isEmpty());
    }

    @Test
    void aPlayerBuiltPortalReadsBackAndAMixedEntryIsRefused() throws Exception {
        Portal built = Portal.built("built-1", "world", new ArenaBounds(40, 64, 8, 41, 66, 8),
                new Portal.Built(new UUID(0, 1), "crying_obsidian"));
        String text = PortalCodec.encode(List.of(built));
        assertTrue(text.contains("built-by: 00000000-0000-0000-0000-000000000001"), text);
        assertTrue(!text.contains("arena:"), text);
        assertEquals(List.of(built), PortalCodec.decode(text));

        for (String extra : List.of("    arena: dojo\n", "    lobby: shared\n", "    spectator: false\n")) {
            String mixed = text.replace("    frame:", extra + "    frame:");
            assertThrows(PortalCodec.PortalFormatException.class, () -> PortalCodec.decode(mixed), extra);
        }
        assertThrows(PortalCodec.PortalFormatException.class,
                () -> PortalCodec.decode(text.replace("00000000-0000-0000-0000-000000000001", "alice")));
        assertThrows(PortalCodec.PortalFormatException.class,
                () -> PortalCodec.decode(text.replace("crying_obsidian", "Crying Obsidian")));
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
