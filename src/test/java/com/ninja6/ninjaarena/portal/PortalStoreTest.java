package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.portal.PortalCodec.PortalFormatException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortalStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("portals.yml");
    }

    static Portal portal(String name, int x) {
        return new Portal(name, "world", new ArenaBounds(x, 64, 0, x + 2, 67, 0), "dojo");
    }

    @Test
    void missingFileMeansNoPortals() throws Exception {
        assertEquals(List.of(), new PortalStore(file()).load());
    }

    @Test
    void portalsSurviveARoundTrip() throws Exception {
        List<Portal> portals = List.of(portal("lobby", 10),
                new Portal("nether", "world_nether", new ArenaBounds(-5, 30, -5, -3, 33, -5), "pit", true));
        new PortalStore(file()).save(portals);

        assertEquals(portals, new PortalStore(file()).load());
        assertFalse(Files.readString(file()).contains("spectator: false"));
        assertEquals(Set.of("portals.yml"), fileNames());
    }

    @Test
    void theHubAndLobbyChoicesSurviveARoundTripAndOlderFilesStillRead() throws Exception {
        ArenaPoint hub = new ArenaPoint(-300.5, 64, -300.5, 90, 10);
        List<Portal> portals = List.of(portal("lobby", 10).withLobby(LobbyMode.SHARED),
                portal("door", 20).withLobby(LobbyMode.DEDICATED), portal("plain", 30));
        new PortalStore(file()).save(portals, hub);
        PortalCodec.Contents read = new PortalStore(file()).loadAll();
        assertEquals(portals, read.portals());
        assertEquals(hub, read.hub());
        assertFalse(Files.readString(file()).contains("lobby: null"));

        Files.writeString(file(), """
                format: 1
                portals:
                  lobby:
                    world: world
                    arena: dojo
                    region: {min-x: 10, min-y: 64, min-z: 10, max-x: 12, max-y: 67, max-z: 10}
                """);
        PortalCodec.Contents old = new PortalStore(file()).loadAll();
        assertEquals(null, old.hub());
        assertEquals(null, old.portals().getFirst().lobby());
    }

    @Test
    void failedMoveKeepsThePreviousFileAndCleansUp() throws Exception {
        new PortalStore(file()).save(List.of(portal("lobby", 10)));
        String before = Files.readString(file(), StandardCharsets.UTF_8);

        PortalStore failing = new PortalStore(file(), (source, target) -> {
            throw new IOException("disk full");
        });
        assertThrows(IOException.class, () -> failing.save(List.of()));

        assertEquals(before, Files.readString(file(), StandardCharsets.UTF_8));
        assertEquals(Set.of("portals.yml"), fileNames());
    }

    @Test
    void leftoverTempFilesAreRemovedOnLoad() throws Exception {
        Files.writeString(dir.resolve("portals.yml-123.tmp"), "partial");
        new PortalStore(file()).load();
        assertEquals(Set.of(), fileNames());
    }

    @Test
    void aBadEntryFailsTheLoadNamingThePortal() throws Exception {
        assertRejected("format: 2\n", "unsupported format 2");
        assertRejected("portals: {}\n", "missing format");
        assertRejected("format: 1\nportals: 5\n", "portals is not a section");
        assertRejected("format: 1\nportals:\n  lobby: 5\n", "portal lobby is not a section");
        assertRejected("""
                format: 1
                portals:
                  ok:
                    world: world
                    arena: dojo
                    region: {min-x: 0, min-y: 64, min-z: 0, max-x: 1, max-y: 65, max-z: 0}
                  lobby:
                    world: world
                    arena: dojo
                    region: {min-x: 10, min-y: 64, min-z: 10, max-x: 12, max-y: 67}
                """, "portal lobby: missing region.max-z");
        assertRejected("""
                format: 1
                portals:
                  lobby:
                    arena: dojo
                    region: {min-x: 10, min-y: 64, min-z: 10, max-x: 12, max-y: 67, max-z: 10}
                """, "portal lobby: missing world");
        assertRejected("""
                format: 1
                portals:
                  Lobby:
                    world: world
                    arena: dojo
                    region: {min-x: 10, min-y: 64, min-z: 10, max-x: 12, max-y: 67, max-z: 10}
                """, "portal Lobby: invalid portal name Lobby");
        assertRejected("""
                format: 1
                portals:
                  lobby:
                    world: ninjaarena_arenas
                    arena: dojo
                    region: {min-x: 10, min-y: 64, min-z: 10, max-x: 12, max-y: 67, max-z: 10}
                """, "portal lobby: portals cannot be in the arena world");
        assertRejected("""
                format: 1
                portals:
                  lobby:
                    world: world
                    arena: dojo
                    region: {min-x: 0, min-y: 64, min-z: 0, max-x: 500, max-y: 67, max-z: 0}
                """, "portal lobby: a portal can be at most 128 blocks along each axis");
        assertRejected("""
                format: 1
                portals:
                  lobby:
                    world: world
                    arena: dojo
                    lobby: everywhere
                    region: {min-x: 10, min-y: 64, min-z: 10, max-x: 12, max-y: 67, max-z: 10}
                """, "portal lobby: lobby must be dedicated or shared");
        assertRejected("""
                format: 1
                portals:
                  lobby:
                    world: world
                    arena: dojo
                    spectator: true
                    lobby: shared
                    region: {min-x: 10, min-y: 64, min-z: 10, max-x: 12, max-y: 67, max-z: 10}
                """, "portal lobby: a spectator portal leads to a live match and has no lobby");
        assertRejected("""
                format: 1
                hub: {x: 1.5, y: 64, z: 2.5, yaw: 0}
                """, "hub: missing pitch");
        assertRejected("""
                format: 1
                portals:
                  a:
                    world: world
                    arena: dojo
                    region: {min-x: 0, min-y: 64, min-z: 0, max-x: 4, max-y: 67, max-z: 0}
                  b:
                    world: world
                    arena: dojo
                    region: {min-x: 4, min-y: 67, min-z: 0, max-x: 6, max-y: 70, max-z: 0}
                """, "portal b overlaps portal a");
    }

    private void assertRejected(String text, String message) throws IOException {
        Files.writeString(file(), text);
        PortalFormatException thrown = assertThrows(PortalFormatException.class, () -> new PortalStore(file()).load(),
                text);
        assertEquals(message, thrown.getMessage());
    }

    private Set<String> fileNames() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }
}
