package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Converts the portal list to and from {@code portals.yml}:
 *
 * <pre>
 * format: 1
 * portals:
 *   lobby:
 *     world: world
 *     arena: dojo
 *     region: {min-x: 10, min-y: 64, min-z: 10, max-x: 12, max-y: 67, max-z: 10}
 * </pre>
 *
 * <p>Reading is strict: anything that does not decode fails the whole load with the name
 * of the portal, so no portal is ever dropped without the admin seeing why.
 */
public final class PortalCodec {

    /** Bumped whenever the layout changes in a way older readers cannot handle. */
    public static final int FORMAT_VERSION = 1;

    private PortalCodec() {
    }

    public static String encode(Collection<Portal> portals) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT_VERSION);
        ConfigurationSection root = yaml.createSection("portals");
        for (Portal portal : portals) {
            ConfigurationSection section = root.createSection(portal.name());
            section.set("world", portal.world());
            section.set("arena", portal.arena());
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
        List<Portal> portals = new ArrayList<>();
        if (!yaml.contains("portals")) {
            return portals;
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
        return portals;
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
        return new Portal(name, requireString(section, "world"), region, requireString(section, "arena"));
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
