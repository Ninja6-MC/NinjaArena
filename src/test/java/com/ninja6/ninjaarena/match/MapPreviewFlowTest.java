package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.lobby.LobbyReturns;
import com.ninja6.ninjaarena.map.ArenaMaps;
import com.ninja6.ninjaarena.map.BudgetedCellWriter;
import com.ninja6.ninjaarena.map.FakeBlocks;
import com.ninja6.ninjaarena.map.FakeWriters;
import com.ninja6.ninjaarena.map.GeneratedMaps;
import com.ninja6.ninjaarena.restore.ArenaBusy;
import com.ninja6.ninjaarena.restore.JoinRestore;
import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.room.MapPreviews;
import com.ninja6.ninjaarena.room.Room;
import com.ninja6.ninjaarena.room.RoomLobby;
import com.ninja6.ninjaarena.room.RoomService;
import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.terrain.MapSearch;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.CrossWorldTransfers;
import com.ninja6.ninjaarena.world.LobbyWorld;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The build progress bar and map previews of rooms on generated maps, with the real room
 * service, slot cells, snapshot store and restore service: every way a preview ends puts
 * the player back in the lobby or home, and never leaves them in spectator mode.
 */
class MapPreviewFlowTest extends MatchFixture {
    private static final String PREVIEW_LEFT_HOME =
            "You left the map preview and your room. /narena leave has nothing more to leave.";


    private static final String HOME = "world";

    private final World lobbyWorld = world(LobbyWorld.NAME);
    private final Set<UUID> lobby = new LinkedHashSet<>();
    private final CrossWorldTransfers transfers = new CrossWorldTransfers();
    private final FakeBlocks blocks = new FakeBlocks();
    private final BudgetedCellWriter writer = FakeWriters.perTick(blocks, 1 << 21);
    private GeneratedMaps generated;
    private RoomService rooms;
    private SnapshotService snapshots;
    private RestoreService restores;
    private MapPreviews previews;
    private LobbyReturns returns;
    private boolean lobbyRefuses;
    private boolean restoreFails;

    @BeforeEach
    void setUpPreviews() throws Exception {
        for (Fake fake : players.values()) {
            fake.location = new Location(overworld, 0, 70, 0);
            fake.permissions.add(NinjaArenaCommand.PLAY);
            fake.permissions.add(RoomService.CREATE);
        }
        snapshots = new SnapshotService(new SnapshotStore(dir.resolve("snapshots")), plugin.getLogger());
        restores = restoreService();
        generated = new GeneratedMaps(arenas.grid(), () -> arenas.all().stream().map(Arena::cell).toList(),
                arenas::isReadable, writer, blocks.ticketsView(), Runnable::run, Runnable::run, plugin.getLogger());
        arenas.setReservedCells(generated::isReserved);
        arenas.setSlotCellsInUse(generated::inUse);
        ArenaMaps maps = new ArenaMaps(arenas, generated);
        matches = new MatchService(plugin, maps, () -> arenaWorld, SETTINGS, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, events::add, player -> readied.add(player.getUniqueId()));
        deaths = new MatchDeathListener(plugin, matches, maps);
        RoomLobby fakeLobby = new RoomLobby() {
            @Override
            public boolean isMember(UUID player) {
                return lobby.contains(player);
            }

            @Override
            public List<UUID> members() {
                return List.copyOf(lobby);
            }

            @Override
            public Optional<String> join(Player player) {
                snapshots.persist(snapshot(player.getUniqueId()));
                enter(player);
                return Optional.empty();
            }

            @Override
            public boolean enter(Player player) {
                // As LobbyMembers: the lobby spawn by the plugin's own teleport, emptied for waiting.
                if (lobbyRefuses || !OwnTeleports.teleport(player, new Location(lobbyWorld, 0, 64, 0),
                        TeleportCause.PLUGIN)) {
                    return false;
                }
                player.setGameMode(GameMode.ADVENTURE);
                lobby.add(player.getUniqueId());
                return true;
            }
        };
        rooms = new RoomService(plugin, arenas, matches, fakeLobby, () -> new WaitingRoomSettings(2), transfers,
                () -> () -> { }, 2);
        rooms.setGeneratedMaps(generated);
        matches.setLobby(rooms::enterLobby);
        previews = new MapPreviews(plugin, () -> arenaWorld, snapshots::hasPending, player -> restores.restore(player));
        rooms.setPreviews(previews);
        returns = new LobbyReturns(id -> lobby.contains(id) || rooms.isMember(id) || matches.isInMatch(id)
                || rooms.isPreviewing(id), rooms::rejoin);
        MatchListener crossing = new MatchListener(matches, deaths, transfers);
        teleportEvents = event -> {
            previews.onTeleport(event);
            if (event.isCancelled()) {
                return;
            }
            if (!PluginWorlds.isLobby(event.getTo())) {
                // As LobbyListener: a member taken out of the lobby world is no longer one.
                lobby.remove(event.getPlayer().getUniqueId());
            }
            crossing.onTeleport(event);
        };
        gameModeChanges = previews::onGameMode;
        drain();
    }

    /** Restores by putting the player home in the game mode they had: what PlayerRestorer does. */
    private RestoreService restoreService() {
        return new RestoreService(snapshots, (player, snapshot) -> {
            if (restoreFails) {
                throw new RestoreService.RestoreException("refused");
            }
            if (!OwnTeleports.teleport(player, new Location(overworld, 1, 70, 1), TeleportCause.PLUGIN)) {
                throw new RestoreService.RestoreException("teleport cancelled");
            }
            player.setGameMode(snapshot.gameMode());
        }, plugin.getLogger());
    }

    private PlayerSnapshot snapshot(UUID id) {
        return new PlayerSnapshot(id, players.get(id).name, Instant.parse("2026-11-16T12:00:00Z"),
                new SnapshotLocation(overworld.getUID(), HOME, 0, 70, 0, 0, 0), new byte[0], new byte[0],
                new byte[0], 0, 0, 0, 20, 20, 5, 0, List.of(), GameMode.SURVIVAL, 0, 0);
    }

    private void drain() {
        for (int i = 0; i < 1000 && (writer.pending() > 0 || !nextTick.isEmpty()); i++) {
            writer.tick();
            runNextTick();
        }
        assertEquals(0, writer.pending());
    }

    private Room openWith(UUID host, UUID... guests) {
        rooms.create(player(host));
        for (UUID guest : guests) {
            rooms.invite(player(host), players.get(guest).name);
            rooms.accept(player(guest), players.get(host).name);
        }
        return rooms.hostedBy(host).orElseThrow();
    }

    /** A room of Alice and Bob on a generated map that is placed. */
    private Room placedRoom() {
        Room room = openWith(ALICE, BOB);
        rooms.playGenerated(room, 42, 1);
        drain();
        assertTrue(rooms.canPreview(room));
        return room;
    }

    private String last(UUID id) {
        return players.get(id).messages.getLast();
    }

    private Fake fake(UUID id) {
        return players.get(id);
    }

    private void startPreview(UUID id, Room room) {
        rooms.preview(player(id));
        assertTrue(rooms.isPreviewing(id), String.valueOf(fake(id).messages));
        assertEquals(GameMode.SPECTATOR, fake(id).gameMode);
        assertTrue(PluginWorlds.isArena(fake(id).location));
        Arena map = generated.get(room.arena()).orElseThrow();
        ArenaBounds bounds = map.bounds();
        Location at = fake(id).location;
        assertTrue(at.getY() > bounds.maxY(), "above the map");
        assertTrue(at.getX() >= bounds.minX() && at.getX() <= bounds.maxX() + 1, "over the map");
        assertFalse(lobby.contains(id), "out of the lobby while previewing");
        assertTrue(rooms.isMember(id), "still in the room");
        assertTrue(snapshots.hasPending(id), "the lobby's snapshot is kept");
    }

    /** Back in the lobby, still in the room, out of spectator mode, snapshot kept. */
    private void assertBackInRoom(UUID id, Room room) {
        assertFalse(rooms.isPreviewing(id));
        assertNotEquals(GameMode.SPECTATOR, fake(id).gameMode);
        assertEquals(GameMode.ADVENTURE, fake(id).gameMode);
        assertTrue(lobby.contains(id));
        assertTrue(PluginWorlds.isLobby(fake(id).location));
        assertTrue(snapshots.hasPending(id));
        if (room != null) {
            assertEquals(room, rooms.roomOf(id).orElseThrow());
        }
    }

    /** Restored home from the snapshot: the game mode it saved, outside the plugin's worlds. */
    private void assertHome(UUID id) {
        assertFalse(rooms.isPreviewing(id));
        assertRestored(id);
    }

    private void assertRestored(UUID id) {
        assertEquals(GameMode.SURVIVAL, fake(id).gameMode);
        assertEquals(HOME, fake(id).location.getWorld().getName());
        assertFalse(snapshots.hasPending(id));
    }

    private void quit(UUID id) {
        PlayerQuitEvent event = new PlayerQuitEvent(player(id), Component.empty());
        // In listener priority order: the lobby return first, the preview, then the room.
        returns.onQuit(event);
        previews.onQuit(event);
        rooms.onQuit(event);
        restores.markIfPending(id);
        fake(id).online = false;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ---- Build progress ----

    @Test
    void membersSeeTheBuildProgressAndStartIsRefusedUntilTheMapIsPlaced() {
        Room room = openWith(ALICE, BOB);
        rooms.playGenerated(room, 42, 1);
        assertTrue(rooms.building(room).isPresent());

        rooms.showProgress();
        assertTrue(rooms.showsProgress(room));
        for (UUID id : List.of(ALICE, BOB)) {
            assertEquals(1, fake(id).bossBars.size());
            BossBar bar = fake(id).bossBars.getFirst();
            assertTrue(bar.progress() < 1);
            assertEquals("Building the room's map: 0%", plain(bar.name()));
        }
        assertEquals(1, Set.copyOf(List.of(fake(ALICE).bossBars.getFirst(), fake(BOB).bossBars.getFirst())).size(),
                "one bar per room");
        rooms.showProgress();
        assertEquals(1, fake(ALICE).bossBars.size(), "shown once");

        rooms.start(player(ALICE), null);
        assertEquals("The room's map is still being built (0%). Start once it is ready.", last(ALICE));
        assertFalse(room.isPlaying());
        rooms.preview(player(BOB));
        assertEquals("The room's map is still being built (0%). Preview it once it is ready.", last(BOB));
        assertFalse(rooms.isPreviewing(BOB));

        drain();
        rooms.showProgress();
        assertFalse(rooms.showsProgress(room), "gone once the map is placed");
        assertTrue(fake(ALICE).bossBars.isEmpty());
        assertTrue(fake(BOB).bossBars.isEmpty());
        rooms.start(player(ALICE), null);
        assertTrue(room.isPlaying());
    }

    @Test
    void theBarFollowsTheMembersAndGoesWhenTheMapFailsOrTheRoomCloses() {
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.playGenerated(room, 42, 1);
        rooms.showProgress();
        assertEquals(1, fake(CAROL).bossBars.size());
        rooms.leave(player(CAROL));
        rooms.showProgress();
        assertTrue(fake(CAROL).bossBars.isEmpty(), "a member who leaves stops seeing it");
        assertEquals(1, fake(ALICE).bossBars.size());

        // No map from the seed: the room falls back to the hand-built arena, and the bar goes.
        generated.setSearch((seed, difficulty, teams, size) -> new MapSearch.Failed(seed, List.of()));
        rooms.playGenerated(room, 7, 1);
        drain();
        rooms.showProgress();
        assertFalse(rooms.showsProgress(room));
        assertTrue(fake(ALICE).bossBars.isEmpty());

        // A room that closes while its map is built takes its bar with it at once.
        generated.setSearch(GeneratedMaps.Search.DEFAULT);
        Room other = openWith(CAROL, DAVE);
        rooms.playGenerated(other, 43, 1);
        rooms.showProgress();
        assertEquals(1, fake(DAVE).bossBars.size());
        rooms.leave(player(DAVE));
        rooms.leave(player(CAROL));
        assertFalse(rooms.showsProgress(other));
        assertTrue(fake(CAROL).bossBars.isEmpty());

        rooms.playGenerated(room, 44, 1);
        rooms.showProgress();
        assertEquals(1, fake(ALICE).bossBars.size());
        rooms.close();
        assertTrue(fake(ALICE).bossBars.isEmpty(), "and on disable");
    }

    // ---- Starting a preview ----

    @Test
    void onlyAPlacedGeneratedMapOfTheRoomCanBePreviewedFromTheLobby() {
        rooms.preview(player(ALICE));
        assertEquals("You are not in a room.", last(ALICE));
        Room room = openWith(ALICE, BOB);
        assertEquals("dojo", room.arena());
        rooms.preview(player(ALICE));
        assertEquals("Only a generated map can be previewed.", last(ALICE));
        assertFalse(rooms.isPreviewing(ALICE));
        assertEquals(GameMode.ADVENTURE, fake(ALICE).gameMode);

        rooms.playGenerated(room, 42, 1);
        drain();
        lobby.remove(BOB);
        rooms.preview(player(BOB));
        assertEquals("Preview the map from the lobby.", last(BOB));
        rooms.enterLobby(player(BOB));

        rooms.start(player(ALICE), null);
        assertTrue(room.isPlaying());
        rooms.preview(player(BOB));
        assertEquals("The map cannot be previewed while the room is playing.", last(BOB));
        assertFalse(rooms.isPreviewing(BOB));
    }

    @Test
    void aPlayerWithoutASnapshotIsNeverTakenIntoAPreview() throws Exception {
        Room room = placedRoom();
        snapshots.delete(BOB);
        rooms.preview(player(BOB));
        assertEquals("The map preview could not start.", last(BOB));
        assertFalse(rooms.isPreviewing(BOB));
        assertTrue(lobby.contains(BOB));
        assertEquals(GameMode.ADVENTURE, fake(BOB).gameMode);
        assertTrue(rooms.isMember(BOB));
        assertEquals(room, rooms.roomOf(BOB).orElseThrow());
    }

    @Test
    void aRefusedTeleportLeavesThePlayerInTheLobby() {
        Room room = placedRoom();
        teleportEvents = event -> event.setCancelled(true);
        rooms.preview(player(BOB));
        assertEquals("The map preview could not start.", last(BOB));
        assertFalse(rooms.isPreviewing(BOB));
        assertTrue(lobby.contains(BOB));
        assertEquals(room, rooms.roomOf(BOB).orElseThrow());
    }

    @Test
    void aRefusedGameModeChangeEndsThePreviewAtOnce() {
        Room room = placedRoom();
        gameModeChanges = event -> {
            if (event.getNewGameMode() == GameMode.SPECTATOR) {
                event.setCancelled(true);
            }
        };
        rooms.preview(player(BOB));
        assertEquals("The map preview has ended; you are back in the lobby.", last(BOB));
        assertBackInRoom(BOB, room);
    }

    // ---- Ways out ----

    @Test
    void previewingAgainOrLeavingReturnsThePlayerToTheRoom() {
        Room room = placedRoom();
        startPreview(BOB, room);
        assertTrue(new ArenaBusy(() -> matches, () -> null, () -> null, () -> rooms::isPreviewing).test(BOB),
                "a previewer is busy, so nothing is delivered to them meanwhile");
        rooms.tick();
        assertTrue(rooms.isPreviewing(BOB), "the room keeps a previewer though they are out of the lobby");
        assertEquals(room, rooms.roomOf(BOB).orElseThrow());

        rooms.preview(player(BOB));
        assertEquals("You left the map preview. You are in the lobby, still in your room; "
                + "/narena leave again leaves the room.",
                last(BOB));
        assertBackInRoom(BOB, room);

        // /narena leave ends the preview first, and only that.
        startPreview(BOB, room);
        assertTrue(previews.leave(player(BOB)));
        assertEquals("You left the map preview. You are in the lobby, still in your room; "
                + "/narena leave again leaves the room.",
                last(BOB));
        assertBackInRoom(BOB, room);
        assertFalse(previews.leave(player(BOB)), "nothing more to end");
    }

    @Test
    void startingTheMatchReturnsPreviewersFirstAndTakesThemFromTheLobby() {
        Room room = placedRoom();
        startPreview(BOB, room);
        int before = fake(BOB).teleports.size();
        rooms.start(player(ALICE), null);
        assertTrue(room.isPlaying());
        assertFalse(rooms.isPreviewing(BOB));
        assertTrue(matches.isInMatch(BOB));
        assertNotEquals(GameMode.SPECTATOR, fake(BOB).gameMode);
        List<Location> moves = fake(BOB).teleports.subList(before, fake(BOB).teleports.size());
        assertTrue(PluginWorlds.isLobby(moves.getFirst()), "back to the lobby before the match: " + moves);
        assertTrue(PluginWorlds.isArena(moves.getLast()));
        assertEquals(List.of(ALICE, BOB), matches.matchOf(ALICE).orElseThrow().players().stream().sorted().toList());
    }

    @Test
    void aPreviewerTheLobbyCannotTakeAtTheStartIsSentHomeAndLeftOut() {
        Room room = openWith(ALICE, BOB, CAROL);
        room.setSettings(room.settings().withPlayers(2));
        rooms.playGenerated(room, 42, 1);
        drain();
        startPreview(CAROL, room);
        lobbyRefuses = true;
        rooms.start(player(ALICE), null);
        assertHome(CAROL);
        assertFalse(rooms.isMember(CAROL));
        lobbyRefuses = false;
        assertTrue(room.isPlaying(), "the others still play");
        assertFalse(matches.isInMatch(CAROL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"preview", "leave", "check", "clearing"})
    void aPreviewerSentHomeLeavesTheRoomAtOnceSoAStartBeforeTheNextTickLeavesThemOut(String way) {
        Room room = openWith(ALICE, BOB, CAROL);
        room.setSettings(room.settings().withPlayers(2));
        rooms.playGenerated(room, 42, 1);
        drain();
        startPreview(CAROL, room);
        lobbyRefuses = true;
        switch (way) {
            case "preview" -> rooms.preview(player(CAROL));
            case "leave" -> assertTrue(previews.leave(player(CAROL)));
            case "check" -> {
                room.setPlaying(true);
                rooms.tick();
                room.setPlaying(false);
            }
            case "clearing" -> previews.clearing(generated.cellOf(room.arena()).orElseThrow());
            default -> throw new IllegalArgumentException(way);
        }
        lobbyRefuses = false;
        assertHome(CAROL);
        assertFalse(rooms.isMember(CAROL), "out of the room as soon as they are home");
        if (way.equals("preview") || way.equals("leave")) {
            assertEquals(PREVIEW_LEFT_HOME, last(CAROL));
        }

        rooms.start(player(ALICE), null);
        assertTrue(room.isPlaying());
        assertFalse(matches.isInMatch(CAROL), "never taken into the match from home");
        assertFalse(rooms.isMember(CAROL));
        assertTrue(matches.isInMatch(ALICE) && matches.isInMatch(BOB));
    }

    @Test
    void aRoomClosedWhileItsMapIsFreedForANewOneKeepsNoSlotOrCell() {
        Room room = placedRoom();
        fake(BOB).permissions.remove(RoomService.CREATE);
        startPreview(ALICE, room);
        lobbyRefuses = true;
        // Three teams need another layout: the map is freed, which ends Alice's preview; the
        // lobby refuses her, so she is home and out of the room, and nobody left may host.
        rooms.settings(player(ALICE), "teams", "3");
        lobbyRefuses = false;
        assertHome(ALICE);
        assertTrue(rooms.rooms().isEmpty(), "the room closed");
        assertFalse(rooms.hasLease(room), "and gave up its slot");
        drain();
        assertEquals(List.of(), generated.inUse(), "its cell is free");

        rooms.create(player(CAROL));
        rooms.create(player(DAVE));
        Room first = rooms.hostedBy(CAROL).orElseThrow();
        Room second = rooms.hostedBy(DAVE).orElseThrow();
        rooms.playGenerated(first, 43, 1);
        rooms.playGenerated(second, 44, 1);
        assertTrue(rooms.hasLease(first) && rooms.hasLease(second), "both slots are there for new rooms");
        drain();
        assertTrue(rooms.canPreview(first) && rooms.canPreview(second));
    }

    @Test
    void disconnectingWhilePreviewingPutsThePlayerInTheLobbyOnRejoin() {
        Room room = placedRoom();
        startPreview(BOB, room);
        quit(BOB);
        assertFalse(rooms.isPreviewing(BOB));
        assertFalse(rooms.isMember(BOB), "a room member who quits leaves the room, as before");
        assertTrue(snapshots.hasPending(BOB));
        assertEquals(GameMode.SPECTATOR, fake(BOB).gameMode, "the server saved them in spectator mode");

        fake(BOB).online = true;
        JoinRestore joins = new JoinRestore(restores, (ticks, task) -> nextTick.add(task),
                plugin.getServer()::getPlayer, id -> lobby.contains(id) || rooms.isPreviewing(id), returns);
        joins.onJoin(BOB);
        runNextTick();
        assertBackInRoom(BOB, null);
        assertTrue(rooms.roomOf(BOB).isEmpty());
        rooms.accept(player(BOB), "Alice");
        assertEquals(room, rooms.roomOf(BOB).orElseThrow(), "and can join the room again");
    }

    private void rejoin(UUID id) {
        fake(id).online = true;
        new JoinRestore(restores, (ticks, task) -> nextTick.add(task), plugin.getServer()::getPlayer,
                player -> lobby.contains(player) || rooms.isPreviewing(player), returns).onJoin(id);
        runNextTick();
    }

    @Test
    void aHostWhoDisconnectsWhilePreviewingIsBackInTheLobbyAndHostsTheRoomOnRejoin() {
        Room room = placedRoom();
        startPreview(ALICE, room);
        quit(ALICE);
        assertFalse(rooms.isPreviewing(ALICE));
        assertTrue(room.isHostAway(), "held for the host grace");
        rooms.tick();
        assertTrue(room.contains(ALICE));
        assertTrue(rooms.hasLease(room));
        assertTrue(rooms.canPreview(room), "the map stays placed");

        rejoin(ALICE);
        assertBackInRoom(ALICE, room);
        assertEquals(ALICE, room.host());
        assertFalse(room.isHostAway());
        assertEquals("Alice is back and hosts the room again.", last(BOB));
    }

    @Test
    void aPreviewingHostTheClosedLobbyTurnsAwayOnRejoinGoesHomeAndTheRoomIsHandedOn() {
        Room room = placedRoom();
        startPreview(ALICE, room);
        quit(ALICE);
        lobbyRefuses = true;
        rejoin(ALICE);
        assertHome(ALICE);
        assertFalse(room.contains(ALICE));
        assertEquals(BOB, room.host());
        assertTrue(rooms.hasLease(room));
    }

    @Test
    void aCrashWhilePreviewingRestoresThePlayerHomeOnTheirNextJoin() {
        Room room = placedRoom();
        startPreview(BOB, room);
        // The server stops without disabling the plugin: nothing in memory survives.
        fake(BOB).online = false;
        RestoreService afterRestart = restoreService();
        afterRestart.markPendingOnStartup();
        MapPreviews fresh = new MapPreviews(plugin, () -> arenaWorld, snapshots::hasPending, afterRestart::restore);
        gameModeChanges = fresh::onGameMode;
        teleportEvents = fresh::onTeleport;
        fake(BOB).online = true;
        new JoinRestore(afterRestart, (ticks, task) -> nextTick.add(task), plugin.getServer()::getPlayer)
                .onJoin(BOB);
        assertEquals(GameMode.SPECTATOR, fake(BOB).gameMode);
        runNextTick();
        // The rooms of the stopped server are gone with it; the restore alone brings them back.
        assertRestored(BOB);
    }

    @Test
    void disablingThePluginRestoresPreviewersHomeBeforeTheLobbyCloses() {
        Room room = placedRoom();
        startPreview(BOB, room);
        rooms.close();
        assertHome(BOB);
        assertTrue(snapshots.hasPending(ALICE), "lobby members are left to the lobby's own close");
    }

    @Test
    void whenNeitherTheLobbyNorTheRestoreTakesThemTheyStillLeaveSpectatorMode() {
        Room room = placedRoom();
        startPreview(BOB, room);
        lobbyRefuses = true;
        restoreFails = true;
        rooms.preview(player(BOB));
        assertFalse(rooms.isPreviewing(BOB));
        assertEquals(GameMode.ADVENTURE, fake(BOB).gameMode);
        assertTrue(snapshots.hasPending(BOB), "kept for their next join");
        List<String> bob = fake(BOB).messages;
        assertEquals(List.of(PREVIEW_LEFT_HOME, "Your belongings could not be returned yet. Rejoin the server, or"
                + " ask an admin if this keeps happening."), bob.subList(bob.size() - 2, bob.size()));

        startPreview(ALICE, room);
        rooms.close();
        assertEquals(GameMode.ADVENTURE, fake(ALICE).gameMode, "on disable as well");
    }

    @Test
    void leavingTheRoomOrBeingRemovedEndsThePreview() {
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.playGenerated(room, 42, 1);
        drain();
        startPreview(CAROL, room);
        rooms.kick(player(ALICE), "Carol");
        assertBackInRoom(CAROL, null);
        assertFalse(rooms.isMember(CAROL));

        startPreview(BOB, room);
        rooms.leave(player(BOB));
        assertBackInRoom(BOB, null);
        assertFalse(rooms.isMember(BOB));
    }

    @Test
    void theRoomClosingEndsEveryPreviewOfIt() {
        Room room = placedRoom();
        fake(BOB).permissions.remove(RoomService.CREATE);
        startPreview(BOB, room);
        // The host goes and nobody left may host: the room closes.
        rooms.leave(player(ALICE));
        assertTrue(rooms.rooms().isEmpty());
        assertBackInRoom(BOB, null);
        assertTrue(rooms.roomOf(BOB).isEmpty());
    }

    @Test
    void clearingTheMapEndsItsPreviews() {
        Room room = placedRoom();
        startPreview(BOB, room);
        GridCell cell = generated.cellOf(room.arena()).orElseThrow();
        rooms.setArena(room, "dojo");
        assertBackInRoom(BOB, room);
        assertTrue(generated.inUse().contains(cell), "the cell is being cleared");
        drain();
    }

    @Test
    void theOnceASecondCheckEndsAPreviewTheRoomNoLongerAllows() {
        Room room = placedRoom();
        startPreview(BOB, room);
        room.setPlaying(true);
        rooms.tick();
        assertBackInRoom(BOB, room);
        room.setPlaying(false);

        startPreview(BOB, room);
        fake(BOB).online = false;
        rooms.tick();
        assertFalse(rooms.isPreviewing(BOB), "a player gone without a quit is forgotten");
    }

    @Test
    void aPreviewerStaysOverTheMapAndInSpectatorMode() {
        Room room = placedRoom();
        startPreview(BOB, room);
        ArenaBounds bounds = generated.get(room.arena()).orElseThrow().bounds();
        Location from = fake(BOB).location;
        Location far = new Location(arenaWorld, bounds.maxX() + 100, from.getY(), from.getZ());
        PlayerMoveEvent away = new PlayerMoveEvent(player(BOB), from, far);
        previews.onMove(away);
        assertTrue(away.isCancelled());
        Location near = new Location(arenaWorld, bounds.maxX() + 2, from.getY(), from.getZ());
        PlayerMoveEvent along = new PlayerMoveEvent(player(BOB), from, near);
        previews.onMove(along);
        assertFalse(along.isCancelled(), "some room around the map");
        PlayerTeleportEvent elsewhere = new PlayerTeleportEvent(player(BOB), from, new Location(overworld, 0, 70, 0),
                TeleportCause.COMMAND);
        previews.onTeleport(elsewhere);
        assertTrue(elsewhere.isCancelled(), "no teleport out of the arena world while previewing");

        player(BOB).setGameMode(GameMode.CREATIVE);
        assertEquals(GameMode.SPECTATOR, fake(BOB).gameMode);
        Entity entity = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[] {Entity.class},
                (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        PlayerStartSpectatingEntityEvent camera = new PlayerStartSpectatingEntityEvent(player(BOB), null, entity);
        previews.onCamera(camera);
        assertTrue(camera.isCancelled());
    }

    // ---- Screens ----

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theRoomScreenOffersStartOnlyOnceBuiltAndPreviewThenReturn(boolean dialog) {
        var h = new RoomScreenHarness(rooms, dialog);
        Player alice = h.wrap(player(ALICE));
        Player bob = h.wrap(player(BOB));
        Room room = openWith(ALICE, BOB);
        rooms.playGenerated(room, 42, 1);

        h.menus.open(alice);
        h.press(alice, "Your room");
        assertTrue(labels(h, ALICE, dialog).contains("Start (map still building)"));
        assertFalse(labels(h, ALICE, dialog).contains("Start match"));
        assertFalse(labels(h, ALICE, dialog).contains("Preview map"));
        h.press(alice, "Start (map still building)");
        assertEquals("The room's map is still being built (0%). Start once it is ready.", last(ALICE));
        assertFalse(room.isPlaying());

        drain();
        h.menus.open(bob);
        h.press(bob, "Your room");
        assertFalse(labels(h, BOB, dialog).contains("Start match"), "only the host starts");
        h.press(bob, "Preview map");
        assertTrue(rooms.isPreviewing(BOB));
        assertEquals(GameMode.SPECTATOR, fake(BOB).gameMode);
        h.menus.open(bob);
        h.press(bob, "Your room");
        assertTrue(labels(h, BOB, dialog).contains("Return from preview"));
        assertFalse(labels(h, BOB, dialog).contains("Preview map"));
        h.press(bob, "Return from preview");
        assertBackInRoom(BOB, room);

        h.menus.open(alice);
        h.press(alice, "Your room");
        assertTrue(labels(h, ALICE, dialog).contains("Start match"));
    }

    private static List<String> labels(RoomScreenHarness h, UUID id, boolean dialog) {
        if (dialog) {
            return h.forms.get(id).content().options().stream().map(option -> option.label()).toList();
        }
        return List.copyOf(h.items.get(h.windows.get(id)).values());
    }
}
