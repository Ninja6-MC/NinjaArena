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
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.support.TestTaggedItem;
import com.ninja6.ninjaarena.waiting.WaitingRoomListener;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
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
    private final List<Runnable> nextTick = new ArrayList<>();
    private final List<UUID> restored = new ArrayList<>();
    private World overworld;
    private World arenaWorld;
    private ArenaRegistry arenas;
    private PortalListener listener;
    private WaitingRoomService rooms;
    private WaitingRoomListener roomListener;
    private int captures;
    /** Captures made after the player's inventory screen was closed. */
    private int capturesAfterClose;
    private boolean closed;

    // The test player's state.
    private Location location;
    private GameMode gameMode = GameMode.SURVIVAL;
    private boolean canPlay = true;
    private boolean online = true;
    private boolean teleportRefused;
    /** Whether the waiting room's chunk is already loaded, so its future is complete at once. */
    private boolean chunkLoaded;
    /** What the player holds on the cursor, and in the first slot of their 2x2 crafting grid. */
    private Material cursor = Material.AIR;
    private Material gridItem = Material.AIR;

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
            if (closed) {
                capturesAfterClose++;
            }
            return CaptureResult.SAVED;
        });
        Plugin plugin = plugin(logger);
        rooms = new WaitingRoomService(plugin, arenas, new WaitingRoomSettings(2, 3, 4), player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, player -> { }, (arena, teams) -> { });
        roomListener = new WaitingRoomListener(rooms);
        listener = new PortalListener(plugin, portals, entry, () -> arenaWorld, snapshots, id -> { },
                (player, arena) -> {
                    entered.add(arena);
                    rooms.join(player, arena);
                });
        location = new Location(overworld, 11.5, 64, 9.5);
    }

    private World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getChunkAtAsync" -> {
                        if (chunkLoaded) {
                            yield CompletableFuture.<Chunk>completedFuture(null);
                        }
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
        BukkitTask task = (BukkitTask) Proxy.newProxyInstance(BukkitTask.class.getClassLoader(),
                new Class<?>[] {BukkitTask.class}, (proxy, method, args) -> null);
        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(BukkitScheduler.class.getClassLoader(),
                new Class<?>[] {BukkitScheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "runTask" -> {
                        nextTick.add((Runnable) args[1]);
                        yield task;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isPrimaryThread" -> true;
                    case "getPlayer" -> online && ALICE.equals(args[0]) ? player() : null;
                    case "getScheduler" -> scheduler;
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
                        if (teleportRefused) {
                            yield false;
                        }
                        location = ((Location) args[0]).clone();
                        teleports.add(location);
                        yield true;
                    }
                    case "sendMessage" -> {
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    case "sendActionBar" -> null;
                    case "closeInventory" -> {
                        closed = true;
                        yield null;
                    }
                    case "getItemOnCursor" -> new TestTaggedItem(cursor, Map.of(), cursor == Material.AIR ? 0 : 1);
                    case "getOpenInventory" -> openInventory();
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The player's own inventory screen, whose top half is the 2x2 crafting grid. */
    private InventoryView openInventory() {
        CraftingInventory grid = (CraftingInventory) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {CraftingInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMatrix" -> new ItemStack[] {
                        new TestTaggedItem(gridItem, Map.of(), gridItem == Material.AIR ? 0 : 1),
                        null, null, null};
                    case "hashCode" -> 2;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (InventoryView) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {InventoryView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getTopInventory" -> grid;
                    case "hashCode" -> 3;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * Moves the player to the given spot and returns the event after the listeners saw it, in
     * the order the server calls them: the portal at HIGH, the waiting room at MONITOR. A
     * teleport made inside the event wins over the event's destination, as on the server.
     */
    private PlayerMoveEvent move(double x, double y, double z, float yaw) {
        Location to = new Location(overworld, x, y, z, yaw, 0);
        PlayerMoveEvent event = new PlayerMoveEvent(player(), location.clone(), to);
        int teleported = teleports.size();
        listener.onMove(event);
        if (!event.isCancelled()) {
            roomListener.onMove(event);
        }
        if (teleports.size() == teleported) {
            location = event.getTo().clone();
        }
        return event;
    }

    /** Completes the pending chunk loads and runs what they scheduled for the next tick. */
    private void finishChunkLoads() {
        for (CompletableFuture<Chunk> load : new ArrayList<>(chunkLoads)) {
            load.complete(null);
        }
        runNextTick();
    }

    private void runNextTick() {
        List<Runnable> tasks = new ArrayList<>(nextTick);
        nextTick.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void walkingInSnapshotsAndTeleportsOnceTheChunkHasLoaded() {
        move(11.5, 64, 10.5, 0);
        assertEquals(1, chunkLoads.size());
        assertEquals(0, captures);

        finishChunkLoads();
        assertEquals(1, captures);
        // Cursor and crafting-grid items go back into the inventory before it is saved.
        assertEquals(1, capturesAfterClose);
        assertEquals(1, teleports.size());
        Location target = teleports.get(0);
        assertEquals(arenaWorld, target.getWorld());
        assertEquals(ROOM.x(), target.getX());
        assertEquals(ROOM.z(), target.getZ());
        assertEquals(List.of("dojo"), entered);
    }

    @Test
    void itemsHeldOnTheCursorOrInTheCraftingGridKeepThePlayerOut() {
        for (boolean onCursor : new boolean[] {true, false}) {
            cursor = onCursor ? Material.DIAMOND : Material.AIR;
            gridItem = onCursor ? Material.AIR : Material.DIAMOND;
            messages.clear();
            location = new Location(overworld, 11.5, 64, 9.5);
            move(11.5, 64, 10.5, 0);
            finishChunkLoads();
            assertEquals(0, captures);
            assertFalse(closed);
            assertTrue(teleports.stream().noneMatch(spot -> spot.getWorld() == arenaWorld));
            assertEquals(9, location.getBlockZ());
            // Refusal messages share a cooldown, so only the first round shows one.
            if (onCursor) {
                assertEquals(List.of("Put away the items on your cursor or in your crafting grid, then try again."),
                        messages);
            }
        }

        cursor = Material.AIR;
        gridItem = Material.AIR;
        move(11.5, 64, 9.5, 0);
        move(11.5, 64, 10.5, 0);
        finishChunkLoads();
        assertEquals(1, captures);
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

    @Test
    void anAlreadyLoadedChunkEntersOnTheNextTickAndThePlayerStaysInTheRoom() {
        chunkLoaded = true;
        move(11.5, 64, 10.5, 0);
        assertTrue(teleports.isEmpty());
        assertFalse(rooms.isWaiting(ALICE));

        runNextTick();
        assertEquals(1, captures);
        assertEquals(1, teleports.size());
        assertEquals(arenaWorld, location.getWorld());
        assertEquals(List.of("dojo"), entered);
        assertTrue(rooms.isWaiting(ALICE));

        runNextTick();
        assertTrue(rooms.isWaiting(ALICE));
        assertTrue(restored.isEmpty());
        assertFalse(messages.contains("You left the waiting room."));
    }

    @Test
    void aRefusedTeleportIsToldOnceInAWhile() {
        teleportRefused = true;
        move(11.5, 64, 10.5, 0);
        finishChunkLoads();
        move(11.5, 64, 9.5, 0);
        move(11.5, 64, 10.5, 0);
        finishChunkLoads();
        assertEquals(2, captures);
        assertTrue(entered.isEmpty());
        assertEquals(List.of("You could not be sent to the arena."), messages);
    }
}
