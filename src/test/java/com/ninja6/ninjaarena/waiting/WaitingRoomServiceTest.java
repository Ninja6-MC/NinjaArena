package com.ninja6.ninjaarena.waiting;

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
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.waiting.WaitingRoomService.Exit;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        }, (arena, teams) -> started.add(teams));
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
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        players.put(id, fake);
    }

    private Player player(UUID id) {
        return players.get(id).proxy;
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
}
