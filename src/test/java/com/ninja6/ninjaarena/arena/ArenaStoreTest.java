package com.ninja6.ninjaarena.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class ArenaStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("arenas.yml");
    }

    @Test
    void missingFileIsAnEmptyRegistry() throws Exception {
        assertEquals(List.of(), new ArenaStore(file()).load());
    }

    @Test
    void arenasSurviveARoundTrip() throws Exception {
        Arena complete = Arenas.complete("dojo");
        Arena empty = Arena.create("bare", new GridCell(-1, 2));
        new ArenaStore(file()).save(List.of(complete, empty));

        assertEquals(List.of(complete, empty), new ArenaStore(file()).load());
        assertEquals(Set.of("arenas.yml"), fileNames());
    }

    @Test
    void failedMoveKeepsThePreviousFileAndCleansUp() throws Exception {
        new ArenaStore(file()).save(List.of(Arenas.complete("dojo")));
        String before = Files.readString(file(), StandardCharsets.UTF_8);

        ArenaStore failing = new ArenaStore(file(), (source, target) -> {
            throw new IOException("disk full");
        });
        assertThrows(IOException.class, () -> failing.save(List.of()));

        assertEquals(before, Files.readString(file(), StandardCharsets.UTF_8));
        assertEquals(Set.of("arenas.yml"), fileNames());
    }

    @Test
    void leftoverTempFilesAreRemovedOnLoad() throws Exception {
        Files.writeString(dir.resolve("arenas.yml-123.tmp"), "partial");
        Files.writeString(dir.resolve("other.tmp"), "not ours");
        new ArenaStore(file()).load();
        assertEquals(Set.of("other.tmp"), fileNames());
    }

    @Test
    void handEditedWholeNumbersAreAccepted() throws Exception {
        Files.writeString(file(), """
                format: 1
                arenas:
                  dojo:
                    cell: {x: 0, z: 0}
                    waiting-room: {x: 50, y: 64, z: 50, yaw: 0, pitch: 0}
                """);
        Arena arena = new ArenaStore(file()).load().get(0);
        assertEquals(new ArenaPoint(50, 64, 50, 0, 0), arena.waitingRoom());
        assertEquals(null, arena.bounds());
        assertEquals(List.of(), arena.teamSpawns());
    }

    @Test
    void malformedFilesAreRejected() throws Exception {
        assertRejected("format: 2\n");
        assertRejected("format: 1\narenas:\n  dojo:\n    cell: {x: 0}\n");
        assertRejected("format: 1\narenas:\n  Dojo:\n    cell: {x: 0, z: 0}\n");
        assertRejected("format: 1\narenas:\n  a:\n    cell: {x: 0, z: 0}\n  b:\n    cell: {x: 0, z: 0}\n");
        assertRejected("format: 1\narenas:\n  a:\n    cell: {x: 0, z: 0}\n    team-spawns: oops\n");
        assertRejected("format: [1\n");
    }

    @Test
    void spawnEntryThatIsNotAPositionFailsTheLoad() throws Exception {
        Files.writeString(file(), """
                format: 1
                arenas:
                  a:
                    cell: {x: 0, z: 0}
                    team-spawns: [5, {x: 1, y: 2, z: 3, yaw: 0, pitch: 0}]
                """);
        ArenaCodec.ArenaFormatException thrown = assertThrows(ArenaCodec.ArenaFormatException.class,
                () -> new ArenaStore(file()).load());
        assertEquals("arena a: team-spawns entry 1 is not a position", thrown.getMessage());

        Files.writeString(file(), """
                format: 1
                arenas:
                  a:
                    cell: {x: 0, z: 0}
                    enemy-spawns: [{x: 1, y: 2, z: 3, yaw: 0, pitch: 0}, {x: 1, y: 2, yaw: 0, pitch: 0}]
                """);
        thrown = assertThrows(ArenaCodec.ArenaFormatException.class, () -> new ArenaStore(file()).load());
        assertEquals("arena a: enemy-spawns entry 2: missing z", thrown.getMessage());
    }

    private void assertRejected(String text) throws IOException {
        Files.writeString(file(), text);
        assertThrows(ArenaCodec.ArenaFormatException.class, () -> new ArenaStore(file()).load(), text);
    }

    private Set<String> fileNames() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    @Test
    void writtenFileIsReadable() throws Exception {
        new ArenaStore(file()).save(List.of(Arenas.complete("dojo")));
        String text = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(text.contains("format: 1"), text);
        assertTrue(text.contains("min-x: 10"), text);
    }
}
