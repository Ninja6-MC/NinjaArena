package com.ninja6.ninjaarena.command;

import java.util.ArrayList;
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

    private static final List<String> GROUPS = List.of(ArenaCommand.NAME, PortalCommand.NAME);

    private final ArenaCommand arena;
    private final PortalCommand portal;

    public NinjaArenaCommand(ArenaCommand arena, PortalCommand portal) {
        this.arena = Objects.requireNonNull(arena, "arena");
        this.portal = Objects.requireNonNull(portal, "portal");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }
        String group = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (group) {
            case ArenaCommand.NAME -> arena.execute(sender, label, tail(args));
            case PortalCommand.NAME -> portal.execute(sender, label, tail(args));
            default -> sender.sendMessage(Component.text("Usage: /" + label + " <" + String.join("|", GROUPS)
                    + "> <subcommand>", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            return List.of();
        }
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String group : GROUPS) {
                if (group.startsWith(prefix)) {
                    out.add(group);
                }
            }
            return out;
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
