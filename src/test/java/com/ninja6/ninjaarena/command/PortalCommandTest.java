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
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.portal.LobbyMode;
import com.ninja6.ninjaarena.portal.Portal;
import com.ninja6.ninjaarena.portal.PortalRegistry;
import com.ninja6.ninjaarena.portal.PortalStore;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    // Location owns only a weak reference; this fixture owns each test world's lifetime.
    private final List<World> worlds = new ArrayList<>();
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
        ArenaCommand arenaCommand = new ArenaCommand(arenas, () -> null);
        arenaCommand.setHub(portals::hub);
        command = new NinjaArenaCommand(arenaCommand, portalCommand,
                new DirectorCommand(List::of, id -> Optional.empty(), match -> Optional.empty()), player -> false);
        standing = new Location(world("world"), 10.5, 64, 10.5);
    }

    private World world(String name) {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        worlds.add(world);
        return world;
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
        assertEquals("Portal lobby created in world (10, 64, 10 to 12, 67, 10), leading to arena dojo (server"
                + " default).", messages.get(0));
        assertEquals("Arena dojo is not available right now, so the portal turns players away until it is.",
                messages.get(1));

        // The selection is used up.
        messages.clear();
        run(sender, "portal create other dojo");
        assertEquals(List.of("Set both corners first with setcorner 1 and setcorner 2."), messages);
    }

    @Test
    void aOneBlockTallSelectionWarnsAndSuggestsADoorway() {
        CommandSender sender = player(true);
        run(sender, "portal setcorner 1");
        standing = new Location(world("world"), 12.5, 64.4, 10.5);
        run(sender, "portal setcorner 2");
        assertEquals(List.of("Corner 1 set to 10, 64, 10 in world.", "Corner 2 set to 12, 64, 10 in world.",
                "Selected 10, 64, 10 to 12, 64, 10.",
                "The selection is one block tall, so the portal would be a flat pad. For a doorway, stand in it"
                        + " and use setcorner 2 ~ ~2 ~."), messages);

        messages.clear();
        run(sender, "portal setcorner 2 ~ ~2 ~");
        assertEquals(List.of("Corner 2 set to 12, 66, 10 in world.", "Selected 10, 64, 10 to 12, 66, 10."),
                messages);
    }

    @Test
    void cornersAcceptCoordinatesAndOffsets() {
        CommandSender sender = player(true);
        System.gc();
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
        assertEquals(List.of("Shared hub: not set. Portals with no lobby of their own lead to their arena's"
                + " waiting room.", "far (gone) to missing: world not loaded",
                "lobby (world) to dojo: closed, arena not ready"), messages);

        messages.clear();
        run(sender, "portal info lobby");
        assertEquals(List.of("Portal lobby: closed, arena not ready", "World: world",
                "Region: 10, 64, 10 to 12, 67, 10", "Arena: dojo", "Leads to: arena dojo (server default)"),
                messages);

        messages.clear();
        run(sender, "portal delete lobby");
        run(sender, "portal info lobby");
        assertEquals(List.of("Portal lobby removed. Blocks built around it are still in the world.",
                "No portal called lobby."), messages);
    }

    @Test
    void playerBuiltPortalsAreListedInspectedAndDeletedButChooseNoLobby() throws Exception {
        UUID owner = UUID.fromString("a11ce000-0000-4000-8000-000000000001");
        portals.createBuilt("world", new ArenaBounds(40, 64, 8, 41, 66, 8), new Portal.Built(owner, "crying_obsidian"));
        CommandSender sender = console();
        run(sender, "portal list");
        assertEquals("built-1 (world) to any arena, player-built: open, to whichever arena takes players when someone"
                + " walks in", messages.get(1));

        messages.clear();
        run(sender, "portal info built-1");
        assertEquals(List.of("Portal built-1: open, to whichever arena takes players when someone walks in",
                "World: world", "Region: 40, 64, 8 to 41, 66, 8",
                "Built by player " + owner + " from crying_obsidian. Breaking a frame block removes it.",
                "Leads to: a waiting room that is filling, else a free arena (server default)"), messages);

        messages.clear();
        run(sender, "portal lobby built-1 shared");
        assertEquals(List.of("Portal built-1 is player-built and always follows waiting-room.lobby in config.yml."),
                messages);

        messages.clear();
        run(sender, "portal delete built-1");
        assertEquals(List.of("Portal built-1 removed. Blocks built around it are still in the world."), messages);
        assertTrue(portals.all().isEmpty());
    }

    @Test
    void aPlayerBuiltPortalRefusesEveryLobbyChoiceUnderEitherDefault() throws Exception {
        LobbyMode[] serverDefault = {LobbyMode.DEDICATED};
        portalCommand = new PortalCommand(portals, arenas, world -> true, () -> serverDefault[0]);
        command = new NinjaArenaCommand(new ArenaCommand(arenas, () -> null), portalCommand,
                new DirectorCommand(List::of, id -> Optional.empty(), match -> Optional.empty()), player -> false);
        Portal built = portals.createBuilt("world", new ArenaBounds(40, 64, 8, 41, 66, 8),
                new Portal.Built(ADMIN, "crying_obsidian"));
        CommandSender console = console();
        String refusal = "Portal built-1 is player-built and always follows waiting-room.lobby in config.yml.";

        // This one used to reach the arena check with no arena and throw.
        run(console, "portal lobby built-1 default");
        assertEquals(List.of(refusal), messages);
        for (LobbyMode mode : LobbyMode.values()) {
            serverDefault[0] = mode;
            for (String choice : List.of("default", "dedicated", "shared")) {
                messages.clear();
                run(console, "portal lobby BUILT-1 " + choice);
                assertEquals(List.of(refusal), messages, mode + " " + choice);
            }
        }
        assertEquals(built, portals.get("built-1").orElseThrow());
    }

    @Test
    void lobbyDoesNotSuggestPlayerBuiltPortals() throws Exception {
        portals.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
        portals.createBuilt("world", new ArenaBounds(40, 64, 8, 41, 66, 8), new Portal.Built(ADMIN, "crying_obsidian"));
        CommandSender sender = player(true);
        assertEquals(List.of("lobby"), complete(sender, "portal", "lobby", ""));
        assertEquals(List.of("built-1", "lobby"), complete(sender, "portal", "info", ""));
        assertEquals(List.of("built-1", "lobby"), complete(sender, "portal", "delete", ""));
    }

    @Test
    void aPlayerBuiltPortalStatusFollowsASharedDefaultWithoutAnArena() throws Exception {
        LobbyMode[] serverDefault = {LobbyMode.SHARED};
        portalCommand = new PortalCommand(portals, arenas, world -> true, () -> serverDefault[0]);
        command = new NinjaArenaCommand(new ArenaCommand(arenas, () -> null), portalCommand,
                new DirectorCommand(List::of, id -> Optional.empty(), match -> Optional.empty()), player -> false);
        portals.createBuilt("world", new ArenaBounds(40, 64, 8, 41, 66, 8), new Portal.Built(ADMIN, "crying_obsidian"));
        CommandSender console = console();

        run(console, "portal info built-1");
        assertTrue(messages.contains("Portal built-1: closed, no shared hub is set"), messages.toString());
        assertTrue(messages.contains("Leads to: the shared hub (server default)"), messages.toString());

        messages.clear();
        portals.setHub(new ArenaPoint(-300.5, 64, -300.5, 0, 0));
        run(console, "portal list");
        assertTrue(messages.contains("built-1 (world) to any arena, player-built: open, to the shared hub"),
                messages.toString());
    }

    @Test
    void anAdminSetsAndRemovesTheHubLikeAWaitingRoomPoint() throws Exception {
        CommandSender console = console();
        run(console, "portal sethub");
        assertEquals(List.of("Give coordinates: /narena portal sethub [<x> <y> <z> [<yaw> <pitch>]]"), messages);

        messages.clear();
        run(console, "portal sethub -300.5 64 -300.5 90 0");
        assertEquals(Optional.of(new ArenaPoint(-300.5, 64, -300.5, 90, 0)), portals.hub());
        assertEquals("Shared hub set to -300.5, 64.0, -300.5 in " + ArenaWorld.NAME + ". Anyone waiting at the old"
                + " point is sent home.", messages.get(0));
        assertEquals("No portal leads there yet. Use lobby <portal> shared, or set waiting-room.lobby to shared in"
                + " config.yml.", messages.get(1));

        messages.clear();
        run(console, "portal sethub 5000 64 0");
        arenas.startEdit("dojo");
        arenas.update("dojo", a -> a.withBounds(new ArenaBounds(10, 60, 10, 90, 100, 90)));
        arenas.finishEdit("dojo");
        run(console, "portal sethub 50 64 50");
        assertEquals(List.of("The hub must be inside the arena world border.",
                "The hub cannot be inside the bounds of arena dojo."), messages);

        messages.clear();
        run(console, "portal removehub");
        run(console, "portal removehub");
        assertEquals(Optional.empty(), portals.hub());
        assertEquals(List.of("Shared hub removed. Portals that lead there turn players away until a hub is set.",
                "No shared hub is set."), messages);
    }

    @Test
    void arenaBoundsCannotBeSetAroundTheHub() throws Exception {
        CommandSender console = console();
        run(console, "portal sethub 50.5 64 50.5");
        arenas.startEdit("dojo");
        messages.clear();
        run(console, "arena setbounds dojo 10 60 10 90 100 90");
        assertEquals(List.of("Bounds 10, 60, 10 to 90, 100, 90 enclose the shared hub at 50.5, 64.0, 50.5. Move it"
                + " first with portal sethub."), messages);
        assertEquals(null, arenas.get("dojo").orElseThrow().bounds());

        messages.clear();
        run(console, "arena setbounds dojo 10 60 10 40 100 40");
        assertEquals(new ArenaBounds(10, 60, 10, 40, 100, 40), arenas.get("dojo").orElseThrow().bounds());
    }

    @Test
    void eachPortalChoosesItsLobbyOrFollowsTheServerDefault() throws Exception {
        LobbyMode[] serverDefault = {LobbyMode.DEDICATED};
        portalCommand = new PortalCommand(portals, arenas, world -> true, () -> serverDefault[0]);
        command = new NinjaArenaCommand(new ArenaCommand(arenas, () -> null), portalCommand,
                new DirectorCommand(List::of, id -> Optional.empty(), match -> Optional.empty()), player -> false);
        portals.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
        portals.create("watch", "world", new ArenaBounds(20, 64, 10, 22, 67, 10), "dojo", true);
        CommandSender console = console();

        run(console, "portal lobby lobby shared");
        assertEquals(LobbyMode.SHARED, portals.get("lobby").orElseThrow().lobby());
        assertEquals(List.of("Portal lobby now leads to the shared hub.",
                "No shared hub is set, so the portal turns players away until one is. Stand there and use sethub."),
                messages);

        messages.clear();
        portals.setHub(new ArenaPoint(-300.5, 64, -300.5, 0, 0));
        run(console, "portal info lobby");
        assertTrue(messages.contains("Portal lobby: open, to the shared hub"), messages.toString());
        assertTrue(messages.contains("Leads to: the shared hub"), messages.toString());

        messages.clear();
        run(console, "portal lobby lobby default");
        serverDefault[0] = LobbyMode.SHARED;
        run(console, "portal list");
        assertEquals(null, portals.get("lobby").orElseThrow().lobby());
        assertEquals("Portal lobby now leads to arena dojo (server default).", messages.get(0));
        assertTrue(messages.contains("Shared hub: -300.5, 64.0, -300.5. Portals with no lobby of their own lead to"
                + " the shared hub."), messages.toString());
        assertTrue(messages.contains("lobby (world) to dojo: open, to the shared hub"), messages.toString());
        assertTrue(messages.contains("watch (world) to dojo: spectator portal, requires a live match with space"),
                messages.toString());

        messages.clear();
        run(console, "portal lobby watch shared");
        run(console, "portal lobby lobby everywhere");
        assertEquals(List.of("A spectator portal leads to a live match and has no lobby.",
                "Usage: /narena portal lobby <name> <default|dedicated|shared>"), messages);
        assertEquals(List.of("default", "dedicated", "shared"), complete(player(true), "portal", "lobby", "lobby",
                ""));
    }

    @Test
    void aPortalCanBeCreatedWithItsOwnLobby() {
        CommandSender sender = player(true);
        run(sender, "portal setcorner 1");
        run(sender, "portal setcorner 2 ~ ~2 ~");
        messages.clear();
        run(sender, "portal create door dojo shared");
        assertEquals(LobbyMode.SHARED, portals.get("door").orElseThrow().lobby());
        assertEquals("Portal door created in world (10, 64, 10 to 10, 66, 10), leading to the shared hub.",
                messages.get(0));
        assertEquals(List.of("spectator", "dedicated", "shared"), complete(sender, "portal", "create", "x", "dojo",
                ""));
    }

    @Test
    void tabCompletesSubcommandsCornersAndNames() throws Exception {
        portals.create("lobby", "world", new ArenaBounds(10, 64, 10, 12, 67, 10), "dojo");
        CommandSender sender = player(true);
        assertEquals(List.of("portal"), complete(sender, "p"));
        assertEquals(List.of("setcorner", "create", "delete", "list", "info", "lobby", "sethub", "removehub"),
                complete(sender, "portal", ""));
        assertEquals(List.of("1", "2"), complete(sender, "portal", "setcorner", ""));
        assertEquals(List.of("~", "64"), complete(sender, "portal", "setcorner", "1", "10", ""));
        assertEquals(List.of("dojo"), complete(sender, "portal", "create", "lobby2", ""));
        assertEquals(List.of("lobby"), complete(sender, "portal", "info", "l"));
        assertEquals(List.of(), complete(sender, "portal", "list", ""));
    }
}
