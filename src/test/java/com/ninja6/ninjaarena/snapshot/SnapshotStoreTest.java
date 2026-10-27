package com.ninja6.ninjaarena.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotStoreTest {

    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("b0b00000-0000-4000-8000-000000000002");

    @TempDir
    Path dir;

    @Test
    void savedSnapshotSurvivesReopeningTheStore() throws Exception {
        PlayerSnapshot snapshot = Snapshots.sample(ALICE);
        new SnapshotStore(dir).save(snapshot);

        SnapshotStore reopened = new SnapshotStore(dir);

        assertEquals(Optional.of(snapshot), reopened.load(ALICE));
        assertEquals(List.of(ALICE), reopened.listPending());
    }

    @Test
    void writesOneFilePerPlayerAndNoTempFiles() throws Exception {
        SnapshotStore store = new SnapshotStore(dir);
        store.save(Snapshots.sample(ALICE));
        store.save(Snapshots.sample(BOB));

        assertEquals(Set.of(ALICE + ".yml", BOB + ".yml"), fileNames());
        assertEquals(Set.of(ALICE, BOB), Set.copyOf(store.listPending()));
    }

    @Test
    void saveReplacesTheEarlierSnapshot() throws Exception {
        SnapshotStore store = new SnapshotStore(dir);
        store.save(Snapshots.sample(ALICE, 1));
        store.save(Snapshots.sample(ALICE, 2));

        assertEquals(2, store.load(ALICE).orElseThrow().level());
        assertEquals(Set.of(ALICE + ".yml"), fileNames());
    }

    @Test
    void failedMoveKeepsThePreviousSnapshotAndCleansUp() throws Exception {
        PlayerSnapshot previous = Snapshots.sample(ALICE, 1);
        new SnapshotStore(dir).save(previous);

        SnapshotStore failing = new SnapshotStore(dir, (source, target) -> {
            throw new IOException("disk full");
        });

        IOException thrown = assertThrows(IOException.class,
                () -> failing.save(Snapshots.sample(ALICE, 2)));
        assertEquals("disk full", thrown.getMessage());
        assertEquals(Optional.of(previous), failing.load(ALICE));
        assertEquals(Set.of(ALICE + ".yml"), fileNames());
    }

    @Test
    void failedFirstSaveLeavesNothingBehind() throws Exception {
        SnapshotStore failing = new SnapshotStore(dir, (source, target) -> {
            throw new IOException("disk full");
        });

        assertThrows(IOException.class, () -> failing.save(Snapshots.sample(ALICE)));
        assertFalse(failing.exists(ALICE));
        assertEquals(Set.of(), fileNames());
    }

    @Test
    void openingRemovesTempFilesFromAnInterruptedSave() throws Exception {
        Files.writeString(dir.resolve(ALICE + "-123.tmp"), "half a snapshot");
        Files.writeString(dir.resolve("notes.txt"), "keep me");

        SnapshotStore store = new SnapshotStore(dir);

        assertEquals(Set.of("notes.txt"), fileNames());
        assertEquals(List.of(), store.listPending());
    }

    @Test
    void listPendingIgnoresFilesThatAreNotSnapshots() throws Exception {
        SnapshotStore store = new SnapshotStore(dir);
        store.save(Snapshots.sample(ALICE));
        Files.writeString(dir.resolve("config.yml"), "a: 1");

        assertEquals(List.of(ALICE), store.listPending());
    }

    @Test
    void deleteRemovesOnlyThatPlayer() throws Exception {
        SnapshotStore store = new SnapshotStore(dir);
        store.save(Snapshots.sample(ALICE));
        store.save(Snapshots.sample(BOB));

        assertTrue(store.delete(ALICE));
        assertFalse(store.delete(ALICE));
        assertEquals(Optional.empty(), store.load(ALICE));
        assertEquals(List.of(BOB), store.listPending());
    }

    @Test
    void loadOfAnUnknownPlayerIsEmpty() throws Exception {
        assertEquals(Optional.empty(), new SnapshotStore(dir).load(ALICE));
    }

    @Test
    void loadRejectsAFileHoldingAnotherPlayersSnapshot() throws Exception {
        Files.writeString(dir.resolve(ALICE + ".yml"),
                SnapshotCodec.encode(Snapshots.sample(BOB)), StandardCharsets.UTF_8);

        assertThrows(SnapshotCodec.SnapshotFormatException.class,
                () -> new SnapshotStore(dir).load(ALICE));
    }

    @Test
    void createsTheDirectoryIfMissing() throws Exception {
        Path nested = dir.resolve("plugins/NinjaArena/snapshots");
        new SnapshotStore(nested).save(Snapshots.sample(ALICE));

        assertTrue(Files.isRegularFile(nested.resolve(ALICE + ".yml")));
    }

    private Set<String> fileNames() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return Set.copyOf(files.map(path -> path.getFileName().toString()).toList());
        }
    }
}
