package com.ninja6.ninjaarena.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.arena.ArenaStore;
import com.ninja6.ninjaarena.arena.ChunkTickets;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.arena.MemoryCopies;
import com.ninja6.ninjaarena.portal.Portal;
import com.ninja6.ninjaarena.portal.PortalRegistry;
import com.ninja6.ninjaarena.portal.PortalStore;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortalCommandTest {

    private static final UUID ADMIN = UUID.fromString("a11ce000-0000-4000-8000-000000000001");

    @TempDir
    Path dir;

    private final List<String> messages = new ArrayList<>();
    private ArenaRegistry arenas;
    private PortalRegistry portals;
    private PortalCommand portalCommand;
    private NinjaArenaCommand command;

    /** Where the test player stands; changed between commands. */
    private Location standing;

    @BeforeEach
    void setUp() throws Exception {
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
        arenas.finishEdit("dojo");
        portals = new PortalRegistry(new PortalStore(dir.resolve("portals.yml")));
        portals.load();
        portalCommand = new PortalCommand(portals, arenas, world -> !world.equals("gone"));
        command = new NinjaArenaCommand(new ArenaCommand(arenas, () -> null), portalCommand, player -> false);
        standing = new Location(world("world"), 10.5, 64, 10.5);
    }

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Object record(Object message) {
        if (message instanceof Component component) {
            messages.add(PlainTextComponentSerializer.plainText().serialize(component));
        } else {
            messages.add(String.valueOf(message));
        }
        return null;
    }

    private CommandSender player(boolean admin) {
        return (CommandSender) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> admin && NinjaArenaCommand.ADMIN.equals(args[0]);
                    case "sendMessage" -> record(args[0]);
                    case "getUniqueId" -> ADMIN;
                    case "getName" -> "Admin";
                    case "getLocation" -> standing.clone();
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[] {ConsoleCommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> NinjaArenaCommand.ADMIN.equals(args[0]);
                    case "sendMessage" -> record(args[0]);
                    case "getName" -> "CONSOLE";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private void run(CommandSender sender, String line) {
        command.onCommand(sender, null, "narena", line.split(" "));
    }

    private List<String> complete(CommandSender sender, String... args) {
        return command.onTabComplete(sender, null, "narena", args);
    }

    @Test
    void withoutAdminNothingRunsOrCompletes() {
        CommandSender sender = player(false);
        run(sender, "portal setcorner 1");
        assertEquals(List.of("You do not have permission to use this command."), messages);
        assertEquals(List.of(), complete(sender, "p"));
        assertEquals(List.of(), complete(sender, "portal", ""));
        assertEquals(List.of(), complete(sender, "portal", "create", "lobby", ""));
    }

    @Test
    void aPlayerSelectsCornersAndCreatesAPortal() {
        CommandSender sender = player(true);
        run(sender, "portal setcorner 1");
        standing = new Location(world("world"), 12.2, 67.9, 10.7);
        run(sender, "portal setcorner 2");
        assertEquals("Selected 10, 64, 10 to 12, 67, 10.", messages.get(messages.size() - 1));
        messages.clear();
        run(sender, "portal create Lobby DOJO");

        Portal lobby = portals.get("lobby").orElseThrow();
        assertEquals(new Portal("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo"), lobby);
        assertEquals("Portal lobby created in world (10, 64, 10 to 12, 67, 10), leading to arena dojo.",
                messages.get(0));
        assertEquals("Arena dojo is not available right now, so the portal turns players away until it is.",
                messages.get(1));

        // The selection is used up.
        messages.clear();
        run(sender, "portal create other dojo");
        assertEquals(List.of("Set both corners first with setcorner 1 and setcorner 2."), messages);
    }

    @Test
    void cornersAcceptCoordinatesAndOffsets() {
        CommandSender sender = player(true);
        run(sender, "portal setcorner 1 ~ ~-1 ~2");
        run(sender, "portal setcorner 2 20 70 12");
        assertEquals("Corner 1 set to 10, 63, 12 in world.", messages.get(0));
        run(sender, "portal create lobby dojo");
        assertEquals(new ArenaBounds(10, 63, 12, 20, 70, 12), portals.get("lobby").orElseThrow().region());
    }

    @Test
    void aCornerInAnotherWorldClearsTheOtherOne() {
        CommandSender sender = player(true);
        run(sender, "portal setcorner 1");
        standing = new Location(world("world_nether"), 0, 64, 0);
        run(sender, "portal setcorner 2");
        assertEquals("Your other corner was in world and has been cleared.", messages.get(1));
        messages.clear();
        run(sender, "portal create lobby dojo");
        assertEquals(List.of("Set both corners first with setcorner 1 and setcorner 2."), messages);
    }

    @Test
    void theArenaWorldAndUnknownArenasAreRefused() {
        CommandSender sender = player(true);
        standing = new Location(world(ArenaWorld.NAME), 0, 64, 0);
        run(sender, "portal setcorner 1");
        assertEquals(List.of("Portals cannot be in the arena world."), messages);

        standing = new Location(world("world"), 0, 64, 0);
        run(sender, "portal setcorner 1");
        run(sender, "portal setcorner 2");
        messages.clear();
        run(sender, "portal create lobby nowhere");
        assertEquals(List.of("No arena called nowhere."), messages);
        assertTrue(portals.all().isEmpty());
    }

    @Test
    void consoleCannotSelectButCanListInspectAndDelete() throws Exception {
        portals.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
        portals.create("far", "gone", new ArenaBounds(0, 64, 0, 0, 64, 0), "missing");
        CommandSender sender = console();
        run(sender, "portal setcorner 1");
        run(sender, "portal create lobby2 dojo");
        assertEquals(List.of("Only a player can select a portal region.",
                "Only a player can create a portal from their selection."), messages);

        messages.clear();
        run(sender, "portal list");
        assertEquals(List.of("far (gone) to missing: world not loaded",
                "lobby (world) to dojo: closed, arena not ready"), messages);

        messages.clear();
        run(sender, "portal info lobby");
        assertEquals(List.of("Portal lobby: closed, arena not ready", "World: world",
                "Region: 10, 64, 10 to 12, 67, 10", "Arena: dojo"), messages);

        messages.clear();
        run(sender, "portal delete lobby");
        run(sender, "portal info lobby");
        assertEquals(List.of("Portal lobby removed. Blocks built around it are still in the world.",
                "No portal called lobby."), messages);
    }

    @Test
    void tabCompletesSubcommandsCornersAndNames() throws Exception {
        portals.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
        CommandSender sender = player(true);
        assertEquals(List.of("portal"), complete(sender, "p"));
        assertEquals(List.of("setcorner", "create", "delete", "list", "info"), complete(sender, "portal", ""));
        assertEquals(List.of("1", "2"), complete(sender, "portal", "setcorner", ""));
        assertEquals(List.of("~", "64"), complete(sender, "portal", "setcorner", "1", "10", ""));
        assertEquals(List.of("dojo"), complete(sender, "portal", "create", "lobby2", ""));
        assertEquals(List.of("lobby"), complete(sender, "portal", "info", "l"));
        assertEquals(List.of(), complete(sender, "portal", "list", ""));
    }
}
