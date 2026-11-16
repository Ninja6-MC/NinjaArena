package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
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
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ask-to-join requests with the real room service, match engine, and both room screen modes.
 */
class RoomRequestsTest extends MatchFixture {
    private static final UUID EVE = new UUID(0, 5);
    private static final UUID FRANK = new UUID(0, 6);

    private final World lobbyWorld = world(LobbyWorld.NAME);
    private final Set<UUID> lobby = new LinkedHashSet<>();
    private final Set<UUID> lobbyRefuses = new HashSet<>();
    private final CrossWorldTransfers transfers = new CrossWorldTransfers();
    private final List<String> worldHolds = new ArrayList<>();
    private RoomService rooms;
    private long now;
    private Runnable onLobbyJoin = () -> { };

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
                onLobbyJoin.run();
                if (lobbyRefuses.contains(player.getUniqueId())) {
                    return Optional.of("The lobby is closed.");
                }
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
                }, 2, () -> now);
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

    private Room askRoom() {
        Room room = openWith(ALICE);
        rooms.settings(player(ALICE), "privacy", "ask");
        return room;
    }

    private void request(UUID requester) {
        rooms.accept(player(requester), "Alice");
    }

    private void advance(int seconds) {
        now += seconds * 1000L;
        rooms.tick();
    }

    @Test
    void requestsSendAcceptAndDeclineWithNoInvitationBypassLeftBehind() {
        Room room = askRoom();
        request(BOB);
        assertFalse(room.contains(BOB));
        assertTrue(lobby.contains(BOB));
        assertEquals("Request sent to the room of Alice. It expires in 60 seconds.", lastMessage(BOB));
        assertTrue(lastMessage(ALICE).contains("Bob asks to join your room. [Accept] [Decline] [Requests]"));
        assertEquals(1, rooms.pendingRequests(player(ALICE)).size());
        rooms.acceptRequest(player(ALICE), "bob");
        assertTrue(room.contains(BOB));
        assertTrue(players.get(BOB).messages.contains("Your request to join the room of Alice was accepted."));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
        assertFalse(room.isInvited(BOB));
        request(CAROL);
        rooms.declineRequest(player(ALICE), "Carol");
        assertFalse(room.contains(CAROL));
        assertEquals("Your request to join the room of Alice was declined.", lastMessage(CAROL));
        rooms.acceptRequest(player(ALICE), "Carol");
        assertEquals("That request is no longer pending.", lastMessage(ALICE));
    }

    @Test
    void cooldownIsPerPlayerPerRoomAndPendingRequestsAreNotDuplicated() {
        askRoom();
        request(BOB);
        advance(1);
        request(BOB);
        assertEquals("Wait 29 seconds before requesting this room again.", lastMessage(BOB));
        rooms.declineRequest(player(ALICE), "Bob");
        request(BOB);
        assertEquals("Wait 29 seconds before requesting this room again.", lastMessage(BOB));
        advance(29);
        request(BOB);
        assertEquals(1, rooms.pendingRequests(player(ALICE)).size());
        advance(30);
        request(BOB);
        assertEquals("Your request to the room of Alice is still pending.", lastMessage(BOB));
        rooms.create(player(CAROL));
        rooms.settings(player(CAROL), "privacy", "ask");
        rooms.accept(player(BOB), "Carol");
        assertEquals(1, rooms.pendingRequests(player(CAROL)).size());
        assertEquals(1, rooms.pendingRequests(player(ALICE)).size());
    }

    @Test
    void expiryUsesTheDeadlineIncludingDecisionsBetweenTicksAndReloadKeepsExistingDeadlines() {
        Room room = askRoom();
        request(BOB);
        advance(59);
        assertEquals(1, rooms.pendingRequests(player(ALICE)).size());
        rooms.setRequestTimes(5, 0);
        now += 1000;
        rooms.acceptRequest(player(ALICE), "Bob");
        assertFalse(room.contains(BOB));
        assertEquals("Your request to join the room of Alice expired.", lastMessage(BOB));
        request(BOB);
        advance(5);
        assertEquals("Your request to join the room of Alice expired.", lastMessage(BOB));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
    }

    @Test
    void leavingTheLobbyJoiningAnotherRoomCreatingARoomAndDisconnectingEndRequests() {
        askRoom();
        request(BOB);
        lobby.remove(BOB);
        rooms.acceptRequest(player(ALICE), "Bob");
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
        assertEquals("Your request to join the room of Alice ended because you left the lobby.", lastMessage(BOB));
        advance(30);
        request(BOB);
        rooms.create(player(CAROL));
        rooms.accept(player(BOB), "Carol");
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
        assertTrue(lastMessage(BOB).contains("joined the room of Carol"));
        request(DAVE);
        rooms.create(player(DAVE));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
        request(EVE);
        rooms.onQuit(new PlayerQuitEvent(player(EVE), Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
    }

    @Test
    void closingStartingAndChangingPrivacyEndRequestsImmediately() {
        Room room = askRoom();
        request(BOB);
        rooms.settings(player(ALICE), "privacy", "invite");
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
        assertEquals("Your request to join the room of Alice ended because the room is now invite only.",
                lastMessage(BOB));
        rooms.settings(player(ALICE), "privacy", "ask");
        advance(30);
        request(BOB);
        rooms.invite(player(ALICE), "Carol");
        rooms.accept(player(CAROL), "Alice");
        rooms.start(player(ALICE), null);
        assertTrue(room.isPlaying());
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
        assertFalse(room.contains(BOB));
        assertEquals("Your request to join the room of Alice ended because its match started.", lastMessage(BOB));
    }

    @Test
    void orphanedRoomAndPluginDisableEndRequests() {
        askRoom();
        request(BOB);
        rooms.leave(player(ALICE));
        assertTrue(rooms.rooms().isEmpty());
        assertEquals("Your request to join the room of Alice ended because the room closed.", lastMessage(BOB));
        askRoom();
        request(BOB);
        rooms.close();
        assertTrue(rooms.rooms().isEmpty());
        assertEquals("Your request to join the room of Alice ended because the room closed.", lastMessage(BOB));
    }

    @Test
    void acceptanceRechecksFullLobbyPermissionAndChangesDuringTeleport() {
        Room room = askRoom();
        request(BOB);
        rooms.settings(player(ALICE), "team-size", "1");
        rooms.invite(player(ALICE), "Carol");
        rooms.accept(player(CAROL), "Alice");
        rooms.acceptRequest(player(ALICE), "Bob");
        assertFalse(room.contains(BOB));
        assertTrue(players.get(BOB).messages.contains("The room of Alice is full."));
        assertFalse(room.isInvited(BOB));
        rooms.settings(player(ALICE), "team-size", "0");
        advance(30);
        request(BOB);
        lobbyRefuses.add(BOB);
        rooms.acceptRequest(player(ALICE), "Bob");
        assertFalse(room.contains(BOB));
        assertTrue(players.get(BOB).messages.contains("The lobby is closed."));
        lobbyRefuses.clear();
        advance(30);
        request(BOB);
        players.get(BOB).permissions.remove(NinjaArenaCommand.PLAY);
        rooms.acceptRequest(player(ALICE), "Bob");
        assertFalse(room.contains(BOB));
        players.get(BOB).permissions.add(NinjaArenaCommand.PLAY);
        advance(30);
        request(BOB);
        onLobbyJoin = () -> rooms.settings(player(ALICE), "privacy", "public");
        rooms.acceptRequest(player(ALICE), "Bob");
        assertFalse(room.contains(BOB), "a privacy change during entry invalidates the approval");
    }

    @Test
    void portalsSkipAskRoomsButAdmitInvitedPlayersToInviteRooms() {
        Room ask = askRoom();
        rooms.create(player(CAROL));
        Room publicRoom = rooms.hostedBy(CAROL).orElseThrow();
        rooms.fromPortal(player(BOB), Optional.empty());
        assertTrue(publicRoom.contains(BOB));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
        rooms.invite(player(ALICE), "Dave");
        rooms.fromPortal(player(DAVE), Optional.of(ask.arena()));
        assertFalse(ask.contains(DAVE));
        assertTrue(lobby.contains(DAVE));
        rooms.settings(player(CAROL), "privacy", "invite");
        rooms.invite(player(CAROL), "Eve");
        rooms.fromPortal(player(EVE), Optional.empty());
        assertTrue(publicRoom.contains(EVE));
    }

    @Test
    void cohostsCanDecideWhilePlainMembersAndFormerCohostsCannot() {
        Room room = openWith(ALICE, CAROL, DAVE);
        rooms.settings(player(ALICE), "privacy", "ask");
        rooms.cohost(player(ALICE), "Carol");
        request(BOB);
        rooms.acceptRequest(player(DAVE), "Bob");
        assertFalse(room.contains(BOB));
        rooms.acceptRequest(player(CAROL), "Bob");
        assertTrue(room.contains(BOB));
        request(EVE);
        rooms.uncohost(player(ALICE), "Carol");
        rooms.declineRequest(player(CAROL), "Eve");
        assertEquals(1, rooms.pendingRequests(player(ALICE)).size());
        rooms.cohost(player(ALICE), "Carol");
        rooms.declineRequest(player(CAROL), "Eve");
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void requestScreensRouteThroughBothModesCloseOnceAndCannotHandleAReplacement(boolean dialog) {
        Room room = askRoom();
        var h = new RoomScreenHarness(rooms, dialog);
        Player alice = h.wrap(player(ALICE));
        Player bob = h.wrap(player(BOB));
        // The third privacy value is offered by both dialog inputs and chest cycling.
        h.menus.open(alice);
        h.press(alice, "Your room");
        h.press(alice, "Settings");
        h.press(alice, "Privacy");
        h.press(alice, "Save", dialog ? "ask" : "Ask to join");
        assertTrue(room.settings().asksToJoin());
        h.menus.open(bob);
        h.press(bob, "Room list");
        h.press(bob, "Ask Alice (1)");
        assertFalse(room.contains(BOB));
        assertEquals(1, rooms.pendingRequests(alice).size());
        rooms.requests(alice);
        h.press(alice, "Request from Bob");
        RoomScreenHarness.Form old = h.forms.get(ALICE);
        int shown = h.shows;
        h.press(alice, "Decline request");
        assertEquals(shown, h.shows, "deciding never opens another form");
        assertTrue(rooms.pendingRequests(alice).isEmpty());
        advance(30);
        request(BOB);
        if (dialog) {
            old.press().run(alice, 0, java.util.Map.of());
            assertFalse(room.contains(BOB), "old form callbacks cannot decide a later request");
        }
        rooms.requests(alice);
        h.press(alice, "Request from Bob");
        shown = h.shows;
        h.press(alice, "Accept request");
        assertEquals(shown, h.shows);
        assertTrue(room.contains(BOB));
    }

    @Test
    void bedrockChatUsesTypedDecisionCommandsAndJavaChatHasClickableButtons() {
        askRoom();
        rooms.setBedrock(ALICE::equals);
        request(BOB);
        String bedrock = lastMessage(ALICE);
        assertTrue(bedrock.contains("/ninjaarena room accept-request Bob"), bedrock);
        assertTrue(bedrock.contains("/ninjaarena room decline-request Bob"), bedrock);
        assertTrue(bedrock.contains("/ninjaarena room requests"), bedrock);
        rooms.setBedrock(id -> false);
        request(CAROL);
        List<ClickEvent> commands = new ArrayList<>();
        collectCommands(players.get(ALICE).messageComponents.getLast(), commands);
        assertEquals(List.of(ClickEvent.runCommand("/ninjaarena room accept-request Carol"),
                ClickEvent.runCommand("/ninjaarena room decline-request Carol"),
                ClickEvent.runCommand("/ninjaarena room requests")), commands);
    }

    private static void collectCommands(Component component, List<ClickEvent> commands) {
        if (component.clickEvent() != null) { commands.add(component.clickEvent()); }
        component.children().forEach(child -> collectCommands(child, commands));
    }

    @Test
    void removedPlayersNeedAnInvitationAndDecisionsFollowTheCurrentManagerRole() {
        Room room = openWith(ALICE, CAROL);
        rooms.settings(player(ALICE), "privacy", "ask");
        request(BOB);
        rooms.cohost(player(ALICE), "Carol");
        rooms.leave(player(ALICE));
        rooms.acceptRequest(player(CAROL), "Bob");
        assertTrue(room.contains(BOB), "pending requests belong to the room across handover");
        rooms.kick(player(CAROL), "Bob");
        rooms.accept(player(BOB), "Carol");
        assertTrue(rooms.pendingRequests(player(CAROL)).isEmpty());
        assertFalse(room.contains(BOB));
        rooms.invite(player(CAROL), "Bob");
        rooms.accept(player(BOB), "Carol");
        assertTrue(room.contains(BOB));
    }

    @Test
    void leavingAndReturningBeforeTheNextTickCannotReviveARequest() {
        Room room = askRoom();
        request(BOB);
        lobby.remove(BOB);
        players.get(BOB).location = new Location(overworld, 0, 70, 0);
        rooms.onWorldChanged(new org.bukkit.event.player.PlayerChangedWorldEvent(player(BOB), lobbyWorld));
        rooms.fromPortal(player(BOB), Optional.empty());
        rooms.acceptRequest(player(ALICE), "Bob");
        assertFalse(room.contains(BOB));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
    }

    @Test
    void anApproverWhoseRoleChangesDuringLobbyEntryCannotAdmitTheRequester() {
        Room room = openWith(ALICE, CAROL);
        rooms.cohost(player(ALICE), "Carol");
        rooms.settings(player(ALICE), "privacy", "ask");
        request(BOB);
        onLobbyJoin = () -> rooms.uncohost(player(ALICE), "Carol");
        int before = players.get(BOB).messages.size();
        rooms.acceptRequest(player(CAROL), "Bob");
        assertTrue(players.get(CAROL).messages.contains("You can no longer approve requests for this room."));
        assertEquals(List.of("Your request to join the room of Alice ended."),
                players.get(BOB).messages.subList(before, players.get(BOB).messages.size()));
        assertFalse(room.contains(BOB));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
    }

    @Test
    void cohostsMayAcceptDuringHostGraceLikeInviting() {
        Room room = openWith(ALICE, CAROL);
        rooms.cohost(player(ALICE), "Carol");
        rooms.settings(player(ALICE), "privacy", "ask");
        lobby.remove(ALICE);
        players.get(ALICE).online = false;
        rooms.onQuit(new PlayerQuitEvent(player(ALICE), Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        request(BOB);
        rooms.acceptRequest(player(CAROL), "Bob");
        assertTrue(room.contains(BOB));
        assertTrue(room.isHostAway());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void aFormLeftOpenPastExpiryCannotAcceptAReplacementRequest(boolean dialog) {
        Room room = askRoom();
        var h = new RoomScreenHarness(rooms, dialog);
        Player alice = h.wrap(player(ALICE));
        request(BOB);
        rooms.requests(alice);
        h.press(alice, "Request from Bob");
        advance(60);
        request(BOB);
        int shows = h.shows;
        h.press(alice, "Accept request");
        assertFalse(room.contains(BOB));
        assertEquals(1, rooms.pendingRequests(alice).size());
        assertEquals(shows, h.shows, "no stale form is reopened");
    }

    @Test
    void publicPrivacyCancellationSaysThePlayerCanJoinNow() {
        Room room = askRoom();
        request(BOB);
        rooms.settings(player(ALICE), "privacy", "public");
        assertEquals("Your request to join the room of Alice ended because the room is now public. You can join it now.",
                lastMessage(BOB));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
        rooms.accept(player(BOB), "Alice");
        assertTrue(room.contains(BOB));
    }

    private List<String> requestNoticesSince(UUID player, int before) {
        List<String> messages = players.get(player).messages;
        return messages.subList(before, messages.size()).stream()
                .filter(message -> message.startsWith("Your request to join")).toList();
    }

    @Test
    void creatingJoiningAndAcceptanceElsewhereCancelOtherRequestsSilently() {
        askRoom();
        request(BOB);
        int before = players.get(BOB).messages.size();
        rooms.create(player(BOB));
        assertEquals(List.of(), requestNoticesSince(BOB, before));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());

        request(DAVE);
        before = players.get(DAVE).messages.size();
        rooms.accept(player(DAVE), "Bob");
        assertEquals(List.of(), requestNoticesSince(DAVE, before));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());

        rooms.settings(player(BOB), "privacy", "ask");
        request(EVE);
        rooms.accept(player(EVE), "Bob");
        before = players.get(EVE).messages.size();
        rooms.acceptRequest(player(BOB), "Eve");
        assertEquals(List.of("Your request to join the room of Bob was accepted."), requestNoticesSince(EVE, before));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void invitedAskRoomPlayersSeeJoinInChatAndScreens(boolean dialog) {
        Room room = askRoom();
        rooms.invite(player(ALICE), "Dave");
        rooms.show(player(DAVE));
        assertEquals("- Alice ♛ on dojo (1/2) [Join]", lastMessage(DAVE));
        var h = new RoomScreenHarness(rooms, dialog);
        Player dave = h.wrap(player(DAVE));
        h.menus.open(dave);
        h.press(dave, "Room list");
        h.press(dave, "Join Alice (1)");
        assertTrue(room.contains(DAVE));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void staleRequestListEntriesNeverOpenADecisionScreen(boolean dialog) {
        askRoom();
        var h = new RoomScreenHarness(rooms, dialog);
        Player alice = h.wrap(player(ALICE));
        request(BOB);
        rooms.requests(alice);
        advance(60);
        int shows = h.shows;
        h.press(alice, "Request from Bob");
        assertEquals(shows, h.shows, "expiry must not open a stale form");
        assertEquals("That request is no longer pending.", lastMessage(ALICE));

        request(BOB);
        rooms.requests(alice);
        rooms.declineRequest(player(ALICE), "Bob");
        shows = h.shows;
        h.press(alice, "Request from Bob");
        assertEquals(shows, h.shows, "a decided request must not open a stale form");
        assertEquals("That request is no longer pending.", lastMessage(ALICE));
    }

}
