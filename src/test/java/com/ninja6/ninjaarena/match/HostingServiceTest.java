package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.portal.ArenaAdmission;
import com.ninja6.ninjaarena.portal.PortalEntry;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.support.TestTaggedItem;
import com.ninja6.ninjaarena.waiting.HostingService;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostingServiceTest extends MatchFixture {

    private final List<CompletableFuture<Chunk>> loads = new ArrayList<>();
    private final Map<UUID, Set<String>> permissions = new HashMap<>();
    private final Set<UUID> inventoryClosed = new HashSet<>();
    private final List<UUID> captured = new ArrayList<>();
    private WaitingRoomService rooms;
    private HostingService hosting;
    private SnapshotService snapshots;
    private boolean captureFails;
    private boolean emptyFails;

    @Override
    protected World world(String name) {
        World delegate = super.world(name);
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getChunkAtAsync")) {
                        CompletableFuture<Chunk> load = new CompletableFuture<>();
                        loads.add(load);
                        return load;
                    }
                    if (method.getName().equals("equals")) return proxy == args[0];
                    return method.invoke(delegate, args);
                });
    }

    @Override
    protected Plugin plugin() {
        Plugin delegate = super.plugin();
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPlayerExact")) {
                        return players.values().stream().filter(fake -> fake.online && fake.name.equalsIgnoreCase((String) args[0]))
                                .map(fake -> fake.proxy).findFirst().orElse(null);
                    }
                    return method.invoke(delegate.getServer(), args);
                });
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "isEnabled" -> true;
                    default -> method.invoke(delegate, args);
                });
    }

    @Override
    protected void add(UUID id, String name) {
        super.add(id, name);
        permissions.put(id, new HashSet<>(Set.of(NinjaArenaCommand.PLAY, NinjaArenaCommand.HOST)));
        Fake fake = players.get(id);
        Player delegate = fake.proxy;
        fake.proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) return permissions.get(id).contains(args[0]);
                    if (method.getName().equals("closeInventory")) inventoryClosed.add(id);
                    if (method.getName().equals("sendMessage") && args[0] instanceof net.kyori.adventure.text.Component message) {
                        assertPlain(message);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @BeforeEach
    void hosting() throws Exception {
        snapshots = new SnapshotService(new SnapshotStore(dir.resolve("host-snapshots")), plugin.getLogger());
        rooms = new WaitingRoomService(plugin, arenas, new WaitingRoomSettings(2, 3, 4), player -> {
            restored.add(player.getUniqueId());
            try { snapshots.delete(player.getUniqueId()); } catch (Exception e) { throw new IllegalStateException(e); }
            return RestoreResult.RESTORED;
        }, player -> {
            if (emptyFails) throw new IllegalStateException("empty failed");
        }, matches);
        arenas.onEditOrDelete(rooms::closeRoom);
        PortalEntry entry = new PortalEntry(arenas, snapshots::hasPending, player -> {
            assertTrue(inventoryClosed.contains(player.getUniqueId()));
            captured.add(player.getUniqueId());
            if (captureFails) return CaptureResult.WRITE_FAILED;
            return snapshots.persist(snapshot(player));
        });
        hosting = new HostingService(plugin, arenas, rooms, entry,
                new ArenaAdmission(plugin, entry, () -> arenaWorld, snapshots, rooms::join), () -> arenaWorld,
                matches::isInMatch);
    }

    private PlayerSnapshot snapshot(Player player) {
        var at = player.getLocation();
        return new PlayerSnapshot(player.getUniqueId(), player.getName(), Instant.EPOCH,
                new SnapshotLocation(at.getWorld().getUID(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                        at.getYaw(), at.getPitch()), new byte[] {1}, new byte[] {2}, new byte[] {3},
                2, 0.5f, 10, 20, 20, 5, 0, List.of(), GameMode.SURVIVAL, 0, 0);
    }

    private static void assertPlain(net.kyori.adventure.text.Component message) {
        assertEquals(null, message.clickEvent());
        assertEquals(null, message.hoverEvent());
        message.children().forEach(HostingServiceTest::assertPlain);
    }

    private void finish() {
        loads.getLast().complete(null);
        runNextTick();
    }

    private void inviteBob() {
        hosting.host(player(ALICE), "dojo");
        hosting.invite(player(ALICE), "Bob");
    }

    @Test
    void openingAndInvitingNeverSnapshotOrMoveAnyoneAndReservationIsExclusive() {
        inviteBob();
        assertFalse(arenas.isHeld("dojo"));
        assertTrue(captured.isEmpty());
        assertTrue(loads.isEmpty());
        players.values().forEach(fake -> assertTrue(fake.teleports.isEmpty()));
        assertTrue(players.get(BOB).messages.getLast().contains("/narena accept Alice"));
        hosting.host(player(CAROL), "dojo");
        assertTrue(players.get(CAROL).messages.getLast().contains("not free"));
        hosting.leave(player(ALICE));
        hosting.host(player(CAROL), "dojo");
        assertTrue(players.get(CAROL).messages.getLast().contains("You opened"));
    }

    @Test
    void acceptanceBeforeHostArrivalUsesSnapshotAndRestoreAndGroupsAsRoomGrows() throws Exception {
        inviteBob();
        hosting.accept(player(BOB), "Alice");
        assertTrue(captured.isEmpty());
        assertFalse(rooms.isWaiting(BOB));
        finish();
        assertEquals(List.of(BOB), captured);
        assertTrue(snapshots.hasPending(BOB));
        assertEquals(1, players.get(BOB).teleports.size());
        assertTrue(rooms.isWaiting(BOB));
        assertFalse(rooms.isWaiting(ALICE));
        rooms.join(player(ALICE), "dojo");
        assertFalse(rooms.teamOf(ALICE) == rooms.teamOf(BOB));
        rooms.join(player(CAROL), "dojo");
        assertEquals(rooms.teamOf(ALICE), rooms.teamOf(BOB));
        rooms.leave(player(BOB), WaitingRoomService.Exit.COMMAND);
        assertTrue(restored.contains(BOB));
        assertFalse(snapshots.hasPending(BOB));
    }

    @Test
    void captureAndTeleportFailuresKeepTheInvitationForRetryAndDoNotCreatePartyMembers() {
        inviteBob();
        captureFails = true;
        hosting.accept(player(BOB), "Alice");
        finish();
        assertFalse(rooms.isWaiting(BOB));
        assertTrue(players.get(BOB).teleports.isEmpty());
        captureFails = false;
        teleportEvents = event -> event.setCancelled(true);
        hosting.accept(player(BOB), "Alice");
        finish();
        assertFalse(snapshots.hasPending(BOB));
        assertFalse(rooms.isWaiting(BOB));
        teleportEvents = event -> { };
        hosting.accept(player(BOB), "Alice");
        finish();
        assertTrue(rooms.isWaiting(BOB));
    }

    @Test
    void failedRoomPreparationRestoresInsteadAndLeavesInvitationOpen() {
        inviteBob();
        emptyFails = true;
        hosting.accept(player(BOB), "Alice");
        finish();
        assertTrue(restored.contains(BOB));
        assertFalse(rooms.isWaiting(BOB));
        emptyFails = false;
        hosting.accept(player(BOB), "Alice");
        finish();
        assertTrue(rooms.isWaiting(BOB));
    }

    @Test
    void pendingSnapshotsAndLooseItemsPreventAdmissionWithoutOverwritingBelongings() {
        inviteBob();
        snapshots.persist(snapshot(player(BOB)));
        hosting.accept(player(BOB), "Alice");
        assertTrue(loads.isEmpty());
        assertTrue(captured.isEmpty());
        try { snapshots.delete(BOB); } catch (Exception e) { throw new IllegalStateException(e); }
        players.get(BOB).cursor = new TestTaggedItem(Material.DIAMOND, Map.of(), 1);
        hosting.accept(player(BOB), "Alice");
        finish();
        assertTrue(captured.isEmpty());
        assertFalse(inventoryClosed.contains(BOB));
        players.get(BOB).cursor = null;
        hosting.accept(player(BOB), "Alice");
        finish();
        assertTrue(rooms.isWaiting(BOB));
    }

    @Test
    void chunkFailureAndDuplicateAcceptPreserveTheInvitation() {
        inviteBob();
        hosting.accept(player(BOB), "Alice");
        hosting.accept(player(BOB), "Alice");
        assertEquals(1, loads.size());
        loads.getFirst().completeExceptionally(new IllegalStateException("load failed"));
        runNextTick();
        assertTrue(captured.isEmpty());
        hosting.accept(player(BOB), "Alice");
        finish();
        assertTrue(rooms.isWaiting(BOB));
    }

    @Test
    void cancelledReservationAndQuitInvalidateOldAsyncRequestsEvenAfterReinvite() {
        inviteBob();
        hosting.accept(player(BOB), "Alice");
        hosting.onQuit(new PlayerQuitEvent(player(BOB), (net.kyori.adventure.text.Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
        hosting.invite(player(ALICE), "Bob");
        hosting.accept(player(BOB), "Alice");
        loads.getFirst().complete(null);
        runNextTick();
        assertTrue(captured.isEmpty());
        finish();
        assertTrue(rooms.isWaiting(BOB));
        rooms.leave(player(BOB), WaitingRoomService.Exit.COMMAND);
        hosting.invite(player(ALICE), "Bob");
        hosting.accept(player(BOB), "Alice");
        hosting.leave(player(ALICE));
        finish();
        assertEquals(1, captured.size());
        assertFalse(rooms.isWaiting(BOB));
    }

    @Test
    void revokedPermissionOrArenaEditDuringLoadingPreventsCapture() throws Exception {
        inviteBob();
        hosting.accept(player(BOB), "Alice");
        permissions.get(BOB).remove(NinjaArenaCommand.PLAY);
        finish();
        assertTrue(captured.isEmpty());
        permissions.get(BOB).add(NinjaArenaCommand.PLAY);
        hosting.accept(player(BOB), "Alice");
        arenas.startEdit("dojo");
        finish();
        assertTrue(captured.isEmpty());
        arenas.finishEdit("dojo");
        hosting.accept(player(BOB), "Alice");
        assertTrue(players.get(BOB).messages.getLast().contains("no open invitation"));
    }

    @Test
    void hostQuitDepartureRoomCloseAndMatchStartReleaseReservations() {
        inviteBob();
        hosting.onQuit(new PlayerQuitEvent(player(ALICE), (net.kyori.adventure.text.Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
        hosting.host(player(CAROL), "dojo");
        assertTrue(players.get(CAROL).messages.getLast().contains("You opened"));
        rooms.join(player(CAROL), "dojo");
        rooms.leave(player(CAROL), WaitingRoomService.Exit.COMMAND);
        hosting.host(player(ALICE), "dojo");
        assertTrue(players.get(ALICE).messages.getLast().contains("You opened"));
        rooms.closeRoom("dojo");
        hosting.host(player(CAROL), "dojo");
        rooms.join(player(CAROL), "dojo");
        rooms.join(player(DAVE), "dojo");
        rooms.tick(); rooms.tick(); rooms.tick();
        assertTrue(matches.match("dojo").isPresent());
        assertFalse(hosting.leave(player(CAROL)));
        hosting.host(player(ALICE), "dojo");
        assertTrue(players.get(ALICE).messages.getLast().contains("not free"));
    }

    @Test
    void unrelatedOccupiedRoomAndUninvitedPlayersCannotBeTakenOver() {
        rooms.join(player(CAROL), "dojo");
        hosting.host(player(ALICE), "dojo");
        assertTrue(players.get(ALICE).messages.getLast().contains("not free"));
        rooms.closeRoom("dojo");
        inviteBob();
        hosting.accept(player(DAVE), "Alice");
        assertTrue(players.get(DAVE).messages.getLast().contains("no open invitation"));
        assertTrue(loads.isEmpty());
        assertTrue(captured.isEmpty());
    }

    @Test
    void acceptingInsideTheSameRoomGroupsPlayersWithoutAnotherSnapshotOrTeleport() {
        inviteBob();
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        rooms.join(player(CAROL), "dojo");
        hosting.accept(player(BOB), "Alice");
        assertEquals(rooms.teamOf(ALICE), rooms.teamOf(BOB));
        assertTrue(loads.isEmpty());
        assertTrue(captured.isEmpty());
        assertTrue(players.get(BOB).teleports.isEmpty());
    }

    @Test
    void closingBeforeLoadedEntryDiscardsRequestsWithoutMovement() {
        inviteBob();
        hosting.accept(player(BOB), "Alice");
        hosting.close();
        finish();
        assertTrue(captured.isEmpty());
        assertFalse(rooms.isWaiting(BOB));
    }

    @Test
    void leavingWhileAcceptedEntryLoadsCancelsItAndKeepsTheInvitationForRetry() {
        inviteBob();
        hosting.accept(player(BOB), "Alice");
        assertTrue(hosting.leave(player(BOB)));
        finish();
        assertTrue(captured.isEmpty());
        assertTrue(players.get(BOB).teleports.isEmpty());
        hosting.accept(player(BOB), "Alice");
        finish();
        assertTrue(rooms.isWaiting(BOB));
    }

    @Test
    void revokedHostOrNewPendingSnapshotDuringLoadingCannotAdmitTheInvitee() {
        inviteBob();
        hosting.accept(player(BOB), "Alice");
        permissions.get(ALICE).remove(NinjaArenaCommand.HOST);
        finish();
        assertTrue(captured.isEmpty());
        permissions.get(ALICE).add(NinjaArenaCommand.HOST);
        inviteBob();
        hosting.accept(player(BOB), "Alice");
        snapshots.persist(snapshot(player(BOB)));
        finish();
        assertTrue(captured.isEmpty());
        assertTrue(players.get(BOB).teleports.isEmpty());
    }

    @Test
    void permissionsSelfOfflineTargetsAndDeadOrSpectatorPlayersCannotOpenOrAccept() {
        permissions.get(ALICE).remove(NinjaArenaCommand.HOST);
        hosting.host(player(ALICE), "dojo");
        assertTrue(players.get(ALICE).messages.getLast().contains("permission"));
        permissions.get(ALICE).add(NinjaArenaCommand.HOST);
        inviteBob();
        hosting.invite(player(ALICE), "Alice");
        assertTrue(players.get(ALICE).messages.getLast().contains("yourself"));
        hosting.invite(player(ALICE), "Nobody");
        assertTrue(players.get(ALICE).messages.getLast().contains("No online player"));
        permissions.get(BOB).remove(NinjaArenaCommand.PLAY);
        hosting.accept(player(BOB), "Alice");
        assertTrue(players.get(BOB).messages.getLast().contains("permission"));
        permissions.get(BOB).add(NinjaArenaCommand.PLAY);
        players.get(BOB).dead = true;
        hosting.accept(player(BOB), "Alice");
        players.get(BOB).dead = false;
        players.get(BOB).gameMode = GameMode.SPECTATOR;
        hosting.accept(player(BOB), "Alice");
        assertTrue(loads.isEmpty());
        assertTrue(captured.isEmpty());
    }
}
