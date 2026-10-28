package com.ninja6.ninjaarena.command;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaException;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /ninjaarena arena ...}: building and registering arenas. The caller has already
 * checked the admin permission.
 *
 * <p>Every position can be given as coordinates, so each subcommand except {@code tp} also
 * runs from the console. A player who leaves the coordinates out uses where they stand, and
 * may write {@code ~} or {@code ~n} for a coordinate relative to it.
 */
public final class ArenaCommand {

    public static final String NAME = "arena";

    private static final List<String> SUBCOMMANDS = List.of(
            "create", "delete", "list", "info", "edit", "tp", "setbounds", "setwaitingroom",
            "setteamspawn", "removeteamspawn", "addenemyspawn", "removeenemyspawn");

    private static final String POSITION = "[<x> <y> <z> [<yaw> <pitch>]]";

    private final ArenaRegistry registry;
    private final Supplier<World> world;

    /**
     * @param world the arena world, for {@code tp}
     */
    public ArenaCommand(ArenaRegistry registry, Supplier<World> world) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.world = Objects.requireNonNull(world, "world");
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
                case "create" -> create(sender, rest, usagePrefix);
                case "delete" -> delete(sender, rest, usagePrefix);
                case "list" -> list(sender);
                case "info" -> info(sender, rest, usagePrefix);
                case "edit" -> edit(sender, label, rest, usagePrefix);
                case "tp" -> teleport(sender, rest, usagePrefix);
                case "setbounds" -> setBounds(sender, rest, usagePrefix);
                case "setwaitingroom" -> setWaitingRoom(sender, rest, usagePrefix);
                case "setteamspawn" -> setTeamSpawn(sender, rest, usagePrefix);
                case "removeteamspawn" -> removeTeamSpawn(sender, rest, usagePrefix);
                case "addenemyspawn" -> addEnemySpawn(sender, rest, usagePrefix);
                case "removeenemyspawn" -> removeEnemySpawn(sender, rest, usagePrefix);
                default -> error(sender, "Unknown subcommand " + args[0] + ". Use one of: "
                        + String.join(", ", SUBCOMMANDS) + ".");
            }
        } catch (ArenaException e) {
            error(sender, e.getMessage());
        }
    }

    private void create(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 1 && args.length != 3) {
            error(sender, "Usage: " + usage + "create <name> [<cell x> <cell z>]");
            return;
        }
        GridCell cell = null;
        if (args.length == 3) {
            cell = new GridCell(parseInt(args[1], "cell x"), parseInt(args[2], "cell z"));
        }
        Arena arena = registry.create(args[0], cell);
        success(sender, "Arena " + arena.name() + " created in cell " + arena.cell().describe() + " ("
                + registry.grid().cellBounds(arena.cell()).describe() + ") and put in edit mode.");
    }

    private void delete(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 1) {
            error(sender, "Usage: " + usage + "delete <name>");
            return;
        }
        String name = registry.get(args[0]).map(Arena::name).orElse(args[0]);
        registry.delete(args[0]);
        success(sender, "Arena " + name + " removed from the registry. Its blocks are still in the world.");
    }

    private void list(CommandSender sender) {
        if (registry.all().isEmpty()) {
            info(sender, "No arenas are registered.");
            return;
        }
        for (Arena arena : registry.all()) {
            info(sender, arena.name() + " (cell " + arena.cell().describe() + "): " + status(arena));
        }
    }

    private void info(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 1) {
            error(sender, "Usage: " + usage + "info <name>");
            return;
        }
        Arena arena = require(args[0]);
        info(sender, "Arena " + arena.name() + ": " + status(arena));
        info(sender, "Cell " + arena.cell().describe() + " (" + registry.grid().cellBounds(arena.cell()).describe()
                + ")");
        info(sender, "Bounds: " + (arena.bounds() == null ? "not set" : arena.bounds().describe()));
        info(sender, "Waiting room: " + (arena.waitingRoom() == null ? "not set" : arena.waitingRoom().describe()));
        for (int i = 0; i < arena.teamSpawns().size(); i++) {
            info(sender, "Team " + (i + 1) + " spawn: " + arena.teamSpawns().get(i).describe());
        }
        for (int i = 0; i < arena.enemySpawns().size(); i++) {
            info(sender, "Enemy spawn " + (i + 1) + ": " + arena.enemySpawns().get(i).describe());
        }
        for (String problem : registry.problems(arena.name())) {
            warn(sender, problem);
        }
    }

    private void edit(CommandSender sender, String label, String[] args, String usage) throws ArenaException {
        if (args.length != 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            error(sender, "Usage: " + usage + "edit <name> <on|off>");
            return;
        }
        Arena arena = require(args[0]);
        if (args[1].equalsIgnoreCase("on")) {
            registry.startEdit(arena.name());
            success(sender, "Arena " + arena.name() + " is in edit mode: the arena world saves and the arena"
                    + " takes no matches.");
            return;
        }
        List<String> problems = registry.finishEdit(arena.name());
        if (problems.isEmpty()) {
            success(sender, "Arena " + arena.name() + " saved and ready for matches.");
            return;
        }
        warn(sender, "Arena " + arena.name() + " saved and left edit mode, but it cannot host a match yet:");
        for (String problem : problems) {
            warn(sender, problem);
        }
        warn(sender, "Turn edit mode back on with /" + label + " " + NAME + " edit " + arena.name()
                + " on to fix them.");
    }

    private void teleport(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 1) {
            error(sender, "Usage: " + usage + "tp <name>");
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Only a player can be teleported.");
            return;
        }
        Arena arena = require(args[0]);
        World arenaWorld = world.get();
        ArenaBounds cell = registry.grid().cellBounds(arena.cell());
        boolean hover = arena.waitingRoom() == null;
        Location target;
        if (!hover) {
            ArenaPoint room = arena.waitingRoom();
            target = new Location(arenaWorld, room.x(), room.y(), room.z(), room.yaw(), room.pitch());
        } else {
            target = new Location(arenaWorld, (cell.minX() + cell.maxX() + 1) / 2.0, 64,
                    (cell.minZ() + cell.maxZ() + 1) / 2.0);
        }
        success(sender, "Teleporting to arena " + arena.name() + " in cell " + arena.cell().describe() + " ("
                + cell.describe() + "). Bounds: " + (arena.bounds() == null ? "not set" : arena.bounds().describe())
                + ".");
        player.teleportAsync(target).thenAccept(moved -> {
            if (!moved) {
                error(sender, "The teleport to arena " + arena.name() + " failed.");
                return;
            }
            if (hover) {
                hover(player);
            }
        });
    }

    /**
     * Keeps an admin from falling into the void of a cell with nothing built yet. Applied once
     * the teleport is done: a change of world can turn flight off (EssentialsX
     * {@code world-change-fly-reset}), so flight set before it would not last.
     */
    private static void hover(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        boolean granted = false;
        if (!player.getAllowFlight()) {
            granted = true;
            player.setAllowFlight(true);
        }
        player.setFlying(true);
        warn(player, "The arena world is empty until you build: there is nothing under you yet. Use creative"
                + " or flight while building.");
        if (granted) {
            // Not undone automatically: the admin may still be building when they leave.
            warn(player, "Flight was turned on because the arena has no waiting room yet. It stays on until"
                    + " your game mode changes.");
        }
    }

    private void setBounds(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 7) {
            error(sender, "Usage: " + usage + "setbounds <name> <x1> <y1> <z1> <x2> <y2> <z2>");
            return;
        }
        Location base = baseLocation(sender);
        ArenaBounds bounds = new ArenaBounds(
                blockCoordinate(args[1], base == null ? null : base.getBlockX(), "x1"),
                blockCoordinate(args[2], base == null ? null : base.getBlockY(), "y1"),
                blockCoordinate(args[3], base == null ? null : base.getBlockZ(), "z1"),
                blockCoordinate(args[4], base == null ? null : base.getBlockX(), "x2"),
                blockCoordinate(args[5], base == null ? null : base.getBlockY(), "y2"),
                blockCoordinate(args[6], base == null ? null : base.getBlockZ(), "z2"));
        ArenaBounds cell = registry.grid().cellBounds(require(args[0]).cell());
        if (!cell.contains(bounds)) {
            throw new ArenaException("Bounds " + bounds.describe() + " leave the arena's cell (" + cell.describe()
                    + ").");
        }
        Arena arena = registry.update(args[0], a -> a.withBounds(bounds));
        success(sender, "Bounds of " + arena.name() + " set to " + bounds.describe() + ".");
        warnOutside(sender, arena);
    }

    private void setWaitingRoom(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length < 1) {
            error(sender, "Usage: " + usage + "setwaitingroom <name> " + POSITION);
            return;
        }
        ArenaPoint point = position(sender, Arrays.copyOfRange(args, 1, args.length),
                usage + "setwaitingroom <name> " + POSITION);
        Arena arena = registry.update(args[0], a -> a.withWaitingRoom(point));
        success(sender, "Waiting room of " + arena.name() + " set to " + point.describe() + ".");
        warnOutside(sender, arena);
    }

    private void setTeamSpawn(CommandSender sender, String[] args, String usage) throws ArenaException {
        String full = usage + "setteamspawn <name> <team> " + POSITION;
        if (args.length < 2) {
            error(sender, "Usage: " + full);
            return;
        }
        int team = parseInt(args[1], "team");
        ArenaPoint point = position(sender, Arrays.copyOfRange(args, 2, args.length), full);
        Arena arena = registry.update(args[0], a -> a.withTeamSpawn(team, point));
        success(sender, "Team " + team + " spawn of " + arena.name() + " set to " + point.describe() + ".");
        warnOutside(sender, arena);
    }

    private void removeTeamSpawn(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 1) {
            error(sender, "Usage: " + usage + "removeteamspawn <name>");
            return;
        }
        int team = require(args[0]).teamSpawns().size();
        Arena arena = registry.update(args[0], Arena::withoutLastTeamSpawn);
        success(sender, "Team " + team + " spawn of " + arena.name() + " removed.");
    }

    private void addEnemySpawn(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length < 1) {
            error(sender, "Usage: " + usage + "addenemyspawn <name> " + POSITION);
            return;
        }
        ArenaPoint point = position(sender, Arrays.copyOfRange(args, 1, args.length),
                usage + "addenemyspawn <name> " + POSITION);
        Arena arena = registry.update(args[0], a -> a.withEnemySpawn(point));
        success(sender, "Enemy spawn " + arena.enemySpawns().size() + " of " + arena.name() + " added at "
                + point.describe() + ".");
        warnOutside(sender, arena);
    }

    private void removeEnemySpawn(CommandSender sender, String[] args, String usage) throws ArenaException {
        if (args.length != 2) {
            error(sender, "Usage: " + usage + "removeenemyspawn <name> <number>");
            return;
        }
        int number = parseInt(args[1], "number");
        Arena arena = registry.update(args[0], a -> a.withoutEnemySpawn(number));
        success(sender, "Enemy spawn " + number + " of " + arena.name() + " removed; later ones moved up.");
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return matching(SUBCOMMANDS, args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list") || !SUBCOMMANDS.contains(sub)) {
            return List.of();
        }
        int position = args.length - 2;
        String current = args[args.length - 1];
        if (position == 0) {
            return sub.equals("create") ? List.of() : matching(names(), current);
        }
        Arena arena = registry.get(args[1]).orElse(null);
        return switch (sub) {
            case "edit" -> position == 1 ? matching(List.of("on", "off"), current) : List.of();
            case "setbounds" -> position <= 6 ? coordinate(sender, (position - 1) % 3, current) : List.of();
            case "setwaitingroom", "addenemyspawn" -> position <= 3
                    ? coordinate(sender, position - 1, current) : List.of();
            case "setteamspawn" -> {
                if (position == 1) {
                    int teams = arena == null ? 1 : Math.min(arena.teamSpawns().size() + 1, Arena.MAX_TEAMS);
                    List<String> numbers = new ArrayList<>();
                    for (int team = 1; team <= teams; team++) {
                        numbers.add(Integer.toString(team));
                    }
                    yield matching(numbers, current);
                }
                yield position <= 4 ? coordinate(sender, position - 2, current) : List.of();
            }
            case "removeenemyspawn" -> {
                if (position != 1 || arena == null) {
                    yield List.of();
                }
                List<String> numbers = new ArrayList<>();
                for (int number = 1; number <= arena.enemySpawns().size(); number++) {
                    numbers.add(Integer.toString(number));
                }
                yield matching(numbers, current);
            }
            default -> List.of();
        };
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        for (Arena arena : registry.all()) {
            names.add(arena.name());
        }
        return names;
    }

    /** Suggests the player's own block coordinate on the given axis (0 x, 1 y, 2 z), and {@code ~}. */
    static List<String> coordinate(CommandSender sender, int axis, String current) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        Location location = player.getLocation();
        int value = switch (axis) {
            case 0 -> location.getBlockX();
            case 1 -> location.getBlockY();
            default -> location.getBlockZ();
        };
        return matching(List.of("~", Integer.toString(value)), current);
    }

    static List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }

    private Arena require(String name) throws ArenaException {
        return registry.get(name).orElseThrow(() -> new ArenaException("No arena called " + name + "."));
    }

    private String status(Arena arena) {
        if (registry.isHeld(arena.name())) {
            return "in use by a match";
        }
        if (registry.isEditing(arena.name())) {
            return "in edit mode";
        }
        try {
            int problems = registry.problems(arena.name()).size();
            return problems == 0 ? "ready" : "incomplete (" + problems + (problems == 1 ? " problem)" : " problems)");
        } catch (ArenaException e) {
            return "unknown";
        }
    }

    /** Flags a point that is outside bounds already set, so a mistake shows up straight away. */
    private void warnOutside(CommandSender sender, Arena arena) throws ArenaException {
        if (arena.bounds() == null) {
            return;
        }
        for (String problem : registry.problems(arena.name())) {
            if (problem.contains("outside the bounds")) {
                warn(sender, problem);
            }
        }
    }

    /**
     * The player's location in the arena world, as the base for {@code ~} coordinates, or
     * {@code null} for the console or a player in another world.
     */
    private static Location baseLocation(CommandSender sender) {
        if (sender instanceof Player player && player.getWorld().getName().equals(ArenaWorld.NAME)) {
            return player.getLocation();
        }
        return null;
    }

    /**
     * Reads a position from no arguments (where the player stands), three (x y z) or five
     * (x y z yaw pitch).
     */
    private static ArenaPoint position(CommandSender sender, String[] args, String usage) throws ArenaException {
        Location base = baseLocation(sender);
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                throw new ArenaException("Give coordinates: " + usage);
            }
            if (base == null) {
                throw new ArenaException("Stand in the arena world (" + ArenaWorld.NAME + ") or give coordinates.");
            }
            return new ArenaPoint(base.getX(), base.getY(), base.getZ(), base.getYaw(), base.getPitch());
        }
        if (args.length != 3 && args.length != 5) {
            throw new ArenaException("Usage: " + usage);
        }
        double x = coordinate(args[0], base == null ? null : base.getX(), "x");
        double y = coordinate(args[1], base == null ? null : base.getY(), "y");
        double z = coordinate(args[2], base == null ? null : base.getZ(), "z");
        float yaw = base == null ? 0 : base.getYaw();
        float pitch = base == null ? 0 : base.getPitch();
        if (args.length == 5) {
            yaw = (float) coordinate(args[3], null, "yaw");
            pitch = (float) coordinate(args[4], null, "pitch");
            if (pitch < -90 || pitch > 90) {
                throw new ArenaException("Pitch must be between -90 and 90.");
            }
        }
        return new ArenaPoint(x, y, z, yaw, pitch);
    }

    static double coordinate(String token, Double base, String what) throws ArenaException {
        if (token.startsWith("~")) {
            if (base == null) {
                throw new ArenaException("~ needs a player standing in the arena world; give a number for " + what
                        + ".");
            }
            String offset = token.substring(1);
            return offset.isEmpty() ? base : base + parseDouble(offset, what);
        }
        return parseDouble(token, what);
    }

    static int blockCoordinate(String token, Integer base, String what) throws ArenaException {
        if (token.startsWith("~")) {
            if (base == null) {
                throw new ArenaException("~ needs a player standing in the arena world; give a number for " + what
                        + ".");
            }
            String offset = token.substring(1);
            if (offset.isEmpty()) {
                return base;
            }
            long value = (long) base + parseInt(offset, what);
            if (value != (int) value) {
                throw new ArenaException(capitalize(what) + " is out of range.");
            }
            return (int) value;
        }
        return parseInt(token, what);
    }

    private static double parseDouble(String token, String what) throws ArenaException {
        try {
            double value = Double.parseDouble(token);
            if (!Double.isFinite(value) || Math.abs(value) > 30_000_000) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ArenaException(capitalize(what) + " must be a number, not " + token + ".");
        }
    }

    private static int parseInt(String token, String what) throws ArenaException {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new ArenaException(capitalize(what) + " must be a whole number, not " + token + ".");
        }
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
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
