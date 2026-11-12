package com.ninja6.ninjaarena.command;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.message.Keyed;
import com.ninja6.ninjaarena.portal.PortalEntry;
import com.ninja6.ninjaarena.texture.PlayerKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** The {@code /ninjaarena} root (alias {@code /narena}), with play, host and admin routes. */
public final class NinjaArenaCommand implements TabExecutor {

    public static final String ADMIN = "ninjaarena.admin";
    public static final String HOST = "ninjaarena.host";
    public static final String PLAY = "ninjaarena.play";
    /** Lobby edit mode and reset; a child of {@link #ADMIN}. */
    public static final String LOBBY = "ninjaarena.admin.lobby";
    /** Leaves the waiting room or match the player is in and sends them home. */
    public static final String LEAVE = "leave";
    public static final String TEXTURES = "textures";
    public static final String ADMIN_ROUTE = "admin";
    private static final String LOBBY_USAGE = "admin lobby <edit|done|reset>";

    /** A route's help text is the message {@code help.<name>}. */
    private record Route(String name, String permission, String usage) {
    }

    private static final List<Route> ROUTES = List.of(
            new Route(LEAVE, PLAY, LEAVE),
            new Route("spectate", PortalEntry.SPECTATE, "spectate <arena>"),
            new Route(LeaderboardCommand.NAME, LeaderboardCommand.PERMISSION, LeaderboardCommand.USAGE),
            new Route(TEXTURES, PLAY, TEXTURES),
            new Route("accept", PLAY, "accept <host>"),
            new Route("host", HOST, "host <arena>"),
            new Route("invite", HOST, "invite <player>"),
            new Route(ArenaCommand.NAME, ADMIN, ArenaCommand.NAME + " <subcommand>"),
            new Route(PortalCommand.NAME, ADMIN, PortalCommand.NAME + " <subcommand>"),
            new Route(DirectorCommand.NAME, ADMIN, DirectorCommand.NAME + " [arena]"),
            new Route(EventCommand.NAME, EventCommand.PERMISSION, EventCommand.USAGE),
            new Route("pack", ADMIN, "pack status"),
            new Route(ADMIN_ROUTE, ADMIN, "admin <reload|lobby <edit|done|reset>|textures reset <java|bedrock> <id>>"));

    private final ArenaCommand arena;
    private final PortalCommand portal;
    private final DirectorCommand director;
    private final Predicate<Player> leave;
    private final HostingCommands hosting;
    private final Consumer<CommandSender> reload;
    private final BiConsumer<Player, String> spectate;
    private final LeaderboardCommand leaderboard;
    private final EventCommand event;
    private java.util.function.Supplier<String> packStatus =
            () -> com.ninja6.ninjaarena.message.Messages.text("pack.status-unavailable");

    private TextureCommands textures;
    private LobbyCommands lobby;

    public void setPackStatus(java.util.function.Supplier<String> status) {
        packStatus = Objects.requireNonNull(status);
    }

    /** Without it the lobby subcommands say the feature is unavailable. */
    public void setLobby(LobbyCommands lobby) {
        this.lobby = lobby;
    }

    /** Without it the textures route says the feature is unavailable. */
    public void setTextures(TextureCommands textures) {
        this.textures = textures;
    }

    /**
     * @param leave takes the player out of their waiting room or match and sends them home;
     *              false if they were in neither
     */
    public NinjaArenaCommand(ArenaCommand arena, PortalCommand portal, DirectorCommand director,
            Predicate<Player> leave) {
        this(arena, portal, director, leave, null, null);
    }

    public NinjaArenaCommand(ArenaCommand arena, PortalCommand portal, DirectorCommand director,
            Predicate<Player> leave, HostingCommands hosting) {
        this(arena, portal, director, leave, hosting, null);
    }

    public NinjaArenaCommand(ArenaCommand arena, PortalCommand portal, DirectorCommand director,
            Predicate<Player> leave, Consumer<CommandSender> reload) {
        this(arena, portal, director, leave, null, reload);
    }

    public NinjaArenaCommand(ArenaCommand arena, PortalCommand portal, DirectorCommand director,
            Predicate<Player> leave, HostingCommands hosting, Consumer<CommandSender> reload) {
        this(arena, portal, director, leave, hosting, reload, null);
    }

    public NinjaArenaCommand(ArenaCommand arena, PortalCommand portal, DirectorCommand director,
            Predicate<Player> leave, HostingCommands hosting, Consumer<CommandSender> reload,
            BiConsumer<Player, String> spectate) {
        this(arena, portal, director, leave, hosting, reload, spectate, null);
    }

    public NinjaArenaCommand(ArenaCommand arena, PortalCommand portal, DirectorCommand director,
            Predicate<Player> leave, HostingCommands hosting, Consumer<CommandSender> reload,
            BiConsumer<Player, String> spectate, LeaderboardCommand leaderboard) {
        this(arena, portal, director, leave, hosting, reload, spectate, leaderboard, null);
    }

    public NinjaArenaCommand(ArenaCommand arena, PortalCommand portal, DirectorCommand director,
            Predicate<Player> leave, HostingCommands hosting, Consumer<CommandSender> reload,
            BiConsumer<Player, String> spectate, LeaderboardCommand leaderboard, EventCommand event) {
        this.arena = Objects.requireNonNull(arena, "arena");
        this.portal = Objects.requireNonNull(portal, "portal");
        this.director = Objects.requireNonNull(director, "director");
        this.leave = Objects.requireNonNull(leave, "leave");
        this.hosting = hosting;
        this.reload = reload;
        this.spectate = spectate;
        this.leaderboard = leaderboard;
        this.event = event;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!canUseRoot(sender)) {
            denied(sender);
            return true;
        }
        if (args.length == 0) {
            help(sender, label, null);
            return true;
        }
        String group = args[0].toLowerCase(Locale.ROOT);
        if (group.equals("help")) {
            if (args.length == 1) {
                help(sender, label, null);
            } else {
                usage(sender, label, "help");
            }
            return true;
        }
        Route route = ROUTES.stream().filter(candidate -> candidate.name().equals(group)).findFirst().orElse(null);
        if (route == null) {
            help(sender, label, args[0]);
        } else if (!canUse(sender, route)) {
            denied(sender);
        } else {
            switch (group) {
                case LEAVE -> {
                    if (args.length == 1) {
                        leave(sender);
                    } else {
                        usage(sender, label, route.usage());
                    }
                }
                case LeaderboardCommand.NAME -> {
                    if (leaderboard == null) {
                        unavailable(sender, group);
                    } else {
                        leaderboard.execute(sender, label, tail(args));
                    }
                }
                case EventCommand.NAME -> {
                    if (event == null) {
                        unavailable(sender, group);
                    } else {
                        event.execute(sender, label, tail(args));
                    }
                }
                case "spectate" -> {
                    if (args.length != 2) {
                        usage(sender, label, route.usage());
                    } else if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg("command.players-only"));
                    } else if (spectate == null) {
                        unavailable(sender, group);
                    } else {
                        spectate.accept(player, args[1]);
                    }
                }
                case "accept", "host", "invite" -> {
                    if (args.length != 2) {
                        usage(sender, label, route.usage());
                    } else if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg("command.players-only"));
                    } else if (hosting == null) {
                        unavailable(sender, group);
                    } else {
                        switch (group) {
                            case "host" -> hosting.host(player, args[1]);
                            case "invite" -> hosting.invite(player, args[1]);
                            case "accept" -> hosting.accept(player, args[1]);
                            default -> throw new IllegalStateException(group);
                        }
                    }
                }
                case "pack" -> {
                    if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
                        sender.sendMessage(msg("pack.status", "status", packStatus.get()));
                    } else {
                        usage(sender, label, route.usage());
                    }
                }
                case TEXTURES -> {
                    if (args.length != 1) {
                        usage(sender, label, route.usage());
                    } else if (!(sender instanceof Player player)) {
                        sender.sendMessage(msg("command.players-only"));
                    } else if (textures == null) {
                        unavailable(sender, msg("command.feature.textures"));
                    } else {
                        textures.open(player);
                    }
                }
                case ADMIN_ROUTE -> {
                    if (args.length >= 2 && args[1].equalsIgnoreCase("lobby")) {
                        lobby(sender, label, args);
                    } else if (!sender.hasPermission(ADMIN)) {
                        denied(sender);
                    } else if (args.length == 2 && args[1].equalsIgnoreCase("reload")) {
                        if (reload == null) {
                            unavailable(sender, msg("command.feature.reload"));
                        }
                        else reload.accept(sender);
                    } else if (args.length >= 2 && args[1].equalsIgnoreCase(TEXTURES)) {
                        resetTextures(sender, label, args);
                    } else {
                        usage(sender, label, route.usage());
                    }
                }
                case ArenaCommand.NAME -> arena.execute(sender, label, tail(args));
                case PortalCommand.NAME -> portal.execute(sender, label, tail(args));
                case DirectorCommand.NAME -> director.execute(sender, label, tail(args));
                default -> throw new IllegalStateException("No handler for " + group);
            }
        }
        return true;
    }

    private void lobby(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(LOBBY)) {
            denied(sender);
            return;
        }
        String action = args.length == 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        if (!List.of("edit", "done", "reset").contains(action)) {
            usage(sender, label, LOBBY_USAGE);
        } else if (lobby == null) {
            unavailable(sender, msg("command.feature.lobby"));
        } else if (action.equals("reset")) {
            lobby.reset(sender);
        } else if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("command.players-only"));
        } else if (action.equals("edit")) {
            lobby.edit(player, label);
        } else {
            lobby.done(player);
        }
    }

    private void resetTextures(CommandSender sender, String label, String[] args) {
        String usage = "admin textures reset <java|bedrock> <uuid|xuid>";
        if (args.length != 5 || !args[2].equalsIgnoreCase("reset")) {
            usage(sender, label, usage);
            return;
        }
        PlayerKey.Edition edition = args[3].equalsIgnoreCase("java") ? PlayerKey.Edition.JAVA
                : args[3].equalsIgnoreCase("bedrock") ? PlayerKey.Edition.BEDROCK : null;
        if (edition == null) {
            usage(sender, label, usage);
            return;
        }
        PlayerKey key;
        try {
            key = PlayerKey.of(edition, args[4]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(msg("command.invalid-player-id", "reason", e instanceof Keyed keyed ? keyed.text()
                    : e.getMessage(), "label", label, "usage", usage));
            return;
        }
        if (textures == null) {
            unavailable(sender, msg("command.feature.textures"));
        } else {
            textures.reset(sender, key);
        }
    }

    private static boolean canUseRoot(CommandSender sender) {
        return sender.hasPermission(PLAY) || sender.hasPermission(HOST) || sender.hasPermission(ADMIN)
                || sender.hasPermission(LOBBY) || sender.hasPermission(PortalEntry.SPECTATE)
                || sender.hasPermission(LeaderboardCommand.PERMISSION) || sender.hasPermission(EventCommand.PERMISSION);
    }

    private static boolean canUse(CommandSender sender, Route route) {
        return sender.hasPermission(route.permission())
                || (route.name().equals(LEAVE) && sender.hasPermission(PortalEntry.SPECTATE))
                || (route.name().equals(ADMIN_ROUTE) && sender.hasPermission(LOBBY));
    }

    private static void denied(CommandSender sender) {
        sender.sendMessage(msg("command.no-permission"));
    }

    private static void usage(CommandSender sender, String label, String usage) {
        sender.sendMessage(msg("command.usage", "label", label, "usage", usage));
    }

    private static void unavailable(CommandSender sender, Object feature) {
        sender.sendMessage(msg("command.unavailable", "feature", feature));
    }

    /** Lists only the routes the sender can use. */
    private static void help(CommandSender sender, String label, String unknown) {
        if (unknown != null) {
            sender.sendMessage(msg("command.unknown", "command", unknown));
        } else {
            sender.sendMessage(msg("command.help-header"));
        }
        line(sender, label, "help", "help");
        for (Route route : ROUTES) {
            if (canUse(sender, route)) {
                line(sender, label, route.usage(), route.name());
            }
        }
    }

    private static void line(CommandSender sender, String label, String usage, String route) {
        sender.sendMessage(msg("command.help-line", "label", label, "usage", usage,
                "description", msg("help." + route)));
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("command.leave-players-only"));
        } else if (!leave.test(player)) {
            sender.sendMessage(msg("command.not-in-arena"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!canUseRoot(sender)) {
            return List.of();
        }
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("help".startsWith(prefix)) {
                out.add("help");
            }
            for (Route route : ROUTES) {
                if (canUse(sender, route) && route.name().startsWith(prefix)) {
                    out.add(route.name());
                }
            }
            return out;
        }
        if (args[0].equalsIgnoreCase(LeaderboardCommand.NAME)) {
            return sender.hasPermission(LeaderboardCommand.PERMISSION)
                    ? LeaderboardCommand.complete(tail(args)) : List.of();
        }
        if (args[0].equalsIgnoreCase(EventCommand.NAME)) {
            return event != null && sender.hasPermission(EventCommand.PERMISSION) ? event.complete(tail(args)) : List.of();
        }
        if (args[0].equalsIgnoreCase(ADMIN_ROUTE)) {
            return completeAdmin(sender, args);
        }
        if (!sender.hasPermission(ADMIN)) {
            return List.of();
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "pack" -> args.length == 2 && "status".startsWith(args[1].toLowerCase(Locale.ROOT))
                    ? List.of("status") : List.of();
            case ArenaCommand.NAME -> arena.complete(sender, tail(args));
            case PortalCommand.NAME -> portal.complete(sender, tail(args));
            case DirectorCommand.NAME -> director.complete(sender, tail(args));
            default -> List.of();
        };
    }

    private static List<String> completeAdmin(CommandSender sender, String[] args) {
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission(ADMIN);
        boolean lobby = sender.hasPermission(LOBBY);
        List<String> options = switch (args.length) {
            case 2 -> {
                List<String> routes = new ArrayList<>();
                if (admin) {
                    routes.add("reload");
                }
                if (lobby) {
                    routes.add("lobby");
                }
                if (admin) {
                    routes.add(TEXTURES);
                }
                yield routes;
            }
            case 3 -> admin && args[1].equalsIgnoreCase(TEXTURES) ? List.of("reset")
                    : lobby && args[1].equalsIgnoreCase("lobby") ? List.of("edit", "done", "reset") : List.of();
            case 4 -> admin && args[1].equalsIgnoreCase(TEXTURES) && args[2].equalsIgnoreCase("reset")
                    ? List.of("java", "bedrock") : List.of();
            default -> List.of();
        };
        return options.stream().filter(option -> option.startsWith(last)).toList();
    }

    private static String[] tail(String[] args) {
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }
}
