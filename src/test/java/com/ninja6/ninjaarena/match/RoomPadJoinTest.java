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
 * Standing on a room's lobby pad ({@code RoomPads}) with the real room service: each privacy
 * outcome and the checks of the join path.
 */
class RoomPadJoinTest extends MatchFixture {
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

    /** The pad's countdown ran out for the player. */
    private void pad(UUID player, Room room) {
        rooms.fromPad(player(player), room);
    }

    private Room privacy(String privacy) {
        Room room = openWith(ALICE);
        rooms.settings(player(ALICE), "privacy", privacy);
        lobby.add(BOB);
        lobby.add(CAROL);
        return room;
    }

    @Test
    void publicRoomJoins() {
        Room room = privacy("public");
        pad(BOB, room);
        assertTrue(room.contains(BOB));
        assertTrue(players.get(BOB).messages.stream().anyMatch(message -> message.contains("joined the room of Alice")));
    }

    @Test
    void askRoomSendsARequestWithItsCooldownAndMessages() {
        Room room = privacy("ask");
        pad(BOB, room);
        assertFalse(room.contains(BOB));
        assertEquals("Request sent to the room of Alice. It expires in 60 seconds.", lastMessage(BOB));
        assertTrue(lastMessage(ALICE).contains("Bob asks to join your room."));
        assertEquals(1, rooms.pendingRequests(player(ALICE)).size());
        pad(BOB, room);
        assertEquals("Wait 30 seconds before requesting this room again.", lastMessage(BOB));
        rooms.declineRequest(player(ALICE), "Bob");
        now += 30_000;
        pad(BOB, room);
        assertEquals("Request sent to the room of Alice. It expires in 60 seconds.", lastMessage(BOB));
        rooms.acceptRequest(player(ALICE), "Bob");
        assertTrue(room.contains(BOB));
    }

    @Test
    void inviteOnlyRoomSaysSoUnlessThePlayerWasInvited() {
        Room room = privacy("invite");
        pad(BOB, room);
        assertFalse(room.contains(BOB));
        assertEquals("The room of Alice is invite only, and you have no invitation.", lastMessage(BOB));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
        rooms.invite(player(ALICE), "Carol");
        pad(CAROL, room);
        assertTrue(room.contains(CAROL));
    }

    @Test
    void invitedPlayerJoinsAnAskRoomWithoutARequest() {
        Room room = privacy("ask");
        rooms.invite(player(ALICE), "Bob");
        pad(BOB, room);
        assertTrue(room.contains(BOB));
        assertTrue(rooms.pendingRequests(player(ALICE)).isEmpty());
    }

    @Test
    void fullRoomAndClosedLobbyAreRefused() {
        Room room = privacy("public");
        rooms.settings(player(ALICE), "team-size", "1");
        pad(CAROL, room);
        assertTrue(room.contains(CAROL));
        pad(BOB, room);
        assertFalse(room.contains(BOB));
        assertEquals("The room of Alice is full.", lastMessage(BOB));
        rooms.settings(player(ALICE), "team-size", "0");
        lobbyRefuses.add(BOB);
        pad(BOB, room);
        assertFalse(room.contains(BOB));
        assertTrue(lastMessage(BOB).contains("The lobby is closed."));
    }

    @Test
    void roomInAMatchOrClosedAndPlayerInARoomAreRefused() {
        Room room = privacy("public");
        rooms.create(player(DAVE));
        pad(DAVE, room);
        assertFalse(room.contains(DAVE));
        assertEquals("You are already in a room. Use /narena room leave first.", lastMessage(DAVE));
        pad(CAROL, room);
        rooms.start(player(ALICE), null);
        assertTrue(room.isPlaying());
        pad(BOB, room);
        assertFalse(room.contains(BOB));
        assertEquals("The room of Alice is in a match. Try again when it is back in the lobby.", lastMessage(BOB));
        Room other = rooms.hostedBy(DAVE).orElseThrow();
        rooms.leave(player(DAVE));
        pad(BOB, other);
        assertFalse(other.contains(BOB));
        assertTrue(rooms.roomOf(BOB).isEmpty());
    }

    @Test
    void roomWhoseHostIsAwayKeepsTakingPlayersLikeAnInvitationDoes() {
        Room room = privacy("public");
        rooms.onQuit(new PlayerQuitEvent(player(ALICE), Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        lobby.remove(ALICE);
        players.get(ALICE).online = false;
        assertTrue(room.isHostAway());
        pad(CAROL, room);
        assertTrue(room.contains(CAROL));
    }
}
