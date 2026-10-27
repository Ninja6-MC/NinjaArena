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
    private final MemoryCopies copies = new MemoryCopies();
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
                saving::add, recordingTickets, copies);
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
    void startingAnEditOrDeletingNotifiesButCreatingDoesNot() throws Exception {
        List<String> notified = new ArrayList<>();
        registry.onEditOrDelete(notified::add);
        buildComplete("dojo");
        assertEquals(List.of(), notified);

        registry.startEdit("DOJO");
        assertEquals(List.of("dojo"), notified);
        registry.finishEdit("dojo");
        registry.delete("dojo");
        assertEquals(List.of("dojo", "dojo"), notified);

        // A refused edit or delete changes nothing, so it does not notify.
        assertThrows(ArenaException.class, () -> registry.delete("dojo"));
        assertEquals(2, notified.size());
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
    void noArenaCanBeHeldWhileAnyArenaIsInEditMode() throws Exception {
        buildComplete("dojo");
        registry.create("other", null);

        assertFalse(registry.isAvailable("dojo"));
        ArenaException refused = assertThrows(ArenaException.class, () -> registry.hold("dojo"));
        assertTrue(refused.getMessage().contains("other is in edit mode"), refused.getMessage());
        assertEquals(List.of(), tickets);

        registry.finishEdit("other");
        assertTrue(registry.isAvailable("dojo"));
        registry.hold("dojo");
        assertTrue(registry.isHeld("dojo"));
    }

    @Test
    void noEditCanStartWhileAnyArenaIsHeld() throws Exception {
        buildComplete("dojo");
        registry.create("temple", null);
        registry.finishEdit("temple");
        saving.clear();
        registry.hold("dojo");

        ArenaException refused = assertThrows(ArenaException.class, () -> registry.startEdit("temple"));
        assertTrue(refused.getMessage().contains("match is running in dojo"), refused.getMessage());
        assertThrows(ArenaException.class, () -> registry.create("bare", null));
        assertTrue(registry.get("bare").isEmpty());
        assertTrue(registry.editing().isEmpty());
        assertEquals(List.of(), saving);

        registry.release("dojo");
        registry.startEdit("temple");
        assertEquals(List.of(true), saving);
    }

    @Test
    void closeReleasesTicketsAndEndsEdits() throws Exception {
        buildComplete("dojo");
        registry.hold("dojo");
        tickets.clear();
        saving.clear();

        registry.close();

        assertEquals(36, tickets.size());
        assertFalse(registry.isHeld("dojo"));
        assertEquals(List.of(), saving);

        registry.create("bare", null);
        saving.clear();
        registry.close();
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
        ArenaRegistry broken = new ArenaRegistry(new ArenaStore(file), Arenas.GRID, saving::add, recordingTickets,
                copies);
        assertThrows(IOException.class, broken::load);

        assertThrows(ArenaException.class, () -> broken.create("dojo", null));
        assertEquals("format: [broken\n", Files.readString(file));
    }

    @Test
    void finishEditSavesTheCopyThatAvailabilityNeeds() throws Exception {
        buildComplete("dojo");
        assertEquals(Arenas.complete("dojo").bounds(), copies.saved.get("dojo"));
        assertTrue(registry.isAvailable("dojo"));

        copies.saved.clear();
        assertFalse(registry.isAvailable("dojo"));
    }

    @Test
    void finishEditWithoutBoundsTakesNoCopy() throws Exception {
        registry.create("bare", null);
        registry.finishEdit("bare");
        assertEquals(List.of("delete bare"), copies.calls);
    }

    @Test
    void failedCopyKeepsTheArenaInEditMode() throws Exception {
        registry.create("dojo", null);
        registry.update("dojo", a -> a.withBounds(Arenas.complete("dojo").bounds()));
        copies.failing = true;

        ArenaException refused = assertThrows(ArenaException.class, () -> registry.finishEdit("dojo"));
        assertTrue(refused.getMessage().contains("stays in edit mode"), refused.getMessage());
        assertTrue(registry.isEditing("dojo"));
        assertEquals(List.of(true), saving);
    }

    @Test
    void resetRestoresTheCopyEvenWhileHeld() throws Exception {
        buildComplete("dojo");
        registry.hold("dojo");
        copies.calls.clear();

        registry.reset("dojo");
        assertEquals(List.of("restore dojo"), copies.calls);
    }

    @Test
    void resetRefusesAnEditAndAMissingCopy() throws Exception {
        buildComplete("dojo");
        registry.startEdit("dojo");
        ArenaException editing = assertThrows(ArenaException.class, () -> registry.reset("dojo"));
        assertTrue(editing.getMessage().contains("undo the edit"), editing.getMessage());

        registry.finishEdit("dojo");
        copies.saved.clear();
        ArenaException missing = assertThrows(ArenaException.class, () -> registry.reset("dojo"));
        assertTrue(missing.getMessage().contains("no saved copy"), missing.getMessage());
        assertThrows(ArenaException.class, () -> registry.reset("nowhere"));
    }

    @Test
    void resetAllRestoresCopiesAndCopiesArenasThatHaveNone() throws Exception {
        buildComplete("dojo");
        registry.create("bare", new GridCell(2, 2));
        registry.finishEdit("bare");
        registry.create("temple", new GridCell(1, 1));
        registry.update("temple", a -> a.withBounds(new ArenaBounds(266, 60, 266, 300, 90, 300)));
        registry.finishEdit("temple");
        copies.saved.remove("temple");
        copies.calls.clear();

        ArenaRegistry.ResetReport report = reopen().resetAll();

        assertEquals(List.of("dojo"), report.reset());
        assertEquals(List.of("temple"), report.copied());
        assertEquals(List.of(), report.failed());
        // The arena with no bounds has nothing to copy or reset.
        assertEquals(List.of("restore dojo", "save temple"), copies.calls);
        assertTrue(copies.exists("temple"));
    }

    @Test
    void resetAllReportsFailuresAndCarriesOn() throws Exception {
        buildComplete("dojo");
        copies.failing = true;

        ArenaRegistry.ResetReport report = reopen().resetAll();
        assertEquals(List.of(), report.reset());
        assertEquals(1, report.failed().size());
        assertTrue(report.failed().get(0).contains("dojo"), report.failed().get(0));
    }

    @Test
    void aFailedResetKeepsTheArenaUnavailableUntilOneSucceeds() throws Exception {
        buildComplete("dojo");
        copies.failing = true;
        ArenaRegistry reopened = reopen();
        reopened.resetAll();
        assertFalse(reopened.isAvailable("dojo"));

        copies.failing = false;
        reopened.reset("dojo");
        assertTrue(reopened.isAvailable("dojo"));

        copies.failing = true;
        assertThrows(ArenaException.class, () -> reopened.reset("dojo"));
        assertFalse(reopened.isAvailable("dojo"));

        // A finished edit takes a fresh copy of the world as it is, so the arena is clean again.
        copies.failing = false;
        reopened.startEdit("dojo");
        reopened.finishEdit("dojo");
        assertTrue(reopened.isAvailable("dojo"));
    }

    @Test
    void deleteAndCreateDropTheOldCopy() throws Exception {
        buildComplete("dojo");
        registry.delete("dojo");
        assertFalse(copies.exists("dojo"));

        // A copy left behind, for example by a failed delete, never survives a new arena.
        copies.saved.put("dojo", Arenas.complete("dojo").bounds());
        registry.create("dojo", null);
        assertFalse(copies.exists("dojo"));
    }

    @Test
    void createRefusesWhenAStaleCopyCannotBeRemoved() throws Exception {
        copies.failing = true;
        assertThrows(ArenaException.class, () -> registry.create("dojo", null));
        assertTrue(registry.get("dojo").isEmpty());
    }

    @Test
    void closeSavesTheCopyOfAnOpenEdit() throws Exception {
        buildComplete("dojo");
        registry.startEdit("dojo");
        copies.calls.clear();

        assertEquals(List.of(), registry.close());
        assertEquals(List.of("save dojo"), copies.calls);

        registry.startEdit("dojo");
        copies.failing = true;
        List<String> failed = registry.close();
        assertEquals(1, failed.size());
        assertFalse(registry.isEditing("dojo"));
    }
}
