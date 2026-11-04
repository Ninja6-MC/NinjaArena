package com.ninja6.ninjaarena.command;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.portal.LobbyMode;
import com.ninja6.ninjaarena.portal.Portal;
import com.ninja6.ninjaarena.portal.PortalRegistry;
import com.ninja6.ninjaarena.world.ArenaExtent;
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
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /ninjaarena portal ...}: marking out portals, binding them to arenas and choosing
 * where they lead. The caller has already checked the admin permission.
 *
 * <p>An admin selects a region with {@code setcorner 1} and {@code setcorner 2}, each taken
 * from where they stand or from coordinates in their current world, then {@code create}s
 * the portal from it. Selections are per player and dropped when they leave. {@code delete},
 * {@code list} and {@code info} also run from the console.
 *
 * <p>{@code lobby} sets whether a portal leads to its arena's waiting room or the shared hub,
 * or back to the server default. {@code sethub} sets the hub point in the arena world, like
 * {@code arena setwaitingroom}: where the admin stands, or coordinates from the console;
 * {@code removehub} clears it.
 */
public final class PortalCommand {

    public static final String NAME = "portal";

    private static final List<String> SUBCOMMANDS = List.of("setcorner", "create", "delete", "list", "info",
            "lobby", "sethub", "removehub");

    private static final String POSITION = "[<x> <y> <z> [<yaw> <pitch>]]";

    /** The lobby choices {@code lobby} takes; {@code default} clears the portal's own choice. */
    private static final List<String> LOBBIES = List.of("default", "dedicated", "shared");

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
    private final Supplier<LobbyMode> defaultLobby;
    private final Map<UUID, Selection> selections = new HashMap<>();

    /**
     * @param worldLoaded whether a world of that name is loaded, for {@code list} and {@code info}
     */
    public PortalCommand(PortalRegistry portals, ArenaRegistry arenas, Predicate<String> worldLoaded) {
        this(portals, arenas, worldLoaded, () -> LobbyMode.DEDICATED);
    }

    /** @param defaultLobby the server default for portals with no lobby choice of their own */
    public PortalCommand(PortalRegistry portals, ArenaRegistry arenas, Predicate<String> worldLoaded,
            Supplier<LobbyMode> defaultLobby) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
        this.worldLoaded = Objects.requireNonNull(worldLoaded, "worldLoaded");
        this.defaultLobby = Objects.requireNonNull(defaultLobby, "defaultLobby");
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
                case "lobby" -> lobby(sender, rest, usagePrefix);
                case "sethub" -> setHub(sender, rest, usagePrefix);
                case "removehub" -> removeHub(sender, rest, usagePrefix);
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
        String kind = args.length == 3 ? args[2].toLowerCase(Locale.ROOT) : null;
        if (args.length != 2 && (args.length != 3 || !List.of("spectator", "dedicated", "shared").contains(kind))) {
            error(sender, "Usage: " + usage + "create <name> <arena> [spectator|dedicated|shared]");
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
        boolean spectator = "spectator".equals(kind);
        LobbyMode lobby = spectator || kind == null ? null : LobbyMode.parse(kind).orElseThrow();
        Portal portal = portals.create(args[0], selection.world(), selection.bounds(), arena.name(), spectator, lobby);
        selections.remove(player.getUniqueId());
        if (portal.spectator()) {
            success(sender, "Portal " + portal.name() + " created in " + portal.world() + " ("
                    + portal.region().describe() + "), leading to arena " + arena.name() + " for spectating.");
            return;
        }
        success(sender, "Portal " + portal.name() + " created in " + portal.world() + " ("
                + portal.region().describe() + "), leading to " + destination(portal) + ".");
        warnUnavailable(sender, portal);
    }

    private void lobby(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 2 || !LOBBIES.contains(args[1].toLowerCase(Locale.ROOT))) {
            error(sender, "Usage: " + usage + "lobby <name> <" + String.join("|", LOBBIES) + ">");
            return;
        }
        LobbyMode lobby = LobbyMode.parse(args[1]).orElse(null);
        Portal portal = portals.setLobby(args[0], lobby);
        success(sender, "Portal " + portal.name() + " now leads to " + destination(portal) + ".");
        warnUnavailable(sender, portal);
    }

    private void setHub(CommandSender sender, String[] args, String usage) throws ArenaException {
        ArenaPoint point = ArenaCommand.position(sender, args, usage + "sethub " + POSITION);
        if (!ArenaExtent.contains(point.x(), point.z(), arenas.grid().borderSize())) {
            throw new ArenaException("The hub must be inside the arena world border.");
        }
        for (Arena arena : arenas.all()) {
            if (arena.bounds() != null && arena.bounds().contains(point)) {
                throw new ArenaException("The hub cannot be inside the bounds of arena " + arena.name() + ".");
            }
        }
        portals.setHub(point);
        success(sender, "Shared hub set to " + point.describe() + " in " + ArenaWorld.NAME + ". Anyone waiting at"
                + " the old point is sent home.");
        if (defaultLobby.get() != LobbyMode.SHARED
                && portals.all().stream().noneMatch(portal -> portal.lobby() == LobbyMode.SHARED)) {
            warn(sender, "No portal leads there yet. Use lobby <portal> shared, or set waiting-room.lobby to shared"
                    + " in config.yml.");
        }
    }

    private void removeHub(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 0) {
            error(sender, "Usage: " + usage + "removehub");
            return;
        }
        if (portals.hub().isEmpty()) {
            throw new ArenaException("No shared hub is set.");
        }
        portals.setHub(null);
        success(sender, "Shared hub removed. Portals that lead there turn players away until a hub is set.");
    }

    /** Where a non-spectator portal sends players now, for chat. */
    private String destination(Portal portal) {
        String choice = portal.lobby() == null ? " (server default)" : "";
        if (portal.lobby(defaultLobby.get()) == LobbyMode.SHARED) {
            return "the shared hub" + choice;
        }
        if (portal.arena() == null) {
            return "a waiting room that is filling, else a free arena" + choice;
        }
        return "arena " + portal.arena() + choice;
    }

    private void warnUnavailable(CommandSender sender, Portal portal) {
        if (portal.lobby(defaultLobby.get()) == LobbyMode.SHARED) {
            if (portals.hub().isEmpty()) {
                warn(sender, "No shared hub is set, so the portal turns players away until one is. Stand there and"
                        + " use sethub.");
            }
        } else if (!arenas.isAvailable(portal.arena())) {
            warn(sender, "Arena " + portal.arena() + " is not available right now, so the portal turns players away"
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
        info(sender, "Shared hub: " + portals.hub().map(ArenaPoint::describe).orElse("not set")
                + ". Portals with no lobby of their own lead to " + (defaultLobby.get() == LobbyMode.SHARED
                ? "the shared hub." : "their arena's waiting room."));
        if (portals.all().isEmpty()) {
            info(sender, "No portals are registered.");
            return;
        }
        for (Portal portal : portals.all()) {
            info(sender, portal.name() + " (" + portal.world() + ") to "
                    + (portal.playerBuilt() ? "any arena, player-built" : portal.arena()) + ": " + status(portal));
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
        if (portal.playerBuilt()) {
            info(sender, "Built by player " + portal.built().owner() + " from " + portal.built().frame()
                    + ". Breaking a frame block removes it.");
        } else {
            info(sender, "Arena: " + portal.arena() + (portal.spectator() ? " (spectator portal)" : ""));
        }
        if (!portal.spectator()) {
            info(sender, "Leads to: " + destination(portal));
        }
    }

    /** What a player walking in now would meet. */
    private String status(Portal portal) {
        if (!worldLoaded.test(portal.world())) {
            return "world not loaded";
        }
        if (!portal.spectator() && portal.lobby(defaultLobby.get()) == LobbyMode.SHARED) {
            return portals.hub().isPresent() ? "open, to the shared hub" : "closed, no shared hub is set";
        }
        if (portal.arena() == null) {
            return "open, to whichever arena takes players when someone walks in";
        }
        Arena arena = arenas.get(portal.arena()).orElse(null);
        if (arena == null) {
            return "arena missing";
        }
        if (portal.spectator()) {
            return "spectator portal, requires a live match with space";
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
            case "create" -> switch (position) {
                case 1 -> ArenaCommand.matching(arenaNames(), current);
                case 2 -> ArenaCommand.matching(List.of("spectator", "dedicated", "shared"), current);
                default -> List.of();
            };
            case "delete", "info" -> position == 0 ? ArenaCommand.matching(portalNames(), current) : List.of();
            case "lobby" -> switch (position) {
                case 0 -> ArenaCommand.matching(portalNames(), current);
                case 1 -> ArenaCommand.matching(LOBBIES, current);
                default -> List.of();
            };
            case "sethub" -> position <= 2 ? ArenaCommand.coordinate(sender, position, current) : List.of();
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
