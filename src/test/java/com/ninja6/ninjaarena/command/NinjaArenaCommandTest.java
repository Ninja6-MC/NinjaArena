package com.ninja6.ninjaarena.command;

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
import com.ninja6.ninjaarena.portal.PortalRegistry;
import com.ninja6.ninjaarena.portal.PortalStore;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NinjaArenaCommandTest {

    @TempDir
    Path dir;

    private final List<String> messages = new ArrayList<>();
    private ArenaRegistry registry;
    private NinjaArenaCommand command;
    private final List<String> left = new ArrayList<>();
    private boolean waiting;

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
        registry = new ArenaRegistry(new ArenaStore(dir.resolve("arenas.yml")),
                new ArenaGrid(256, 2048, -64, 320), enabled -> { }, noTickets, new MemoryCopies());
        registry.load();
        PortalRegistry portals = new PortalRegistry(new PortalStore(dir.resolve("portals.yml")));
        portals.load();
        command = new NinjaArenaCommand(new ArenaCommand(registry, () -> null),
                new PortalCommand(portals, registry, world -> true), player -> {
                    left.add(player.getName());
                    return waiting;
                });
    }

    /** A console sender that holds the admin node or not, and records what it is told. */
    private CommandSender console(boolean admin) {
        return (CommandSender) Proxy.newProxyInstance(
                ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[] {ConsoleCommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> admin && NinjaArenaCommand.ADMIN.equals(args[0]);
                    case "sendMessage" -> {
                        if (args[0] instanceof Component component) {
                            messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                        } else {
                            messages.add(String.valueOf(args[0]));
                        }
                        yield null;
                    }
                    case "getName" -> "CONSOLE";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private void run(CommandSender sender, String line) {
        command.onCommand(sender, null, "narena", line.split(" "));
    }

    @Test
    void withoutAdminNothingRunsOrCompletes() {
        CommandSender sender = console(false);
        run(sender, "arena create dojo");
        assertEquals(List.of("You do not have permission to use this command."), messages);
        assertTrue(registry.all().isEmpty());
        assertEquals(List.of(), command.onTabComplete(sender, null, "narena", new String[] {""}));
        assertEquals(List.of(), command.onTabComplete(sender, null, "narena", new String[] {"arena", ""}));
    }

    @Test
    void consoleCanBuildAnArenaEndToEnd() {
        CommandSender sender = console(true);
        run(sender, "arena create dojo 0 0");
        run(sender, "arena setbounds dojo 10 60 10 90 100 90");
        run(sender, "arena setwaitingroom dojo 50.5 64 50.5");
        run(sender, "arena setteamspawn dojo 1 20.5 64 20.5 -90 0");
        run(sender, "arena setteamspawn dojo 2 80.5 64 80.5");
        run(sender, "arena addenemyspawn dojo 50.5 64 20.5");
        messages.clear();
        run(sender, "arena edit dojo off");

        assertEquals(List.of("Arena dojo saved and ready for matches."), messages);
        Arena arena = registry.get("dojo").orElseThrow();
        assertEquals(new GridCell(0, 0), arena.cell());
        assertEquals(new ArenaBounds(10, 60, 10, 90, 100, 90), arena.bounds());
        assertEquals(new ArenaPoint(20.5, 64, 20.5, -90, 0), arena.teamSpawns().get(0));
        assertTrue(registry.isAvailable("dojo"));
    }

    @Test
    void editOffWithProblemsSaysItLeftEditModeAndHowToReturn() {
        CommandSender sender = console(true);
        run(sender, "arena create dojo 0 0");
        run(sender, "arena setbounds dojo 10 60 10 90 100 90");
        messages.clear();
        run(sender, "arena edit dojo off");

        assertEquals(List.of("Arena dojo saved and left edit mode, but it cannot host a match yet:",
                "The waiting room is not set.",
                "Needs 2 to 4 team spawns, has 0.",
                "Needs at least one enemy spawn point: enemies spawn there during rounds.",
                "Turn edit mode back on with /narena arena edit dojo on to fix them."), messages);
        assertFalse(registry.isEditing("dojo"));

        messages.clear();
        run(sender, "arena edit dojo on");
        assertTrue(registry.isEditing("dojo"));
    }

    @Test
    void theRootListsItsGroupsWhenNoneOrAnUnknownOneIsGiven() {
        CommandSender sender = console(true);
        command.onCommand(sender, null, "narena", new String[0]);
        List<String> help = List.of(
                "/narena arena <subcommand> - create, edit and set up arenas",
                "/narena portal <subcommand> - mark out portals that lead to arenas",
                "/narena leave - leave the waiting room or match you are in");
        assertEquals("NinjaArena commands:", messages.get(0));
        assertEquals(help, messages.subList(1, messages.size()));

        messages.clear();
        run(sender, "create dojo");
        assertEquals("Unknown command create. Subcommands sit under a group:", messages.get(0));
        assertEquals(help, messages.subList(1, messages.size()));
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void boundsOutsideTheCellAreRefused() {
        CommandSender sender = console(true);
        run(sender, "arena create dojo 0 0");
        messages.clear();
        run(sender, "arena setbounds dojo 10 60 10 300 100 90");
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("leave the arena's cell"), messages.get(0));
        assertEquals(null, registry.get("dojo").orElseThrow().bounds());
    }

    @Test
    void consoleMustGiveCoordinates() {
        CommandSender sender = console(true);
        run(sender, "arena create dojo");
        messages.clear();
        run(sender, "arena addenemyspawn dojo");
        run(sender, "arena addenemyspawn dojo ~ 64 10");
        assertTrue(messages.get(0).startsWith("Give coordinates"), messages.get(0));
        assertTrue(messages.get(1).startsWith("~ needs a player"), messages.get(1));
        assertTrue(registry.get("dojo").orElseThrow().enemySpawns().isEmpty());
    }

    @Test
    void tabCompletesSubcommandsNamesAndModes() {
        CommandSender sender = console(true);
        run(sender, "arena create dojo");
        assertEquals(List.of("arena"), command.onTabComplete(sender, null, "narena", new String[] {"a"}));
        assertEquals(List.of("arena", "portal"), command.onTabComplete(sender, null, "narena", new String[] {""}));
        assertEquals(List.of("setbounds", "setwaitingroom", "setteamspawn"),
                command.onTabComplete(sender, null, "narena", new String[] {"arena", "set"}));
        assertEquals(List.of("dojo"), command.onTabComplete(sender, null, "narena", new String[] {"arena", "info", "d"}));
        assertEquals(List.of("on", "off"),
                command.onTabComplete(sender, null, "narena", new String[] {"arena", "edit", "dojo", ""}));
        assertEquals(List.of("1"),
                command.onTabComplete(sender, null, "narena", new String[] {"arena", "setteamspawn", "dojo", ""}));
    }

    @Test
    void unknownArenaIsReported() {
        CommandSender sender = console(true);
        run(sender, "arena info nowhere");
        assertEquals(List.of("No arena called nowhere."), messages);
    }

    /** A player who holds only the play node. */
    private Player playerWithPlay() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> NinjaArenaCommand.PLAY.equals(args[0]);
                    case "sendMessage" -> {
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    case "getName" -> "Alice";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void playersCanLeaveAWaitingRoomButNothingElse() {
        Player player = playerWithPlay();
        waiting = true;
        run(player, "leave");
        assertEquals(List.of("Alice"), left);
        assertTrue(messages.isEmpty());

        waiting = false;
        run(player, "LEAVE");
        assertEquals(List.of("You are not in an arena."), messages);

        messages.clear();
        run(player, "arena list");
        assertEquals(List.of("You do not have permission to use this command."), messages);
        assertEquals(List.of("leave"), command.onTabComplete(player, null, "narena", new String[] {""}));
        assertEquals(List.of(), command.onTabComplete(player, null, "narena", new String[] {"arena", ""}));
    }

    @Test
    void leaveNeedsThePlayNode() {
        run(console(false), "leave");
        assertEquals(List.of("You do not have permission to use this command."), messages);
        assertTrue(left.isEmpty());
    }
}
