package com.ninja6.ninjaarena.message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Carries the deprecated {@code events.messages} texts of config.yml into a newly saved
 * messages.yml. Only texts that differ from the old defaults are moved; the old
 * {@code {name}} placeholders become MiniMessage tags.
 */
public final class MessageMigration {

    /** The old config.yml defaults, by key under {@code events.messages}. */
    static final Map<String, String> OLD_DEFAULTS = Map.of(
            "upcoming", "Arena event {id} starts in {minutes} minute(s) at {arena}.",
            "start", "Arena event {id} has started at {arena}.");

    private static final String SECTION = "event";

    private MessageMigration() {
    }

    /**
     * Rewrites {@code event.upcoming} and {@code event.start} in {@code file} from
     * {@code config}. The file keeps its comments and layout; only those lines change.
     *
     * @return the config.yml paths that were moved
     */
    public static List<String> migrate(Path file, ConfigurationSection config, Consumer<String> log)
            throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        List<String> moved = new ArrayList<>();
        for (String name : List.of("upcoming", "start")) {
            String path = "events.messages." + name;
            String old = config.getString(path);
            if (old == null || old.equals(OLD_DEFAULTS.get(name))) {
                continue;
            }
            if (!replace(lines, name, quote("<gold>" + toMiniMessage(old)))) {
                log.accept("Could not move " + path + " into " + Messages.FILE + ": " + SECTION + "." + name
                        + " was not found. Set it there by hand.");
                continue;
            }
            moved.add(path);
            log.accept("Moved " + path + " from config.yml to " + Messages.FILE + " as " + SECTION + "." + name
                    + ". The config.yml setting is no longer read.");
        }
        if (!moved.isEmpty()) {
            Files.write(file, lines, StandardCharsets.UTF_8);
        }
        return moved;
    }

    /** Escapes text that was never MiniMessage, then turns {@code {name}} into {@code <name>}. */
    static String toMiniMessage(String old) {
        return MiniMessage.miniMessage().escapeTags(old)
                .replace("{id}", "<id>").replace("{arena}", "<arena>").replace("{minutes}", "<minutes>");
    }

    private static boolean replace(List<String> lines, String name, String value) {
        boolean inSection = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")) {
                inSection = line.equals(SECTION + ":");
            } else if (inSection && line.startsWith("  " + name + ":")) {
                lines.set(i, "  " + name + ": " + value);
                return true;
            }
        }
        return false;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
