package com.ninja6.ninjaarena.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.restore.RestoreService.RestoreException;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreServiceTest {

    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("b0b00000-0000-4000-8000-000000000002");

    @TempDir
    Path dir;

    private Logger logger;
    private SnapshotService snapshots;
    private final List<PlayerSnapshot> applied = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        snapshots = new SnapshotService(new SnapshotStore(dir), logger);
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "Alice";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PlayerSnapshot snapshot(UUID id) {
        return new PlayerSnapshot(id, "Alice", Instant.parse("2026-10-27T10:00:00Z"),
                new SnapshotLocation(UUID.fromString("7a1b2c3d-0000-4000-8000-000000000001"), "world",
                        0.5, 64, 0.5, 0, 0),
                new byte[] {1}, new byte[] {2}, new byte[] {3}, 5, 0.5f, 60, 20, 20, 5, 0,
                List.of(), GameMode.SURVIVAL, 0, 0);
    }

    private RestoreService service(RestoreService.Applier applier) {
        return new RestoreService(snapshots, applier, logger);
    }

    @Test
    void aSuccessfulRestoreAppliesThenDeletesTheSnapshot() throws Exception {
        snapshots.persist(snapshot(ALICE));
        RestoreService service = service((player, snapshot) -> {
            // The snapshot is still on disk while it is being applied.
            assertTrue(snapshots.hasPending(ALICE));
            applied.add(snapshot);
        });

        assertEquals(RestoreResult.RESTORED, service.restore(player(ALICE)));
        assertEquals(List.of(snapshot(ALICE)), applied);
        assertFalse(snapshots.hasPending(ALICE));
    }

    @Test
    void aFailedRestoreKeepsTheSnapshot() throws Exception {
        snapshots.persist(snapshot(ALICE));
        RestoreService service = service((player, snapshot) -> {
            throw new RestoreException("teleport cancelled");
        });

        assertEquals(RestoreResult.FAILED, service.restore(player(ALICE)));
        assertTrue(snapshots.hasPending(ALICE));
        assertTrue(service.shouldRestoreOnJoin(ALICE));
    }

    @Test
    void anUnexpectedErrorWhileApplyingKeepsTheSnapshot() throws Exception {
        snapshots.persist(snapshot(ALICE));
        RestoreService service = service((player, snapshot) -> {
            throw new IllegalStateException("inventory rejected");
        });

        assertEquals(RestoreResult.FAILED, service.restore(player(ALICE)));
        assertTrue(snapshots.hasPending(ALICE));
    }

    @Test
    void anUnreadableSnapshotIsNeitherAppliedNorDeleted() throws Exception {
        Files.writeString(dir.resolve(ALICE + ".yml"), "format: 1\n");
        RestoreService service = service((player, snapshot) -> applied.add(snapshot));

        assertEquals(RestoreResult.FAILED, service.restore(player(ALICE)));
        assertTrue(applied.isEmpty());
        assertTrue(Files.exists(dir.resolve(ALICE + ".yml")));
    }

    @Test
    void noSnapshotMeansNothingToDo() {
        RestoreService service = service((player, snapshot) -> applied.add(snapshot));

        assertEquals(RestoreResult.NO_SNAPSHOT, service.restore(player(ALICE)));
        assertTrue(applied.isEmpty());
    }

    @Test
    void startupMarksEverySnapshotLeftOnDisk() throws Exception {
        snapshots.persist(snapshot(ALICE));
        snapshots.persist(snapshot(BOB));
        RestoreService service = service((player, snapshot) -> applied.add(snapshot));

        service.markPendingOnStartup();

        assertEquals(Set.of(ALICE, BOB), service.awaitingJoin());
        assertEquals(RestoreResult.RESTORED, service.restore(player(ALICE)));
        assertEquals(Set.of(BOB), service.awaitingJoin());
        assertFalse(service.shouldRestoreOnJoin(ALICE));
        assertTrue(service.shouldRestoreOnJoin(BOB));
    }

    @Test
    void quittingWithAPendingSnapshotMarksThePlayer() throws Exception {
        RestoreService service = service((player, snapshot) -> applied.add(snapshot));
        service.markIfPending(ALICE);
        assertEquals(Set.of(), service.awaitingJoin());

        snapshots.persist(snapshot(ALICE));
        service.markIfPending(ALICE);
        assertEquals(Set.of(ALICE), service.awaitingJoin());
    }

    @Test
    void aSnapshotThatAppearedAfterStartupIsStillRestoredOnJoin() throws Exception {
        RestoreService service = service((player, snapshot) -> applied.add(snapshot));
        service.markPendingOnStartup();
        assertFalse(service.shouldRestoreOnJoin(ALICE));

        snapshots.persist(snapshot(ALICE));
        assertTrue(service.shouldRestoreOnJoin(ALICE));
    }
}
