package com.ninja6.ninjaarena.lobby;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Where the bundled lobby build stands in the lobby world and the spots marked in it, read
 * from {@value #SPEC}. Every position here is a lobby-world coordinate: the file's relative
 * coordinates are moved by its {@code origin}.
 *
 * @param build    the blocks the bundled structure covers
 * @param bounds   where lobby members may be; it holds the build
 * @param spawn    where members arrive and where a fall or a walk out of bounds returns them
 * @param roomArea where rooms are shown
 * @param shopArea where the shop stands
 * @param boards   board positions by name, in file order
 */
public record LobbyLayout(ArenaBounds build, ArenaBounds bounds, ArenaPoint spawn, ArenaBounds roomArea,
        ArenaBounds shopArea, Map<String, ArenaPoint> boards) {

    /** The readable spec inside the plugin JAR. */
    public static final String SPEC = "lobby/obsidian-lobby.yml";
    /** The structure generated from {@link #SPEC}. */
    public static final String STRUCTURE = "lobby/obsidian-lobby.nbt";

    public LobbyLayout {
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(spawn, "spawn");
        Objects.requireNonNull(roomArea, "roomArea");
        Objects.requireNonNull(shopArea, "shopArea");
        boards = Collections.unmodifiableMap(new LinkedHashMap<>(boards));
        if (!bounds.contains(build)) {
            throw new IllegalArgumentException("the lobby bounds must hold the whole build");
        }
        if (!build.contains(spawn) || !build.contains(roomArea) || !build.contains(shopArea)) {
            throw new IllegalArgumentException("the spawn, room area and shop area must be inside the build");
        }
        boards.forEach((name, point) -> {
            if (!build.contains(point)) {
                throw new IllegalArgumentException("board " + name + " must be inside the build");
            }
        });
    }

    /** The layout of the build bundled in the plugin JAR. */
    public static LobbyLayout bundled() {
        try (InputStream in = LobbyLayout.class.getClassLoader().getResourceAsStream(SPEC)) {
            if (in == null) {
                throw new IllegalStateException(SPEC + " is missing from the plugin JAR");
            }
            return read(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + SPEC, e);
        }
    }

    /** Reads a spec in the format of {@value #SPEC}. */
    public static LobbyLayout read(Reader reader) throws IOException {
        YamlConfiguration spec = new YamlConfiguration();
        try {
            spec.load(reader);
        } catch (InvalidConfigurationException e) {
            throw new IOException("unreadable lobby spec: " + e.getMessage(), e);
        }
        return of(spec);
    }

    /** Builds the layout from a parsed spec. */
    public static LobbyLayout of(ConfigurationSection spec) {
        int[] origin = triple(spec, "origin");
        int[] size = triple(spec, "size");
        if (size[0] < 1 || size[1] < 1 || size[2] < 1) {
            throw new IllegalArgumentException("size must be at least 1 on each axis");
        }
        ArenaBounds build = new ArenaBounds(origin[0], origin[1], origin[2],
                origin[0] + size[0] - 1, origin[1] + size[1] - 1, origin[2] + size[2] - 1);
        ConfigurationSection marks = section(spec, "marks");
        ConfigurationSection boardSection = section(marks, "boards");
        Map<String, ArenaPoint> boards = new LinkedHashMap<>();
        for (String name : boardSection.getKeys(false)) {
            boards.put(name, point(section(boardSection, name), origin));
        }
        return new LobbyLayout(build, box(section(spec, "bounds"), origin), point(section(marks, "spawn"), origin),
                box(section(marks, "room-area"), origin), box(section(marks, "shop-area"), origin), boards);
    }

    /** Whether a location is inside the lobby bounds, in the lobby world. */
    public boolean contains(Location location) {
        return PluginWorlds.isLobby(location)
                && bounds.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** The spawn in the given world. */
    public Location spawn(World world) {
        return new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            throw new IllegalArgumentException(path(parent, key) + " must be a section");
        }
        return section;
    }

    private static ArenaBounds box(ConfigurationSection section, int[] origin) {
        int[] from = triple(section, "from");
        int[] to = triple(section, "to");
        return new ArenaBounds(origin[0] + from[0], origin[1] + from[1], origin[2] + from[2],
                origin[0] + to[0], origin[1] + to[1], origin[2] + to[2]);
    }

    private static ArenaPoint point(ConfigurationSection section, int[] origin) {
        List<?> at = section.getList("at");
        if (at == null || at.size() != 3 || !at.stream().allMatch(Number.class::isInstance)) {
            throw new IllegalArgumentException(path(section, "at") + " must be three numbers");
        }
        return new ArenaPoint(origin[0] + ((Number) at.get(0)).doubleValue(),
                origin[1] + ((Number) at.get(1)).doubleValue(), origin[2] + ((Number) at.get(2)).doubleValue(),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    /** Three whole numbers, such as a size or a block position. */
    static int[] triple(ConfigurationSection section, String key) {
        List<?> values = section.getList(key);
        if (values == null || values.size() != 3 || !values.stream().allMatch(Integer.class::isInstance)) {
            throw new IllegalArgumentException(path(section, key) + " must be three whole numbers");
        }
        return new int[] {(Integer) values.get(0), (Integer) values.get(1), (Integer) values.get(2)};
    }

    private static String path(ConfigurationSection section, String key) {
        return section.getCurrentPath() == null || section.getCurrentPath().isEmpty()
                ? key : section.getCurrentPath() + "." + key;
    }
}
