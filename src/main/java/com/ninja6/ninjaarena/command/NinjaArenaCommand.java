package com.ninja6.ninjaarena.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** The {@code /ninjaarena} root (alias {@code /narena}), with play, host and admin routes. */
public final class NinjaArenaCommand implements TabExecutor {

    public static final String ADMIN = "ninjaarena.admin";
    public static final String HOST = "ninjaarena.host";
    public static final String PLAY = "ninjaarena.play";
    /** Leaves the waiting room or match the player is in and sends them home. */
    public static final String LEAVE = "leave";

    private record Route(String name, String permission, String usage, String description) {
    }

    private static final List<Route> ROUTES = List.of(
            new Route(LEAVE, PLAY, LEAVE, "leave the waiting room or match you are in"),
            new Route("accept", PLAY, "accept <host>", "accept an invitation to the Arena"),
            new Route("host", HOST, "host <arena>", "open a match in the Arena"),
            new Route("invite", HOST, "invite <player>", "invite a player to the Arena"),
            new Route(ArenaCommand.NAME, ADMIN, ArenaCommand.NAME + " <subcommand>", "create, edit and set up arenas"),
            new Route(PortalCommand.NAME, ADMIN, PortalCommand.NAME + " <subcommand>",
                    "mark out portals that lead to arenas"),
            new Route(DirectorCommand.NAME, ADMIN, DirectorCommand.NAME + " [arena]",
                    "show the enemy director of a running match"),
            new Route("admin", ADMIN, "admin reload", "safely reload the Arena configuration"));

    private final ArenaCommand arena;
    private final PortalCommand portal;
    private final DirectorCommand director;
    private final Predicate<Player> leave;
    private final HostingCommands hosting;
    private final Consumer<CommandSender> reload;

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
        this.arena = Objects.requireNonNull(arena, "arena");
        this.portal = Objects.requireNonNull(portal, "portal");
        this.director = Objects.requireNonNull(director, "director");
        this.leave = Objects.requireNonNull(leave, "leave");
        this.hosting = hosting;
        this.reload = reload;
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
        } else if (!sender.hasPermission(route.permission())) {
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
                case "accept", "host", "invite" -> {
                    if (args.length != 2) {
                        usage(sender, label, route.usage());
                    } else if (!(sender instanceof Player player)) {
                        sender.sendMessage(Component.text("Only players can use this Arena command.", NamedTextColor.RED));
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
                case "admin" -> {
                    if (args.length == 2 && args[1].equalsIgnoreCase("reload")) {
                        if (reload == null) {
                            unavailable(sender, "Configuration reload");
                        }
                        else reload.accept(sender);
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

    private static boolean canUseRoot(CommandSender sender) {
        return sender.hasPermission(PLAY) || sender.hasPermission(HOST) || sender.hasPermission(ADMIN);
    }

    private static void denied(CommandSender sender) {
        sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
    }

    private static void usage(CommandSender sender, String label, String usage) {
        sender.sendMessage(Component.text("Usage: /" + label + " " + usage, NamedTextColor.RED));
    }

    private static void unavailable(CommandSender sender, String feature) {
        sender.sendMessage(Component.text(feature + " is not available yet in the Arena.", NamedTextColor.YELLOW));
    }

    /** Lists only the routes the sender can use. */
    private static void help(CommandSender sender, String label, String unknown) {
        if (unknown != null) {
            sender.sendMessage(Component.text("Unknown command " + unknown + ". Available commands:", NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("The Arena commands:", NamedTextColor.GOLD));
        }
        line(sender, label, "help", "show the commands you can use");
        for (Route route : ROUTES) {
            if (sender.hasPermission(route.permission())) {
                line(sender, label, route.usage(), route.description());
            }
        }
    }

    private static void line(CommandSender sender, String label, String usage, String description) {
        sender.sendMessage(Component.text("/" + label + " " + usage, NamedTextColor.YELLOW)
                .append(Component.text(" - " + description, NamedTextColor.GRAY)));
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can leave an arena.", NamedTextColor.RED));
        } else if (!leave.test(player)) {
            sender.sendMessage(Component.text("You are not in an arena.", NamedTextColor.RED));
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
                if (sender.hasPermission(route.permission()) && route.name().startsWith(prefix)) {
                    out.add(route.name());
                }
            }
            return out;
        }
        if (!sender.hasPermission(ADMIN)) {
            return List.of();
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "admin" -> args.length == 2 && "reload".startsWith(args[1].toLowerCase(Locale.ROOT))
                    ? List.of("reload") : List.of();
            case ArenaCommand.NAME -> arena.complete(sender, tail(args));
            case PortalCommand.NAME -> portal.complete(sender, tail(args));
            case DirectorCommand.NAME -> director.complete(sender, tail(args));
            default -> List.of();
        };
    }

    private static String[] tail(String[] args) {
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }
}
