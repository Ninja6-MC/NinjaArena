package com.ninja6.ninjaarena.portal;

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
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortalListenerTest {

    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");
    private static final ArenaPoint ROOM = new ArenaPoint(50.5, 64, 50.5, 0, 0);

    @TempDir
    Path dir;

    private final List<CompletableFuture<Chunk>> chunkLoads = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final List<Location> teleports = new ArrayList<>();
    private final List<String> entered = new ArrayList<>();
    private World overworld;
    private World arenaWorld;
    private ArenaRegistry arenas;
    private PortalListener listener;
    private int captures;

    // The test player's state.
    private Location location;
    private GameMode gameMode = GameMode.SURVIVAL;
    private boolean canPlay = true;
    private boolean online = true;

    @BeforeEach
    void setUp() throws Exception {
        overworld = world("world");
        arenaWorld = world(ArenaWorld.NAME);
        ChunkTickets noTickets = new ChunkTickets() {
            @Override
            public void add(int chunkX, int chunkZ) {
            }

            @Override
            public void remove(int chunkX, int chunkZ) {
            }
        };
        arenas = new ArenaRegistry(new ArenaStore(dir.resolve("arenas.yml")), new ArenaGrid(256, 2048, -64, 320),
                enabled -> { }, noTickets, new MemoryCopies());
        arenas.load();
        arenas.create("dojo", new GridCell(0, 0));
        arenas.update("dojo", a -> a.withBounds(new ArenaBounds(10, 60, 10, 90, 100, 90))
                .withWaitingRoom(ROOM)
                .withTeamSpawn(1, new ArenaPoint(20.5, 64, 20.5, 0, 0))
                .withTeamSpawn(2, new ArenaPoint(80.5, 64, 80.5, 0, 0))
                .withEnemySpawn(new ArenaPoint(50.5, 64, 20.5, 0, 0)));
        arenas.finishEdit("dojo");

        PortalRegistry portals = new PortalRegistry(new PortalStore(dir.resolve("portals.yml")));
        portals.load();
        portals.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");

        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        SnapshotService snapshots = new SnapshotService(new SnapshotStore(dir.resolve("snapshots")), logger);
        PortalEntry entry = new PortalEntry(arenas, new HashSet<UUID>()::contains, player -> {
            captures++;
            return CaptureResult.SAVED;
        });
        listener = new PortalListener(plugin(logger), portals, entry, () -> arenaWorld, snapshots, id -> { },
                (player, arena) -> entered.add(arena));
        location = new Location(overworld, 11.5, 64, 9.5);
    }

    private World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getChunkAtAsync" -> {
                        CompletableFuture<Chunk> load = new CompletableFuture<>();
                        chunkLoads.add(load);
                        yield load;
                    }
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Plugin plugin(Logger logger) {
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isPrimaryThread" -> true;
                    case "getPlayer" -> online && ALICE.equals(args[0]) ? player() : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getLogger" -> logger;
                    case "isEnabled" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ALICE;
                    case "getName" -> "Alice";
                    case "hasPermission" -> canPlay && PortalEntry.PLAY.equals(args[0]);
                    case "getGameMode" -> gameMode;
                    case "getLocation" -> location.clone();
                    case "isOnline" -> online;
                    case "isDead" -> false;
                    case "teleport" -> {
                        location = ((Location) args[0]).clone();
                        teleports.add(location);
                        yield true;
                    }
                    case "sendMessage" -> {
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Moves the player to the given spot and returns the event after the listener saw it. */
    private PlayerMoveEvent move(double x, double y, double z, float yaw) {
        Location to = new Location(overworld, x, y, z, yaw, 0);
        PlayerMoveEvent event = new PlayerMoveEvent(player(), location.clone(), to);
        listener.onMove(event);
        location = event.getTo().clone();
        return event;
    }

    private void finishChunkLoads() {
        for (CompletableFuture<Chunk> load : new ArrayList<>(chunkLoads)) {
            load.complete(null);
        }
    }

    @Test
    void walkingInSnapshotsAndTeleportsOnceTheChunkHasLoaded() {
        move(11.5, 64, 10.5, 0);
        assertEquals(1, chunkLoads.size());
        assertEquals(0, captures);

        finishChunkLoads();
        assertEquals(1, captures);
        assertEquals(1, teleports.size());
        Location target = teleports.get(0);
        assertEquals(arenaWorld, target.getWorld());
        assertEquals(ROOM.x(), target.getX());
        assertEquals(ROOM.z(), target.getZ());
        assertEquals(List.of("dojo"), entered);
    }

    @Test
    void aSecondCrossingWhileTheChunkLoadsIsIgnored() {
        move(11.5, 64, 10.5, 0);
        move(11.5, 64, 9.5, 0);
        move(11.5, 64, 10.5, 0);
        assertEquals(1, chunkLoads.size());

        finishChunkLoads();
        assertEquals(1, captures);
        assertEquals(1, teleports.size());
    }

    @Test
    void movingInsideThePortalOrOnlyTurningDoesNothing() {
        location = new Location(overworld, 10.5, 64, 10.5);
        move(11.5, 64, 10.5, 0);
        move(11.5, 65, 10.5, 0);
        move(11.7, 65.2, 10.3, 90);
        assertTrue(chunkLoads.isEmpty());
        assertTrue(messages.isEmpty());
    }

    @Test
    void spectatorsAreIgnored() {
        gameMode = GameMode.SPECTATOR;
        PlayerMoveEvent event = move(11.5, 64, 10.5, 0);
        assertTrue(chunkLoads.isEmpty());
        assertEquals(10, event.getTo().getBlockZ());
    }

    @Test
    void aRefusedPlayerIsPutBackAndToldOnceInAWhile() {
        canPlay = false;
        PlayerMoveEvent event = move(11.5, 64, 10.5, 45);
        assertEquals(9, event.getTo().getBlockZ());
        assertEquals(45, event.getTo().getYaw());
        assertEquals(List.of("You do not have permission to enter arenas."), messages);

        move(11.5, 64, 10.5, 45);
        assertEquals(1, messages.size());
        assertTrue(chunkLoads.isEmpty());
        assertEquals(0, captures);
    }

    @Test
    void aPlayerTeleportedAwayWhileTheChunkLoadsIsDropped() {
        move(11.5, 64, 10.5, 0);
        location = new Location(arenaWorld, 0, 64, 0);
        finishChunkLoads();
        assertEquals(0, captures);
        assertTrue(teleports.isEmpty());

        location = new Location(overworld, 11.5, 64, 9.5);
        move(11.5, 64, 10.5, 0);
        location = new Location(overworld, 200, 64, 200);
        finishChunkLoads();
        assertEquals(0, captures);
        assertTrue(teleports.isEmpty());
    }

    @Test
    void aPlayerWhoLeftWhileTheChunkLoadsIsDropped() {
        move(11.5, 64, 10.5, 0);
        online = false;
        finishChunkLoads();
        assertEquals(0, captures);
        assertTrue(entered.isEmpty());
    }

    @Test
    void aPlayerWhoSteppedBackOutStillEnters() {
        move(11.5, 64, 10.5, 0);
        location = new Location(overworld, 11.5, 64, 9.5);
        finishChunkLoads();
        assertEquals(1, captures);
        assertFalse(teleports.isEmpty());
    }
}
