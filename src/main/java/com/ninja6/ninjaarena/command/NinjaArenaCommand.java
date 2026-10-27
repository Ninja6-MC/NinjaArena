package com.ninja6.ninjaarena.command;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/**
 * The {@code /ninjaarena} root (alias {@code /narena}). For now every subcommand is an admin
 * one, so {@value #ADMIN} is checked first, before anything is parsed or completed.
 */
public final class NinjaArenaCommand implements TabExecutor {

    public static final String ADMIN = "ninjaarena.admin";

    private final ArenaCommand arena;

    public NinjaArenaCommand(ArenaCommand arena) {
        this.arena = Objects.requireNonNull(arena, "arena");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase(ArenaCommand.NAME)) {
            sender.sendMessage(Component.text("Usage: /" + label + " " + ArenaCommand.NAME + " <subcommand>",
                    NamedTextColor.RED));
            return true;
        }
        arena.execute(sender, label, tail(args));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            return List.of();
        }
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return ArenaCommand.NAME.startsWith(prefix) ? List.of(ArenaCommand.NAME) : List.of();
        }
        if (!args[0].equalsIgnoreCase(ArenaCommand.NAME)) {
            return List.of();
        }
        return arena.complete(sender, tail(args));
    }

    private static String[] tail(String[] args) {
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }
}
