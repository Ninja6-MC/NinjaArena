package com.ninja6.ninjaarena.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.restore.RestoreService.RestoreException;
import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoinRestoreTest {

    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");

    @TempDir
    Path dir;

    private SnapshotService snapshots;
    private RestoreService restores;
    private JoinRestore join;
    private final List<Scheduled> queue = new ArrayList<>();
    private long now;

    /** The player's state as the fake server keeps it. */
    private final List<String> inventory = new ArrayList<>(List.of("arena kit"));
    private Location position = new Location(null, 100, 70, 100);
    private final List<String> messages = new ArrayList<>();
    private final List<Location> teleports = new ArrayList<>();
    private boolean online = true;
    private boolean busy;
    private int cancelledAttempts;
    private int attempts;
    private Player player;

    private record Scheduled(long at, Runnable task) { }

    @BeforeEach
    void setUp() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        snapshots = new SnapshotService(new SnapshotStore(dir), logger);
        snapshots.persist(new PlayerSnapshot(ALICE, "Alice", Instant.parse("2026-10-27T10:00:00Z"),
                new SnapshotLocation(UUID.fromString("7a1b2c3d-0000-4000-8000-000000000001"), "world",
                        0.5, 64, 0.5, 0, 0),
                new byte[] {1}, new byte[] {2}, new byte[] {3}, 5, 0.5f, 60, 20, 20, 5, 0,
                List.of(), GameMode.SURVIVAL, 0, 0));
        // A login plugin cancels the teleport of the first cancelledAttempts attempts.
        RestoreService.Applier applier = (p, snapshot) -> {
            attempts++;
            if (attempts <= cancelledAttempts) {
                throw new RestoreException("the teleport of Alice was cancelled");
            }
            position = new Location(null, 0.5, 64, 0.5);
            inventory.clear();
            inventory.add("home sword");
        };
        restores = new RestoreService(snapshots, applier, logger);
        join = new JoinRestore(restores, (ticks, task) -> queue.add(new Scheduled(now + ticks, task)),
                id -> online ? player : null, id -> busy);
        player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ALICE;
                    case "getName" -> "Alice";
                    case "isDead" -> false;
                    case "isOnline" -> online;
                    case "getLocation" -> position.clone();
                    case "teleport" -> {
                        Location to = (Location) args[0];
                        teleports.add(to);
                        position = to;
                        yield true;
                    }
                    case "sendMessage" -> {
                        messages.add(String.valueOf(args[0]));
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Runs every task due within the given number of ticks, in time order. */
    private void advance(long ticks) {
        long end = now + ticks;
        while (true) {
            Scheduled next = queue.stream().filter(s -> s.at() <= end)
                    .min((a, b) -> Long.compare(a.at(), b.at())).orElse(null);
            if (next == null) {
                break;
            }
            queue.remove(next);
            now = Math.max(now, next.at());
            next.task().run();
        }
        now = end;
    }

    @Test
    void aCancelledRestoreIsRetriedAndThenSucceeds() {
        cancelledAttempts = 2;
        join.onJoin(ALICE);
        advance(1);
        assertEquals(1, attempts);
        assertTrue(snapshots.hasPending(ALICE), "the snapshot stays while the restore is cancelled");
        assertEquals(List.of("arena kit"), inventory);

        advance(20 + 100);
        assertEquals(3, attempts);
        assertFalse(snapshots.hasPending(ALICE));
        assertEquals(List.of("home sword"), inventory);
        assertTrue(messages.isEmpty());

        advance(5000);
        assertEquals(3, attempts, "nothing retries once the restore has completed");
    }

    @Test
    void retriesAreBoundedAndThePlayerIsToldOnce() {
        cancelledAttempts = Integer.MAX_VALUE;
        join.onJoin(ALICE);
        advance(100_000);
        assertEquals(JoinRestore.RETRY_DELAYS.length + 1, attempts);
        assertTrue(snapshots.hasPending(ALICE), "the next join can still retry");
        assertEquals(1, messages.size());
        assertEquals(List.of("arena kit"), inventory);
    }

    @Test
    void aRetryReplacesTheInventoryInsteadOfAddingToIt() {
        cancelledAttempts = 1;
        join.onJoin(ALICE);
        advance(1 + 20);
        assertEquals(List.of("home sword"), inventory);
        join.onJoin(ALICE);
        advance(1);
        assertEquals(2, attempts, "no snapshot left, so no further apply");
        assertEquals(List.of("home sword"), inventory);
    }

    @Test
    void quittingStopsTheRetries() {
        cancelledAttempts = Integer.MAX_VALUE;
        join.onJoin(ALICE);
        advance(1);
        join.onQuit(ALICE);
        advance(100_000);
        assertEquals(1, attempts);
        assertTrue(messages.isEmpty());
    }

    @Test
    void aRejoinStartsANewChainAndDropsTheOldOne() {
        cancelledAttempts = 1;
        join.onJoin(ALICE);
        advance(1);
        join.onQuit(ALICE);
        restores.markIfPending(ALICE);
        join.onJoin(ALICE);
        advance(100_000);
        assertEquals(2, attempts, "one cancelled attempt, one successful attempt, no stray retry");
        assertEquals(List.of("home sword"), inventory);
    }

    @Test
    void aPlacementBeforeTheRestoreIsOverwrittenByIt() {
        // The plot plugin teleports first, as it would on join.
        position = new Location(null, 500, 65, 500);
        join.onJoin(ALICE);
        join.onForeignTeleport(player, position, TeleportCause.PLUGIN);
        advance(1);
        assertEquals(0.5, position.getX());
        assertEquals(List.of("home sword"), inventory);
        assertTrue(teleports.isEmpty(), "no correction is needed");
    }

    @Test
    void aPlacementJustAfterTheRestoreIsUndoneOnce() {
        join.onJoin(ALICE);
        advance(1);
        Location plot = new Location(null, 500, 65, 500);
        position = plot;
        join.onForeignTeleport(player, plot, TeleportCause.PLUGIN);
        advance(1);
        assertEquals(1, teleports.size());
        assertEquals(0.5, position.getX());
        assertEquals(List.of("home sword"), inventory, "the inventory is not touched again");

        // A second placement is left alone.
        position = plot;
        join.onForeignTeleport(player, plot, TeleportCause.PLUGIN);
        advance(1);
        assertEquals(1, teleports.size());
        assertEquals(500, position.getX());
    }

    @Test
    void aPlacementAfterTheSettleWindowIsLeftAlone() {
        join.onJoin(ALICE);
        advance(1 + JoinRestore.SETTLE_TICKS);
        join.onForeignTeleport(player, new Location(null, 500, 65, 500), TeleportCause.PLUGIN);
        advance(5);
        assertTrue(teleports.isEmpty());
    }

    @Test
    void onlyPluginTeleportsAreCorrected() {
        join.onJoin(ALICE);
        advance(1);
        join.onForeignTeleport(player, new Location(null, 500, 65, 500), TeleportCause.ENDER_PEARL);
        advance(5);
        assertTrue(teleports.isEmpty());
    }

    @Test
    void aPlayerWithoutASnapshotIsLeftAlone() throws Exception {
        snapshots.delete(ALICE);
        join.onJoin(ALICE);
        advance(100);
        assertEquals(0, attempts);
        join.onForeignTeleport(player, new Location(null, 500, 65, 500), TeleportCause.PLUGIN);
        advance(5);
        assertTrue(teleports.isEmpty());
    }

    @Test
    void aTeleportNinjaArenaStartsItselfInTheWindowIsLeftAlone() {
        join.onJoin(ALICE);
        advance(1);
        Location arena = new Location(null, 900, 80, 900);
        boolean moved = OwnTeleports.during(ALICE, () -> {
            position = arena;
            join.onForeignTeleport(player, arena, TeleportCause.PLUGIN);
            return true;
        });
        assertTrue(moved);
        advance(5);
        assertTrue(teleports.isEmpty(), "arena entry is not undone");
        assertEquals(900, position.getX());
        assertEquals(1, attempts, "no second restore");
        assertFalse(OwnTeleports.isActive(ALICE));
    }

    @Test
    void aPlayerInAMatchOrWaitingRoomIsNeverUndone() {
        join.onJoin(ALICE);
        advance(1);
        busy = true;
        join.onForeignTeleport(player, new Location(null, 500, 65, 500), TeleportCause.PLUGIN);
        advance(5);
        assertTrue(teleports.isEmpty());
    }

    @Test
    void aPlayerWhoEntersAnArenaBeforeTheCorrectionRunsIsNotMovedBack() {
        join.onJoin(ALICE);
        advance(1);
        join.onForeignTeleport(player, new Location(null, 500, 65, 500), TeleportCause.PLUGIN);
        busy = true;
        advance(5);
        assertTrue(teleports.isEmpty());
    }

    @Test
    void anExternalPluginTeleportIsStillCorrectedAfterOwnOnesEnd() {
        join.onJoin(ALICE);
        advance(1);
        OwnTeleports.during(ALICE, () -> true);
        join.onForeignTeleport(player, new Location(null, 500, 65, 500), TeleportCause.PLUGIN);
        advance(1);
        assertEquals(1, teleports.size());
    }
}
