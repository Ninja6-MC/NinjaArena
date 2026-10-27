package com.ninja6.ninjaarena.arena;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Converts the arena list to and from {@code arenas.yml}. The layout stays readable so an
 * admin can check it by hand:
 *
 * <pre>
 * format: 1
 * arenas:
 *   dojo:
 *     cell: {x: 0, z: 0}
 *     bounds: {min-x: 10, min-y: 60, min-z: 10, max-x: 90, max-y: 100, max-z: 90}
 *     waiting-room: {x: 50.5, y: 64.0, z: 50.5, yaw: 0.0, pitch: 0.0}
 *     team-spawns: [{x: ..., y: ..., z: ..., yaw: ..., pitch: ...}, ...]
 *     enemy-spawns: [...]
 * </pre>
 */
public final class ArenaCodec {

    /** Bumped whenever the layout changes in a way older readers cannot handle. */
    public static final int FORMAT_VERSION = 1;

    private ArenaCodec() {
    }

    public static String encode(Collection<Arena> arenas) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT_VERSION);
        ConfigurationSection root = yaml.createSection("arenas");
        for (Arena arena : arenas) {
            ConfigurationSection section = root.createSection(arena.name());
            section.set("cell.x", arena.cell().x());
            section.set("cell.z", arena.cell().z());
            ArenaBounds bounds = arena.bounds();
            if (bounds != null) {
                section.set("bounds.min-x", bounds.minX());
                section.set("bounds.min-y", bounds.minY());
                section.set("bounds.min-z", bounds.minZ());
                section.set("bounds.max-x", bounds.maxX());
                section.set("bounds.max-y", bounds.maxY());
                section.set("bounds.max-z", bounds.maxZ());
            }
            if (arena.waitingRoom() != null) {
                section.set("waiting-room", encodePoint(arena.waitingRoom()));
            }
            section.set("team-spawns", encodePoints(arena.teamSpawns()));
            section.set("enemy-spawns", encodePoints(arena.enemySpawns()));
        }
        return yaml.saveToString();
    }

    public static List<Arena> decode(String text) throws ArenaFormatException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new ArenaFormatException("not valid YAML", e);
        }
        int format = requireInt(yaml, "format");
        if (format != FORMAT_VERSION) {
            throw new ArenaFormatException("unsupported format " + format);
        }
        List<Arena> arenas = new ArrayList<>();
        ConfigurationSection root = yaml.getConfigurationSection("arenas");
        if (root == null) {
            return arenas;
        }
        Set<GridCell> cells = new HashSet<>();
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                throw new ArenaFormatException("arena " + name + " is not a section");
            }
            Arena arena;
            try {
                arena = decodeArena(name, section);
            } catch (IllegalArgumentException e) {
                throw new ArenaFormatException("arena " + name + ": " + e.getMessage(), e);
            }
            if (!cells.add(arena.cell())) {
                throw new ArenaFormatException("arena " + name + " shares cell " + arena.cell().describe());
            }
            arenas.add(arena);
        }
        return arenas;
    }

    private static Arena decodeArena(String name, ConfigurationSection section) throws ArenaFormatException {
        if (!Arena.isValidName(name)) {
            throw new ArenaFormatException("invalid arena name " + name);
        }
        GridCell cell = new GridCell(requireInt(section, "cell.x"), requireInt(section, "cell.z"));
        ArenaBounds bounds = null;
        if (section.contains("bounds")) {
            bounds = new ArenaBounds(
                    requireInt(section, "bounds.min-x"),
                    requireInt(section, "bounds.min-y"),
                    requireInt(section, "bounds.min-z"),
                    requireInt(section, "bounds.max-x"),
                    requireInt(section, "bounds.max-y"),
                    requireInt(section, "bounds.max-z"));
        }
        ArenaPoint waitingRoom = null;
        if (section.contains("waiting-room")) {
            ConfigurationSection room = section.getConfigurationSection("waiting-room");
            if (room == null) {
                throw new ArenaFormatException("waiting-room is not a section");
            }
            waitingRoom = decodePoint(room);
        }
        return new Arena(name, cell, bounds, waitingRoom,
                decodePoints(section, "team-spawns"), decodePoints(section, "enemy-spawns"));
    }

    private static Map<String, Object> encodePoint(ArenaPoint point) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", point.x());
        map.put("y", point.y());
        map.put("z", point.z());
        map.put("yaw", (double) point.yaw());
        map.put("pitch", (double) point.pitch());
        return map;
    }

    private static List<Map<String, Object>> encodePoints(List<ArenaPoint> points) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ArenaPoint point : points) {
            list.add(encodePoint(point));
        }
        return list;
    }

    private static List<ArenaPoint> decodePoints(ConfigurationSection section, String path)
            throws ArenaFormatException {
        if (!section.contains(path)) {
            return List.of();
        }
        if (!section.isList(path)) {
            throw new ArenaFormatException(path + " is not a list");
        }
        List<ArenaPoint> points = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList(path)) {
            YamlConfiguration holder = new YamlConfiguration();
            points.add(decodePoint(holder.createSection("p", raw)));
        }
        return points;
    }

    private static ArenaPoint decodePoint(ConfigurationSection section) throws ArenaFormatException {
        return new ArenaPoint(
                requireDouble(section, "x"),
                requireDouble(section, "y"),
                requireDouble(section, "z"),
                (float) requireDouble(section, "yaw"),
                (float) requireDouble(section, "pitch"));
    }

    private static int requireInt(ConfigurationSection section, String path) throws ArenaFormatException {
        if (!section.isInt(path)) {
            throw new ArenaFormatException("missing " + path);
        }
        return section.getInt(path);
    }

    private static double requireDouble(ConfigurationSection section, String path)
            throws ArenaFormatException {
        // A hand-edited 64 reads back as an int, 64.5 as a double.
        if (!section.isDouble(path) && !section.isInt(path) && !section.isLong(path)) {
            throw new ArenaFormatException("missing " + path);
        }
        return section.getDouble(path);
    }

    /** An {@code arenas.yml} that cannot be read back. */
    public static final class ArenaFormatException extends IOException {

        private static final long serialVersionUID = 1L;

        public ArenaFormatException(String message) {
            super(message);
        }

        public ArenaFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
