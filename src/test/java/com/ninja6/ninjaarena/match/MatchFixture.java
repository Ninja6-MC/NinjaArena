package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.arena.ArenaStore;
import com.ninja6.ninjaarena.arena.ChunkTickets;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.arena.MemoryCopies;
import com.ninja6.ninjaarena.match.event.MatchEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

abstract class MatchFixture {

    protected static final ArenaPoint ROOM = new ArenaPoint(50.5, 64, 50.5, 0, 0);
    protected static final ArenaPoint SPAWN_1 = new ArenaPoint(20.5, 64, 20.5, 90, 0);
    protected static final ArenaPoint SPAWN_2 = new ArenaPoint(80.5, 64, 80.5, -90, 0);
    protected static final UUID ALICE = new UUID(0, 1);
    protected static final UUID BOB = new UUID(0, 2);
    protected static final UUID CAROL = new UUID(0, 3);
    protected static final UUID DAVE = new UUID(0, 4);

    /** Prep 2, combat 3, overrun limit 2, round end 1, match end 2, 2 rounds, 1 extra. */
    protected static final MatchSettings SETTINGS = new MatchSettings(2, 3, 2, 1, 2, 2, 1);

    @TempDir
    Path dir;

    /** A fake player's state. */
    protected static final class Fake {
        final UUID id;
        final String name;
        Location location;
        boolean online = true;
        boolean dead;
        GameMode gameMode = GameMode.SURVIVAL;
        Entity spectatorTarget;
        Player killer;
        ItemStack[] storage = new ItemStack[0];
        ItemStack[] armour = new ItemStack[4];
        ItemStack offhand;
        ItemStack cursor;
        ItemStack[] crafting = new ItemStack[5];
        PlayerInventory inventory;
        InventoryView view;
        final List<String> messages = new ArrayList<>();
        final List<Location> teleports = new ArrayList<>();
        final List<PotionEffect> effects = new ArrayList<>();
        final Map<Material, Integer> cooldowns = new HashMap<>();
        Vector velocity;
        boolean gliding;
        Player proxy;

        Fake(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    protected final Map<UUID, Fake> players = new HashMap<>();
    protected final List<Runnable> nextTick = new ArrayList<>();
    protected final List<UUID> restored = new ArrayList<>();
    protected final List<UUID> readied = new ArrayList<>();
    protected final List<MatchEvent> events = new ArrayList<>();
    protected final MemoryCopies copies = new MemoryCopies();
    protected World arenaWorld;
    protected World overworld;
    protected Plugin plugin;
    protected ArenaRegistry arenas;
    protected MatchService matches;
    protected MatchListener listener;
    protected MatchDeathListener deaths;
    protected Consumer<PlayerGameModeChangeEvent> gameModeChanges = event -> { };
    protected Consumer<PlayerTeleportEvent> teleportEvents = event -> { };

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
                new ArenaGrid(256, 2048, -64, 320), enabled -> { }, noTickets, copies);
        arenas.load();
        arenas.create("dojo", new GridCell(0, 0));
        arenas.update("dojo", a -> a.withBounds(new ArenaBounds(10, 60, 10, 90, 100, 90))
                .withWaitingRoom(ROOM)
                .withTeamSpawn(1, SPAWN_1)
                .withTeamSpawn(2, SPAWN_2)
                .withEnemySpawn(new ArenaPoint(50.5, 64, 20.5, 0, 0)));
        arenas.finishEdit("dojo");
        copies.calls.clear();

        plugin = plugin();
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, events::add, player -> readied.add(player.getUniqueId()));
        deaths = new MatchDeathListener(plugin, matches, arenas);
        listener = new MatchListener(matches, deaths);
        add(ALICE, "Alice");
        add(BOB, "Bob");
        add(CAROL, "Carol");
        add(DAVE, "Dave");
    }

    protected World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    protected Plugin plugin() {
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

    protected void add(UUID id, String name) {
        Fake fake = new Fake(id, name);
        fake.location = new Location(arenaWorld, ROOM.x(), ROOM.y(), ROOM.z());
        fake.inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[] {PlayerInventory.class}, (p, m, a) -> switch (m.getName()) {
                    case "getStorageContents" -> fake.storage.clone();
                    case "setStorageContents" -> { fake.storage = ((ItemStack[]) a[0]).clone(); yield null; }
                    case "getArmorContents" -> fake.armour.clone();
                    case "setArmorContents" -> { fake.armour = ((ItemStack[]) a[0]).clone(); yield null; }
                    case "getItemInOffHand" -> fake.offhand;
                    case "setItemInOffHand" -> { fake.offhand = (ItemStack) a[0]; yield null; }
                    case "setItem" -> {
                        int slot = (Integer) a[0];
                        ItemStack item = (ItemStack) a[1];
                        if (slot == 40) fake.offhand = item;
                        else if (slot >= 36) fake.armour[slot - 36] = item;
                        else {
                            fake.storage = java.util.Arrays.copyOf(fake.storage, Math.max(36, fake.storage.length));
                            fake.storage[slot] = item;
                        }
                        yield null;
                    }
                    case "clear" -> {
                        fake.storage = new ItemStack[36]; fake.armour = new ItemStack[4]; fake.offhand = null;
                        yield null;
                    }
                    case "setHeldItemSlot" -> null;
                    case "getHolder" -> fake.proxy;
                    case "getType" -> InventoryType.PLAYER;
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Inventory crafting = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, (p, m, a) -> switch (m.getName()) {
                    case "getType" -> InventoryType.CRAFTING;
                    case "getHolder" -> fake.proxy;
                    case "clear" -> { fake.crafting = new ItemStack[5]; yield null; }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        fake.view = (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                new Class<?>[] {InventoryView.class}, (p, m, a) -> switch (m.getName()) {
                    case "getTopInventory" -> crafting;
                    case "getBottomInventory" -> fake.inventory;
                    case "getPlayer" -> fake.proxy;
                    case "getType" -> InventoryType.CRAFTING;
                    case "convertSlot" -> a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        fake.proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "isOnline" -> fake.online;
                    case "isDead" -> fake.dead;
                    case "getKiller" -> fake.killer;
                    case "getGameMode" -> fake.gameMode;
                    case "getSpectatorTarget" -> fake.spectatorTarget;
                    case "setSpectatorTarget" -> {
                        if (fake.gameMode != GameMode.SPECTATOR) {
                            throw new IllegalStateException("player is not a spectator");
                        }
                        fake.spectatorTarget = (Entity) args[0];
                        yield null;
                    }
                    case "setGameMode" -> {
                        var event = new PlayerGameModeChangeEvent(fake.proxy, (GameMode) args[0],
                                PlayerGameModeChangeEvent.Cause.PLUGIN, null);
                        gameModeChanges.accept(event);
                        if (!event.isCancelled()) {
                            fake.gameMode = event.getNewGameMode();
                            if (fake.gameMode != GameMode.SPECTATOR) {
                                fake.spectatorTarget = null;
                            }
                        }
                        yield null;
                    }
                    case "getRespawnLocation", "getAttribute" -> null;
                    case "leaveVehicle", "eject" -> false;
                    case "getInventory" -> fake.inventory;
                    case "getOpenInventory" -> fake.view;
                    case "setItemOnCursor" -> { fake.cursor = (ItemStack) args[0]; yield null; }
                    case "getItemOnCursor" -> fake.cursor;
                    case "getActivePotionEffects" -> List.of();
                    case "setVelocity" -> { fake.velocity = (Vector) args[0]; yield null; }
                    case "isGliding" -> fake.gliding;
                    case "isFlying", "isRiptiding", "isInsideVehicle" -> false;
                    case "setCooldown" -> { fake.cooldowns.put((Material) args[0], (Integer) args[1]); yield null; }
                    case "addPotionEffect" -> fake.effects.add((PotionEffect) args[0]);
                    case "removePotionEffect" -> fake.effects.removeIf(effect -> effect.getType() == args[0]);
                    case "setGliding", "setFallDistance", "closeInventory",
                            "setTotalExperience", "setLevel", "setExp", "setHealth", "setFoodLevel", "setSaturation",
                            "setExhaustion", "setFireTicks" -> null;
                    case "getLocation" -> fake.location.clone();
                    case "teleport" -> {
                        Location to = (Location) args[0];
                        var event = new PlayerTeleportEvent(fake.proxy, fake.location.clone(), to,
                                args.length > 1 && args[1] instanceof PlayerTeleportEvent.TeleportCause cause
                                        ? cause : PlayerTeleportEvent.TeleportCause.PLUGIN);
                        teleportEvents.accept(event);
                        if (event.isCancelled()) {
                            yield false;
                        }
                        to = event.getTo();
                        fake.teleports.add(to);
                        fake.location = to;
                        yield true;
                    }
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

    protected Player player(UUID id) {
        return players.get(id).proxy;
    }

    protected void runNextTick() {
        List<Runnable> tasks = new ArrayList<>(nextTick);
        nextTick.clear();
        tasks.forEach(Runnable::run);
    }

    protected void ticks(int count) {
        for (int i = 0; i < count; i++) {
            matches.tick();
        }
    }

    protected void startTwoOnTwo() {
        matches.start("dojo", List.of(List.of(ALICE, CAROL), List.of(BOB, DAVE)));
    }

    protected <T extends MatchEvent> List<T> events(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    protected static void assertAt(ArenaPoint expected, Location actual) {
        assertEquals(ArenaWorld.NAME, actual.getWorld().getName());
        assertEquals(expected.x(), actual.getX());
        assertEquals(expected.y(), actual.getY());
        assertEquals(expected.z(), actual.getZ());
        assertEquals(expected.yaw(), actual.getYaw());
    }

}
