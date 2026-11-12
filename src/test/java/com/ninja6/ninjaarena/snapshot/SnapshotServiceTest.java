package com.ninja6.ninjaarena.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.LobbyWorld;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SnapshotServiceTest {

    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");

    @TempDir
    Path dir;

    private Logger logger;

    @BeforeEach
    void quietLogger() {
        logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
    }

    @Test
    void persistWritesAndReportsSaved() throws Exception {
        SnapshotService service = new SnapshotService(new SnapshotStore(dir), logger);
        PlayerSnapshot snapshot = Snapshots.sample(ALICE);

        assertEquals(CaptureResult.SAVED, service.persist(snapshot));
        assertTrue(service.hasPending(ALICE));
        assertEquals(List.of(ALICE), service.listPending());
        assertEquals(Optional.of(snapshot), new SnapshotService(new SnapshotStore(dir), logger).load(ALICE));
    }

    @ParameterizedTest
    @ValueSource(strings = {ArenaWorld.NAME, LobbyWorld.NAME})
    void noSnapshotIsTakenOrWrittenInEitherPluginWorld(String name) throws Exception {
        SnapshotService service = new SnapshotService(new SnapshotStore(dir), logger);
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLocation" -> new Location(world, 1.5, 64, 1.5);
                    case "getName" -> "Steve";
                    default -> throw new AssertionError("Captured " + method.getName());
                });

        assertEquals(CaptureResult.PLUGIN_WORLD, service.captureAndPersist(player));
        PlayerSnapshot sample = Snapshots.sample(ALICE);
        SnapshotLocation at = sample.location();
        PlayerSnapshot inWorld = new PlayerSnapshot(sample.playerId(), sample.playerName(), sample.capturedAt(),
                new SnapshotLocation(at.worldId(), name, at.x(), at.y(), at.z(), at.yaw(), at.pitch()),
                sample.storage(), sample.armour(), sample.offhand(), sample.level(), sample.exp(),
                sample.totalExperience(), sample.health(), sample.foodLevel(), sample.saturation(),
                sample.exhaustion(), sample.effects(), sample.gameMode(), sample.fireTicks(), sample.fallDistance());
        assertEquals(CaptureResult.PLUGIN_WORLD, service.persist(inWorld));
        assertFalse(service.hasPending(ALICE));
        assertTrue(service.listPending().isEmpty());
    }

    @Test
    void persistNeverOverwritesAnUnrestoredSnapshot() throws Exception {
        SnapshotService service = new SnapshotService(new SnapshotStore(dir), logger);
        service.persist(Snapshots.sample(ALICE, 1));

        assertEquals(CaptureResult.ALREADY_PENDING, service.persist(Snapshots.sample(ALICE, 2)));
        assertEquals(1, service.load(ALICE).orElseThrow().level());
    }

    @Test
    void persistReportsAWriteFailureSoEntryCanAbort() throws Exception {
        SnapshotStore failing = new SnapshotStore(dir, (source, target) -> {
            throw new IOException("read-only filesystem");
        });
        SnapshotService service = new SnapshotService(failing, logger);

        assertEquals(CaptureResult.WRITE_FAILED, service.persist(Snapshots.sample(ALICE)));
        assertFalse(service.hasPending(ALICE));
    }

    @Test
    void deleteClearsThePendingSnapshot() throws Exception {
        SnapshotService service = new SnapshotService(new SnapshotStore(dir), logger);
        service.persist(Snapshots.sample(ALICE, 1));

        assertTrue(service.delete(ALICE));
        assertFalse(service.hasPending(ALICE));
        assertEquals(CaptureResult.SAVED, service.persist(Snapshots.sample(ALICE, 2)));
    }
}
