package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.arena.ArenaStore;
import com.ninja6.ninjaarena.arena.ChunkTickets;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.arena.MemoryCopies;
import com.ninja6.ninjaarena.portal.PortalEntry.Reason;
import com.ninja6.ninjaarena.portal.PortalEntry.Refusal;
import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortalEntryTest {

    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");
    private static final Portal LOBBY = new Portal("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
    private static final ArenaPoint ROOM = new ArenaPoint(50.5, 64, 50.5, 0, 0);

    @TempDir
    Path dir;

    private ArenaRegistry arenas;
    private final Set<UUID> pending = new HashSet<>();
    private CaptureResult captureResult = CaptureResult.SAVED;
    private int captures;
    private PortalEntry entry;

    @BeforeEach
    void setUp() throws Exception {
        ChunkTickets noTickets = new ChunkTickets() {
            @Override
            public void add(int chunkX, int chunkZ) {
            }

            @Override
            public void remove(int chunkX, int chunkZ) {
            }
        };
        arenas = new ArenaRegistry(new ArenaStore(dir.resolve("arenas.yml")), new ArenaGrid(256, 2048, -64, 320),
                enabled -> { }, noTickets, new MemoryCopies());
        arenas.load();
        arenas.create("dojo", new GridCell(0, 0));
        arenas.update("dojo", a -> a.withBounds(new ArenaBounds(10, 60, 10, 90, 100, 90))
                .withWaitingRoom(ROOM)
                .withTeamSpawn(1, new ArenaPoint(20.5, 64, 20.5, 0, 0))
                .withTeamSpawn(2, new ArenaPoint(80.5, 64, 80.5, 0, 0))
                .withEnemySpawn(new ArenaPoint(50.5, 64, 20.5, 0, 0)));
        arenas.finishEdit("dojo");
        entry = new PortalEntry(arenas, pending::contains, player -> {
            captures++;
            return captureResult;
        });
    }

    private static Player player(boolean canPlay) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> canPlay && PortalEntry.PLAY.equals(args[0]);
                    case "getUniqueId" -> ALICE;
                    case "getName" -> "Alice";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Reason reason(Optional<Refusal> refusal) {
        return refusal.map(Refusal::reason).orElse(null);
    }

    @Test
    void anAvailableArenaAdmitsAfterTheSnapshotIsSaved() {
        assertEquals(Optional.empty(), entry.check(player(true), LOBBY));
        assertEquals(0, captures);
        assertEquals(Optional.empty(), entry.admit(player(true), LOBBY));
        assertEquals(1, captures);
        assertEquals(Optional.of(ROOM), entry.waitingRoom(LOBBY));
    }

    @Test
    void withoutThePlayNodeNothingIsSaved() {
        assertEquals(Reason.NO_PERMISSION, reason(entry.admit(player(false), LOBBY)));
        assertEquals(0, captures);
    }

    @Test
    void anArenaInEditModeOrMissingIsRefused() throws Exception {
        arenas.startEdit("dojo");
        Optional<Refusal> refusal = entry.admit(player(true), LOBBY);
        assertEquals(Reason.UNAVAILABLE, reason(refusal));
        assertTrue(refusal.get().message().contains("edited"), refusal.get().message());
        arenas.finishEdit("dojo");

        arenas.hold("dojo");
        assertEquals("A match is already running in arena dojo.", entry.check(player(true), LOBBY).get().message());
        arenas.release("dojo");

        arenas.delete("dojo");
        assertEquals(Reason.NO_ARENA, reason(entry.admit(player(true), LOBBY)));
        assertEquals(0, captures);
    }

    @Test
    void anyArenaInEditModeClosesEveryPortal() throws Exception {
        arenas.create("other", new GridCell(1, 0));
        assertEquals(Reason.UNAVAILABLE, reason(entry.check(player(true), LOBBY)));
    }

    @Test
    void aPendingSnapshotIsRefusedBeforeAndDuringCapture() {
        pending.add(ALICE);
        assertEquals(Reason.SNAPSHOT_PENDING, reason(entry.check(player(true), LOBBY)));
        assertEquals(0, captures);

        pending.clear();
        captureResult = CaptureResult.ALREADY_PENDING;
        assertEquals(Reason.SNAPSHOT_PENDING, reason(entry.admit(player(true), LOBBY)));
    }

    @Test
    void aFailedSnapshotWriteStopsTheEntry() {
        captureResult = CaptureResult.WRITE_FAILED;
        Optional<Refusal> refusal = entry.admit(player(true), LOBBY);
        assertEquals(Reason.SNAPSHOT_FAILED, reason(refusal));
        assertEquals("Your belongings could not be saved, so you were not sent to the arena.",
                refusal.get().message());
    }
}
