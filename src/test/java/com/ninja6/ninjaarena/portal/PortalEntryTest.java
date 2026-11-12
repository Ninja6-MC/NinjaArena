package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return player(canPlay ? PortalEntry.PLAY : "");
    }

    private static Player player(String permission) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> permission.equals(args[0]);
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
    void spectatorPortalUsesSeparatePermissionAndTheSameSnapshotGate() throws Exception {
        var spectator = new Portal("watch", "world", LOBBY.region(), "dojo", true);
        var viewer = player(PortalEntry.SPECTATE);
        assertEquals(Reason.UNAVAILABLE, reason(entry.check(viewer, spectator)));

        arenas.hold("dojo");
        var viewPoint = new ArenaPoint(20.5, 64, 20.5, 0, 0);
        entry = new PortalEntry(arenas, pending::contains, ignored -> {
            captures++;
            return captureResult;
        }, arena -> Optional.of(viewPoint));
        assertEquals(Optional.of(viewPoint), entry.destination(spectator));
        assertEquals(Reason.NO_PERMISSION, reason(entry.admit(player(true), spectator)));

        pending.add(ALICE);
        assertEquals(Reason.SNAPSHOT_PENDING, reason(entry.admit(viewer, spectator)));
        assertEquals(0, captures);
        pending.clear();
        assertEquals(Optional.empty(), entry.admit(viewer, spectator));
        assertEquals(1, captures);
    }

    @Test
    void aSharedPortalLeadsToTheHubWhateverItsArenaIsDoing() throws Exception {
        ArenaPoint hub = new ArenaPoint(-300.5, 64, -300.5, 0, 0);
        ArenaPoint[] point = {null};
        entry.setLobbies(() -> LobbyMode.DEDICATED, () -> Optional.ofNullable(point[0]), arena -> Optional.empty());
        Portal shared = LOBBY.withLobby(LobbyMode.SHARED);
        assertTrue(entry.leadsToHub(shared));
        assertEquals(Reason.NO_HUB, reason(entry.admit(player(true), shared)));
        assertEquals(0, captures);

        point[0] = hub;
        assertEquals(Optional.of(hub), entry.destination(shared));
        assertEquals(Reason.NO_PERMISSION, reason(entry.check(player(false), shared)));
        // Players wait in the hub while arenas are edited or in use.
        arenas.startEdit("dojo");
        assertEquals(Optional.empty(), entry.admit(player(true), shared));
        assertEquals(1, captures);
        pending.add(ALICE);
        assertEquals(Reason.SNAPSHOT_PENDING, reason(entry.check(player(true), shared)));
    }

    @Test
    void portalsWithNoChoiceFollowTheServerDefaultAndSpectatorPortalsIgnoreIt() {
        LobbyMode[] serverDefault = {LobbyMode.SHARED};
        entry.setLobbies(() -> serverDefault[0], Optional::empty, arena -> Optional.empty());
        assertTrue(entry.leadsToHub(LOBBY));
        assertFalse(entry.leadsToHub(LOBBY.withLobby(LobbyMode.DEDICATED)));
        assertFalse(entry.leadsToHub(new Portal("watch", "world", LOBBY.region(), "dojo", true)));
        serverDefault[0] = LobbyMode.DEDICATED;
        assertFalse(entry.leadsToHub(LOBBY));
        assertTrue(entry.leadsToHub(LOBBY.withLobby(LobbyMode.SHARED)));
    }

    @Test
    void aFullWaitingRoomIsRefusedBeforeTheSnapshot() {
        entry.setLobbies(() -> LobbyMode.DEDICATED, Optional::empty,
                arena -> arena.equals("dojo") ? Optional.of("Full.") : Optional.empty());
        Optional<Refusal> refusal = entry.admit(player(true), LOBBY);
        assertEquals(Reason.FULL, reason(refusal));
        assertEquals("Full.", refusal.get().message());
        assertEquals(Reason.FULL, reason(entry.admit(player(true), "dojo")));
        assertEquals(0, captures);
    }

    private static final Portal BUILT = Portal.built("built-1", "world", new ArenaBounds(40, 64, 8, 41, 66, 8),
            new Portal.Built(ALICE, "crying_obsidian"));

    @Test
    void aPlayerBuiltPortalIsRoutedToTheOpenArenaAndUsesItsChecks() {
        String[] open = {"dojo"};
        entry.setRouting(() -> Optional.ofNullable(open[0]));
        Portal routed = entry.route(BUILT).orElseThrow();
        assertEquals("dojo", routed.arena());
        assertEquals(BUILT.name(), routed.name());
        assertEquals(Optional.empty(), entry.check(player(true), BUILT));
        assertEquals(Optional.of(ROOM), entry.destination(BUILT));
        assertEquals(Optional.empty(), entry.admit(player(true), routed));
        assertEquals(1, captures);

        // The routed arena's own checks still apply.
        entry.setLobbies(() -> LobbyMode.DEDICATED, Optional::empty, arena -> Optional.of("Full."));
        assertEquals(Reason.FULL, reason(entry.check(player(true), BUILT)));
        assertEquals(Reason.NO_PERMISSION, reason(entry.check(player(false), BUILT)));

        open[0] = null;
        Optional<Refusal> busy = entry.check(player(true), BUILT);
        assertEquals(Reason.BUSY, reason(busy));
        assertTrue(busy.get().message().startsWith("All arenas are busy"), busy.get().message());
        assertEquals(Optional.empty(), entry.route(BUILT));
        assertEquals(Optional.empty(), entry.destination(BUILT));
        // Without the play node the player hears that first.
        assertEquals(Reason.NO_PERMISSION, reason(entry.check(player(false), BUILT)));
        assertEquals(1, captures);
    }

    @Test
    void aPlayerBuiltPortalFollowsASharedServerDefaultToTheHub() {
        ArenaPoint hub = new ArenaPoint(-300.5, 64, -300.5, 0, 0);
        entry.setLobbies(() -> LobbyMode.SHARED, () -> Optional.of(hub), arena -> Optional.empty());
        entry.setRouting(Optional::empty);
        assertTrue(entry.leadsToHub(BUILT));
        assertEquals(Optional.of(BUILT), entry.route(BUILT));
        assertEquals(Optional.empty(), entry.check(player(true), BUILT));
        assertEquals(Optional.of(hub), entry.destination(BUILT));
        // Admin portals are never rerouted.
        entry.setLobbies(() -> LobbyMode.DEDICATED, Optional::empty, arena -> Optional.empty());
        assertEquals(Optional.of(LOBBY), entry.route(LOBBY));
    }

    @Test
    void aFailedSnapshotWriteStopsTheEntry() {
        captureResult = CaptureResult.WRITE_FAILED;
        Optional<Refusal> refusal = entry.admit(player(true), LOBBY);
        assertEquals(Reason.SNAPSHOT_FAILED, reason(refusal));
        assertEquals("Your belongings could not be saved, so you were not sent to the arena.",
                refusal.get().message());
    }

    @Test
    void anEntryFromAPluginWorldIsRefusedWithoutASnapshot() {
        captureResult = CaptureResult.PLUGIN_WORLD;
        Optional<Refusal> refusal = entry.admit(player(true), LOBBY);
        assertEquals(Reason.PLUGIN_WORLD, reason(refusal));
        assertEquals("You cannot enter an arena from the arena or lobby world. Go to another world first.",
                refusal.get().message());
    }
}
