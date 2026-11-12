package com.ninja6.ninjaarena.command;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.portal.LobbyMode;
import com.ninja6.ninjaarena.portal.Portal;
import com.ninja6.ninjaarena.portal.PortalRegistry;
import com.ninja6.ninjaarena.world.PluginWorlds;
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
 *
 * <p>A play portal leads to the lobby. {@code lobby} is a deprecated alias that still sets
 * the portal's old lobby choice, which now only decides which open rooms a player joins
 * there: those on the portal's arena (dedicated) or any (shared). {@code sethub} is a
 * deprecated alias that changes nothing, since the lobby took the shared hub's place;
 * {@code removehub} clears a hub point an older version saved.
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
            tell(sender, "command.usage-full", "usage", usagePrefix + "<" + String.join("|", SUBCOMMANDS) + ">");
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
                default -> tell(sender, "command.unknown-subcommand", "subcommand", args[0],
                        "subcommands", String.join(", ", SUBCOMMANDS));
            }
        } catch (ArenaException e) {
            tell(sender, "command.error", "message", e.text());
        }
    }

    private void setCorner(CommandSender sender, String[] args, String usage) throws ArenaException {
        if ((args.length != 1 && args.length != 4) || !(args[0].equals("1") || args[0].equals("2"))) {
            tell(sender, "command.usage-full", "usage", usage + "setcorner <1|2> [<x> <y> <z>]");
            return;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "portal.corner-players-only");
            return;
        }
        Location base = player.getLocation();
        String world = base.getWorld().getName();
        if (PluginWorlds.isArena(world)) {
            throw new ArenaException("portal.invalid.arena-world");
        }
        if (PluginWorlds.isLobby(world)) {
            throw new ArenaException("portal.invalid.lobby-world");
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
            tell(sender, "portal.corner-cleared", "world", selection.world());
            selection = null;
        }
        if (selection == null) {
            selection = new Selection(world, null, null);
        }
        selection = selection.with(corner, block);
        selections.put(player.getUniqueId(), selection);
        tell(sender, "portal.corner-set", "corner", corner, "x", block[0], "y", block[1], "z", block[2],
                "world", world);
        if (selection.complete()) {
            ArenaBounds bounds = selection.bounds();
            tell(sender, "portal.selected", "bounds", bounds.describe());
            if (!fits(bounds)) {
                tell(sender, "portal.too-large", "max", Portal.MAX_SPAN);
            }
            if (bounds.minY() == bounds.maxY()) {
                tell(sender, "portal.flat", "corner", corner);
            }
        }
    }

    private void create(CommandSender sender, String[] args, String usage) throws ArenaException {
        String kind = args.length == 3 ? args[2].toLowerCase(Locale.ROOT) : null;
        if (args.length != 2 && (args.length != 3 || !List.of("spectator", "dedicated", "shared").contains(kind))) {
            tell(sender, "command.usage-full", "usage", usage + "create <name> <arena> [spectator|dedicated|shared]");
            return;
        }
        if (!(sender instanceof Player player)) {
            tell(sender, "portal.create-players-only");
            return;
        }
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || !selection.complete()) {
            throw new ArenaException("portal.error.no-selection");
        }
        Arena arena = arenas.get(args[1])
                .orElseThrow(() -> new ArenaException("arena.error.unknown", "arena", args[1]));
        boolean spectator = "spectator".equals(kind);
        LobbyMode lobby = spectator || kind == null ? null : LobbyMode.parse(kind).orElseThrow();
        Portal portal = portals.create(args[0], selection.world(), selection.bounds(), arena.name(), spectator, lobby);
        selections.remove(player.getUniqueId());
        if (portal.spectator()) {
            tell(sender, "portal.created-spectator", "portal", portal.name(), "world", portal.world(),
                    "region", portal.region().describe(), "arena", arena.name());
            return;
        }
        tell(sender, "portal.created", "portal", portal.name(), "world", portal.world(),
                "region", portal.region().describe(), "destination", destination(portal));
    }

    private void lobby(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 2 || !LOBBIES.contains(args[1].toLowerCase(Locale.ROOT))) {
            tell(sender, "command.usage-full", "usage", usage + "lobby <name> <" + String.join("|", LOBBIES) + ">");
            return;
        }
        LobbyMode lobby = LobbyMode.parse(args[1]).orElse(null);
        Portal portal = portals.setLobby(args[0], lobby);
        tell(sender, "portal.lobby-set", "portal", portal.name(), "destination", destination(portal));
        tell(sender, "portal.lobby-deprecated");
    }

    /**
     * Deprecated: the lobby took the shared hub's place, so there is no hub point to set. The
     * arguments are still checked as before; nothing is saved.
     */
    private void setHub(CommandSender sender, String[] args, String usage) throws ArenaException {
        ArenaCommand.position(sender, args, usage + "sethub " + POSITION);
        tell(sender, "portal.sethub-deprecated");
    }

    private void removeHub(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 0) {
            tell(sender, "command.usage-full", "usage", usage + "removehub");
            return;
        }
        if (portals.hub().isEmpty()) {
            throw new ArenaException("portal.error.no-hub");
        }
        portals.setHub(null);
        tell(sender, "portal.hub-removed");
    }

    /** Where a play portal sends players now, for chat: the lobby, and which rooms they join there. */
    private Component destination(Portal portal) {
        Component destination = portal.arena() == null || portal.lobby(defaultLobby.get()) == LobbyMode.SHARED
                ? msg("portal.destination.any") : msg("portal.destination.arena", "arena", portal.arena());
        return portal.lobby() == null && portal.arena() != null
                ? msg("portal.destination.server-default", "destination", destination) : destination;
    }

    private void delete(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 1) {
            tell(sender, "command.usage-full", "usage", usage + "delete <name>");
            return;
        }
        String name = portals.get(args[0]).map(Portal::name).orElse(args[0]);
        portals.delete(args[0]);
        tell(sender, "portal.deleted", "portal", name);
    }

    private void list(CommandSender sender) {
        tell(sender, "portal.list-header");
        if (portals.all().isEmpty()) {
            tell(sender, "portal.list-empty");
            return;
        }
        for (Portal portal : portals.all()) {
            tell(sender, "portal.list-line", "portal", portal.name(), "world", portal.world(),
                    "target", portal.playerBuilt() ? msg("portal.player-built-target") : portal.arena(),
                    "status", status(portal));
        }
    }

    private void info(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 1) {
            tell(sender, "command.usage-full", "usage", usage + "info <name>");
            return;
        }
        Portal portal = portals.get(args[0])
                .orElseThrow(() -> new ArenaException("portal.error.unknown", "portal", args[0]));
        tell(sender, "portal.info.header", "portal", portal.name(), "status", status(portal));
        tell(sender, worldLoaded.test(portal.world()) ? "portal.info.world" : "portal.info.world-unloaded",
                "world", portal.world());
        tell(sender, "portal.info.region", "region", portal.region().describe());
        if (portal.playerBuilt()) {
            tell(sender, "portal.info.built", "owner", portal.built().owner(), "frame", portal.built().frame());
        } else {
            tell(sender, portal.spectator() ? "portal.info.arena-spectator" : "portal.info.arena",
                    "arena", portal.arena());
        }
        if (!portal.spectator()) {
            tell(sender, "portal.info.leads-to", "destination", destination(portal));
        }
    }

    /** What a player walking in now would meet. */
    private Component status(Portal portal) {
        return msg("portal.status." + statusKey(portal));
    }

    private String statusKey(Portal portal) {
        if (!worldLoaded.test(portal.world())) {
            return "world-unloaded";
        }
        if (!portal.spectator()) {
            return "lobby";
        }
        return arenas.get(portal.arena()).isPresent() ? "spectator" : "arena-missing";
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
                case 0 -> ArenaCommand.matching(adminPortalNames(), current);
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

    /** Portals an admin marked out; a player-built one takes no lobby choice. */
    private List<String> adminPortalNames() {
        List<String> names = new ArrayList<>();
        for (Portal portal : portals.all()) {
            if (!portal.playerBuilt()) {
                names.add(portal.name());
            }
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

    private static void tell(CommandSender sender, String key, Object... placeholders) {
        sender.sendMessage(msg(key, placeholders));
    }
}
