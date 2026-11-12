package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.restore.ArenaBusy;
import com.ninja6.ninjaarena.restore.RestoreListener;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.spectate.SpectatorSettings;
import com.ninja6.ninjaarena.spectate.Spectators;
import com.ninja6.ninjaarena.waiting.WaitingRoomListener;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.LobbyWorld;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpectatorLifecycleTest extends MatchFixture {

    private SnapshotStore snapshots;
    private RestoreListener restoreListener;
    private WaitingRoomService waiting;
    private Spectators viewers;

    @BeforeEach
    void setupSpectating() throws Exception {
        snapshots = new SnapshotStore(dir.resolve("snapshots"));
        var restores = new RestoreService(new SnapshotService(snapshots, plugin.getLogger()), (player, snapshot) -> {
            restored.add(player.getUniqueId());
            player.teleport(new Location(overworld, snapshot.location().x(), snapshot.location().y(),
                    snapshot.location().z()));
            player.setGameMode(snapshot.gameMode());
        }, plugin.getLogger());
        restoreListener = new RestoreListener(plugin, restores);
        waiting = new WaitingRoomService(plugin, arenas, WaitingRoomSettings.from(null), restores::restore,
                player -> player.setGameMode(GameMode.ADVENTURE), matches);
        viewers = new Spectators(plugin, arenas, matches, waiting, restores, () -> arenaWorld,
                new SpectatorSettings(true, 1));
        var waitingListener = new WaitingRoomListener(waiting);
        teleportEvents = event -> {
            viewers.onTeleport(event);
            if (!event.isCancelled()) {
                waitingListener.onTeleport(event);
            }
            if (!event.isCancelled()) {
                restoreListener.onTeleport(event);
            }
        };
        gameModeChanges = viewers::onGameMode;
    }

    @Test
    void waitingTransferRetainsSnapshotUntilMatchEndAndDoesNotChangeTheRoster() throws Exception {
        snapshots.save(snapshot(CAROL));
        arenas.create("garden", new GridCell(1, 0));
        arenas.update("garden", a -> a.withWaitingRoom(new ArenaPoint(300, 64, 50, 0, 0)));
        players.get(CAROL).location = new Location(arenaWorld, 300, 64, 50);
        arenas.finishEdit("garden");
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        players.get(CAROL).canSpectate = true;
        waiting.join(player(CAROL), "garden");

        viewers.fromWaitingRoom(player(CAROL), "dojo");

        assertFalse(waiting.isWaiting(CAROL));
        assertFalse(matches.isInMatch(CAROL));
        assertEquals(List.of(ALICE, BOB), matches.match("dojo").orElseThrow().players());
        assertEquals(GameMode.SPECTATOR, players.get(CAROL).gameMode);
        assertEquals(snapshot(CAROL), snapshots.load(CAROL).orElseThrow());
        assertTrue(viewers.destination("dojo").isEmpty());

        var move = new PlayerMoveEvent(player(CAROL), player(CAROL).getLocation(),
                new Location(arenaWorld, 20, 101, 20));
        viewers.onMove(move);
        assertTrue(move.isCancelled());
        assertFalse(player(CAROL).teleport(new Location(overworld, 20, 64, 20),
                PlayerTeleportEvent.TeleportCause.PLUGIN));
        player(CAROL).setGameMode(GameMode.CREATIVE);
        assertEquals(GameMode.SPECTATOR, players.get(CAROL).gameMode);

        matches.close();
        viewers.onMatchEnd(events(MatchEndEvent.class).getLast());
        assertFalse(viewers.contains(CAROL));
        assertEquals(GameMode.SURVIVAL, players.get(CAROL).gameMode);
        assertFalse(snapshots.exists(CAROL));
    }

    /** A lobby that takes every viewer it is given, as the room service does. */
    private List<UUID> lobbyFor(Spectators spectators) {
        List<UUID> lobby = new java.util.ArrayList<>();
        spectators.setLobby(player -> {
            lobby.add(player.getUniqueId());
            players.get(player.getUniqueId()).location = new Location(world(LobbyWorld.NAME), 0, 64, 0);
            player.setGameMode(GameMode.ADVENTURE);
            return true;
        });
        return lobby;
    }

    @Test
    void leavingSpectatingGoesToTheLobbyWithTheSnapshotKept() throws Exception {
        List<UUID> lobby = lobbyFor(viewers);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        snapshots.save(snapshot(DAVE));
        viewers.arrived(player(DAVE), "dojo");

        assertTrue(viewers.leave(player(DAVE)));
        assertEquals(List.of(DAVE), lobby);
        assertEquals(List.of(), restored);
        assertTrue(snapshots.exists(DAVE));
        assertEquals(GameMode.ADVENTURE, players.get(DAVE).gameMode);
        assertFalse(viewers.leave(player(DAVE)));
    }

    @Test
    void theEndOfTheWatchedMatchAlsoTakesViewersToTheLobbyButShutdownSendsThemHome() throws Exception {
        List<UUID> lobby = lobbyFor(viewers);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        snapshots.save(snapshot(DAVE));
        viewers.arrived(player(DAVE), "dojo");
        for (int i = 0; i < 60 && events(MatchEndEvent.class).isEmpty(); i++) {
            ticks(1);
        }
        viewers.onMatchEnd(events(MatchEndEvent.class).getLast());
        assertTrue(lobby.contains(DAVE));
        assertFalse(viewers.contains(DAVE));

        ticks(5);
        assertTrue(matches.match("dojo").isEmpty());
        snapshots.save(snapshot(CAROL));
        matches.start("dojo", List.of(List.of(ALICE), List.of(CAROL)));
        viewers.arrived(player(CAROL), "dojo");
        viewers.close();
        assertFalse(lobby.contains(CAROL));
        assertTrue(restored.contains(CAROL), "the plugin shutting down sends viewers home");
    }

    @Test
    void viewersGoToTheShopAreaAndBackToTheArenaAtTheNextPrep() throws Exception {
        org.bukkit.World lobbyWorld = world(LobbyWorld.NAME);
        Location shop = new Location(lobbyWorld, 31, 65, 12.5, 180, 0);
        com.ninja6.ninjaarena.arena.ArenaBounds lobbyBounds = new com.ninja6.ninjaarena.arena.ArenaBounds(0, 60, 0,
                40, 80, 40);
        viewers.setShopArea(() -> shop, to -> com.ninja6.ninjaarena.world.PluginWorlds.isLobby(to)
                && lobbyBounds.contains(to.getBlockX(), to.getBlockY(), to.getBlockZ()));
        matches.setShopSettings(new ShopSettings(true, 3, 1, 0));
        matches.setShopArea(() -> shop);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        snapshots.save(snapshot(DAVE));
        viewers.arrived(player(DAVE), "dojo");
        assertTrue(viewers.contains(DAVE));
        ticks(SETTINGS.prepSeconds());
        matches.died(player(BOB), player(ALICE));
        runNextTick();
        ticks(SETTINGS.roundEndSeconds());
        MatchView match = matches.match("dojo").orElseThrow();
        assertEquals(MatchPhase.SHOP, match.phase());

        viewers.onPhase(events(com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent.class).getLast());
        Location at = players.get(DAVE).location;
        assertEquals(lobbyWorld, at.getWorld());
        assertEquals(shop.getX(), at.getX());
        assertTrue(players.get(DAVE).ownMarks.getLast());
        assertTrue(viewers.contains(DAVE), "still watching");
        assertEquals(GameMode.SPECTATOR, players.get(DAVE).gameMode);
        var inLobby = new PlayerMoveEvent(player(DAVE), at, new Location(lobbyWorld, 10, 66, 10));
        viewers.onMove(inLobby);
        assertFalse(inLobby.isCancelled(), "free to fly about the lobby");
        var outOfLobby = new PlayerMoveEvent(player(DAVE), at, new Location(lobbyWorld, 100, 66, 10));
        viewers.onMove(outOfLobby);
        assertTrue(outOfLobby.isCancelled());
        assertTrue(viewers.destination("dojo").isEmpty(), "nobody new is let in during the shop");

        ticks(3);
        assertEquals(MatchPhase.PREP, match.phase());
        viewers.onPhase(events(com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent.class).getLast());
        assertAt(SPAWN_1, players.get(DAVE).location);
        assertTrue(viewers.contains(DAVE));
        assertTrue(snapshots.exists(DAVE), "the snapshot is kept throughout");
        assertTrue(restored.isEmpty());
    }

    @Test
    void whenTheLobbyCannotTakeAViewerTheyAreSentHome() throws Exception {
        viewers.setLobby(player -> false);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        snapshots.save(snapshot(DAVE));
        viewers.arrived(player(DAVE), "dojo");
        viewers.leave(player(DAVE));
        assertEquals(List.of(DAVE), restored);
        assertFalse(snapshots.exists(DAVE));
    }

    @Test
    void quittingWhileSpectatingAndRejoiningReturnsToTheLobbyWithTheSnapshotKept() throws Exception {
        List<UUID> lobby = new java.util.ArrayList<>();
        var returns = new com.ninja6.ninjaarena.lobby.LobbyReturns(viewers::contains, player -> {
            lobby.add(player.getUniqueId());
            return true;
        });
        var restores = new RestoreService(new SnapshotService(snapshots, plugin.getLogger()), (player, snapshot) ->
                restored.add(player.getUniqueId()), plugin.getLogger());
        var rejoining = new RestoreListener(plugin, restores, id -> false, returns);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        snapshots.save(snapshot(DAVE));
        viewers.arrived(player(DAVE), "dojo");

        var quit = new PlayerQuitEvent(player(DAVE), Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED);
        returns.onQuit(quit);
        rejoining.onQuit(quit);
        viewers.onQuit(quit);
        rejoining.onJoin(new PlayerJoinEvent(player(DAVE), Component.empty()));
        runNextTick();

        assertEquals(List.of(DAVE), lobby);
        assertEquals(List.of(), restored);
        assertTrue(snapshots.exists(DAVE));
    }

    @Test
    void quitFreesTheSlotAndJoinRestoresThroughTheExistingListener() throws Exception {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        snapshots.save(snapshot(DAVE));
        viewers.arrived(player(DAVE), "dojo");
        assertTrue(viewers.contains(DAVE));

        var quit = new PlayerQuitEvent(player(DAVE), Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED);
        restoreListener.onQuit(quit);
        viewers.onQuit(quit);

        assertFalse(viewers.contains(DAVE));
        assertTrue(viewers.destination("dojo").isPresent());
        assertTrue(snapshots.exists(DAVE));

        restoreListener.onJoin(new PlayerJoinEvent(player(DAVE), Component.empty()));
        runNextTick();
        assertEquals(List.of(DAVE), restored);
        assertEquals(GameMode.SURVIVAL, players.get(DAVE).gameMode);
        assertFalse(snapshots.exists(DAVE));
    }

    @Test
    void aSpectatorSeatIsInTheArenaWorldOnlySoNoTeleportReachesTheLobbyWorld() throws Exception {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        snapshots.save(snapshot(DAVE));
        viewers.arrived(player(DAVE), "dojo");
        assertTrue(player(DAVE).teleport(new Location(arenaWorld, 20, 64, 20), PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertFalse(player(DAVE).teleport(new Location(world(LobbyWorld.NAME), 20, 64, 20),
                PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertTrue(viewers.contains(DAVE));
    }

    /** Joins and lets the restore run, which opens the window in which a competing teleport is undone. */
    private void restoreOnJoin(UUID id) throws Exception {
        snapshots.save(snapshot(id));
        restoreListener.onJoin(new PlayerJoinEvent(player(id), Component.empty()));
        runNextTick();
        assertEquals(List.of(id), restored);
    }

    @Test
    void matchEntryInsideTheSettleWindowIsNeitherUndoneNorRestoredAgain() throws Exception {
        restoreOnJoin(DAVE);
        int teleports = players.get(DAVE).teleports.size();

        matches.start("dojo", List.of(List.of(ALICE), List.of(DAVE)));
        runNextTick();

        assertEquals(teleports + 1, players.get(DAVE).teleports.size(), "only the team spawn teleport");
        assertEquals(arenaWorld, players.get(DAVE).location.getWorld());
        assertEquals(List.of(DAVE), restored, "no second restore");
    }

    @Test
    void spectatorTransferInsideTheSettleWindowIsNeitherUndoneNorRestoredAgain() throws Exception {
        arenas.create("garden", new GridCell(1, 0));
        arenas.update("garden", a -> a.withWaitingRoom(new ArenaPoint(300, 64, 50, 0, 0)));
        arenas.finishEdit("garden");
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        restoreOnJoin(CAROL);
        snapshots.save(snapshot(CAROL));
        players.get(CAROL).canSpectate = true;
        players.get(CAROL).location = new Location(arenaWorld, 300, 64, 50);
        waiting.join(player(CAROL), "garden");

        viewers.fromWaitingRoom(player(CAROL), "dojo");
        runNextTick();

        assertTrue(viewers.contains(CAROL));
        assertEquals(arenaWorld, players.get(CAROL).location.getWorld());
        assertEquals(List.of(CAROL), restored, "no second restore");
    }

    @Test
    void anotherPluginTeleportInTheWindowIsStillUndoneAfterNinjaArenaOwnTeleports() throws Exception {
        restoreOnJoin(DAVE);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        Location hub = new Location(overworld, 5, 64, 5);

        player(DAVE).teleport(hub, PlayerTeleportEvent.TeleportCause.PLUGIN);
        runNextTick();

        assertEquals(100, players.get(DAVE).location.getX(), "moved back to the restored spot");
    }

    @Test
    void theBusyPredicateCoversMatchWaitingRoomAndSpectators() throws Exception {
        arenas.create("garden", new GridCell(1, 0));
        arenas.update("garden", a -> a.withWaitingRoom(new ArenaPoint(300, 64, 50, 0, 0)));
        arenas.finishEdit("garden");
        var busy = new ArenaBusy(() -> matches, () -> waiting, () -> viewers);
        assertFalse(busy.test(ALICE));
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        assertTrue(busy.test(ALICE));

        snapshots.save(snapshot(DAVE));
        viewers.arrived(player(DAVE), "dojo");
        assertTrue(busy.test(DAVE));

        waiting.join(player(CAROL), "garden");
        assertTrue(busy.test(CAROL));

        var unset = new ArenaBusy(() -> null, () -> null, () -> null);
        assertFalse(unset.test(ALICE));
    }

    private PlayerSnapshot snapshot(UUID id) {
        return new PlayerSnapshot(id, players.get(id).name, Instant.EPOCH,
                new SnapshotLocation(overworld.getUID(), "world", 100, 64, 100, 0, 0),
                new byte[0], new byte[0], new byte[0], 0, 0, 0, 20, 20, 0, 0,
                List.of(), GameMode.SURVIVAL, 0, 0);
    }
}
