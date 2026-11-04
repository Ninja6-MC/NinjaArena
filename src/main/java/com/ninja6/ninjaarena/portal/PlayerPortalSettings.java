package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code player-portals} section of {@code config.yml}: where players may build portals,
 * from what, how many and how close together, and the colour of their surface.
 *
 * @param worlds       worlds players may build in; empty turns building off. The arena world
 *                     is never one of them
 * @param frame        the frame block of new portals
 * @param maxPerPlayer portals one player may own at once; 0 for no limit
 * @param minDistance  the least distance between the centre of a new portal and that of any
 *                     other portal in its world, in blocks; 0 for none
 * @param color        the surface colour as {@code 0xRRGGBB}
 */
public record PlayerPortalSettings(Set<String> worlds, Material frame, int maxPerPlayer, int minDistance, int color) {

    public static final String SECTION = "player-portals";
    public static final int MAX_PER_PLAYER = 1000;
    public static final int MAX_DISTANCE = 1024;

    public static final PlayerPortalSettings DEFAULTS = new PlayerPortalSettings(Set.of("world"),
            Material.CRYING_OBSIDIAN, 2, 32, 0x40E0D0);

    /** No building anywhere; portals already built still work. */
    public static final PlayerPortalSettings OFF = new PlayerPortalSettings(Set.of(), DEFAULTS.frame(),
            DEFAULTS.maxPerPlayer(), DEFAULTS.minDistance(), DEFAULTS.color());

    public PlayerPortalSettings {
        worlds = Set.copyOf(new TreeSet<>(Objects.requireNonNull(worlds, "worlds")));
        Objects.requireNonNull(frame, "frame");
        if (maxPerPlayer < 0 || maxPerPlayer > MAX_PER_PLAYER) {
            throw new IllegalArgumentException(SECTION + ".max-per-player must be from 0 to " + MAX_PER_PLAYER);
        }
        if (minDistance < 0 || minDistance > MAX_DISTANCE) {
            throw new IllegalArgumentException(SECTION + ".min-distance must be from 0 to " + MAX_DISTANCE);
        }
        if ((color & ~0xFFFFFF) != 0) {
            throw new IllegalArgumentException(SECTION + ".color must be an RGB colour");
        }
    }

    /** Whether players may build in the world. */
    public boolean allows(String world) {
        return !world.equals(ArenaWorld.NAME) && worlds.contains(world);
    }

    /** The frame block as stored with each portal. */
    public String frameName() {
        return frame.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads the section; a missing section or key takes its default.
     *
     * @throws IllegalArgumentException naming the setting that is wrong
     */
    public static PlayerPortalSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        Object worlds = section.get("worlds", List.copyOf(DEFAULTS.worlds()));
        String worldProblem = worldsProblem(worlds);
        if (worldProblem != null) {
            throw new IllegalArgumentException(SECTION + ".worlds: " + worldProblem);
        }
        List<String> names = new ArrayList<>();
        for (Object world : (List<?>) worlds) {
            names.add((String) world);
        }
        String frame = text(section, "frame-material", DEFAULTS.frameName());
        String color = text(section, "color", hex(DEFAULTS.color()));
        Object max = section.get("max-per-player", DEFAULTS.maxPerPlayer());
        Object distance = section.get("min-distance", DEFAULTS.minDistance());
        if (!(max instanceof Integer perPlayer) || !(distance instanceof Integer apart)) {
            throw new IllegalArgumentException(SECTION + ".max-per-player and min-distance must be integers");
        }
        return new PlayerPortalSettings(Set.copyOf(names), Material.matchMaterial(frame), perPlayer, apart,
                Integer.parseInt(color.substring(1), 16));
    }

    private static String text(ConfigurationSection section, String path, String fallback) {
        Object value = section.get(path, fallback);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(SECTION + "." + path + ": expected text");
        }
        String problem = problem(SECTION + "." + path, text);
        if (problem != null) {
            throw new IllegalArgumentException(SECTION + "." + path + ": " + problem);
        }
        return text;
    }

    /** {@code #RRGGBB}. */
    public static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color);
    }

    /**
     * What is wrong with a text setting under {@code player-portals}, or {@code null} when it
     * is valid. Used by both startup and the reload validation.
     *
     * @throws IllegalStateException for a path that is not a text setting
     */
    public static String problem(String path, String value) {
        return switch (path) {
            case SECTION + ".frame-material" -> frameProblem(value);
            case SECTION + ".color" -> value.matches("#[0-9A-Fa-f]{6}") ? null : "expected a colour such as #40E0D0";
            default -> throw new IllegalStateException("No validation for " + path);
        };
    }

    /** What is wrong with the {@code worlds} list, or {@code null} when it is valid. */
    public static String worldsProblem(Object value) {
        if (!(value instanceof List<?> list)) {
            return "expected a list of world names";
        }
        for (Object world : list) {
            if (!(world instanceof String name) || name.isBlank() || name.length() > 64
                    || name.chars().anyMatch(Character::isISOControl)) {
                return "expected a list of world names";
            }
            if (name.equals(ArenaWorld.NAME)) {
                return "the arena world cannot hold player-built portals";
            }
        }
        return null;
    }

    private static String frameProblem(String value) {
        Material material = Material.matchMaterial(value);
        if (material == null || !material.isBlock() || !material.isSolid()) {
            return "expected a solid block";
        }
        if (material == Material.OBSIDIAN) {
            return "obsidian would take over nether portals; choose another block";
        }
        return null;
    }
}
