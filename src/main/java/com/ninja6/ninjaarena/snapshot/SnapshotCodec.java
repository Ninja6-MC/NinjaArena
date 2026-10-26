package com.ninja6.ninjaarena.snapshot;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Converts a {@link PlayerSnapshot} to and from the YAML stored on disk. Item data is
 * Base64 inside the YAML; everything else stays readable so an admin can inspect a
 * snapshot by hand.
 */
public final class SnapshotCodec {

    /** Bumped whenever the layout changes in a way older readers cannot handle. */
    public static final int FORMAT_VERSION = 1;

    private SnapshotCodec() {
    }

    public static String encode(PlayerSnapshot snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT_VERSION);
        yaml.set("player.id", snapshot.playerId().toString());
        yaml.set("player.name", snapshot.playerName());
        yaml.set("captured-at", snapshot.capturedAt().toString());

        SnapshotLocation location = snapshot.location();
        yaml.set("location.world-id", location.worldId().toString());
        yaml.set("location.world-name", location.worldName());
        yaml.set("location.x", location.x());
        yaml.set("location.y", location.y());
        yaml.set("location.z", location.z());
        yaml.set("location.yaw", (double) location.yaw());
        yaml.set("location.pitch", (double) location.pitch());

        Base64.Encoder base64 = Base64.getEncoder();
        yaml.set("inventory.storage", base64.encodeToString(snapshot.storage()));
        yaml.set("inventory.armour", base64.encodeToString(snapshot.armour()));
        yaml.set("inventory.offhand", base64.encodeToString(snapshot.offhand()));

        yaml.set("experience.level", snapshot.level());
        yaml.set("experience.progress", (double) snapshot.exp());
        yaml.set("experience.total", snapshot.totalExperience());

        yaml.set("health", snapshot.health());
        yaml.set("food.level", snapshot.foodLevel());
        yaml.set("food.saturation", (double) snapshot.saturation());
        yaml.set("food.exhaustion", (double) snapshot.exhaustion());

        List<Map<String, Object>> effects = new ArrayList<>();
        for (SnapshotEffect effect : snapshot.effects()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", effect.type());
            entry.put("duration", effect.duration());
            entry.put("amplifier", effect.amplifier());
            entry.put("ambient", effect.ambient());
            entry.put("particles", effect.particles());
            entry.put("icon", effect.icon());
            effects.add(entry);
        }
        yaml.set("effects", effects);

        yaml.set("game-mode", snapshot.gameMode().name());
        yaml.set("fire-ticks", snapshot.fireTicks());
        yaml.set("fall-distance", (double) snapshot.fallDistance());
        return yaml.saveToString();
    }

    public static PlayerSnapshot decode(String text) throws SnapshotFormatException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new SnapshotFormatException("not valid YAML", e);
        }
        int format = requireInt(yaml, "format");
        if (format != FORMAT_VERSION) {
            throw new SnapshotFormatException("unsupported format " + format);
        }
        try {
            SnapshotLocation location = new SnapshotLocation(
                    UUID.fromString(requireString(yaml, "location.world-id")),
                    requireString(yaml, "location.world-name"),
                    requireDouble(yaml, "location.x"),
                    requireDouble(yaml, "location.y"),
                    requireDouble(yaml, "location.z"),
                    (float) requireDouble(yaml, "location.yaw"),
                    (float) requireDouble(yaml, "location.pitch"));

            Base64.Decoder base64 = Base64.getDecoder();
            return new PlayerSnapshot(
                    UUID.fromString(requireString(yaml, "player.id")),
                    requireString(yaml, "player.name"),
                    Instant.parse(requireString(yaml, "captured-at")),
                    location,
                    base64.decode(requireString(yaml, "inventory.storage")),
                    base64.decode(requireString(yaml, "inventory.armour")),
                    base64.decode(requireString(yaml, "inventory.offhand")),
                    requireInt(yaml, "experience.level"),
                    (float) requireDouble(yaml, "experience.progress"),
                    requireInt(yaml, "experience.total"),
                    requireDouble(yaml, "health"),
                    requireInt(yaml, "food.level"),
                    (float) requireDouble(yaml, "food.saturation"),
                    (float) requireDouble(yaml, "food.exhaustion"),
                    decodeEffects(yaml),
                    GameMode.valueOf(requireString(yaml, "game-mode")),
                    requireInt(yaml, "fire-ticks"),
                    (float) requireDouble(yaml, "fall-distance"));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new SnapshotFormatException("malformed value: " + e.getMessage(), e);
        }
    }

    private static List<SnapshotEffect> decodeEffects(YamlConfiguration yaml)
            throws SnapshotFormatException {
        if (!yaml.isList("effects")) {
            throw new SnapshotFormatException("missing effects");
        }
        List<SnapshotEffect> effects = new ArrayList<>();
        for (Map<?, ?> raw : yaml.getMapList("effects")) {
            YamlConfiguration entry = new YamlConfiguration();
            ConfigurationSection section = entry.createSection("e", raw);
            effects.add(new SnapshotEffect(
                    requireString(section, "type"),
                    requireInt(section, "duration"),
                    requireInt(section, "amplifier"),
                    requireBoolean(section, "ambient"),
                    requireBoolean(section, "particles"),
                    requireBoolean(section, "icon")));
        }
        return effects;
    }

    private static String requireString(ConfigurationSection section, String path)
            throws SnapshotFormatException {
        if (!section.isString(path)) {
            throw new SnapshotFormatException("missing " + path);
        }
        return section.getString(path);
    }

    private static int requireInt(ConfigurationSection section, String path)
            throws SnapshotFormatException {
        if (!section.isInt(path)) {
            throw new SnapshotFormatException("missing " + path);
        }
        return section.getInt(path);
    }

    private static double requireDouble(ConfigurationSection section, String path)
            throws SnapshotFormatException {
        // YAML writes 20.0 as a double but a hand-edited 20 reads back as an int.
        if (!section.isDouble(path) && !section.isInt(path) && !section.isLong(path)) {
            throw new SnapshotFormatException("missing " + path);
        }
        return section.getDouble(path);
    }

    private static boolean requireBoolean(ConfigurationSection section, String path)
            throws SnapshotFormatException {
        if (!section.isBoolean(path)) {
            throw new SnapshotFormatException("missing " + path);
        }
        return section.getBoolean(path);
    }

    /** A snapshot file that cannot be read back. */
    public static final class SnapshotFormatException extends IOException {

        private static final long serialVersionUID = 1L;

        public SnapshotFormatException(String message) {
            super(message);
        }

        public SnapshotFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
