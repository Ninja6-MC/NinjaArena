package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.portal.PortalEntry.Reason;
import com.ninja6.ninjaarena.portal.PortalEntry.Refusal;
import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which portal leads where after rooms replaced waiting rooms and the shared hub. */
class PortalEntryTest {

    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");
    private static final ArenaBounds REGION = new ArenaBounds(10, 64, 10, 12, 67, 10);
    /** An old play portal with no lobby choice of its own. */
    private static final Portal PLAY = new Portal("lobby", "world", REGION, "dojo");
    private static final Portal WATCH = new Portal("watch", "world", REGION, "dojo", true);
    private static final Portal BUILT = Portal.built("built-1", "world", new ArenaBounds(40, 64, 8, 41, 66, 8),
            new Portal.Built(ALICE, "crying_obsidian"));
    private static final ArenaPoint VIEW = new ArenaPoint(20.5, 64, 20.5, 0, 0);

    private final Set<UUID> pending = new HashSet<>();
    private CaptureResult captureResult = CaptureResult.SAVED;
    private int captures;
    private boolean watchable = true;
    private PortalEntry entry;

    @BeforeEach
    void setUp() {
        entry = new PortalEntry(pending::contains, player -> {
            captures++;
            return captureResult;
        }, arena -> watchable && arena.equals("dojo") ? Optional.of(VIEW) : Optional.empty());
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
    void everyPlayPortalLeadsToTheLobbyWhateverItsArenaIsDoing() {
        for (Portal portal : new Portal[] {PLAY, PLAY.withLobby(LobbyMode.DEDICATED), PLAY.withLobby(LobbyMode.SHARED),
                BUILT}) {
            assertEquals(Optional.empty(), entry.check(player(true), portal), portal.name());
            assertEquals(Optional.empty(), entry.destination(portal), "no arena point: the lobby takes them");
        }
        assertEquals(0, captures, "the lobby saves the snapshot, not the portal");
    }

    @Test
    void anOldDedicatedPortalJoinsRoomsOnItsArenaAndAnOldHubPortalAnyRoom() {
        assertEquals(Optional.of("dojo"), entry.roomArena(PLAY), "the default is dedicated");
        assertEquals(Optional.of("dojo"), entry.roomArena(PLAY.withLobby(LobbyMode.DEDICATED)));
        assertEquals(Optional.empty(), entry.roomArena(PLAY.withLobby(LobbyMode.SHARED)));
        assertEquals(Optional.empty(), entry.roomArena(BUILT), "a player-built portal has no arena");
        assertEquals(Optional.empty(), entry.roomArena(WATCH));
    }

    @Test
    void theOldServerDefaultStillPicksTheRoomsForPortalsWithNoChoice() {
        LobbyMode[] serverDefault = {LobbyMode.SHARED};
        entry.setDefaultLobby(() -> serverDefault[0]);
        assertEquals(Optional.empty(), entry.roomArena(PLAY));
        assertEquals(Optional.of("dojo"), entry.roomArena(PLAY.withLobby(LobbyMode.DEDICATED)));
        serverDefault[0] = LobbyMode.DEDICATED;
        assertEquals(Optional.of("dojo"), entry.roomArena(PLAY));
        assertEquals(Optional.empty(), entry.roomArena(PLAY.withLobby(LobbyMode.SHARED)));
    }

    @Test
    void aPlayPortalStillNeedsThePlayNodeAndNoPendingSnapshot() {
        assertEquals(Reason.NO_PERMISSION, reason(entry.check(player(false), PLAY)));
        assertEquals(Reason.NO_PERMISSION, reason(entry.check(player(false), BUILT)));
        pending.add(ALICE);
        assertEquals(Reason.SNAPSHOT_PENDING, reason(entry.check(player(true), PLAY)));
        assertEquals(Reason.SNAPSHOT_PENDING, reason(entry.check(player(true), PLAY.withLobby(LobbyMode.SHARED))));
    }

    @Test
    void aSpectatorPortalStillSeatsTheViewerAtTheLiveMatchAfterTheSnapshot() {
        var viewer = player(PortalEntry.SPECTATE);
        assertEquals(Optional.of(VIEW), entry.destination(WATCH));
        assertEquals(Reason.NO_PERMISSION, reason(entry.admit(player(true), WATCH)));

        pending.add(ALICE);
        assertEquals(Reason.SNAPSHOT_PENDING, reason(entry.admit(viewer, WATCH)));
        assertEquals(0, captures);
        pending.clear();
        assertEquals(Optional.empty(), entry.admit(viewer, WATCH));
        assertEquals(1, captures);

        watchable = false;
        assertEquals(Reason.UNAVAILABLE, reason(entry.check(viewer, WATCH)));
    }

    @Test
    void aFailedOrRepeatedSnapshotStopsASpectatorEntry() {
        var viewer = player(PortalEntry.SPECTATE);
        captureResult = CaptureResult.ALREADY_PENDING;
        assertEquals(Reason.SNAPSHOT_PENDING, reason(entry.admit(viewer, WATCH)));
        captureResult = CaptureResult.WRITE_FAILED;
        Optional<Refusal> refusal = entry.admit(viewer, WATCH);
        assertEquals(Reason.SNAPSHOT_FAILED, reason(refusal));
        assertEquals("Your belongings could not be saved, so you were not sent to the arena.",
                refusal.get().message());
        captureResult = CaptureResult.PLUGIN_WORLD;
        refusal = entry.admit(viewer, WATCH);
        assertEquals(Reason.PLUGIN_WORLD, reason(refusal));
        assertEquals("You cannot enter an arena from the arena or lobby world. Go to another world first.",
                refusal.get().message());
    }
}
