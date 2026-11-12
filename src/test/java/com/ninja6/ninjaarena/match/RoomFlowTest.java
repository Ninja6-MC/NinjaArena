package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.command.HostingCommands;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.event.EventSettings;
import com.ninja6.ninjaarena.room.Room;
import com.ninja6.ninjaarena.room.RoomLobby;
import com.ninja6.ninjaarena.room.RoomService;
import com.ninja6.ninjaarena.sound.Sounds;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.CrossWorldTransfers;
import com.ninja6.ninjaarena.world.LobbyWorld;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rooms end to end with the real match engine and arena registry: creating, inviting,
 * starting, the slot leases, and every way out of a room or its match.
 */
class RoomFlowTest extends MatchFixture {

    private static final UUID EVE = new UUID(0, 5);
    private static final UUID FRANK = new UUID(0, 6);

    private final World lobbyWorld = world(LobbyWorld.NAME);
    private final Set<UUID> lobby = new LinkedHashSet<>();
    private final Set<UUID> lobbyRefuses = new HashSet<>();
    private final CrossWorldTransfers transfers = new CrossWorldTransfers();
    private final List<String> worldHolds = new ArrayList<>();
    private RoomService rooms;

    @BeforeEach
    void setUpRooms() throws Exception {
        arena("garden", new GridCell(1, 0));
        arena("temple", new GridCell(-1, 0));
        add(EVE, "Eve");
        add(FRANK, "Frank");
        for (Fake fake : players.values()) {
            fake.location = new Location(overworld, 0, 70, 0);
            // What every player has by default on a server where nobody has op.
            fake.permissions.add(NinjaArenaCommand.PLAY);
            fake.permissions.add(RoomService.CREATE);
        }
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
                if (lobby.contains(player.getUniqueId())) {
                    return Optional.empty();
                }
                return enter(player) ? Optional.empty() : Optional.of("The lobby is closed.");
            }

            @Override
            public boolean enter(Player player) {
                UUID id = player.getUniqueId();
                if (lobbyRefuses.contains(id)) {
                    return false;
                }
                players.get(id).location = new Location(lobbyWorld, 0, 64, 0);
                lobby.add(id);
                return true;
            }
        };
        rooms = new RoomService(plugin, arenas, matches, fakeLobby, () -> new WaitingRoomSettings(2),
                transfers, () -> {
                    worldHolds.add("hold");
                    return () -> worldHolds.add("release");
                }, 2);
        matches.setLobby(rooms::enterLobby);
        MatchListener crossing = new MatchListener(matches, deaths, transfers);
        teleportEvents = event -> {
            // As the lobby listener does: a teleport out of the lobby world ends membership.
            if (!PluginWorlds.isLobby(event.getTo())) {
                lobby.remove(event.getPlayer().getUniqueId());
            }
            crossing.onTeleport(event);
        };
    }

    private void arena(String name, GridCell cell) throws Exception {
        int x = cell.x() * 256;
        arenas.create(name, cell);
        arenas.update(name, a -> a.withBounds(new ArenaBounds(x + 10, 60, 10, x + 90, 100, 90))
                .withWaitingRoom(new ArenaPoint(x + 50.5, 64, 50.5, 0, 0))
                .withTeamSpawn(1, new ArenaPoint(x + 20.5, 64, 20.5, 90, 0))
                .withTeamSpawn(2, new ArenaPoint(x + 80.5, 64, 80.5, -90, 0))
                .withEnemySpawn(new ArenaPoint(x + 50.5, 64, 20.5, 0, 0)));
        arenas.finishEdit(name);
    }

    private Room room(UUID host) {
        return rooms.hostedBy(host).orElseThrow();
    }

    private String lastMessage(UUID id) {
        return players.get(id).messages.getLast();
    }

    /** Opens a room for the host and lets the guests in by invitation. */
    private Room openWith(UUID host, UUID... guests) {
        rooms.create(player(host));
        for (UUID guest : guests) {
            rooms.invite(player(host), players.get(guest).name);
            rooms.accept(player(guest), players.get(host).name);
        }
        return room(host);
    }

    /** Ticks until the match in the arena has closed. */
    private void playToTheEnd(String arena) {
        for (int i = 0; i < 100 && matches.match(arena).isPresent(); i++) {
            matches.tick();
            runNextTick();
        }
        assertTrue(matches.match(arena).isEmpty(), "the match closed");
    }

    private static CommandSender console() {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "sendMessage" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void joinCuesStayInsideTheirRoomIncludingTheJoiningPlayer() {
        rooms.setSounds(new Sounds(plugin.getServer()::getPlayer));
        rooms.create(player(ALICE));
        rooms.create(player(CAROL));
        players.values().forEach(fake -> fake.sounds.clear());
        rooms.invite(player(ALICE), "Bob");
        rooms.accept(player(BOB), "Alice");
        assertEquals(1, players.get(ALICE).sounds.size());
        assertEquals(1, players.get(BOB).sounds.size());
        assertTrue(players.get(CAROL).sounds.isEmpty());
        assertTrue(players.get(DAVE).sounds.isEmpty());
    }

    @Test
    void portalAndHostingJoinsPlayTheJoinCueLikeCommandJoins() {
        rooms.setSounds(new Sounds(plugin.getServer()::getPlayer));
        rooms.create(player(ALICE));
        players.values().forEach(fake -> fake.sounds.clear());
        rooms.fromPortal(player(BOB), Optional.of("dojo"));
        assertTrue(room(ALICE).contains(BOB));
        assertEquals(1, players.get(ALICE).sounds.size());
        assertEquals(1, players.get(BOB).sounds.size());

        players.get(CAROL).permissions.add(NinjaArenaCommand.HOST);
        HostingCommands hosting = rooms;
        hosting.host(player(CAROL), "temple");
        players.values().forEach(fake -> fake.sounds.clear());
        hosting.invite(player(CAROL), "Dave");
        hosting.accept(player(DAVE), "Carol");
        assertEquals(1, players.get(CAROL).sounds.size());
        assertEquals(1, players.get(DAVE).sounds.size());
        assertTrue(players.get(ALICE).sounds.isEmpty() && players.get(BOB).sounds.isEmpty());

        players.values().forEach(fake -> fake.sounds.clear());
        rooms.fromPortal(player(EVE), Optional.of("garden"));
        assertTrue(players.get(EVE).sounds.isEmpty(), "no room to join: no cue");
    }

    @Test
    void mapChangesRefuseEachInvalidChoiceWithoutMovingTheLease() throws Exception {
        Room room = openWith(ALICE, BOB);
        rooms.mapSet(player(ALICE), "garden");
        assertEquals("You do not have permission to change your room's map.", lastMessage(ALICE));
        players.get(ALICE).permissions.add(NinjaArenaCommand.MAP_CHANGE);
        players.get(BOB).permissions.add(NinjaArenaCommand.MAP_CHANGE);
        players.get(CAROL).permissions.add(NinjaArenaCommand.MAP_CHANGE);
        rooms.mapSet(player(CAROL), "garden");
        assertEquals("You are not in a room.", lastMessage(CAROL));
        rooms.mapSet(player(BOB), "garden");
        assertEquals("Only the host of the room can do that.", lastMessage(BOB));
        rooms.mapSet(player(ALICE), "missing");
        assertEquals("No arena called missing.", lastMessage(ALICE));
        rooms.mapSet(player(ALICE), "garden");
        assertEquals("Arena garden is not pickable.", lastMessage(ALICE));
        arenas.startEdit("temple");
        arenas.update("temple", a -> a.withoutLastTeamSpawn());
        arenas.finishEdit("temple");
        arenas.setPickable("temple", true);
        rooms.mapSet(player(ALICE), "temple");
        assertEquals("Arena temple needs at least two team spawns to be chosen.", lastMessage(ALICE));
        assertEquals("dojo", room.arena());
        assertTrue(rooms.hasLease(room));
        rooms.start(player(ALICE), null);
        rooms.mapSet(player(ALICE), "garden");
        assertEquals("The room's map cannot change while it is playing.", lastMessage(ALICE));
        assertEquals("dojo", room.arena());
    }

    @Test
    void choosingTheCurrentMapKeepsTheLeaseAndQueueAndOnlyTellsTheSender() throws Exception {
        arenas.setPickable("dojo", true);
        Room first = openWith(ALICE, BOB);
        Room second = openWith(CAROL, DAVE);
        rooms.setArena(second, "dojo");
        Room third = openWith(EVE, FRANK);
        rooms.setArena(third, "dojo");
        players.get(ALICE).permissions.add(NinjaArenaCommand.MAP_CHANGE);
        players.get(CAROL).permissions.add(NinjaArenaCommand.MAP_CHANGE);

        for (UUID sender : List.of(ALICE, CAROL)) {
            players.values().forEach(fake -> fake.messages.clear());
            rooms.mapSet(player(sender), "DOJO");

            assertTrue(rooms.hasLease(first));
            assertTrue(rooms.position(first).isEmpty());
            assertFalse(rooms.hasLease(second));
            assertFalse(rooms.hasLease(third));
            assertEquals(1, rooms.position(second).orElseThrow());
            assertEquals(2, rooms.position(third).orElseThrow());
            for (UUID member : players.keySet()) {
                assertEquals(member.equals(sender) ? List.of("The room is already on map dojo.") : List.of(),
                        players.get(member).messages);
            }
        }
    }

    @Test
    void settingTheCurrentArenaDirectlyKeepsBothTheLeaseAndQueuedRoomsInPlace() {
        Room first = openWith(ALICE, BOB);
        Room second = openWith(CAROL, DAVE);
        rooms.setArena(second, "dojo");
        Room third = openWith(EVE, FRANK);
        rooms.setArena(third, "dojo");
        var settings = first.settings();
        players.values().forEach(fake -> fake.messages.clear());

        rooms.setArena(first, "DOJO");
        rooms.setArena(second, "DOJO");

        assertEquals("dojo", first.arena());
        assertEquals(settings, first.settings());
        assertTrue(rooms.hasLease(first));
        assertTrue(rooms.position(first).isEmpty());
        assertFalse(rooms.hasLease(second));
        assertFalse(rooms.hasLease(third));
        assertEquals(1, rooms.position(second).orElseThrow());
        assertEquals(2, rooms.position(third).orElseThrow());
        players.values().forEach(fake -> assertTrue(fake.messages.isEmpty()));
    }

    @Test
    void choosingABusyMapQueuesTheRoomAndGrantsItsOldSlotToTheNextRoom() throws Exception {
        arenas.setPickable("garden", true);
        Room first = openWith(ALICE, BOB);
        Room second = openWith(CAROL, DAVE);
        Room third = openWith(EVE, FRANK);
        rooms.setArena(third, "dojo");
        players.get(ALICE).permissions.add(NinjaArenaCommand.MAP_CHANGE);
        rooms.mapSet(player(ALICE), "GARDEN");
        assertEquals("garden", first.arena());
        assertFalse(rooms.hasLease(first));
        assertTrue(rooms.hasLease(second));
        assertTrue(rooms.hasLease(third), "the room waiting for dojo takes the released slot");
        assertEquals(1, rooms.position(first).orElseThrow());
        for (UUID member : List.of(ALICE, BOB)) {
            assertTrue(players.get(member).messages.contains("Alice changed the room's map from dojo to garden."));
            assertTrue(lastMessage(member).contains("number 1 in the queue"));
        }
        rooms.leave(player(CAROL));
        rooms.leave(player(DAVE));
        assertTrue(rooms.hasLease(first));
    }

    @Test
    void choosingAFreeMapMovesTheLeaseAndReducesTeams() throws Exception {
        arenas.startEdit("dojo");
        arenas.update("dojo", a -> a.withTeamSpawn(3, a.teamSpawns().getFirst())
                .withTeamSpawn(4, a.teamSpawns().getFirst()));
        arenas.finishEdit("dojo");
        arenas.setPickable("garden", true);
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.settings(player(ALICE), "team-size", "1");
        assertEquals(4, room.settings().teams());
        players.get(ALICE).permissions.add(NinjaArenaCommand.MAP_CHANGE);
        rooms.mapSet(player(ALICE), "garden");
        assertEquals("garden", room.arena());
        assertEquals(2, room.settings().teams());
        assertEquals(0, room.settings().teamSize(), "existing members still fit");
        assertTrue(rooms.hasLease(room));
        assertTrue(rooms.position(room).isEmpty());
        for (UUID member : room.members()) {
            assertEquals("Alice changed the room's map from dojo to garden.", lastMessage(member));
        }
        rooms.create(player(DAVE));
        assertEquals("dojo", room(DAVE).arena());
        assertTrue(rooms.hasLease(room(DAVE)), "the old arena is free");
    }

    @Test
    void mapListIsForAnyRoomMemberAndMarksOnlyThePickableCurrentMap() throws Exception {
        rooms.mapList(player(ALICE));
        assertTrue(lastMessage(ALICE).contains("not in a room"));
        openWith(ALICE, BOB);
        rooms.mapList(player(BOB));
        assertEquals("No maps are pickable.", lastMessage(BOB));
        arenas.setPickable("dojo", true);
        arenas.setPickable("garden", true);
        players.get(BOB).messages.clear();
        rooms.mapList(player(BOB));
        assertEquals(List.of("Pickable maps:", "- dojo (current)", "- garden"), players.get(BOB).messages);
        assertEquals(List.of("dojo", "garden"), rooms.pickableMaps());
        assertEquals("dojo", room(ALICE).arena(), "a host without the map node keeps the default");
    }

    @Test
    void noMatchStartsWithoutTheHostsOrAnAdminsPress() {
        Room room = openWith(ALICE, BOB, CAROL);
        assertTrue(room.canStart());
        for (int i = 0; i < 120; i++) {
            rooms.tick();
            matches.tick();
        }
        assertTrue(matches.matches().isEmpty(), "nothing counts down");

        rooms.start(player(BOB), null);
        assertEquals("Only the host or an admin can start the match of this room.", lastMessage(BOB));
        assertTrue(matches.matches().isEmpty());

        rooms.start(player(ALICE), null);
        MatchView match = matches.match("dojo").orElseThrow();
        assertEquals(Set.of(ALICE, BOB, CAROL), Set.copyOf(match.players()));
        assertTrue(room.isPlaying());
        assertTrue(players.get(BOB).messages.contains("The match is starting in arena dojo."));
    }

    @Test
    void anAdminCanStartSomeoneElsesRoomByTheHostsName() {
        openWith(ALICE, BOB);
        players.get(DAVE).permissions.add(NinjaArenaCommand.ADMIN);
        rooms.start(player(DAVE), "alice");
        assertTrue(matches.match("dojo").isPresent());
        assertFalse(matches.isInMatch(DAVE), "the admin does not join the match");

        openWith(CAROL, EVE);
        rooms.start(console(), "Carol");
        assertTrue(matches.match("garden").isPresent());
    }

    @Test
    void aRoomBelowItsRequiredCountCannotStart() {
        rooms.create(player(ALICE));
        rooms.start(player(ALICE), null);
        assertEquals("The room needs 2 players to start; it has 1.", lastMessage(ALICE));
        assertTrue(matches.matches().isEmpty());

        rooms.settings(player(ALICE), "players", "3");
        rooms.invite(player(ALICE), "Bob");
        rooms.accept(player(BOB), "Alice");
        rooms.start(player(ALICE), null);
        assertEquals("The room needs 3 players to start; it has 2.", lastMessage(ALICE));
        assertTrue(matches.matches().isEmpty());
    }

    @Test
    void withMaxActiveTwoAndThreeArenasTheThirdRoomQueuesUntilALeaseIsReleased() {
        Room first = openWith(ALICE, BOB);
        Room second = openWith(CAROL, DAVE);
        rooms.create(player(EVE));
        Room third = room(EVE);
        assertEquals(List.of("dojo", "garden", "temple"), List.of(first.arena(), second.arena(), third.arena()));
        assertTrue(rooms.hasLease(first) && rooms.hasLease(second));
        assertFalse(rooms.hasLease(third));
        assertEquals("Every arena slot is in use. Your room is number 1 in the queue.", lastMessage(EVE));
        rooms.invite(player(EVE), "Frank");
        rooms.accept(player(FRANK), "Eve");

        rooms.start(player(EVE), null);
        assertEquals("Every arena slot is in use. Your room is number 1 in the queue.", lastMessage(EVE));
        assertTrue(matches.match("temple").isEmpty());
        rooms.tick();
        assertEquals("Waiting for an arena: number 1 in the queue", players.get(FRANK).actionBars.getLast());

        // The lease lasts through the start, every round and the end of the match.
        rooms.start(player(ALICE), null);
        for (int i = 0; i < 12; i++) {
            matches.tick();
            rooms.tick();
            assertFalse(rooms.hasLease(third));
        }
        playToTheEnd("dojo");

        assertTrue(rooms.hasLease(third), "the first room is back in the lobby");
        assertTrue(players.get(FRANK).messages.contains("Arena temple is ready for your room."));
        assertFalse(rooms.hasLease(first));
        assertEquals(1, rooms.position(first).orElseThrow(), "the first room queues for its next match");
        rooms.start(player(EVE), null);
        assertTrue(matches.match("temple").isPresent());
    }

    @Test
    void aRoomThatClosesGivesUpItsLeaseToTheNextInTheQueue() {
        openWith(ALICE, BOB);
        rooms.create(player(CAROL));
        rooms.create(player(DAVE));
        Room queued = room(DAVE);
        assertFalse(rooms.hasLease(queued));

        rooms.leave(player(CAROL));
        assertTrue(rooms.hostedBy(CAROL).isEmpty(), "nobody was left to host it");
        assertTrue(rooms.hasLease(queued));
        assertTrue(players.get(DAVE).messages.contains("Arena temple is ready for your room."));
    }

    @Test
    void aLowerMaxActiveOnReloadNeverEndsAGrantedLease() {
        rooms.setMaxActive(3);
        Room first = openWith(ALICE, BOB);
        Room second = openWith(CAROL, DAVE);
        Room third = openWith(EVE, FRANK);
        rooms.start(player(ALICE), null);

        rooms.setMaxActive(1);
        assertEquals(1, rooms.maxActive());
        assertTrue(rooms.hasLease(first) && rooms.hasLease(second) && rooms.hasLease(third));
        assertTrue(matches.match("dojo").isPresent(), "the running match goes on");
        rooms.start(player(CAROL), null);
        assertTrue(matches.match("garden").isPresent(), "a granted lease still starts");

        rooms.leave(player(EVE));
        rooms.leave(player(FRANK));
        rooms.create(player(EVE));
        assertFalse(rooms.hasLease(room(EVE)));
    }

    @Test
    void aReloadThatChangesTheGridWorksTheCapOutAgainFromTheConfiguredValue() throws Exception {
        for (String name : List.of("dojo", "garden", "temple")) {
            arenas.delete(name);
        }
        rooms.setMaxActive(20);
        assertEquals(20, rooms.maxActive());

        arenas.setGrid(new com.ninja6.ninjaarena.arena.ArenaGrid(256, 512, -64, 320));
        rooms.gridChanged();
        int cells = arenas.grid().cellsNearestFirst().size();
        assertTrue(cells < 20, "cells " + cells);
        assertEquals(cells, rooms.maxActive());

        arenas.setGrid(new com.ninja6.ninjaarena.arena.ArenaGrid(256, 2048, -64, 320));
        rooms.gridChanged();
        assertEquals(20, rooms.maxActive(), "back to the configured value once the grid has room");
    }

    @Test
    void maxActiveIsLimitedToTheCellsOfTheArenaGrid() {
        rooms.setMaxActive(100);
        int cells = arenas.grid().cellsNearestFirst().size();
        assertEquals(Math.min(100, cells), rooms.maxActive());
    }

    @Test
    void aServerWhereNobodyHasOpCanCreateRoomsAndPlay() {
        for (Fake fake : players.values()) {
            assertEquals(Set.of(NinjaArenaCommand.PLAY, RoomService.CREATE), fake.permissions);
        }
        rooms.create(player(ALICE));
        rooms.invite(player(ALICE), "Bob");
        rooms.accept(player(BOB), "Alice");
        rooms.start(player(ALICE), null);
        assertEquals(Set.of(ALICE, BOB), Set.copyOf(matches.match("dojo").orElseThrow().players()));
        playToTheEnd("dojo");
        assertEquals(Set.of(ALICE, BOB), lobby);
        assertEquals(List.of(), restored);
    }

    @Test
    void withoutTheNodeAPlayerCannotOpenARoom() {
        players.get(ALICE).permissions.remove(RoomService.CREATE);
        rooms.create(player(ALICE));
        assertEquals("You do not have permission to open a room.", lastMessage(ALICE));
        assertTrue(rooms.rooms().isEmpty());
        assertFalse(lobby.contains(ALICE));
    }

    @Test
    void roomPlayersCrossFromTheLobbyIntoTheirMatchWithoutLeavingIt() {
        openWith(ALICE, BOB);
        assertEquals(lobbyWorld, players.get(ALICE).location.getWorld());
        rooms.start(player(ALICE), null);
        assertTrue(matches.isInMatch(ALICE) && matches.isInMatch(BOB));
        assertEquals(arenaWorld, players.get(ALICE).location.getWorld());
        assertFalse(lobby.contains(ALICE), "they left the lobby for the match");
        assertTrue(rooms.isMember(ALICE));
    }

    // ---- Exits, one by one ----

    @Test
    void theEndOfTheMatchReturnsTheRoomToTheLobby() {
        Room room = openWith(ALICE, BOB);
        rooms.start(player(ALICE), null);
        playToTheEnd("dojo");

        assertEquals(Set.of(ALICE, BOB), lobby);
        assertEquals(lobbyWorld, players.get(BOB).location.getWorld());
        assertEquals(List.of(), restored, "nobody is sent home");
        assertFalse(room.isPlaying());
        assertEquals(List.of(ALICE, BOB), room.members());
        assertTrue(players.get(BOB).messages.contains("The match is over and the room is back in the lobby."));
        assertFalse(arenas.isHeld("dojo"));
    }

    @Test
    void leavingARoomInTheLobbyStaysInTheLobby() {
        Room room = openWith(ALICE, BOB);
        rooms.leave(player(BOB));
        assertEquals("You left the room.", lastMessage(BOB));
        assertFalse(room.contains(BOB));
        assertTrue(lobby.contains(BOB));
        assertEquals(List.of(), restored);
    }

    @Test
    void theLeaveCommandFromAMatchGoesToTheLobby() {
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.start(player(ALICE), null);

        assertTrue(rooms.leaveRoom(player(CAROL)));
        assertFalse(matches.isInMatch(CAROL));
        assertFalse(room.contains(CAROL));
        assertTrue(lobby.contains(CAROL));
        assertEquals(lobbyWorld, players.get(CAROL).location.getWorld());
        assertEquals(List.of(), restored);
    }

    @Test
    void theLeaveCommandFromAMatchWithNoRoomAlsoGoesToTheLobby() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        assertFalse(rooms.leaveRoom(player(ALICE)), "not in a room");
        assertTrue(matches.leave(player(ALICE), MatchService.Exit.COMMAND));
        assertTrue(lobby.contains(ALICE));
        assertEquals(List.of(), restored);
    }

    @Test
    void whenTheLobbyCannotTakeThemPlayersAreSentHome() {
        openWith(ALICE, BOB);
        rooms.start(player(ALICE), null);
        lobbyRefuses.add(BOB);
        playToTheEnd("dojo");
        assertEquals(List.of(BOB), restored);
        assertTrue(lobby.contains(ALICE));
        rooms.tick();
        assertFalse(rooms.isMember(BOB), "out of the room once out of the lobby and the match");
    }

    @Test
    void aTeleportOutOfThePluginWorldsGoesHome() {
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.start(player(ALICE), null);

        player(CAROL).teleport(new Location(overworld, 5, 70, 5), PlayerTeleportEvent.TeleportCause.COMMAND);
        assertFalse(matches.isInMatch(CAROL));
        runNextTick();
        assertTrue(restored.contains(CAROL));
        rooms.tick();
        assertFalse(room.contains(CAROL));

        // From the lobby as well: the lobby sends them home and the room lets them go.
        playToTheEnd("dojo");
        player(BOB).teleport(new Location(overworld, 5, 70, 5), PlayerTeleportEvent.TeleportCause.COMMAND);
        rooms.tick();
        assertFalse(room.contains(BOB));
        assertFalse(lobby.contains(BOB));
    }

    @Test
    void theLeaseIsKeptThroughTheShopAndTheRoomComesBackWhenTheMatchCloses() {
        matches.setShopSettings(new ShopSettings(true, 30, 5, 10));
        Location shop = new Location(lobbyWorld, 31, 65, 12.5, 180, 0);
        matches.setShopArea(() -> shop);
        // As the plugin registers it.
        transfers.allow(matches::crossesWorlds);
        Room room = openWith(ALICE, BOB, CAROL, DAVE);
        rooms.start(player(ALICE), null);
        MatchView match = matches.match("dojo").orElseThrow();
        ticks(SETTINGS.prepSeconds());
        List<UUID> losers = match.playersOf(2);
        List<UUID> winners = match.playersOf(1);
        assertEquals(2, losers.size());
        for (UUID id : losers) {
            matches.died(player(id), null);
        }
        runNextTick();
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.SHOP, match.phase());
        rooms.tick();
        for (UUID id : match.players()) {
            assertTrue(PluginWorlds.isLobby(players.get(id).location), players.get(id).name + " is in the shop");
            assertTrue(rooms.isMember(id), "still in the room");
            assertFalse(lobby.contains(id), "a match player, not a lobby member");
        }
        assertTrue(rooms.hasLease(room));
        assertTrue(room.isPlaying());

        // A disconnect in the shop: out of the match and the room; the lease stays.
        UUID quitter = losers.getFirst();
        PlayerQuitEvent quit = new PlayerQuitEvent(player(quitter), Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED);
        new MatchListener(matches, deaths, transfers).onQuit(quit);
        rooms.onQuit(quit);
        players.get(quitter).online = false;
        assertFalse(matches.isInMatch(quitter));
        assertFalse(rooms.isMember(quitter));
        assertEquals(MatchPhase.SHOP, match.phase());
        assertTrue(rooms.hasLease(room));

        // The last player of team 2 leaves: a forfeit, and the lease lasts until the match closes.
        assertTrue(rooms.leaveRoom(player(losers.getLast())));
        assertTrue(lobby.contains(losers.getLast()));
        assertEquals(MatchPhase.MATCH_END, match.phase());
        assertTrue(rooms.hasLease(room));
        assertTrue(room.isPlaying());
        playToTheEnd("dojo");
        assertFalse(room.isPlaying());
        for (UUID id : winners) {
            assertTrue(lobby.contains(id), "back in the lobby from the shop");
            assertTrue(players.get(id).messages.contains("The match is over and the room is back in the lobby."));
        }
    }

    @Test
    void quittingTakesThePlayerOutOfTheRoomAndHandsItOn() {
        Room room = openWith(ALICE, BOB, CAROL);
        players.get(BOB).permissions.remove(RoomService.CREATE);
        rooms.onQuit(new PlayerQuitEvent(player(ALICE), Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(CAROL, room.host(), "Bob waited longest but may not host");
        assertTrue(players.get(BOB).messages.contains("Carol is now the host of the room."));
        assertTrue(rooms.hostedBy(CAROL).isPresent());
    }

    // ---- Hosts, invitations and buttons ----

    @Test
    void theRoomClosesWhenNobodyLeftCanHostAndItsPlayersStayInTheLobby() {
        Room room = openWith(ALICE, BOB);
        players.get(BOB).permissions.remove(RoomService.CREATE);
        assertEquals(List.of("hold"), worldHolds);

        rooms.leave(player(ALICE));
        assertTrue(rooms.rooms().isEmpty());
        assertFalse(rooms.isMember(BOB));
        assertTrue(lobby.contains(BOB));
        assertEquals("The room closed: nobody left in it can host.", lastMessage(BOB));
        assertEquals(List.of("hold", "release"), worldHolds, "the arena world is let go with the last room");
        assertFalse(rooms.hasLease(room));
    }

    @Test
    void theHostLeavingMidMatchHandsOnAndTheMatchGoesOn() {
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.start(player(ALICE), null);
        rooms.leaveRoom(player(ALICE));
        assertEquals(BOB, room.host());
        assertTrue(matches.match("dojo").isPresent());
        playToTheEnd("dojo");
        assertEquals(List.of(BOB, CAROL), room.members());
        assertFalse(room.isPlaying());
    }

    @Test
    void invitationsArriveAsAJoinButtonAndInviteOnlyRoomsNeedThem() {
        rooms.create(player(ALICE));
        rooms.settings(player(ALICE), "privacy", "invite");
        rooms.accept(player(BOB), "Alice");
        assertEquals("The room of Alice is invite only, and you have no invitation.", lastMessage(BOB));

        rooms.invite(player(ALICE), "bob");
        Component invitation = players.get(BOB).messageComponents.getLast();
        assertEquals("Alice invited you to their room on arena dojo. [Join]", lastMessage(BOB));
        assertEquals(ClickEvent.runCommand("/ninjaarena room accept Alice"), clickIn(invitation));

        rooms.accept(player(BOB), "Alice");
        assertTrue(room(ALICE).contains(BOB));
        assertEquals("You joined the room of Alice on team 2.", lastMessage(BOB));
    }

    @Test
    void theHostSeesKickButtonsAndTheLobbyListHasInviteButtons() {
        openWith(ALICE, BOB);
        rooms.create(player(CAROL));
        rooms.leave(player(CAROL));
        rooms.show(player(ALICE));
        Component bobLine = players.get(ALICE).messageComponents.stream()
                .filter(line -> plain(line).startsWith("- Bob")).findFirst().orElseThrow();
        assertEquals(ClickEvent.runCommand("/ninjaarena room kick Bob"), clickIn(bobLine));

        rooms.invite(player(ALICE), null);
        Component carolLine = players.get(ALICE).messageComponents.getLast();
        assertEquals("- Carol [Invite]", plain(carolLine));
        assertEquals(ClickEvent.runCommand("/ninjaarena room invite Carol"), clickIn(carolLine));

        players.get(BOB).messageComponents.clear();
        rooms.show(player(BOB));
        assertTrue(players.get(BOB).messageComponents.stream().noneMatch(line -> clickIn(line) != null),
                "only the host gets buttons");
    }

    @Test
    void kickRemovesAPlayerToTheLobbyAndKeepsThemOutOfAPublicRoom() {
        Room room = openWith(ALICE, BOB);
        rooms.kick(player(BOB), "Alice");
        assertEquals("Only the host of the room can do that.", lastMessage(BOB));
        rooms.kick(player(ALICE), "Bob");
        assertFalse(room.contains(BOB));
        assertTrue(lobby.contains(BOB));
        assertEquals("Alice removed you from their room.", lastMessage(BOB));
        rooms.accept(player(BOB), "Alice");
        assertFalse(room.contains(BOB));

        rooms.invite(player(ALICE), "Bob");
        rooms.accept(player(BOB), "Alice");
        rooms.start(player(ALICE), null);
        rooms.kick(player(ALICE), "Bob");
        assertTrue(room.contains(BOB), "not during a match");
    }

    @Test
    void settingsAreHostOnlyValidatedAndToldToTheRoom() {
        Room room = openWith(ALICE, BOB);
        rooms.settings(player(BOB), "players", "4");
        assertEquals("Only the host of the room can do that.", lastMessage(BOB));
        rooms.settings(player(ALICE), "teams", "3");
        assertEquals("teams must be a whole number from 1 to 2.", lastMessage(ALICE));
        rooms.settings(player(ALICE), "team-size", "1");
        assertEquals(1, room.settings().teamSize());
        assertEquals("Alice set team-size to 1.", lastMessage(BOB));
        rooms.settings(player(ALICE), "players", "3");
        assertEquals("players must be a whole number from 1 to 2.", lastMessage(ALICE));
        rooms.settings(player(ALICE), "teams", "1");
        assertEquals("That leaves no space for 2 players.", lastMessage(ALICE));
        rooms.settings(player(ALICE), "colour", "red");
        assertEquals("Unknown setting colour. Use players, teams, team-size or privacy.", lastMessage(ALICE));
    }

    @Test
    void aDeletedArenaMovesItsRoomToAnotherOne() throws Exception {
        Room room = openWith(ALICE, BOB);
        arenas.delete("dojo");
        assertEquals("garden", room.arena(), "the first free arena");
        assertTrue(rooms.hasLease(room));
        assertTrue(players.get(BOB).messages.contains("Arena dojo was deleted, so the room now plays on arena garden."));
    }

    private static ClickEvent clickIn(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent found = clickIn(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void closingEveryRoomReleasesTheWorldHold() {
        openWith(ALICE, BOB);
        rooms.close();
        assertTrue(rooms.rooms().isEmpty());
        assertEquals(List.of("hold", "release"), worldHolds);
        assertNotNull(rooms);
    }

    // ---- Moving waiting rooms, the hub, hosting and events onto rooms ----

    /** Every running match belongs to a room that holds its arena's lease. */
    private void assertEveryMatchHoldsALease() {
        for (var match : matches.matches()) {
            assertTrue(rooms.rooms().stream().anyMatch(room -> room.arena().equals(match.arena())
                    && rooms.hasLease(room)) || closedRoomLeases.contains(match.arena()), match.arena());
        }
    }

    private final Set<String> closedRoomLeases = new HashSet<>();

    @Test
    void noMatchStartsWithoutAHostOrAdminPressFromAnyWayIn() {
        rooms.setFeatured(() -> Optional.of("garden"));
        // An old play portal, an old hub portal, /narena host, invitations and the clock.
        assertEquals(Optional.empty(), rooms.fromPortal(player(ALICE), Optional.of("dojo")));
        assertEquals(Optional.empty(), rooms.fromPortal(player(BOB), Optional.empty()));
        players.get(CAROL).permissions.add(NinjaArenaCommand.HOST);
        HostingCommands hosting = rooms;
        hosting.host(player(CAROL), "temple");
        hosting.invite(player(CAROL), "Dave");
        hosting.accept(player(DAVE), "Carol");
        rooms.create(player(EVE));
        rooms.invite(player(EVE), "Frank");
        rooms.accept(player(FRANK), "Eve");
        for (int second = 0; second < 120; second++) {
            rooms.tick();
            matches.tick();
            runNextTick();
        }
        assertTrue(matches.matches().isEmpty(), "full rooms with leases still wait for start");
        assertTrue(rooms.rooms().stream().noneMatch(Room::isPlaying));

        rooms.start(player(EVE), null);
        assertEquals(1, matches.matches().size());
        assertEveryMatchHoldsALease();
    }

    @Test
    void anOldPlayPortalJoinsTheFullestOpenRoomOnItsArenaOrStaysInTheLobby() {
        Room small = openWith(ALICE);
        Room big = openWith(CAROL, DAVE);
        rooms.setArena(big, "dojo");
        assertEquals("dojo", small.arena());
        rooms.fromPortal(player(EVE), Optional.of("DOJO"));
        assertTrue(big.contains(EVE), "the room with more players fills first");
        assertTrue(lobby.contains(EVE));

        rooms.fromPortal(player(FRANK), Optional.of("temple"));
        assertTrue(rooms.roomOf(FRANK).isEmpty());
        assertTrue(lobby.contains(FRANK), "no room on temple: the player waits in the lobby");
        assertTrue(players.get(FRANK).messages.contains("No open room on arena temple has space for you yet."
                + " Create one with /narena room create, or join another below."));
        assertTrue(players.get(FRANK).messages.contains("Open rooms:"));
        assertTrue(matches.matches().isEmpty());
    }

    @Test
    void anOldHubPortalJoinsAnyOpenPublicRoomFeaturedMapFirst() {
        Room first = openWith(ALICE, BOB);
        rooms.create(player(CAROL));
        Room featured = room(CAROL);
        assertEquals("garden", featured.arena());
        rooms.settings(player(ALICE), "privacy", "invite");
        rooms.setFeatured(() -> Optional.of("garden"));

        rooms.fromPortal(player(DAVE), Optional.empty());
        assertTrue(featured.contains(DAVE), "the featured map's room comes first");
        rooms.setFeatured(Optional::empty);
        rooms.fromPortal(player(EVE), Optional.empty());
        assertTrue(featured.contains(EVE), "an invite-only room takes no portal player");
        assertFalse(first.contains(EVE));
    }

    @Test
    void aPortalThatTheLobbyRefusesLeavesThePlayerOutsideWithTheReason() {
        lobbyRefuses.add(ALICE);
        assertEquals(Optional.of("The lobby is closed."), rooms.fromPortal(player(ALICE), Optional.empty()));
        assertTrue(rooms.roomOf(ALICE).isEmpty());
    }

    @Test
    void hostOpensAnInviteOnlyRoomOnTheNamedArenaAndInviteAndAcceptAreTheRoomOnes() {
        HostingCommands hosting = rooms;
        hosting.host(player(ALICE), "missing");
        assertEquals("No arena called missing.", lastMessage(ALICE));
        hosting.host(player(ALICE), "TEMPLE");
        Room room = room(ALICE);
        assertEquals("temple", room.arena(), "no pickable flag needed");
        assertTrue(room.settings().inviteOnly());
        assertTrue(rooms.hasLease(room));
        assertEquals("Your room on arena temple is invite only. Invite your players with /narena invite <player>.",
                lastMessage(ALICE));

        hosting.accept(player(BOB), "Alice");
        assertEquals("The room of Alice is invite only, and you have no invitation.", lastMessage(BOB));
        hosting.invite(player(ALICE), "Bob");
        hosting.accept(player(BOB), "Alice");
        assertTrue(room.contains(BOB));
        hosting.host(player(ALICE), "dojo");
        assertEquals("You are already in a room. Use /narena room leave first.", lastMessage(ALICE));
        assertTrue(matches.matches().isEmpty());
    }

    @Test
    void theRoomListShowsTheFeaturedMapAndNewRoomsPreferIt() {
        rooms.setFeatured(() -> Optional.of("temple"));
        rooms.show(player(EVE));
        assertEquals(List.of("Featured map: temple. Matches there earn the event bonus while the event runs.",
                "No rooms are open. Create one with /narena room create."), players.get(EVE).messages);

        Room room = openWith(ALICE, BOB);
        assertEquals("temple", room.arena(), "a free featured map is the default");
        players.get(EVE).messages.clear();
        rooms.show(player(EVE));
        assertEquals("Open rooms:", players.get(EVE).messages.get(1));
        assertEquals("- Alice on temple (featured) (2/2) [Join]", players.get(EVE).messages.get(2));
        rooms.show(player(ALICE));
        assertTrue(players.get(ALICE).messages.contains("Arena temple is the featured map of the event running"
                + " now: its matches earn the event bonus."));

        rooms.setFeatured(Optional::empty);
        players.get(EVE).messages.clear();
        rooms.show(player(EVE));
        assertEquals("- Alice on temple (2/2) [Join]", players.get(EVE).messages.get(1));
    }

    @Test
    void aRoomMatchOnTheFeaturedMapDuringTheEventGetsTheBonus() {
        EventSettings.Bonus bonus = new EventSettings.Bonus(500, null, false);
        String[] featured = {"temple"};
        matches.setEventBonus(arena -> arena.equals(featured[0]) ? bonus : EventSettings.Bonus.NONE);
        rooms.setFeatured(() -> Optional.ofNullable(featured[0]));
        Room onFeatured = openWith(ALICE, BOB);
        Room elsewhere = openWith(CAROL, DAVE);
        assertEquals("temple", onFeatured.arena());
        assertEquals("dojo", elsewhere.arena());
        rooms.start(player(ALICE), null);
        rooms.start(player(CAROL), null);
        assertEquals(bonus, matches.match("temple").orElseThrow().eventBonus());
        assertEquals(EventSettings.Bonus.NONE, matches.match("dojo").orElseThrow().eventBonus());

        playToTheEnd("temple");
        featured[0] = null;
        rooms.start(player(ALICE), null);
        assertEquals(EventSettings.Bonus.NONE, matches.match("temple").orElseThrow().eventBonus(),
                "after the event the same room plays without it");
    }

    @Test
    void aRoomThatClosesWhileItsMatchRunsKeepsTheLeaseUntilTheMatchEnds() {
        rooms.setMaxActive(1);
        Room room = openWith(ALICE, BOB, CAROL, DAVE);
        rooms.start(player(ALICE), null);
        rooms.create(player(EVE));
        Room queued = room(EVE);
        for (UUID guest : List.of(BOB, CAROL, DAVE)) {
            players.get(guest).permissions.remove(RoomService.CREATE);
        }
        // The host leaves and nobody left may host: the room closes, its match goes on.
        rooms.leave(player(ALICE));
        assertFalse(rooms.rooms().contains(room));
        assertTrue(matches.match("dojo").isPresent(), "the match goes on");
        closedRoomLeases.add("dojo");
        assertFalse(rooms.hasLease(queued), "the running match still holds the only slot");
        assertEveryMatchHoldsALease();

        playToTheEnd("dojo");
        assertTrue(rooms.hasLease(queued), "granted when the match ends");
    }
}
