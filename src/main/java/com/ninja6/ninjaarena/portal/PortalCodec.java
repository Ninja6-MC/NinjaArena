package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Converts the portal list to and from {@code portals.yml}:
 *
 * <pre>
 * format: 1
 * hub: {x: 0.5, y: 64.0, z: 0.5, yaw: 0.0, pitch: 0.0}
 * portals:
 *   lobby:
 *     world: world
 *     arena: dojo
 *     lobby: shared
 *     region: {min-x: 10, min-y: 64, min-z: 10, max-x: 12, max-y: 67, max-z: 10}
 *   built-1:
 *     world: world
 *     built-by: 7a3c1d2e-0000-4000-8000-000000000001
 *     frame: crying_obsidian
 *     region: {min-x: 40, min-y: 64, min-z: 8, max-x: 41, max-y: 66, max-z: 8}
 * </pre>
 *
 * <p>{@code hub} is the shared hub point in the arena world and {@code lobby} a portal's own
 * lobby choice; both are left out while unset, and a file without them reads as before.
 *
 * <p>A player-built portal has {@code built-by} and {@code frame} and no {@code arena},
 * {@code spectator} or {@code lobby}; its region is the opening of its frame. Versions from
 * before player-built portals refuse a file that holds one, as they refuse any portal they
 * cannot read, rather than drop it.
 *
 * <p>Reading is strict: anything that does not decode fails the whole load with the name
 * of the portal, so no portal is ever dropped without the admin seeing why.
 */
public final class PortalCodec {

    /** Bumped whenever the layout changes in a way older readers cannot handle. */
    public static final int FORMAT_VERSION = 1;

    private PortalCodec() {
    }

    /**
     * Everything {@code portals.yml} holds.
     *
     * @param hub the shared hub point, or {@code null} while none is set
     */
    public record Contents(List<Portal> portals, ArenaPoint hub) {

        public Contents {
            portals = List.copyOf(portals);
        }
    }

    public static String encode(Collection<Portal> portals) {
        return encode(portals, null);
    }

    public static String encode(Collection<Portal> portals, ArenaPoint hub) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT_VERSION);
        if (hub != null) {
            ConfigurationSection point = yaml.createSection("hub");
            point.set("x", hub.x());
            point.set("y", hub.y());
            point.set("z", hub.z());
            point.set("yaw", (double) hub.yaw());
            point.set("pitch", (double) hub.pitch());
        }
        ConfigurationSection root = yaml.createSection("portals");
        for (Portal portal : portals) {
            ConfigurationSection section = root.createSection(portal.name());
            section.set("world", portal.world());
            if (portal.playerBuilt()) {
                section.set("built-by", portal.built().owner().toString());
                section.set("frame", portal.built().frame());
            } else {
                section.set("arena", portal.arena());
            }
            if (portal.spectator()) {
                section.set("spectator", true);
            }
            if (portal.lobby() != null) {
                section.set("lobby", portal.lobby().id());
            }
            ArenaBounds region = portal.region();
            section.set("region.min-x", region.minX());
            section.set("region.min-y", region.minY());
            section.set("region.min-z", region.minZ());
            section.set("region.max-x", region.maxX());
            section.set("region.max-y", region.maxY());
            section.set("region.max-z", region.maxZ());
        }
        return yaml.saveToString();
    }

    public static List<Portal> decode(String text) throws PortalFormatException {
        return decodeAll(text).portals();
    }

    public static Contents decodeAll(String text) throws PortalFormatException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new PortalFormatException("not valid YAML", e);
        }
        if (!yaml.isInt("format")) {
            throw new PortalFormatException("missing format");
        }
        int format = yaml.getInt("format");
        if (format != FORMAT_VERSION) {
            throw new PortalFormatException("unsupported format " + format);
        }
        ArenaPoint hub = null;
        if (yaml.contains("hub")) {
            ConfigurationSection point = yaml.getConfigurationSection("hub");
            if (point == null) {
                throw new PortalFormatException("hub is not a section");
            }
            try {
                hub = new ArenaPoint(requireDouble(point, "x"), requireDouble(point, "y"), requireDouble(point, "z"),
                        (float) requireDouble(point, "yaw"), (float) requireDouble(point, "pitch"));
            } catch (PortalFormatException | IllegalArgumentException e) {
                throw new PortalFormatException("hub: " + e.getMessage(), e);
            }
        }
        List<Portal> portals = new ArrayList<>();
        if (!yaml.contains("portals")) {
            return new Contents(portals, hub);
        }
        ConfigurationSection root = yaml.getConfigurationSection("portals");
        if (root == null) {
            throw new PortalFormatException("portals is not a section");
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                throw new PortalFormatException("portal " + name + " is not a section");
            }
            Portal portal;
            try {
                portal = decodePortal(name, section);
            } catch (PortalFormatException | IllegalArgumentException e) {
                throw new PortalFormatException("portal " + name + ": " + e.getMessage(), e);
            }
            for (Portal other : portals) {
                if (other.overlaps(portal)) {
                    throw new PortalFormatException("portal " + name + " overlaps portal " + other.name());
                }
            }
            portals.add(portal);
        }
        return new Contents(portals, hub);
    }

    private static Portal decodePortal(String name, ConfigurationSection section) throws PortalFormatException {
        if (!Portal.isValidName(name)) {
            throw new PortalFormatException("invalid portal name " + name);
        }
        ArenaBounds region = new ArenaBounds(
                requireInt(section, "region.min-x"),
                requireInt(section, "region.min-y"),
                requireInt(section, "region.min-z"),
                requireInt(section, "region.max-x"),
                requireInt(section, "region.max-y"),
                requireInt(section, "region.max-z"));
        if (section.contains("spectator") && !section.isBoolean("spectator")) {
            throw new PortalFormatException("spectator must be a boolean");
        }
        LobbyMode lobby = null;
        if (section.contains("lobby")) {
            lobby = LobbyMode.parse(section.getString("lobby"))
                    .orElseThrow(() -> new PortalFormatException("lobby must be dedicated or shared"));
        }
        if (section.contains("built-by")) {
            if (section.contains("arena") || section.contains("spectator") || section.contains("lobby")) {
                throw new PortalFormatException("a player-built portal has no arena, spectator or lobby");
            }
            UUID owner;
            try {
                owner = UUID.fromString(requireString(section, "built-by"));
            } catch (IllegalArgumentException e) {
                throw new PortalFormatException("built-by is not a player id", e);
            }
            return Portal.built(name, requireString(section, "world"), region,
                    new Portal.Built(owner, requireString(section, "frame")));
        }
        return new Portal(name, requireString(section, "world"), region, requireString(section, "arena"),
                section.getBoolean("spectator"), lobby);
    }

    private static double requireDouble(ConfigurationSection section, String path) throws PortalFormatException {
        // A hand-edited 64 reads back as an int, 64.5 as a double.
        if (!section.isDouble(path) && !section.isInt(path) && !section.isLong(path)) {
            throw new PortalFormatException("missing " + path);
        }
        return section.getDouble(path);
    }

    private static int requireInt(ConfigurationSection section, String path) throws PortalFormatException {
        if (!section.isInt(path)) {
            throw new PortalFormatException("missing " + path);
        }
        return section.getInt(path);
    }

    private static String requireString(ConfigurationSection section, String path) throws PortalFormatException {
        if (!section.isString(path)) {
            throw new PortalFormatException("missing " + path);
        }
        return section.getString(path);
    }

    /** A {@code portals.yml} that cannot be read back. */
    public static final class PortalFormatException extends IOException {

        private static final long serialVersionUID = 1L;

        public PortalFormatException(String message) {
            super(message);
        }

        public PortalFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
