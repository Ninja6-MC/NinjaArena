package com.ninja6.ninjaarena.command;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.portal.Portal;
import com.ninja6.ninjaarena.portal.PortalRegistry;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /ninjaarena portal ...}: marking out portals and binding them to arenas. The caller
 * has already checked the admin permission.
 *
 * <p>An admin selects a region with {@code setcorner 1} and {@code setcorner 2}, each taken
 * from where they stand or from coordinates in their current world, then {@code create}s
 * the portal from it. Selections are per player and dropped when they leave. {@code delete},
 * {@code list} and {@code info} also run from the console.
 */
public final class PortalCommand {

    public static final String NAME = "portal";

    private static final List<String> SUBCOMMANDS = List.of("setcorner", "create", "delete", "list", "info");

    /** One admin's corners, both in {@link #world}. Either may be unset. */
    private record Selection(String world, int[] first, int[] second) {

        Selection with(int corner, int[] block) {
            return corner == 1 ? new Selection(world, block, second) : new Selection(world, first, block);
        }

        boolean complete() {
            return first != null && second != null;
        }

        ArenaBounds bounds() {
            return new ArenaBounds(first[0], first[1], first[2], second[0], second[1], second[2]);
        }
    }

    private final PortalRegistry portals;
    private final ArenaRegistry arenas;
    private final Predicate<String> worldLoaded;
    private final Map<UUID, Selection> selections = new HashMap<>();

    /**
     * @param worldLoaded whether a world of that name is loaded, for {@code list} and {@code info}
     */
    public PortalCommand(PortalRegistry portals, ArenaRegistry arenas, Predicate<String> worldLoaded) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
        this.worldLoaded = Objects.requireNonNull(worldLoaded, "worldLoaded");
    }

    /** Drops a player's selection, for when they leave the server. */
    public void forget(UUID playerId) {
        selections.remove(playerId);
    }

    void execute(CommandSender sender, String label, String[] args) {
        String usagePrefix = "/" + label + " " + NAME + " ";
        if (args.length == 0) {
            error(sender, "Usage: " + usagePrefix + "<" + String.join("|", SUBCOMMANDS) + ">");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            switch (sub) {
                case "setcorner" -> setCorner(sender, rest, usagePrefix);
                case "create" -> create(sender, rest, usagePrefix);
                case "delete" -> delete(sender, rest, usagePrefix);
                case "list" -> list(sender);
                case "info" -> info(sender, rest, usagePrefix);
                default -> error(sender, "Unknown subcommand " + args[0] + ". Use one of: "
                        + String.join(", ", SUBCOMMANDS) + ".");
            }
        } catch (ArenaException e) {
            error(sender, e.getMessage());
        }
    }

    private void setCorner(CommandSender sender, String[] args, String usage) throws ArenaException {
        if ((args.length != 1 && args.length != 4) || !(args[0].equals("1") || args[0].equals("2"))) {
            error(sender, "Usage: " + usage + "setcorner <1|2> [<x> <y> <z>]");
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Only a player can select a portal region.");
            return;
        }
        Location base = player.getLocation();
        String world = base.getWorld().getName();
        if (world.equals(ArenaWorld.NAME)) {
            throw new ArenaException("Portals cannot be in the arena world.");
        }
        int[] block = {base.getBlockX(), base.getBlockY(), base.getBlockZ()};
        if (args.length == 4) {
            block = new int[] {
                ArenaCommand.blockCoordinate(args[1], base.getBlockX(), "x"),
                ArenaCommand.blockCoordinate(args[2], base.getBlockY(), "y"),
                ArenaCommand.blockCoordinate(args[3], base.getBlockZ(), "z")};
        }
        int corner = Integer.parseInt(args[0]);
        Selection selection = selections.get(player.getUniqueId());
        if (selection != null && !selection.world().equals(world)) {
            warn(sender, "Your other corner was in " + selection.world() + " and has been cleared.");
            selection = null;
        }
        if (selection == null) {
            selection = new Selection(world, null, null);
        }
        selection = selection.with(corner, block);
        selections.put(player.getUniqueId(), selection);
        success(sender, "Corner " + corner + " set to " + block[0] + ", " + block[1] + ", " + block[2] + " in "
                + world + ".");
        if (selection.complete()) {
            ArenaBounds bounds = selection.bounds();
            info(sender, "Selected " + bounds.describe() + ".");
            if (!fits(bounds)) {
                warn(sender, "That is more than " + Portal.MAX_SPAN + " blocks along an axis; create will refuse it.");
            }
            if (bounds.minY() == bounds.maxY()) {
                warn(sender, "The selection is one block tall, so the portal would be a flat pad. For a doorway,"
                        + " stand in it and use setcorner " + corner + " ~ ~2 ~.");
            }
        }
    }

    private void create(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 2) {
            error(sender, "Usage: " + usage + "create <name> <arena>");
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Only a player can create a portal from their selection.");
            return;
        }
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || !selection.complete()) {
            throw new ArenaException("Set both corners first with setcorner 1 and setcorner 2.");
        }
        Arena arena = arenas.get(args[1]).orElseThrow(() -> new ArenaException("No arena called " + args[1] + "."));
        Portal portal = portals.create(args[0], selection.world(), selection.bounds(), arena.name());
        selections.remove(player.getUniqueId());
        success(sender, "Portal " + portal.name() + " created in " + portal.world() + " ("
                + portal.region().describe() + "), leading to arena " + arena.name() + ".");
        if (!arenas.isAvailable(arena.name())) {
            warn(sender, "Arena " + arena.name() + " is not available right now, so the portal turns players away"
                    + " until it is.");
        }
    }

    private void delete(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 1) {
            error(sender, "Usage: " + usage + "delete <name>");
            return;
        }
        String name = portals.get(args[0]).map(Portal::name).orElse(args[0]);
        portals.delete(args[0]);
        success(sender, "Portal " + name + " removed. Blocks built around it are still in the world.");
    }

    private void list(CommandSender sender) {
        if (portals.all().isEmpty()) {
            info(sender, "No portals are registered.");
            return;
        }
        for (Portal portal : portals.all()) {
            info(sender, portal.name() + " (" + portal.world() + ") to " + portal.arena() + ": " + status(portal));
        }
    }

    private void info(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 1) {
            error(sender, "Usage: " + usage + "info <name>");
            return;
        }
        Portal portal = portals.get(args[0]).orElseThrow(() -> new ArenaException("No portal called " + args[0]
                + "."));
        info(sender, "Portal " + portal.name() + ": " + status(portal));
        info(sender, "World: " + portal.world() + (worldLoaded.test(portal.world()) ? "" : " (not loaded)"));
        info(sender, "Region: " + portal.region().describe());
        info(sender, "Arena: " + portal.arena());
    }

    /** What a player walking in now would meet. */
    private String status(Portal portal) {
        if (!worldLoaded.test(portal.world())) {
            return "world not loaded";
        }
        Arena arena = arenas.get(portal.arena()).orElse(null);
        if (arena == null) {
            return "arena missing";
        }
        if (arenas.isAvailable(arena.name())) {
            return "open";
        }
        if (arenas.isHeld(arena.name())) {
            return "closed, arena in use by a match";
        }
        if (!arenas.editing().isEmpty()) {
            return "closed while arenas are in edit mode";
        }
        return "closed, arena not ready";
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return ArenaCommand.matching(SUBCOMMANDS, args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        int position = args.length - 2;
        String current = args[args.length - 1];
        return switch (sub) {
            case "setcorner" -> {
                if (position == 0) {
                    yield ArenaCommand.matching(List.of("1", "2"), current);
                }
                yield position <= 3 ? ArenaCommand.coordinate(sender, position - 1, current) : List.of();
            }
            case "create" -> position == 1 ? ArenaCommand.matching(arenaNames(), current) : List.of();
            case "delete", "info" -> position == 0 ? ArenaCommand.matching(portalNames(), current) : List.of();
            default -> List.of();
        };
    }

    private List<String> portalNames() {
        List<String> names = new ArrayList<>();
        for (Portal portal : portals.all()) {
            names.add(portal.name());
        }
        return names;
    }

    private List<String> arenaNames() {
        List<String> names = new ArrayList<>();
        for (Arena arena : arenas.all()) {
            names.add(arena.name());
        }
        return names;
    }

    private static boolean fits(ArenaBounds bounds) {
        return (long) bounds.maxX() - bounds.minX() < Portal.MAX_SPAN
                && (long) bounds.maxY() - bounds.minY() < Portal.MAX_SPAN
                && (long) bounds.maxZ() - bounds.minZ() < Portal.MAX_SPAN;
    }

    private static void success(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private static void warn(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.YELLOW));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
