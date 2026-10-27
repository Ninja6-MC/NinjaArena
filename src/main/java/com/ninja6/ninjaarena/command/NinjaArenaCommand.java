package com.ninja6.ninjaarena.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * The {@code /ninjaarena} root (alias {@code /narena}). {@value #LEAVE} is for players and
 * needs {@value #PLAY}; every other subcommand is an admin one, so {@value #ADMIN} is checked
 * before anything else is parsed or completed.
 */
public final class NinjaArenaCommand implements TabExecutor {

    public static final String ADMIN = "ninjaarena.admin";
    public static final String PLAY = "ninjaarena.play";
    /** Leaves the waiting room the player is in and sends them home. */
    public static final String LEAVE = "leave";

    private static final List<String> GROUPS = List.of(ArenaCommand.NAME, PortalCommand.NAME);

    private final ArenaCommand arena;
    private final PortalCommand portal;
    private final Predicate<Player> leave;

    /**
     * @param leave takes the player out of their waiting room and sends them home; false if
     *              they were not in one
     */
    public NinjaArenaCommand(ArenaCommand arena, PortalCommand portal, Predicate<Player> leave) {
        this.arena = Objects.requireNonNull(arena, "arena");
        this.portal = Objects.requireNonNull(portal, "portal");
        this.leave = Objects.requireNonNull(leave, "leave");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase(LEAVE) && sender.hasPermission(PLAY)) {
            leave(sender);
            return true;
        }
        if (!sender.hasPermission(ADMIN)) {
            if (args.length == 0 && sender.hasPermission(PLAY)) {
                sender.sendMessage(Component.text("Usage: /" + label + " " + LEAVE, NamedTextColor.RED));
            } else {
                sender.sendMessage(Component.text("You do not have permission to use this command.",
                        NamedTextColor.RED));
            }
            return true;
        }
        String group = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (group) {
            case ArenaCommand.NAME -> arena.execute(sender, label, tail(args));
            case PortalCommand.NAME -> portal.execute(sender, label, tail(args));
            default -> sender.sendMessage(Component.text("Usage: /" + label + " <" + String.join("|", GROUPS)
                    + "> <subcommand>, or /" + label + " " + LEAVE, NamedTextColor.RED));
        }
        return true;
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can leave a waiting room.", NamedTextColor.RED));
        } else if (!leave.test(player)) {
            sender.sendMessage(Component.text("You are not in a waiting room.", NamedTextColor.RED));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if (sender.hasPermission(PLAY) && LEAVE.startsWith(prefix)) {
                out.add(LEAVE);
            }
            if (!admin) {
                return out;
            }
            for (String group : GROUPS) {
                if (group.startsWith(prefix)) {
                    out.add(group);
                }
            }
            return out;
        }
        if (!admin) {
            return List.of();
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case ArenaCommand.NAME -> arena.complete(sender, tail(args));
            case PortalCommand.NAME -> portal.complete(sender, tail(args));
            default -> List.of();
        };
    }

    private static String[] tail(String[] args) {
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }
}
