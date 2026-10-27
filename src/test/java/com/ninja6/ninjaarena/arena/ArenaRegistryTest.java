package com.ninja6.ninjaarena.arena;

import static com.ninja6.ninjaarena.arena.Arenas.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaRegistryTest {

    @TempDir
    Path dir;

    private final List<Boolean> saving = new ArrayList<>();
    private final List<String> tickets = new ArrayList<>();
    private ArenaRegistry registry;

    private final ChunkTickets recordingTickets = new ChunkTickets() {
        @Override
        public void add(int chunkX, int chunkZ) {
            tickets.add("+" + chunkX + "," + chunkZ);
        }

        @Override
        public void remove(int chunkX, int chunkZ) {
            tickets.add("-" + chunkX + "," + chunkZ);
        }
    };

    @BeforeEach
    void open() throws IOException {
        registry = reopen();
    }

    private ArenaRegistry reopen() throws IOException {
        ArenaRegistry opened = new ArenaRegistry(new ArenaStore(dir.resolve("arenas.yml")), Arenas.GRID,
                saving::add, recordingTickets);
        opened.load();
        return opened;
    }

    /** Builds the complete test arena through the registry and leaves edit mode. */
    private void buildComplete(String name) throws ArenaException {
        Arena target = Arenas.complete(name);
        registry.create(name, target.cell());
        registry.update(name, a -> a.withBounds(target.bounds())
                .withWaitingRoom(target.waitingRoom())
                .withTeamSpawn(1, target.teamSpawns().get(0))
                .withTeamSpawn(2, target.teamSpawns().get(1))
                .withEnemySpawn(target.enemySpawns().get(0))
                .withEnemySpawn(target.enemySpawns().get(1)));
        assertEquals(List.of(), registry.finishEdit(name));
    }

    @Test
    void createPicksTheNearestFreeCellAndStartsEditing() throws Exception {
        Arena first = registry.create("Dojo", null);
        Arena second = registry.create("temple", null);

        assertEquals("dojo", first.name());
        assertEquals(new GridCell(0, 0), first.cell());
        assertEquals(Arenas.GRID.cellsNearestFirst().get(1), second.cell());
        assertTrue(registry.isEditing("dojo"));
        assertTrue(registry.isEditing("TEMPLE"));
        // Saving goes on for the first edit only.
        assertEquals(List.of(true), saving);
    }

    @Test
    void createRefusesDuplicatesAndBadCells() throws Exception {
        registry.create("dojo", new GridCell(1, 1));
        assertThrows(ArenaException.class, () -> registry.create("DOJO", null));
        assertThrows(ArenaException.class, () -> registry.create("other", new GridCell(1, 1)));
        assertThrows(ArenaException.class, () -> registry.create("far", new GridCell(4, 0)));
        assertThrows(ArenaException.class, () -> registry.create("bad name", null));
    }

    @Test
    void changesPersistAcrossARestart() throws Exception {
        buildComplete("dojo");
        registry.create("bare", new GridCell(2, 2));

        ArenaRegistry restarted = reopen();
        assertEquals(registry.get("dojo"), restarted.get("dojo"));
        assertEquals(registry.get("bare"), restarted.get("bare"));
        // Edit mode is not stored.
        assertFalse(restarted.isEditing("bare"));
    }

    @Test
    void changesNeedEditMode() throws Exception {
        buildComplete("dojo");
        ArenaException refused = assertThrows(ArenaException.class,
                () -> registry.update("dojo", a -> a.withEnemySpawn(point(20, 64, 20))));
        assertTrue(refused.getMessage().contains("not in edit mode"), refused.getMessage());
    }

    @Test
    void refusedChangeLeavesTheArenaAsItWas() throws Exception {
        registry.create("dojo", null);
        ArenaException refused = assertThrows(ArenaException.class,
                () -> registry.update("dojo", a -> a.withTeamSpawn(3, point(1, 1, 1))));
        assertEquals("Team must be 1 to 1.", refused.getMessage());
        assertEquals(List.of(), registry.get("dojo").orElseThrow().teamSpawns());
    }

    @Test
    void editModeTogglesWorldSavingAndAvailability() throws Exception {
        buildComplete("dojo");
        assertTrue(registry.isAvailable("dojo"));
        assertEquals(List.of(true, false), saving);

        registry.startEdit("dojo");
        assertFalse(registry.isAvailable("dojo"));
        assertThrows(ArenaException.class, () -> registry.startEdit("dojo"));

        registry.finishEdit("dojo");
        assertTrue(registry.isAvailable("dojo"));
        assertEquals(List.of(true, false, true, false), saving);
        assertThrows(ArenaException.class, () -> registry.finishEdit("dojo"));
    }

    @Test
    void savingStaysOnUntilTheLastEditFinishes() throws Exception {
        registry.create("a", null);
        registry.create("b", null);
        registry.finishEdit("a");
        assertEquals(List.of(true), saving);
        registry.finishEdit("b");
        assertEquals(List.of(true, false), saving);
    }

    @Test
    void incompleteArenaIsNotAvailable() throws Exception {
        registry.create("dojo", null);
        List<String> problems = registry.finishEdit("dojo");
        assertEquals(4, problems.size());
        assertFalse(registry.isAvailable("dojo"));
        assertThrows(ArenaException.class, () -> registry.hold("dojo"));
        assertEquals(List.of(), tickets);
    }

    @Test
    void holdTicketsEveryChunkOfTheBoundsAndReleaseRemovesThem() throws Exception {
        buildComplete("dojo");
        registry.hold("dojo");

        // Bounds 10..90 on x and z span chunks 0 to 5.
        assertEquals(36, tickets.size());
        assertTrue(tickets.contains("+0,0"));
        assertTrue(tickets.contains("+5,5"));
        assertTrue(registry.isHeld("dojo"));
        assertFalse(registry.isAvailable("dojo"));
        assertThrows(ArenaException.class, () -> registry.hold("dojo"));
        assertThrows(ArenaException.class, () -> registry.startEdit("dojo"));
        assertThrows(ArenaException.class, () -> registry.delete("dojo"));

        tickets.clear();
        registry.release("dojo");
        assertEquals(36, tickets.size());
        assertTrue(tickets.stream().allMatch(t -> t.startsWith("-")));
        assertTrue(registry.isAvailable("dojo"));

        tickets.clear();
        registry.release("dojo");
        assertEquals(List.of(), tickets);
    }

    @Test
    void closeReleasesTicketsAndEndsEdits() throws Exception {
        buildComplete("dojo");
        registry.hold("dojo");
        registry.create("bare", null);
        tickets.clear();
        saving.clear();

        registry.close();

        assertEquals(36, tickets.size());
        assertFalse(registry.isHeld("dojo"));
        assertFalse(registry.isEditing("bare"));
        assertEquals(List.of(false), saving);
    }

    @Test
    void deleteRemovesTheArenaAndItsEdit() throws Exception {
        registry.create("dojo", null);
        registry.delete("dojo");
        assertTrue(registry.get("dojo").isEmpty());
        assertEquals(List.of(true, false), saving);
        assertTrue(reopen().all().isEmpty());
    }

    @Test
    void unreadableFileIsNeverOverwritten() throws Exception {
        Path file = dir.resolve("arenas.yml");
        Files.writeString(file, "format: [broken\n");
        ArenaRegistry broken = new ArenaRegistry(new ArenaStore(file), Arenas.GRID, saving::add, recordingTickets);
        assertThrows(IOException.class, broken::load);

        assertThrows(ArenaException.class, () -> broken.create("dojo", null));
        assertEquals("format: [broken\n", Files.readString(file));
    }
}
