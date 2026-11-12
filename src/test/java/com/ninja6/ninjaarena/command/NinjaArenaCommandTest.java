package com.ninja6.ninjaarena.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NinjaArenaCommandTest {

    @TempDir
    Path dir;

    /** No match running, so no director. */
    private static final DirectorCommand NO_DIRECTOR = new DirectorCommand(List::of, id -> Optional.empty(),
            match -> Optional.empty());

    private final List<String> messages = new ArrayList<>();
    private final List<Component> components = new ArrayList<>();
    private ArenaRegistry registry;
    private NinjaArenaCommand command;
    private PortalCommand portalCommand;
    private final List<String> left = new ArrayList<>();
    private boolean waiting;

    @Test
    void packStatusIsAdminOnlyReadOnlyAndHasStrictSyntax() {
        int[] reads = {0};
        String status = com.ninja6.ninjaarena.resourcepack.PackStatus.describe(
                com.ninja6.ninjaarena.resourcepack.ResourcePackSettings.OFF,
                "vanilla (same-server Geyser absent, disabled or unavailable)",
                com.ninja6.ninjaarena.resourcepack.JavaAuthentication.NONE);
        command.setPackStatus(() -> { reads[0]++; return status; });
        Player admin = playerWithPermissions(NinjaArenaCommand.ADMIN);
        Player player = playerWithPermissions(NinjaArenaCommand.PLAY);
        run(player, "pack status");
        assertEquals(0, reads[0]);
        assertEquals(List.of(), command.onTabComplete(player, null, "narena", new String[] {"pack", ""}));
        assertEquals(List.of("status"), command.onTabComplete(admin, null, "narena", new String[] {"pack", "s"}));
        run(admin, "pack status extra");
        run(admin, "pack reload");
        assertEquals(0, reads[0]);
        run(admin, "pack status");
        assertEquals(1, reads[0]);
        assertEquals(status, messages.getLast());
        assertTrue(status.contains("Java: disabled; Java accounts: not authenticated: choices last one connection; Bedrock: vanilla"));
    }

    @Test
    void eventPermissionAloneOpensTheRouteAndProtectsItsCompletions() {
        var night = new com.ninja6.ninjaarena.event.EventSettings.Night("friday", java.time.DayOfWeek.FRIDAY,
                java.time.LocalTime.NOON, "dojo", java.time.Duration.ofHours(1),
                com.ninja6.ninjaarena.event.EventSettings.Bonus.NONE);
        var settings = new com.ninja6.ninjaarena.event.EventSettings(java.time.ZoneOffset.UTC, List.of(), null,
                List.of(night));
        var events = new com.ninja6.ninjaarena.event.ArenaEvents(settings,
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC), (window, minutes, webhook) -> { });
        command = new NinjaArenaCommand(new ArenaCommand(registry, () -> null), portalCommand, NO_DIRECTOR,
                player -> false, null, null, null, null, new EventCommand(events));
        Player manager = playerWithPermissions(EventCommand.PERMISSION);
        Player player = playerWithPermissions(NinjaArenaCommand.PLAY);
        assertEquals(List.of("help", "event"), command.onTabComplete(manager, null, "narena", new String[0]));
        assertEquals(List.of("friday"), command.onTabComplete(manager, null, "narena",
                new String[] {"event", "start", "fr"}));
        assertEquals(List.of(), command.onTabComplete(player, null, "narena", new String[] {"event", "start", ""}));
        run(player, "event start friday");
        assertTrue(events.active().isEmpty());
        run(manager, "event start friday");
        assertEquals("friday", events.active().orElseThrow().night().id());
        run(manager, "event cancel friday");
        assertTrue(events.active().isEmpty());
    }

    @Test
    void leaderboardPermissionAloneOpensTheRouteAndItsCompletions() {
        NinjaArenaCommand withLeaderboard = new NinjaArenaCommand(new ArenaCommand(registry, () -> null),
                portalCommand, NO_DIRECTOR, player -> false, null, null, null,
                new LeaderboardCommand(null, () -> null));
        Player viewer = playerWithPermissions("ninjaarena.leaderboard");
        Player denied = playerWithPermissions(NinjaArenaCommand.PLAY);

        assertEquals(List.of("help", "leaderboard"), withLeaderboard.onTabComplete(viewer, null, "narena", new String[0]));
        assertEquals(List.of("player_kills"), withLeaderboard.onTabComplete(viewer, null, "narena",
                new String[] {"leaderboard", "P"}));
        assertEquals(List.of("season"), withLeaderboard.onTabComplete(viewer, null, "narena",
                new String[] {"leaderboard", "wins", "s"}));
        assertEquals(List.of(), withLeaderboard.onTabComplete(denied, null, "narena",
                new String[] {"leaderboard", ""}));
        withLeaderboard.onCommand(viewer, null, "narena", new String[] {"leaderboard", "wins", "season"});
        withLeaderboard.onCommand(denied, null, "narena", new String[] {"leaderboard", "wins"});
        assertEquals(List.of("Arena stats are disabled.", "You do not have permission to use this command."), messages);
    }

    @Test
    void adminTeleportRetainsItsWorldOperationUntilTheScheduledCompletionEvenOnFailure() throws Exception {
        registry.create("dojo", new GridCell(1, 0));
        for (boolean exceptional : List.of(false, true)) {
            CompletableFuture<Boolean> teleport = new CompletableFuture<>();
            List<Runnable> completions = new ArrayList<>();
            int[] operations = {0};
            Player admin = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class<?>[] {Player.class}, (object, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> java.util.UUID.nameUUIDFromBytes(new byte[] {1});
                        case "teleportAsync" -> teleport;
                        case "sendMessage" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            ArenaCommand route = new ArenaCommand(registry, () -> null, () -> {
                operations[0]++;
                return () -> operations[0]--;
            }, completions::add);
            route.execute(admin, "narena", new String[] {"tp", "dojo"});
            assertEquals(1, operations[0]);
            if (exceptional) {
                teleport.completeExceptionally(new IllegalStateException("teleport failure"));
            } else {
                teleport.complete(false);
            }
            assertEquals(1, operations[0]);
            completions.getFirst().run();
            assertEquals(0, operations[0]);
        }
    }

    @Test
    void aSynchronousTeleportFailureEndsTheOwnTeleportMarkAndReleasesTheOperation() throws Exception {
        registry.create("dojo", new GridCell(1, 0));
        java.util.UUID adminId = java.util.UUID.nameUUIDFromBytes(new byte[] {3});
        int[] operations = {0};
        Player admin = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (object, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> adminId;
                    case "teleportAsync" -> {
                        assertTrue(com.ninja6.ninjaarena.restore.OwnTeleports.isActive(adminId));
                        throw new IllegalStateException("refused");
                    }
                    case "sendMessage" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var route = new ArenaCommand(registry, () -> null, () -> {
            operations[0]++;
            return () -> operations[0]--;
        }, completion -> { });

        assertThrows(IllegalStateException.class,
                () -> route.execute(admin, "narena", new String[] {"tp", "dojo"}));

        assertFalse(com.ninja6.ninjaarena.restore.OwnTeleports.isActive(adminId));
        assertEquals(0, operations[0]);
    }

    @Test
    void refusedOutsideAdminTargetReleasesItsOperationBeforeAnyAsyncTeleport() throws Exception {
        registry.create("dojo", new GridCell(1, 0));
        registry.update("dojo", arena -> arena.withWaitingRoom(new ArenaPoint(50000, 64, 50000, 0, 0)));
        int[] operations = {0};
        Player admin = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (object, method, args) -> {
                    if (method.getName().equals("sendMessage")) return null;
                    throw new AssertionError("Refused target requested player work: " + method.getName());
                });
        var route = new ArenaCommand(registry, () -> null, () -> {
            operations[0]++;
            return () -> operations[0]--;
        }, completion -> { throw new AssertionError("Refused target scheduled a completion"); });
        route.execute(admin, "narena", new String[] {"tp", "dojo"});
        assertEquals(0, operations[0]);
    }

    @Test
    void introUsesPlayPermissionStrictSyntaxAndPlayerOnlyDispatch() {
        List<Player> shown = new ArrayList<>();
        command.setIntro(shown::add);
        Player player = playerWithPermissions(NinjaArenaCommand.PLAY);
        Player host = playerWithPermissions(NinjaArenaCommand.HOST);
        run(host, "intro");
        run(player, "intro extra");
        run(console(true), "intro");
        assertTrue(shown.isEmpty());
        run(player, "intro");
        assertEquals(List.of(player), shown);
        assertEquals(List.of("intro"), command.onTabComplete(player, null, "narena", new String[] {"int"}));
        assertEquals(List.of(), command.onTabComplete(host, null, "narena", new String[] {"int"}));
        assertEquals(List.of(), command.onTabComplete(player, null, "narena", new String[] {"intro", ""}));
        messages.clear();
        run(player, "help");
        assertTrue(messages.contains("/narena intro - show the lobby introduction again"));
    }

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
        portalCommand = new PortalCommand(portals, registry, world -> true);
        command = new NinjaArenaCommand(new ArenaCommand(registry, () -> null),
                portalCommand, NO_DIRECTOR, player -> {
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
                "/narena help - show the commands you can use",
                "/narena arena <subcommand> - create, edit and set up arenas",
                "/narena portal <subcommand> - mark out portals that lead to arenas",
                "/narena director [arena] - show the enemy director of a running match",
                "/narena pack status - show Java and Bedrock resource pack state",
                "/narena admin <reload|lobby <edit|done|reset>|textures reset <java|bedrock> <id>> - reload the"
                        + " Arena configuration, edit or reset the lobby, or reset a player's texture quality to"
                        + " Standard");
        assertEquals("The Arena commands:", messages.get(0));
        assertEquals(help, messages.subList(1, messages.size()));

        messages.clear();
        run(sender, "create dojo");
        assertEquals("Unknown command create. Available commands:", messages.get(0));
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
        assertEquals(List.of("arena", "admin"), command.onTabComplete(sender, null, "narena", new String[] {"a"}));
        assertEquals(List.of("help", "arena", "portal", "director", "pack", "admin"),
                command.onTabComplete(sender, null, "narena", new String[] {""}));
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
        return playerWithPermissions(NinjaArenaCommand.PLAY);
    }

    private Player playerWithPermissions(String... permissions) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> Arrays.asList(permissions).contains(args[0]);
                    case "sendMessage" -> {
                        components.add((Component) args[0]);
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    case "getName" -> "Alice";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Records lobby subcommands as they reach the lobby. */
    private final List<String> lobbyCalls = new ArrayList<>();

    private final LobbyCommands lobby = new LobbyCommands() {
        @Override
        public void edit(Player player, String label) {
            lobbyCalls.add("edit " + player.getName() + " " + label);
        }

        @Override
        public void done(Player player) {
            lobbyCalls.add("done " + player.getName());
        }

        @Override
        public void reset(CommandSender sender) {
            lobbyCalls.add("reset " + sender.getName());
        }
    };

    @Test
    void theLobbyNodeRunsEditDoneAndResetAndCompletesThem() {
        command.setLobby(lobby);
        Player editor = playerWithPermissions(NinjaArenaCommand.LOBBY);
        run(editor, "admin lobby edit");
        run(editor, "admin LOBBY done");
        run(editor, "admin lobby reset");
        assertEquals(List.of("edit Alice narena", "done Alice", "reset Alice"), lobbyCalls);
        assertEquals(List.of(), messages);

        assertEquals(List.of("admin"), command.onTabComplete(editor, null, "narena", new String[] {"ad"}));
        assertEquals(List.of("lobby"), command.onTabComplete(editor, null, "narena", new String[] {"admin", ""}));
        assertEquals(List.of("edit", "done", "reset"),
                command.onTabComplete(editor, null, "narena", new String[] {"admin", "lobby", ""}));

        // The lobby node alone does not reach the rest of admin.
        run(editor, "admin reload");
        assertEquals(List.of("You do not have permission to use this command."), messages);
    }

    @Test
    void theLobbySubcommandsNeedTheLobbyNodeAPlayerAndAKnownAction() {
        command.setLobby(lobby);
        Player admin = playerWithPermissions(NinjaArenaCommand.ADMIN);
        run(admin, "admin lobby edit");
        assertEquals(List.of("You do not have permission to use this command."), messages);
        assertEquals(List.of("reload", "textures"),
                command.onTabComplete(admin, null, "narena", new String[] {"admin", ""}));

        messages.clear();
        Player editor = playerWithPermissions(NinjaArenaCommand.ADMIN, NinjaArenaCommand.LOBBY);
        run(editor, "admin lobby");
        run(editor, "admin lobby build");
        run(editor, "admin lobby edit now");
        assertEquals(3, messages.size());
        assertTrue(messages.stream().allMatch(message -> message.contains("admin lobby <edit|done|reset>")),
                messages.toString());
        assertEquals(List.of("reload", "lobby", "textures"),
                command.onTabComplete(editor, null, "narena", new String[] {"admin", ""}));

        CommandSender console = (CommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[] {ConsoleCommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "sendMessage" -> {
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    case "getName" -> "CONSOLE";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        messages.clear();
        run(console, "admin lobby edit");
        run(console, "admin lobby reset");
        assertEquals(List.of("Only players can use this Arena command."), messages);
        assertEquals(List.of("reset CONSOLE"), lobbyCalls);
    }

    @Test
    void withoutTheLobbyTheSubcommandsSayItIsUnavailable() {
        Player editor = playerWithPermissions(NinjaArenaCommand.LOBBY);
        run(editor, "admin lobby reset");
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().contains("Lobby editing"), messages.getFirst());
    }

    @Test
    void playersCanLeaveAWaitingRoomButCannotAdministerArenas() {
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
        assertEquals(List.of("help", "leave", "textures", "intro", "accept"),
                command.onTabComplete(player, null, "narena", new String[] {""}));
        assertEquals(List.of(), command.onTabComplete(player, null, "narena", new String[] {"arena", ""}));
    }

    @Test
    void teleportToAnEmptyCellTurnsFlightOnOnceTheTeleportIsDone() {
        run(console(true), "arena create dojo 1 0");
        List<String> calls = new ArrayList<>();
        CompletableFuture<Boolean> teleport = new CompletableFuture<>();
        boolean[] allowFlight = {false};
        Player admin = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> NinjaArenaCommand.ADMIN.equals(args[0]);
                    case "sendMessage" -> {
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    case "getName" -> "Admin";
                    case "getGameMode" -> GameMode.SURVIVAL;
                    case "getAllowFlight" -> allowFlight[0];
                    case "setAllowFlight" -> {
                        allowFlight[0] = (boolean) args[0];
                        calls.add("setAllowFlight(" + args[0] + ")");
                        yield null;
                    }
                    case "setFlying" -> {
                        calls.add("setFlying(" + args[0] + ")");
                        yield null;
                    }
                    case "getUniqueId" -> java.util.UUID.nameUUIDFromBytes(new byte[] {2});
                    case "teleportAsync" -> {
                        Location target = (Location) args[0];
                        calls.add("teleport(" + target.getX() + ", " + target.getY() + ", " + target.getZ() + ")");
                        yield teleport;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        messages.clear();

        run(admin, "arena tp dojo");
        assertEquals(List.of("teleport(384.0, 64.0, 128.0)"), calls);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("cell 1, 0"), messages.get(0));
        assertTrue(messages.get(0).contains("Bounds: not set"), messages.get(0));

        // A world change during the teleport may reset flight, so it is set only afterwards.
        teleport.complete(true);
        assertEquals(List.of("teleport(384.0, 64.0, 128.0)", "setAllowFlight(true)", "setFlying(true)"), calls);
        assertTrue(messages.stream().anyMatch(message -> message.contains("empty until you build")), messages.toString());
    }

    @Test
    void directorStatusNeedsTheAdminNode() {
        Player player = playerWithPlay();
        run(player, "director dojo");
        assertEquals(List.of("You do not have permission to use this command."), messages);
        assertEquals(List.of(), command.onTabComplete(player, null, "narena", new String[] {"d"}));
        assertEquals(List.of(), command.onTabComplete(player, null, "narena", new String[] {"director", ""}));

        messages.clear();
        CommandSender admin = console(true);
        assertEquals(List.of("director"), command.onTabComplete(admin, null, "narena", new String[] {"d"}));
        run(admin, "director dojo");
        assertEquals(List.of("No match is running in arena dojo."), messages);
        messages.clear();
        command.onCommand(admin, null, "narena", new String[] {"director"});
        assertEquals(List.of("Usage: /narena director <arena>, or run it while in a match."), messages);
    }

    @Test
    void leaveNeedsThePlayNode() {
        run(console(false), "leave");
        assertEquals(List.of("You do not have permission to use this command."), messages);
        assertTrue(left.isEmpty());
    }

    @Test
    void playAndHostHelpOnlyShowTheirPermittedRoutes() {
        Player player = playerWithPlay();
        command.onCommand(player, null, "ninjaarena", new String[0]);
        assertEquals(List.of("The Arena commands:",
                "/ninjaarena help - show the commands you can use",
                "/ninjaarena leave - leave the arena and return home",
                "/ninjaarena textures - choose Standard or Detailed ninja tool textures",
                "/ninjaarena intro - show the lobby introduction again",
                "/ninjaarena accept <host> - accept an invitation to the Arena"), messages);

        messages.clear();
        Player host = playerWithPermissions(NinjaArenaCommand.PLAY, NinjaArenaCommand.HOST);
        run(host, "HELP");
        assertEquals(8, messages.size());
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("/narena host <arena>")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("/narena invite <player>")));
        assertFalse(messages.stream().anyMatch(message -> message.contains("admin") || message.contains("portal")));
        assertEquals(List.of("help", "leave", "textures", "intro", "accept", "host", "invite"),
                command.onTabComplete(host, null, "narena", new String[] {""}));
        assertEquals(List.of("help", "host"), command.onTabComplete(host, null, "narena", new String[] {"H"}));
        assertEquals(List.of("accept"), command.onTabComplete(player, null, "narena", new String[] {"AC"}));
    }

    @Test
    void unfinishedCommandsReportTheirStatusWithoutChangingAnything() {
        Player host = playerWithPermissions(NinjaArenaCommand.PLAY, NinjaArenaCommand.HOST, NinjaArenaCommand.ADMIN);
        for (String line : List.of("host dojo", "invite Bob", "accept Alice", "ADMIN RELOAD")) {
            messages.clear();
            run(host, line);
            assertEquals(1, messages.size(), line);
            assertTrue(messages.getFirst().contains("not available yet in the Arena"), messages.toString());
        }
        assertTrue(left.isEmpty());
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void deniedCommandsCannotLeaveOrChangeRegistries() {
        Player player = playerWithPlay();
        for (String line : List.of("host dojo", "invite Bob", "admin reload", "arena create dojo", "portal list",
                "director dojo")) {
            messages.clear();
            run(player, line);
            assertEquals(List.of("You do not have permission to use this command."), messages, line);
        }
        Player hostOnly = playerWithPermissions(NinjaArenaCommand.HOST);
        for (String line : List.of("leave", "accept Alice", "admin reload")) {
            messages.clear();
            run(hostOnly, line);
            assertEquals(List.of("You do not have permission to use this command."), messages, line);
        }
        assertTrue(left.isEmpty());
        assertTrue(registry.all().isEmpty());
        assertEquals(List.of(), command.onTabComplete(player, null, "narena", new String[] {"admin", ""}));
        assertEquals(List.of(), command.onTabComplete(hostOnly, null, "narena", new String[] {"arena", ""}));
    }

    @Test
    void malformedPlayerAndPlaceholderCommandsDoNotRun() {
        Player all = playerWithPermissions(NinjaArenaCommand.PLAY, NinjaArenaCommand.HOST, NinjaArenaCommand.ADMIN);
        for (String line : List.of("leave extra", "accept", "accept Alice extra", "host", "host dojo extra",
                "invite", "invite Bob extra", "admin", "admin reload extra", "admin unknown", "help extra")) {
            messages.clear();
            run(all, line);
            assertEquals(1, messages.size(), line);
            assertTrue(messages.getFirst().startsWith("Usage: /narena "), messages.toString());
        }
        assertTrue(left.isEmpty());
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void consoleCanInspectAndReloadPlaceholderButCannotUsePlayerRoutes() {
        CommandSender all = (CommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[] {ConsoleCommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "sendMessage" -> {
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        for (String line : List.of("host dojo", "invite Bob", "accept Alice", "leave")) {
            messages.clear();
            run(all, line);
            assertTrue(messages.getFirst().startsWith("Only players can "), messages.toString());
        }
        messages.clear();
        run(all, "admin reload");
        assertEquals(List.of("Configuration reload is not available yet in the Arena."), messages);
        assertEquals(List.of("reload"), command.onTabComplete(all, null, "narena", new String[] {"ADMIN", "RE"}));
        assertEquals(List.of(), command.onTabComplete(all, null, "narena", new String[] {"admin", "reload", ""}));
        assertTrue(left.isEmpty());
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void helpAndRepliesHaveNoClickOrHoverActions() {
        Player all = playerWithPermissions(NinjaArenaCommand.PLAY, NinjaArenaCommand.HOST, NinjaArenaCommand.ADMIN);
        for (String line : List.of("help", "host dojo", "invite Bob", "accept Alice", "admin reload", "leave extra",
                "unknown")) {
            run(all, line);
        }
        assertFalse(components.isEmpty());
        components.forEach(NinjaArenaCommandTest::assertPlain);
    }

    private static void assertPlain(Component component) {
        assertEquals(null, component.clickEvent());
        assertEquals(null, component.hoverEvent());
        component.children().forEach(NinjaArenaCommandTest::assertPlain);
    }

    @Test
    void rootWithoutAnyTierDeniesHelpAndUnknownCommandsToo() {
        Player none = playerWithPermissions();
        for (String[] args : List.of(new String[0], new String[] {"help"}, new String[] {"unknown"})) {
            messages.clear();
            command.onCommand(none, null, "narena", args);
            assertEquals(List.of("You do not have permission to use this command."), messages);
        }
        assertEquals(List.of(), command.onTabComplete(none, null, "narena", new String[0]));
        assertTrue(left.isEmpty());
    }

    @Test
    void hostingRoutesDispatchOnlyAfterPermissionPlayerAndArityChecks() {
        List<String> calls = new ArrayList<>();
        HostingCommands hosting = new HostingCommands() {
            public void host(Player player, String arena) { calls.add("host " + arena); }
            public void invite(Player player, String invitee) { calls.add("invite " + invitee); }
            public void accept(Player player, String host) { calls.add("accept " + host); }
        };
        command = new NinjaArenaCommand(new ArenaCommand(registry, () -> null), portalCommand, NO_DIRECTOR,
                player -> false, hosting);
        Player host = playerWithPermissions(NinjaArenaCommand.PLAY, NinjaArenaCommand.HOST);
        run(host, "HOST dojo");
        run(host, "invite Bob");
        run(playerWithPlay(), "accept Alice");
        assertEquals(List.of("host dojo", "invite Bob", "accept Alice"), calls);
        run(playerWithPlay(), "host dojo");
        run(host, "invite Bob extra");
        run(host, "accept");
        run(console(true), "host dojo");
        assertEquals(3, calls.size());
    }

    @Test
    void reloadInvokesBackendOnlyForAdminsWithExactArguments() {
        List<String> reloaded = new ArrayList<>();
        command = new NinjaArenaCommand(new ArenaCommand(registry, () -> null),
                new PortalCommand(new com.ninja6.ninjaarena.portal.PortalRegistry(
                        new com.ninja6.ninjaarena.portal.PortalStore(dir.resolve("reload-portals.yml"))), registry,
                        world -> true), NO_DIRECTOR, player -> false, sender -> reloaded.add("reload"));
        run(playerWithPlay(), "admin reload");
        run(console(true), "admin reload extra");
        assertTrue(reloaded.isEmpty());
        run(console(true), "ADMIN RELOAD");
        assertEquals(List.of("reload"), reloaded);
    }

    @Test
    void hostingAndReloadShareTheCommandTreeWithoutCrossDispatching() {
        List<String> calls = new ArrayList<>();
        HostingCommands hosting = new HostingCommands() {
            public void host(Player player, String arena) { calls.add("host " + arena); }
            public void invite(Player player, String invitee) { calls.add("invite " + invitee); }
            public void accept(Player player, String host) { calls.add("accept " + host); }
        };
        command = new NinjaArenaCommand(new ArenaCommand(registry, () -> null), portalCommand, NO_DIRECTOR,
                player -> false, hosting, sender -> calls.add("reload"));
        Player host = playerWithPermissions(NinjaArenaCommand.PLAY, NinjaArenaCommand.HOST);
        run(host, "host dojo");
        run(host, "invite Bob");
        run(playerWithPlay(), "accept Alice");
        run(host, "admin reload");
        run(console(true), "admin reload extra");
        assertEquals(List.of("host dojo", "invite Bob", "accept Alice"), calls);
        run(console(true), "ADMIN RELOAD");
        assertEquals(List.of("host dojo", "invite Bob", "accept Alice", "reload"), calls);
    }


    // Texture quality routes.

    private final List<String> textureCalls = new ArrayList<>();

    private void withTextures() {
        command.setTextures(new TextureCommands() {
            @Override public void open(Player player) { textureCalls.add("open"); }
            @Override public void reset(CommandSender sender, com.ninja6.ninjaarena.texture.PlayerKey key) {
                textureCalls.add("reset " + key);
            }
        });
    }

    private CommandSender everything() {
        return (CommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[] {ConsoleCommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "sendMessage" -> {
                        messages.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void texturesOpensTheMenuForPlayersWithPlayUnderBothAliases() {
        withTextures();
        Player player = playerWithPlay();
        command.onCommand(player, null, "ninjaarena", new String[] {"textures"});
        command.onCommand(player, null, "narena", new String[] {"TEXTURES"});
        assertEquals(List.of("open", "open"), textureCalls);
        assertTrue(messages.isEmpty());
    }

    @Test
    void texturesIsPlayerOnlyAndStrictAboutSyntax() {
        withTextures();
        run(everything(), "textures");
        assertEquals(List.of("Only players can use this Arena command."), messages);
        messages.clear();
        run(playerWithPlay(), "textures extra");
        assertEquals(List.of("Usage: /narena textures"), messages);
        assertTrue(textureCalls.isEmpty());
    }

    @Test
    void texturesNeedsPlayAndStaysOutOfHelpAndCompletionWithoutIt() {
        withTextures();
        Player hostOnly = playerWithPermissions(NinjaArenaCommand.HOST);
        run(hostOnly, "textures");
        assertEquals(List.of("You do not have permission to use this command."), messages);
        assertTrue(textureCalls.isEmpty());
        messages.clear();
        run(hostOnly, "help");
        assertFalse(messages.stream().anyMatch(line -> line.contains("textures")));
        assertEquals(List.of(), command.onTabComplete(hostOnly, null, "narena", new String[] {"tex"}));

        Player player = playerWithPlay();
        assertEquals(List.of("textures"), command.onTabComplete(player, null, "narena", new String[] {"TEX"}));
        assertEquals(List.of(), command.onTabComplete(player, null, "narena", new String[] {"textures", ""}));
        messages.clear();
        run(player, "help");
        assertTrue(messages.stream().anyMatch(line -> line.equals(
                "/narena textures - choose Standard or Detailed ninja tool textures")));
    }

    @Test
    void texturesWithoutAServiceSaysSoInsteadOfFailing() {
        run(playerWithPlay(), "textures");
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().contains("not available"));
    }

    @Test
    void adminTextureResetWorksFromConsoleForBothEditions() {
        withTextures();
        CommandSender console = console(true);
        run(console, "admin textures reset java 123E4567-E89B-12D3-A456-426614174000");
        run(console, "admin TEXTURES RESET Bedrock 2535412345678901");
        assertEquals(List.of("reset java:123e4567-e89b-12d3-a456-426614174000",
                "reset bedrock:2535412345678901"), textureCalls);
        assertTrue(messages.isEmpty());
    }

    @Test
    void adminTextureResetValidatesIdentitiesAndSyntax() {
        withTextures();
        CommandSender console = console(true);
        for (String line : List.of("admin textures", "admin textures reset", "admin textures reset java",
                "admin textures reset other 1", "admin textures reset java a b", "admin textures clear java 1")) {
            messages.clear();
            run(console, line);
            assertEquals(1, messages.size(), line);
            assertTrue(messages.getFirst().startsWith("Usage: /narena admin textures reset"), messages.toString());
        }
        for (String line : List.of("admin textures reset java Steve", "admin textures reset java 12345",
                "admin textures reset bedrock Steve", "admin textures reset bedrock 123456789012345678901")) {
            messages.clear();
            run(console, line);
            assertEquals(1, messages.size(), line);
            assertTrue(messages.getFirst().contains("Usage: /narena admin textures reset"), messages.toString());
        }
        assertTrue(textureCalls.isEmpty());
    }

    @Test
    void adminTextureResetNeedsAdminAndIsNotCompletedForOthers() {
        withTextures();
        Player player = playerWithPlay();
        run(player, "admin textures reset bedrock 1");
        assertEquals(List.of("You do not have permission to use this command."), messages);
        Player host = playerWithPermissions(NinjaArenaCommand.PLAY, NinjaArenaCommand.HOST);
        run(host, "admin textures reset java 123e4567-e89b-12d3-a456-426614174000");
        assertTrue(textureCalls.isEmpty());
        assertEquals(List.of(), command.onTabComplete(host, null, "narena", new String[] {"admin", ""}));

        CommandSender admin = console(true);
        assertEquals(List.of("reload", "textures"), command.onTabComplete(admin, null, "narena", new String[] {"admin", ""}));
        assertEquals(List.of("textures"), command.onTabComplete(admin, null, "narena", new String[] {"admin", "t"}));
        assertEquals(List.of("reset"), command.onTabComplete(admin, null, "narena", new String[] {"admin", "textures", ""}));
        assertEquals(List.of("java", "bedrock"),
                command.onTabComplete(admin, null, "narena", new String[] {"admin", "textures", "reset", ""}));
        assertEquals(List.of("bedrock"),
                command.onTabComplete(admin, null, "narena", new String[] {"admin", "textures", "reset", "B"}));
        assertEquals(List.of(),
                command.onTabComplete(admin, null, "narena", new String[] {"admin", "textures", "reset", "java", ""}));
    }

    @Test
    void packStatusStaysAdminOnlyBesideTheNewRoutes() {
        withTextures();
        run(playerWithPlay(), "pack status");
        assertEquals(List.of("You do not have permission to use this command."), messages);
    }
}
