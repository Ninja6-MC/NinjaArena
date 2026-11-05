package com.ninja6.ninjaarena.waiting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.arena.ArenaStore;
import com.ninja6.ninjaarena.arena.ChunkTickets;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.arena.MemoryCopies;
import com.ninja6.ninjaarena.portal.LobbyMode;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.waiting.WaitingRoomService.Exit;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaitingRoomServiceTest {

    private static final ArenaPoint ROOM = new ArenaPoint(50.5, 64, 50.5, 0, 0);
    private static final ArenaPoint HUB = new ArenaPoint(-300.5, 64, -300.5, 0, 0);
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CAROL = new UUID(0, 3);

    @TempDir
    Path dir;

    /** A fake player's state. */
    private static final class Fake {
        final UUID id;
        final String name;
        Location location;
        boolean online = true;
        final List<String> messages = new ArrayList<>();
        Player proxy;

        Fake(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final Map<UUID, Fake> players = new HashMap<>();
    private final List<Runnable> nextTick = new ArrayList<>();
    private final List<UUID> restored = new ArrayList<>();
    private final List<List<List<UUID>>> started = new ArrayList<>();
    private final List<String> startedIn = new ArrayList<>();
    private final ArenaPoint[] hubPoint = {HUB};
    private World arenaWorld;
    private World overworld;
    private WaitingRoomService rooms;
    private WaitingRoomListener listener;
    private ArenaRegistry arenas;

    @BeforeEach
    void setUp() throws Exception {
        arenaWorld = world(ArenaWorld.NAME);
        overworld = world("world");
        ChunkTickets noTickets = new ChunkTickets() {
            @Override
            public void add(int chunkX, int chunkZ) {
            }

            @Override
            public void remove(int chunkX, int chunkZ) {
            }
        };
        arenas = new ArenaRegistry(new ArenaStore(dir.resolve("arenas.yml")),
                new ArenaGrid(256, 2048, -64, 320), enabled -> { }, noTickets, new MemoryCopies());
        arenas.load();
        arenas.create("dojo", new GridCell(0, 0));
        arenas.update("dojo", a -> a.withBounds(new ArenaBounds(10, 60, 10, 90, 100, 90))
                .withWaitingRoom(ROOM)
                .withTeamSpawn(1, new ArenaPoint(20.5, 64, 20.5, 0, 0))
                .withTeamSpawn(2, new ArenaPoint(80.5, 64, 80.5, 0, 0))
                .withEnemySpawn(new ArenaPoint(50.5, 64, 20.5, 0, 0)));
        arenas.finishEdit("dojo");

        rooms = new WaitingRoomService(plugin(), arenas, new WaitingRoomSettings(2, 3, 4), player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, player -> { }, (arena, teams) -> {
            startedIn.add(arena);
            started.add(teams);
        });
        rooms.setHub(() -> Optional.ofNullable(hubPoint[0]));
        listener = new WaitingRoomListener(rooms);
        arenas.onEditOrDelete(rooms::closeRoom);
        add(ALICE, "Alice");
        add(BOB, "Bob");
        add(CAROL, "Carol");
    }

    private World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Plugin plugin() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        BukkitTask task = (BukkitTask) Proxy.newProxyInstance(BukkitTask.class.getClassLoader(),
                new Class<?>[] {BukkitTask.class}, (proxy, method, args) -> null);
        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(BukkitScheduler.class.getClassLoader(),
                new Class<?>[] {BukkitScheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "runTask" -> {
                        nextTick.add((Runnable) args[1]);
                        yield task;
                    }
                    case "runTaskTimer" -> task;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayer" -> {
                        Fake fake = players.get((UUID) args[0]);
                        yield fake != null && fake.online ? fake.proxy : null;
                    }
                    case "getScheduler" -> scheduler;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private void add(UUID id, String name) {
        Fake fake = new Fake(id, name);
        fake.location = new Location(arenaWorld, ROOM.x(), ROOM.y(), ROOM.z());
        fake.proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "isOnline" -> fake.online;
                    case "getLocation" -> fake.location.clone();
                    case "sendMessage" -> {
                        fake.messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    case "sendActionBar" -> null;
                    case "teleport" -> {
                        fake.location = ((Location) args[0]).clone();
                        yield true;
                    }
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        players.put(id, fake);
    }

    private Player player(UUID id) {
        return players.get(id).proxy;
    }

    /** Puts the player at the hub point, where a portal leading to the hub lands them, and in the hub. */
    private void joinHub(UUID id) {
        players.get(id).location = new Location(arenaWorld, HUB.x(), HUB.y(), HUB.z());
        rooms.joinHub(player(id));
    }

    private static WaitingRoomSettings caps(int min, int max, int teamSize) {
        return new WaitingRoomSettings(min, max, teamSize, 3, 4, LobbyMode.DEDICATED);
    }

    /** A complete arena in the cell at {@code cellX, 0} with the given number of teams. */
    private void arena(String name, int cellX, int teams) throws Exception {
        int base = cellX * 256;
        arenas.create(name, new GridCell(cellX, 0));
        arenas.update(name, a -> {
            Arena built = a.withBounds(new ArenaBounds(base + 10, 60, 10, base + 90, 100, 90))
                    .withWaitingRoom(new ArenaPoint(base + 50.5, 64, 50.5, 0, 0))
                    .withEnemySpawn(new ArenaPoint(base + 50.5, 64, 20.5, 0, 0));
            for (int team = 1; team <= teams; team++) {
                built = built.withTeamSpawn(team, new ArenaPoint(base + 10.5 + team * 10, 64, 20.5, 0, 0));
            }
            return built;
        });
        arenas.finishEdit(name);
    }

    private void runNextTick() {
        List<Runnable> tasks = new ArrayList<>(nextTick);
        nextTick.clear();
        tasks.forEach(Runnable::run);
    }

    private void walk(UUID id, double x, double y, double z) {
        Fake fake = players.get(id);
        Location to = new Location(arenaWorld, x, y, z);
        listener.onMove(new PlayerMoveEvent(fake.proxy, fake.location.clone(), to));
        fake.location = to;
    }

    @Test
    void playersAreBalancedAndTheCountdownHandsThemToTheMatch() {
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        rooms.join(player(CAROL), "dojo");
        assertEquals(1, rooms.teamOf(ALICE));
        assertEquals(2, rooms.teamOf(BOB));
        assertEquals(1, rooms.teamOf(CAROL));
        assertTrue(players.get(ALICE).messages.contains("The match starts in 3 seconds."));

        rooms.tick();
        rooms.tick();
        assertTrue(started.isEmpty());
        rooms.tick();

        assertEquals(List.of(List.of(List.of(ALICE, CAROL), List.of(BOB))), started);
        assertFalse(rooms.isWaiting(ALICE));
        assertFalse(rooms.isWaiting(BOB));
        assertTrue(restored.isEmpty());
    }

    @Test
    void aFinishedCountdownHoldsWhileNoMatchSlotIsFree() {
        boolean[] free = {false};
        rooms.setStartAllowed(() -> free[0]);
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        for (int i = 0; i < 6; i++) {
            rooms.tick();
        }
        assertTrue(started.isEmpty());
        assertTrue(rooms.isWaiting(ALICE));
        assertTrue(rooms.isWaiting(BOB));

        free[0] = true;
        rooms.tick();
        assertEquals(List.of(List.of(List.of(ALICE), List.of(BOB))), started);
        assertFalse(rooms.isWaiting(ALICE));
    }

    @Test
    void aFreedSlotGoesToTheRoomHeldLongestWhateverItsArenaIsCalled() throws Exception {
        arenas.create("cave", new GridCell(1, 0));
        arenas.update("cave", a -> a.withBounds(new ArenaBounds(266, 60, 10, 346, 100, 90))
                .withWaitingRoom(new ArenaPoint(306.5, 64, 50.5, 0, 0))
                .withTeamSpawn(1, new ArenaPoint(276.5, 64, 20.5, 0, 0))
                .withTeamSpawn(2, new ArenaPoint(336.5, 64, 80.5, 0, 0))
                .withEnemySpawn(new ArenaPoint(306.5, 64, 20.5, 0, 0)));
        arenas.finishEdit("cave");
        UUID dave = new UUID(0, 4);
        add(dave, "Dave");
        List<String> startedIn = new ArrayList<>();
        int[] slots = {0};
        rooms = new WaitingRoomService(plugin(), arenas, new WaitingRoomSettings(2, 3, 4), player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, player -> { }, (arena, teams) -> startedIn.add(arena));
        rooms.setStartAllowed(() -> startedIn.size() < slots[0]);

        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        for (int i = 0; i < 3; i++) {
            rooms.tick();
        }
        rooms.join(player(CAROL), "cave");
        rooms.join(player(dave), "cave");
        for (int i = 0; i < 5; i++) {
            rooms.tick();
        }
        assertTrue(startedIn.isEmpty());

        slots[0] = 1;
        rooms.tick();
        assertEquals(List.of("dojo"), startedIn, "dojo was held first, though cave sorts before it");
        assertTrue(rooms.isWaiting(CAROL));

        slots[0] = 2;
        rooms.tick();
        assertEquals(List.of("dojo", "cave"), startedIn);
    }

    @Test
    void aHeldCountdownStillStopsWhenAPlayerLeaves() {
        rooms.setStartAllowed(() -> false);
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        for (int i = 0; i < 4; i++) {
            rooms.tick();
        }
        assertTrue(rooms.leave(player(BOB), Exit.COMMAND));
        assertTrue(players.get(ALICE).messages.contains("Not enough players. The countdown stopped."));

        rooms.setStartAllowed(() -> true);
        for (int i = 0; i < 5; i++) {
            rooms.tick();
        }
        assertTrue(started.isEmpty());
    }

    @Test
    void walkingOutRestoresOnTheNextTickAndStopsTheCountdown() {
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        walk(BOB, 53.5, 64, 50.5);
        assertTrue(rooms.isWaiting(BOB));

        walk(BOB, 55.5, 64, 50.5);
        assertFalse(rooms.isWaiting(BOB));
        assertTrue(restored.isEmpty());
        assertTrue(players.get(ALICE).messages.contains("Not enough players. The countdown stopped."));

        runNextTick();
        assertEquals(List.of(BOB), restored);
        for (int i = 0; i < 5; i++) {
            rooms.tick();
        }
        assertTrue(started.isEmpty());
    }

    @Test
    void fallingIntoTheVoidLeavesTheRoom() {
        rooms.join(player(ALICE), "dojo");
        walk(ALICE, 50.5, 59, 50.5);
        runNextTick();
        assertEquals(List.of(ALICE), restored);
    }

    @Test
    void aTeleportOutOfTheRoomLeavesButOneInsideItDoesNot() {
        rooms.join(player(ALICE), "dojo");
        Fake alice = players.get(ALICE);
        listener.onTeleport(new PlayerTeleportEvent(alice.proxy, alice.location.clone(),
                new Location(arenaWorld, 52, 65, 49)));
        assertTrue(rooms.isWaiting(ALICE));

        listener.onTeleport(new PlayerTeleportEvent(alice.proxy, alice.location.clone(),
                new Location(overworld, 50.5, 64, 50.5)));
        assertFalse(rooms.isWaiting(ALICE));
        runNextTick();
        assertEquals(List.of(ALICE), restored);
    }

    @Test
    void anEventThatStartsOutsideTheArenaWorldDoesNotTakeAWaitingPlayerOut() {
        rooms.join(player(ALICE), "dojo");
        Fake alice = players.get(ALICE);
        // Another plugin sent Alice to the room inside a move or teleport event that began next to a portal.
        listener.onMove(new PlayerMoveEvent(alice.proxy, new Location(overworld, 11.5, 64, 9.5),
                new Location(overworld, 11.5, 64, 10.5)));
        listener.onTeleport(new PlayerTeleportEvent(alice.proxy, new Location(overworld, 11.5, 64, 9.5),
                new Location(overworld, 11.5, 64, 10.5)));
        assertTrue(rooms.isWaiting(ALICE));
        runNextTick();
        assertTrue(restored.isEmpty());
        assertFalse(alice.messages.contains("You left the waiting room."));
    }

    @Test
    void aWaitingPlayerFoundOutsideTheArenaWorldStillLeaves() {
        rooms.join(player(ALICE), "dojo");
        Fake alice = players.get(ALICE);
        alice.location = new Location(overworld, 11.5, 64, 9.5);
        listener.onMove(new PlayerMoveEvent(alice.proxy, alice.location.clone(),
                new Location(overworld, 11.5, 64, 10.5)));
        assertFalse(rooms.isWaiting(ALICE));
        runNextTick();
        assertEquals(List.of(ALICE), restored);
    }

    @Test
    void theLeaveCommandRestoresAtOnce() {
        rooms.join(player(ALICE), "dojo");
        assertTrue(rooms.leave(player(ALICE), Exit.COMMAND));
        assertEquals(List.of(ALICE), restored);
        assertFalse(rooms.leave(player(ALICE), Exit.COMMAND));
        assertEquals(1, restored.size());
    }

    @Test
    void aDisconnectLeavesTheRestoreToTheNextJoin() {
        rooms.join(player(ALICE), "dojo");
        players.get(ALICE).online = false;
        rooms.leave(player(ALICE), Exit.QUIT);
        assertFalse(rooms.isWaiting(ALICE));
        runNextTick();
        assertTrue(restored.isEmpty());
    }

    @Test
    void aDepartureRebalancesTheTeams() {
        add(new UUID(0, 4), "Dave");
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        rooms.join(player(CAROL), "dojo");
        rooms.join(player(new UUID(0, 4)), "dojo");
        rooms.leave(player(ALICE), Exit.COMMAND);
        rooms.leave(player(CAROL), Exit.COMMAND);
        assertEquals(1, rooms.teamOf(new UUID(0, 4)));
        assertTrue(players.get(new UUID(0, 4)).messages.contains("You were moved to team 1 to keep the teams even."));
    }

    @Test
    void closingSendsEveryoneHomeIncludingThoseWaitingForTheNextTick() {
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        walk(BOB, 60.5, 64, 50.5);
        rooms.close();
        assertEquals(2, restored.size());
        assertTrue(restored.containsAll(List.of(ALICE, BOB)));

        runNextTick();
        assertEquals(2, restored.size());
    }

    @Test
    void editingTheArenaClosesItsRoomAndTheNextRoomUsesTheNewPoint() throws Exception {
        rooms.join(player(ALICE), "dojo");
        arenas.startEdit("dojo");
        assertFalse(rooms.isWaiting(ALICE));
        assertEquals(List.of(ALICE), restored);
        assertTrue(players.get(ALICE).messages.contains("Arena dojo is no longer available, so you were sent back."));

        arenas.update("dojo", a -> a.withWaitingRoom(new ArenaPoint(50.5, 64, 80.5, 0, 0)));
        arenas.finishEdit("dojo");
        players.get(BOB).location = new Location(arenaWorld, 50.5, 64, 80.5);
        rooms.join(player(BOB), "dojo");
        walk(BOB, 51.5, 64, 80.5);
        runNextTick();
        assertTrue(rooms.isWaiting(BOB));
        assertEquals(List.of(ALICE), restored);
    }

    @Test
    void deletingTheArenaClosesItsRoom() throws Exception {
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        arenas.delete("dojo");
        assertFalse(rooms.isWaiting(ALICE));
        assertFalse(rooms.isWaiting(BOB));
        assertEquals(List.of(ALICE, BOB), restored);
        for (int i = 0; i < 5; i++) {
            rooms.tick();
        }
        assertTrue(started.isEmpty());
    }

    @Test
    void anUnknownArenaSendsThePlayerStraightBack() {
        rooms.join(player(ALICE), "nowhere");
        assertFalse(rooms.isWaiting(ALICE));
        assertEquals(List.of(ALICE), restored);
    }

    @Test
    void shrinkingRoomRadiusImmediatelyUsesTheExistingLeaveAndRestoreFlow() {
        rooms.join(players.get(ALICE).proxy, "dojo");
        rooms.join(players.get(BOB).proxy, "dojo");
        players.get(BOB).location = new Location(arenaWorld, ROOM.x() + 3, ROOM.y(), ROOM.z());
        assertTrue(rooms.inRoom(BOB, players.get(BOB).location));
        rooms.setSettings(new WaitingRoomSettings(2, 9, 2));
        assertFalse(rooms.isWaiting(BOB));
        assertTrue(rooms.isWaiting(ALICE));
        assertTrue(restored.isEmpty());
        List<Runnable> tasks = new ArrayList<>(nextTick);
        nextTick.clear();
        tasks.forEach(Runnable::run);
        assertEquals(List.of(BOB), restored);
    }

    @Test
    void aFullDedicatedRoomTurnsArrivalsAway() {
        rooms.setSettings(caps(2, 2, 0));
        rooms.join(player(ALICE), "dojo");
        assertTrue(rooms.full("dojo").isEmpty());
        rooms.join(player(BOB), "dojo");
        assertEquals(Optional.of("The waiting room of arena dojo is full (2 players). Try another arena, or come"
                + " back after its match."), rooms.full("dojo"));

        rooms.join(player(CAROL), "dojo");
        assertFalse(rooms.isWaiting(CAROL));
        assertEquals(List.of(CAROL), restored);
        assertTrue(players.get(CAROL).messages.contains("The waiting room of arena dojo filled up before you arrived,"
                + " so you were sent back."));

        for (int i = 0; i < 3; i++) {
            rooms.tick();
        }
        assertEquals(List.of(List.of(List.of(ALICE), List.of(BOB))), started);
    }

    @Test
    void theTeamSizeCapsTheRoomAndAFullRoomStartsBelowTheMinimum() {
        // Two teams of one: the room holds two, though three are needed by the minimum.
        rooms.setSettings(caps(3, 0, 1));
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        assertTrue(players.get(ALICE).messages.contains("The match starts in 3 seconds."));
        rooms.join(player(CAROL), "dojo");
        assertFalse(rooms.isWaiting(CAROL));

        for (int i = 0; i < 3; i++) {
            rooms.tick();
        }
        assertEquals(List.of(List.of(List.of(ALICE), List.of(BOB))), started);
    }

    @Test
    void aSmallerCapOnReloadKeepsThePlayersAlreadyWaiting() {
        add(new UUID(0, 4), "Dave");
        rooms.join(player(ALICE), "dojo");
        rooms.join(player(BOB), "dojo");
        rooms.join(player(CAROL), "dojo");
        rooms.setSettings(caps(2, 2, 0));
        assertTrue(rooms.isWaiting(CAROL));
        assertTrue(rooms.full("dojo").isPresent());
        rooms.join(player(new UUID(0, 4)), "dojo");
        assertFalse(rooms.isWaiting(new UUID(0, 4)));
    }

    @Test
    void theHubSendsTheFirstArrivalsToAFreeArenaAndKeepsTheOverflow() {
        rooms.setSettings(caps(2, 2, 0));
        joinHub(ALICE);
        joinHub(BOB);
        joinHub(CAROL);
        assertTrue(rooms.isWaiting(ALICE));
        assertTrue(rooms.inHub(CAROL));
        assertEquals(Optional.empty(), rooms.arenaOf(ALICE));
        assertEquals(0, rooms.teamOf(ALICE));
        assertTrue(players.get(ALICE).messages.contains("The match starts in 3 seconds."));

        for (int i = 0; i < 3; i++) {
            rooms.tick();
        }
        assertEquals(List.of("dojo"), startedIn);
        assertEquals(List.of(List.of(List.of(ALICE), List.of(BOB))), started);
        assertFalse(rooms.isWaiting(ALICE));
        assertTrue(rooms.inHub(CAROL));
        assertTrue(players.get(CAROL).messages.contains("Arena dojo is full, so you stay in the hub for the next free"
                + " arena."));
        assertTrue(restored.isEmpty());

        // One player is not enough for another match.
        for (int i = 0; i < 5; i++) {
            rooms.tick();
        }
        assertEquals(1, started.size());
    }

    @Test
    void theHubWaitsWhileArenasAreInEditModeAndStartsWhenOneIsFree() throws Exception {
        joinHub(ALICE);
        joinHub(BOB);
        arenas.startEdit("dojo");
        assertTrue(rooms.inHub(ALICE));
        assertTrue(rooms.inHub(BOB));
        for (int i = 0; i < 6; i++) {
            rooms.tick();
        }
        assertTrue(started.isEmpty());
        assertTrue(restored.isEmpty());

        arenas.finishEdit("dojo");
        rooms.tick();
        assertEquals(List.of("dojo"), startedIn);
        assertFalse(rooms.isWaiting(ALICE));
    }

    @Test
    void theHubLeavesArenasWithAnOpenRoomOrAReservationAlone() throws Exception {
        arena("cave", 1, 2);
        UUID dave = new UUID(0, 4);
        add(dave, "Dave");
        rooms.join(player(dave), "dojo");
        boolean[] reserved = {true};
        rooms.setClaimed(name -> reserved[0] && name.equals("cave"));
        joinHub(ALICE);
        joinHub(BOB);
        for (int i = 0; i < 5; i++) {
            rooms.tick();
        }
        assertTrue(started.isEmpty());

        reserved[0] = false;
        rooms.tick();
        assertEquals(List.of("cave"), startedIn);
        assertTrue(rooms.isWaiting(dave));
    }

    @Test
    void theHubPicksTheSmallestFreeArenaThatTakesEveryone() throws Exception {
        rooms.setSettings(caps(2, 0, 1));
        arena("big", 1, 4);
        joinHub(ALICE);
        joinHub(BOB);
        for (int i = 0; i < 3; i++) {
            rooms.tick();
        }
        assertEquals(List.of("dojo"), startedIn, "two players fit dojo, though big sorts first");

        UUID dave = new UUID(0, 4);
        add(dave, "Dave");
        joinHub(ALICE);
        joinHub(BOB);
        joinHub(dave);
        for (int i = 0; i < 3; i++) {
            rooms.tick();
        }
        assertEquals(List.of("dojo", "big"), startedIn);
        assertEquals(List.of(List.of(ALICE), List.of(BOB), List.of(dave), List.of()), started.get(1));
    }

    @Test
    void theHubStartsAnArenaHoldingFewerThanTheMinimumOnceItCanFillIt() throws Exception {
        rooms.setSettings(caps(4, 0, 1));
        arena("big", 1, 4);
        joinHub(ALICE);
        joinHub(BOB);
        assertTrue(players.get(ALICE).messages.contains("The match starts in 3 seconds."),
                "dojo holds two, so two players start its countdown though min-players is four");
        joinHub(CAROL);
        for (int i = 0; i < 3; i++) {
            rooms.tick();
        }
        assertEquals(List.of("dojo"), startedIn, "three players cannot start big, which needs four");
        assertEquals(List.of(List.of(ALICE), List.of(BOB)), started.getFirst());
        assertTrue(rooms.inHub(CAROL));
    }

    @Test
    void theHubCountdownStopsWhenTheOnlyArenaItCouldFillIsTaken() throws Exception {
        rooms.setSettings(caps(4, 0, 1));
        arena("big", 1, 4);
        joinHub(ALICE);
        joinHub(BOB);
        arenas.startEdit("dojo");
        rooms.tick();
        assertTrue(players.get(ALICE).messages.contains("Not enough players. The countdown stopped."));
        for (int i = 0; i < 5; i++) {
            rooms.tick();
        }
        assertTrue(started.isEmpty(), "big needs four players");

        arenas.finishEdit("dojo");
        rooms.tick();
        assertTrue(players.get(ALICE).messages.stream().filter("The match starts in 3 seconds."::equals).count() >= 2);
        for (int i = 0; i < 2; i++) {
            rooms.tick();
        }
        assertEquals(List.of("dojo"), startedIn);
    }

    @Test
    void aFinishedHubCountdownHoldsWhileNoMatchSlotIsFree() {
        boolean[] free = {false};
        rooms.setStartAllowed(() -> free[0]);
        joinHub(ALICE);
        joinHub(BOB);
        for (int i = 0; i < 6; i++) {
            rooms.tick();
        }
        assertTrue(started.isEmpty());
        free[0] = true;
        rooms.tick();
        assertEquals(List.of("dojo"), startedIn);
    }

    @Test
    void leavingTheHubRestoresTheSnapshotLikeLeavingAWaitingRoom() {
        joinHub(ALICE);
        joinHub(BOB);
        joinHub(CAROL);
        assertTrue(rooms.leave(player(ALICE), Exit.COMMAND));
        assertEquals(List.of(ALICE), restored);
        assertTrue(players.get(ALICE).messages.contains("You left the hub."));

        walk(BOB, HUB.x() + 5, HUB.y(), HUB.z());
        assertFalse(rooms.isWaiting(BOB));
        assertTrue(players.get(CAROL).messages.contains("Not enough players. The countdown stopped."));
        runNextTick();
        assertEquals(List.of(ALICE, BOB), restored);

        players.get(CAROL).online = false;
        rooms.leave(player(CAROL), Exit.QUIT);
        assertFalse(rooms.isWaiting(CAROL));
        runNextTick();
        assertEquals(List.of(ALICE, BOB), restored, "a disconnect is restored on the next join");
        for (int i = 0; i < 5; i++) {
            rooms.tick();
        }
        assertTrue(started.isEmpty());
    }

    @Test
    void movingOrRemovingTheHubSendsItsPlayersHome() {
        joinHub(ALICE);
        hubPoint[0] = new ArenaPoint(-200.5, 64, -200.5, 0, 0);
        rooms.tick();
        assertFalse(rooms.isWaiting(ALICE));
        assertEquals(List.of(ALICE), restored);
        assertTrue(players.get(ALICE).messages.contains("The hub was moved or removed, so you were sent back."));

        hubPoint[0] = null;
        rooms.joinHub(player(BOB));
        assertFalse(rooms.isWaiting(BOB));
        assertEquals(List.of(ALICE, BOB), restored);
        assertTrue(players.get(BOB).messages.contains("The hub is gone, so you were sent back."));
    }

    @Test
    void disablingThePluginSendsHubPlayersHome() {
        joinHub(ALICE);
        rooms.join(player(BOB), "dojo");
        rooms.close();
        assertFalse(rooms.isWaiting(ALICE));
        assertFalse(rooms.isWaiting(BOB));
        assertEquals(2, restored.size());
        assertTrue(restored.containsAll(List.of(ALICE, BOB)));
    }

    @Test
    void aHubPlayerWhoGoesToSpectateLeavesTheQueueWithoutARestore() {
        joinHub(ALICE);
        joinHub(BOB);
        assertTrue(rooms.transferToSpectate(player(ALICE), new Location(arenaWorld, 20.5, 64, 20.5)));
        assertFalse(rooms.isWaiting(ALICE));
        assertTrue(restored.isEmpty());
        for (int i = 0; i < 5; i++) {
            rooms.tick();
        }
        assertTrue(started.isEmpty(), "a spectator does not count towards the hub's minimum");
    }

    @Test
    void aWaitingPlayerCannotAlsoJoinTheHub() {
        rooms.join(player(ALICE), "dojo");
        joinHub(ALICE);
        assertFalse(rooms.inHub(ALICE));
        assertEquals(Optional.of("dojo"), rooms.arenaOf(ALICE));
    }

    @Test
    void eventArenaRestrictsAutomaticPortalsAndHubUntilTheWindowEnds() throws Exception {
        arena("bay", 2, 2);
        rooms.setEventArena(() -> Optional.of("dojo"));
        assertEquals(Optional.of("dojo"), rooms.openArena());
        arenas.hold("dojo");
        assertEquals(Optional.empty(), rooms.openArena());
        joinHub(ALICE);
        joinHub(BOB);
        for (int tick = 0; tick < 40; tick++) {
            rooms.tick();
        }
        assertTrue(rooms.inHub(ALICE));
        rooms.setEventArena(Optional::empty);
        assertEquals(Optional.of("bay"), rooms.openArena());
        for (int tick = 0; tick < 40; tick++) {
            rooms.tick();
        }
        assertFalse(rooms.inHub(ALICE));
    }

    @Test
    void playerBuiltPortalsGoToTheFullestFillingRoomThenAFreeArenaThenNowhere() throws Exception {
        arena("cave", 1, 2);
        arena("bay", 2, 2);
        boolean[] reserved = {false};
        rooms.setClaimed(name -> reserved[0] && name.equals("bay"));
        rooms.setSettings(caps(3, 2 + 1, 0));
        // No room is open: the first free arena by name.
        assertEquals(Optional.of("bay"), rooms.openArena());
        reserved[0] = true;
        assertEquals(Optional.of("cave"), rooms.openArena(), "a reserved arena is left to its host");

        rooms.join(player(ALICE), "dojo");
        assertEquals(Optional.of("dojo"), rooms.openArena(), "a filling room beats a free arena");
        rooms.join(player(BOB), "cave");
        rooms.join(player(CAROL), "cave");
        assertEquals(Optional.of("cave"), rooms.openArena(), "the room with the most players starts sooner");

        UUID dave = new UUID(0, 4);
        add(dave, "Dave");
        rooms.join(player(dave), "cave");
        assertEquals(Optional.of("dojo"), rooms.openArena(), "a full room takes no one");

        arenas.hold("dojo");
        // dojo's room is no longer available and cave's is full; bay is reserved.
        assertEquals(Optional.empty(), rooms.openArena());
        reserved[0] = false;
        assertEquals(Optional.of("bay"), rooms.openArena());
    }

}
