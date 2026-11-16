package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.lobby.LobbyReturns;
import com.ninja6.ninjaarena.room.Room;
import com.ninja6.ninjaarena.room.RoomLobby;
import com.ninja6.ninjaarena.room.RoomService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Co-hosts, the creator's crown and hand-back, and the host grace in the lobby, with the real
 * room service, match engine and arena registry.
 */
class RoomHostsTest extends MatchFixture {

    private static final UUID EVE = new UUID(0, 5);

    private final World lobbyWorld = world(LobbyWorld.NAME);
    private final Set<UUID> lobby = new LinkedHashSet<>();
    private final Set<UUID> lobbyRefuses = new HashSet<>();
    private final CrossWorldTransfers transfers = new CrossWorldTransfers();
    private final List<String> worldHolds = new ArrayList<>();
    private RoomService rooms;
    private LobbyReturns returns;

    @BeforeEach
    void setUpRooms() throws Exception {
        arena("garden", new GridCell(1, 0));
        add(EVE, "Eve");
        for (Fake fake : players.values()) {
            fake.location = new Location(overworld, 0, 70, 0);
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
                    // As LobbyMembers while the lobby is built: closed to everyone.
                    player.sendMessage(Component.text("The lobby is being built."));
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
        // As the plugin wires it: a rejoin after quitting from the lobby flow goes through the rooms.
        returns = new LobbyReturns(id -> lobby.contains(id) || rooms.isMember(id) || matches.isInMatch(id),
                rooms::rejoin);
        MatchListener crossing = new MatchListener(matches, deaths, transfers);
        teleportEvents = event -> {
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

    private Room openWith(UUID host, UUID... guests) {
        rooms.create(player(host));
        for (UUID guest : guests) {
            rooms.invite(player(host), players.get(guest).name);
            rooms.accept(player(guest), players.get(host).name);
        }
        return rooms.hostedBy(host).orElseThrow();
    }

    private String last(UUID id) {
        return players.get(id).messages.getLast();
    }

    /** A disconnect as the server sees it: the lobby return first, then the lobby, then the room. */
    private void quit(UUID id) {
        PlayerQuitEvent event = new PlayerQuitEvent(player(id), Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED);
        returns.onQuit(event);
        lobby.remove(id);
        new MatchListener(matches, deaths, transfers).onQuit(event);
        rooms.onQuit(event);
        players.get(id).online = false;
    }

    /** A join as {@code JoinRestore} handles it for a player who quit from the lobby flow. */
    private boolean rejoin(UUID id) {
        players.get(id).online = true;
        return returns.claim(id) && returns.enter(player(id));
    }

    private void tickRooms(int seconds) {
        for (int i = 0; i < seconds; i++) {
            rooms.tick();
        }
    }

    private static CommandSender console() {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "sendMessage" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<ClickEvent> clicksIn(Component component) {
        List<ClickEvent> found = new ArrayList<>();
        if (component.clickEvent() != null) {
            found.add(component.clickEvent());
        }
        for (Component child : component.children()) {
            found.addAll(clicksIn(child));
        }
        return found;
    }

    // ---- Co-hosts ----

    @Test
    void onlyTheHostNamesAndUnnamesCohostsAndTheRoomIsTold() {
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.cohost(player(BOB), "Carol");
        assertEquals("Only the host of the room can do that.", last(BOB));
        rooms.cohost(player(ALICE), "Alice");
        assertEquals("You host the room already.", last(ALICE));
        rooms.cohost(player(ALICE), "Eve");
        assertEquals("Eve is not in your room.", last(ALICE));

        rooms.cohost(player(ALICE), "bob");
        assertTrue(room.isCohost(BOB));
        assertEquals("Bob is now a co-host: they can start the match, invite and remove players.", last(CAROL));
        rooms.cohost(player(ALICE), "Bob");
        assertEquals("Bob is already a co-host.", last(ALICE));
        rooms.cohost(player(BOB), "Carol");
        assertEquals("Only the host of the room can do that.", last(BOB), "co-hosts name no co-hosts");

        rooms.uncohost(player(ALICE), "Carol");
        assertEquals("Carol is not a co-host.", last(ALICE));
        rooms.uncohost(player(ALICE), "Bob");
        assertFalse(room.isCohost(BOB));
        assertEquals("Bob is no longer a co-host.", last(CAROL));
    }

    @Test
    void cohostsStartInviteAndRemovePlainMembersButSettingsAndTheMapStayTheHosts() {
        Room room = openWith(ALICE, BOB, CAROL, DAVE);
        rooms.cohost(player(ALICE), "Bob");
        rooms.cohost(player(ALICE), "Dave");
        players.get(BOB).permissions.add(NinjaArenaCommand.MAP_CHANGE);

        rooms.settings(player(BOB), "players", "3");
        assertEquals("Only the host of the room can do that.", last(BOB));
        rooms.mapSet(player(BOB), "garden");
        assertEquals("Only the host of the room can do that.", last(BOB));
        rooms.kick(player(BOB), "Alice");
        assertEquals("Only the host can remove Alice.", last(BOB));
        rooms.kick(player(BOB), "Dave");
        assertEquals("Only the host can remove Dave.", last(BOB));
        assertTrue(room.contains(DAVE));

        rooms.kick(player(BOB), "Carol");
        assertFalse(room.contains(CAROL));
        assertEquals("Bob removed you from the room of Alice.", last(CAROL));
        assertTrue(lobby.contains(CAROL));

        rooms.invite(player(BOB), "Carol");
        assertEquals("Bob invited you to the room of Alice on arena dojo. [Join]", last(CAROL));
        assertEquals(List.of(ClickEvent.runCommand("/ninjaarena room accept Alice")),
                clicksIn(players.get(CAROL).messageComponents.getLast()), "the button finds the room by its host");
        rooms.accept(player(CAROL), "Alice");
        assertTrue(room.contains(CAROL));
        rooms.invite(player(CAROL), "Eve");
        assertEquals("Only the host or a co-host of the room can do that.", last(CAROL));

        rooms.start(player(CAROL), null);
        assertEquals("Only the host, a co-host or an admin can start the match of this room.", last(CAROL));
        assertFalse(room.isPlaying());
        rooms.start(player(BOB), null);
        assertTrue(room.isPlaying(), "a co-host starts the match");
        rooms.kick(player(BOB), "Carol");
        assertEquals("Players can be removed once the room is back in the lobby.", last(BOB));
    }

    @Test
    void theRoomViewGivesCohostsTheirButtonsAndTheHostCohostButtons() {
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.cohost(player(ALICE), "Bob");

        players.get(ALICE).messageComponents.clear();
        rooms.show(player(ALICE));
        List<String> host = players.get(ALICE).messages.subList(players.get(ALICE).messages.size()
                - players.get(ALICE).messageComponents.size(), players.get(ALICE).messages.size());
        assertTrue(host.contains("- Alice ♛ (host, team 1)"), host.toString());
        assertTrue(host.contains("- Bob (co-host, team 2) [Remove] [Plain member]"), host.toString());
        assertTrue(host.contains("- Carol (team 1) [Remove] [Co-host]"), host.toString());
        Component carol = players.get(ALICE).messageComponents.stream()
                .filter(line -> plain(line).startsWith("- Carol")).findFirst().orElseThrow();
        assertEquals(List.of(ClickEvent.runCommand("/ninjaarena room kick Carol"),
                ClickEvent.runCommand("/ninjaarena room cohost Carol")), clicksIn(carol));

        players.get(BOB).messageComponents.clear();
        rooms.show(player(BOB));
        List<Component> lines = players.get(BOB).messageComponents;
        assertEquals("- Alice ♛ (host, team 1)", plain(lines.stream()
                .filter(line -> plain(line).startsWith("- Alice")).findFirst().orElseThrow()));
        assertEquals("- Bob (co-host, team 2) ", plain(lines.stream()
                .filter(line -> plain(line).startsWith("- Bob")).findFirst().orElseThrow()));
        Component carolForBob = lines.stream().filter(line -> plain(line).startsWith("- Carol")).findFirst()
                .orElseThrow();
        assertEquals(List.of(ClickEvent.runCommand("/ninjaarena room kick Carol")), clicksIn(carolForBob));
        assertEquals("Enough players are here. [Start]", plain(lines.getLast()), "co-hosts may start");
        assertTrue(room.canStart());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theRoomScreenOffersCohostsTheirActionsAndTheHostTheCohostScreen(boolean dialog) {
        var h = new RoomScreenHarness(rooms, dialog);
        Player alice = h.wrap(player(ALICE));
        Player bob = h.wrap(player(BOB));
        Room room = openWith(ALICE, BOB, CAROL);

        h.menus.open(alice);
        h.press(alice, "Your room");
        h.press(alice, "Co-hosts");
        h.press(alice, "Make Bob a co-host");
        assertTrue(room.isCohost(BOB));

        h.menus.open(bob);
        h.press(bob, "Your room");
        if (dialog) {
            List<String> labels = h.forms.get(BOB).content().options().stream().map(o -> o.label()).toList();
            assertEquals(List.of("Invite players", "Remove players", "Start match", "Leave room", "Back"), labels);
            assertTrue(h.forms.get(BOB).content().lines().contains("Co-hosts: Bob"));
            assertTrue(h.forms.get(BOB).content().lines().contains("♛ Opened by Alice"));
        }
        h.press(bob, "Remove players");
        if (dialog) {
            List<String> labels = h.forms.get(BOB).content().options().stream().map(o -> o.label()).toList();
            assertEquals(List.of("Remove Carol", "Back"), labels, "only plain members");
        }
        h.press(bob, "Remove Carol");
        assertFalse(room.contains(CAROL));

        h.menus.open(alice);
        h.press(alice, "Your room");
        h.press(alice, "Co-hosts");
        h.press(alice, "Make Bob a member");
        assertFalse(room.isCohost(BOB));
    }

    // ---- Hand-over order and the creator ----

    @Test
    void theRoomPassesToACohostFirstThenTheLongestWaitingMemberAndClosesWithNobodyEligible() {
        rooms.setHostGraceSeconds(0);
        Room room = openWith(ALICE, BOB, CAROL, DAVE);
        rooms.cohost(player(ALICE), "Dave");
        rooms.leave(player(ALICE));
        assertEquals(DAVE, room.host(), "the co-host, though Bob and Carol waited longer");
        assertEquals("Dave is now the host of the room.", last(BOB));

        rooms.cohost(player(DAVE), "Carol");
        players.get(CAROL).permissions.remove(RoomService.CREATE);
        rooms.leave(player(DAVE));
        assertEquals(BOB, room.host(), "a co-host without ninjaarena.room.create is passed over");

        players.get(BOB).permissions.remove(RoomService.CREATE);
        rooms.leave(player(BOB));
        assertFalse(rooms.rooms().contains(room), "nobody left may host");
        assertEquals("The room closed: nobody left in it can host.", last(CAROL));
        assertTrue(lobby.contains(CAROL));
    }

    @Test
    void theCreatorKeepsTheCrownAcrossAHandOverAndAReturnAndCanBeHandedTheRoomBack() {
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.show(player(EVE));
        assertTrue(players.get(EVE).messages.contains("- Alice ♛ on dojo (3/2) [Join]"),
                String.valueOf(players.get(EVE).messages));
        rooms.tick();
        assertEquals("♛ Alice | Room (3/2): ready to start", players.get(BOB).actionBars.getLast());

        rooms.leave(player(ALICE));
        assertEquals(BOB, room.host());
        rooms.tick();
        assertEquals("Room (2/2): ready to start", players.get(BOB).actionBars.getLast(), "no crown without them");
        rooms.handBack(player(BOB));
        assertEquals("Alice, who opened this room, is not in it.", last(BOB));

        rooms.accept(player(ALICE), "Bob");
        assertEquals(BOB, room.host(), "back with the crown, not the host role");
        assertEquals("You opened this room, so it shows your crown again. Its host can hand it back to you.",
                last(ALICE));
        assertEquals("Alice, who opened this room, is back. [Hand back]", last(BOB));
        assertEquals(List.of(ClickEvent.runCommand("/ninjaarena room handback")),
                clicksIn(players.get(BOB).messageComponents.getLast()));
        rooms.tick();
        assertEquals("♛ Alice | Room (3/2): ready to start", players.get(CAROL).actionBars.getLast());
        players.get(EVE).messages.clear();
        rooms.show(player(EVE));
        assertTrue(players.get(EVE).messages.contains("- Bob (♛ Alice) on dojo (3/2) [Join]"),
                String.valueOf(players.get(EVE).messages));

        rooms.handBack(player(CAROL));
        assertEquals("Only the host of the room can do that.", last(CAROL));
        players.get(ALICE).permissions.remove(RoomService.CREATE);
        rooms.handBack(player(BOB));
        assertEquals("Alice cannot host rooms.", last(BOB));
        players.get(ALICE).permissions.add(RoomService.CREATE);
        rooms.handBack(player(BOB));
        assertEquals(ALICE, room.host());
        assertTrue(room.isCohost(BOB), "the host before stays a co-host");
        assertEquals("Bob handed the room back to Alice, who opened it.", last(CAROL));
        rooms.handBack(player(ALICE));
        assertEquals("You opened this room and host it already.", last(ALICE));
    }

    // ---- The host grace ----

    @Test
    void aHostWhoDisconnectsInTheLobbyStaysHostWithTheLeaseAndTheTickKeepsThem() {
        assertEquals(RoomService.DEFAULT_HOST_GRACE_SECONDS, rooms.hostGraceSeconds());
        rooms.setHostGraceSeconds(5);
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.cohost(player(ALICE), "Bob");
        quit(ALICE);
        assertEquals("The host, Alice, disconnected. The room waits 5 seconds for them; starting and removing"
                + " players wait until then.", last(BOB));
        assertTrue(room.isHostAway());

        tickRooms(4);
        assertTrue(room.contains(ALICE), "the tick does not remove an away host");
        assertEquals(ALICE, room.host());
        assertTrue(rooms.hasLease(room), "the room keeps its slot");
        assertEquals("♛ Alice | Room (3/2): waiting 1s for Alice to come back", players.get(BOB).actionBars.getLast());
        rooms.show(player(EVE));
        assertTrue(players.get(EVE).messages.contains("- Alice ♛ on dojo (3/2) host away [Join]"),
                String.valueOf(players.get(EVE).messages));
    }

    @Test
    void startAndRemovalsWaitDuringTheGraceForCohostsAndAdminsAlike() {
        rooms.setHostGraceSeconds(30);
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.cohost(player(ALICE), "Bob");
        quit(ALICE);
        String waiting = "The host, Alice, is away. Wait for them, or 30 seconds for the room to be handed on.";
        rooms.start(player(BOB), null);
        assertEquals(waiting, last(BOB));
        rooms.kick(player(BOB), "Carol");
        assertEquals(waiting, last(BOB));
        assertTrue(room.contains(CAROL));
        rooms.start(console(), "Alice");
        assertFalse(room.isPlaying(), "an admin's start waits too");
        rooms.invite(player(BOB), "Eve");
        assertEquals("Invited Eve to your room.", last(BOB), "invitations go on");
        rooms.accept(player(EVE), "Alice");
        assertTrue(room.contains(EVE), "and the room can be joined by its away host's name");
        players.get(BOB).messageComponents.clear();
        rooms.show(player(BOB));
        assertTrue(players.get(BOB).messages.contains("The host, Alice, is away: 30 seconds left to come back."));
        assertFalse(players.get(BOB).messages.getLast().startsWith("Enough players are here."), "no start button");
    }

    @Test
    void aHostBackWithinTheGraceIsInTheLobbyAndHostsTheRoomAgain() {
        rooms.setHostGraceSeconds(10);
        Room room = openWith(ALICE, BOB);
        quit(ALICE);
        tickRooms(3);
        assertTrue(rejoin(ALICE));
        assertTrue(lobby.contains(ALICE), "in the lobby");
        assertEquals(room, rooms.roomOf(ALICE).orElseThrow(), "and in their room");
        assertEquals(ALICE, room.host());
        assertFalse(room.isHostAway());
        assertEquals("Alice is back and hosts the room again.", last(BOB));
        tickRooms(20);
        assertTrue(room.contains(ALICE));
        rooms.start(player(ALICE), null);
        assertTrue(room.isPlaying(), "nothing waits any more");
    }

    @Test
    void aHostBackInTheLobbyAnotherWayAlsoEndsTheGrace() {
        rooms.setHostGraceSeconds(10);
        Room room = openWith(ALICE, BOB);
        quit(ALICE);
        players.get(ALICE).online = true;
        // Not claimed by the lobby return: through a play portal, say.
        assertEquals(Optional.empty(), rooms.fromPortal(player(ALICE), Optional.empty()));
        rooms.tick();
        assertFalse(room.isHostAway());
        assertEquals("Alice is back and hosts the room again.", last(BOB));
    }

    @Test
    void whenTheGraceRunsOutTheRoomIsHandedOnOrClosesAndOthersGetNoGrace() {
        rooms.setHostGraceSeconds(3);
        Room room = openWith(ALICE, BOB, CAROL, DAVE);
        rooms.cohost(player(ALICE), "Carol");
        quit(DAVE);
        assertFalse(room.contains(DAVE), "a member who is not the host leaves at once");
        quit(ALICE);
        tickRooms(2);
        assertEquals(ALICE, room.host());
        rooms.tick();
        assertFalse(room.contains(ALICE));
        assertEquals(CAROL, room.host(), "the co-host first");
        List<String> bob = players.get(BOB).messages;
        assertEquals(List.of("Alice did not come back in time.", "Alice left the room (2/2).",
                "Carol is now the host of the room."), bob.subList(bob.size() - 3, bob.size()));
        assertTrue(rooms.hasLease(room));

        assertTrue(rejoin(ALICE));
        assertTrue(lobby.contains(ALICE), "too late: in the lobby, out of the room, as before");
        assertTrue(rooms.roomOf(ALICE).isEmpty());

        players.get(BOB).permissions.remove(RoomService.CREATE);
        quit(CAROL);
        tickRooms(3);
        assertFalse(rooms.rooms().contains(room), "nobody left may host");
        assertFalse(rooms.hasLease(room));
        assertEquals("The room closed: nobody left in it can host.", last(BOB));
    }

    @Test
    void aHostWhomTheClosedLobbyTurnsAwayIsOutOfTheRoomAtOnce() {
        rooms.setHostGraceSeconds(30);
        Room room = openWith(ALICE, BOB);
        quit(ALICE);
        // The lobby is built meanwhile: it takes nobody in, so the join restore sends Alice home.
        lobbyRefuses.add(ALICE);
        assertFalse(rejoin(ALICE));
        assertFalse(room.contains(ALICE));
        assertEquals(BOB, room.host());
        assertEquals("The lobby could not take you back, so you are out of your room and go home. /narena leave"
                + " has nothing more to leave.", last(ALICE));
        assertEquals("Bob is now the host of the room.", last(BOB));
    }

    @Test
    void disablingDuringTheGraceClosesTheRoomAndARestartSendsTheHostHome() {
        rooms.setHostGraceSeconds(30);
        Room room = openWith(ALICE, BOB);
        quit(ALICE);
        rooms.close();
        assertTrue(rooms.rooms().isEmpty());
        assertFalse(rooms.hasLease(room));
        assertFalse(rooms.isMember(ALICE));
        assertEquals(List.of("hold", "release"), worldHolds);
        rooms.tick();

        // After a restart nothing of the grace is left: the lobby return is new and empty.
        LobbyReturns afterRestart = new LobbyReturns(rooms::isMember, rooms::rejoin);
        assertFalse(afterRestart.claim(ALICE), "restored home on joining, as before");
    }

    @Test
    void aHostInAMatchKeepsTheMatchGraceAndNoLobbyGrace() {
        matches.setRejoinGraceSeconds(0);
        rooms.setHostGraceSeconds(30);
        Room room = openWith(ALICE, BOB, CAROL);
        rooms.start(player(ALICE), null);
        quit(ALICE);
        assertFalse(room.contains(ALICE), "a match disconnect is unchanged: no lobby grace");
        assertEquals(BOB, room.host());
    }

    @Test
    void noGraceHandsTheRoomOnAtOnce() {
        rooms.setHostGraceSeconds(0);
        Room room = openWith(ALICE, BOB);
        quit(ALICE);
        assertEquals(BOB, room.host());
        rooms.setHostGraceSeconds(-5);
        assertEquals(0, rooms.hostGraceSeconds());
        rooms.setHostGraceSeconds(10_000);
        assertEquals(RoomService.MAX_HOST_GRACE_SECONDS, rooms.hostGraceSeconds());
    }
}
