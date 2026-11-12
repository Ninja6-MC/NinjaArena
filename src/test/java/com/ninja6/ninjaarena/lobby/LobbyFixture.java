package com.ninja6.ninjaarena.lobby;

import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.LobbyWorld;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/** Fake players, worlds and a plugin whose scheduler runs a task when the test says so. */
abstract class LobbyFixture {

    static final UUID ALICE = new UUID(0, 1);
    static final UUID BOB = new UUID(0, 2);

    final World lobbyWorld = world(LobbyWorld.NAME);
    final World arenaWorld = world(ArenaWorld.NAME);
    final World overworld = world("world");
    final LobbyLayout layout = LobbyLayout.bundled();
    final Map<UUID, Fake> players = new HashMap<>();
    final List<Runnable> nextTick = new ArrayList<>();
    final Set<UUID> busyElsewhere = new HashSet<>();
    final List<UUID> sentHome = new ArrayList<>();
    final List<UUID> emptied = new ArrayList<>();
    final Plugin plugin = plugin();

    /** A fake player's state. */
    static final class Fake {
        final UUID id;
        final String name;
        Location location;
        boolean online = true;
        boolean dead;
        boolean refuseTeleport;
        float fallDistance = 12;
        final List<String> messages = new ArrayList<>();
        /** Whether each teleport was marked as NinjaArena's own. */
        final List<Boolean> ownTeleports = new ArrayList<>();
        Player proxy;

        Fake(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    LobbyMembers members() {
        return new LobbyMembers(plugin, layout, () -> lobbyWorld, busyElsewhere::contains, player -> {
            emptied.add(player.getUniqueId());
        }, player -> {
            sentHome.add(player.getUniqueId());
            players.get(player.getUniqueId()).location = new Location(overworld, 0, 70, 0);
            return RestoreResult.RESTORED;
        });
    }

    static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUID" -> UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    static Logger quiet() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }

    void runNextTick() {
        List<Runnable> tasks = new ArrayList<>(nextTick);
        nextTick.clear();
        tasks.forEach(Runnable::run);
    }

    private Plugin plugin() {
        Logger logger = quiet();
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

    Player add(UUID id, String name, Location location) {
        Fake fake = new Fake(id, name);
        fake.location = location;
        fake.proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> fake.id;
                    case "getName" -> fake.name;
                    case "getLocation" -> fake.location.clone();
                    case "getWorld" -> fake.location.getWorld();
                    case "isOnline" -> fake.online;
                    case "isDead" -> fake.dead;
                    case "teleport" -> {
                        fake.ownTeleports.add(OwnTeleports.isActive(fake.id));
                        if (fake.refuseTeleport) {
                            yield false;
                        }
                        fake.location = ((Location) args[0]).clone();
                        yield true;
                    }
                    case "setFallDistance" -> {
                        fake.fallDistance = (Float) args[0];
                        yield null;
                    }
                    case "setVelocity" -> null;
                    case "sendMessage" -> {
                        fake.messages.add(args[0] instanceof Component component
                                ? PlainTextComponentSerializer.plainText().serialize(component) : String.valueOf(args[0]));
                        yield null;
                    }
                    case "hasPermission" -> true;
                    case "hashCode" -> fake.id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> fake.name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        players.put(id, fake);
        return fake.proxy;
    }

    Location inLobby(double x, double y, double z) {
        return new Location(lobbyWorld, x, y, z);
    }

    Location spawn() {
        return layout.spawn(lobbyWorld);
    }
}
