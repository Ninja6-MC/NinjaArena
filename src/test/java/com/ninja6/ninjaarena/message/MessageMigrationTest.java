package com.ninja6.ninjaarena.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageMigrationTest {

    @TempDir Path dir;
    private Path file;
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void bundledCopy() throws IOException {
        file = dir.resolve(Messages.FILE);
        try (InputStream bundled = getClass().getClassLoader().getResourceAsStream(Messages.FILE)) {
            Files.copy(bundled, file);
        }
    }

    private static YamlConfiguration config(String upcoming, String start) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("events.messages.upcoming", upcoming);
        config.set("events.messages.start", start);
        return config;
    }

    @Test
    void customisedEventTextsMoveIntoMessagesWithNamedPlaceholders() throws IOException {
        String before = Files.readString(file);
        List<String> moved = MessageMigration.migrate(file, config(
                "Ninja night {id} in {minutes} min at {arena} <b>\"sharp\"</b> \\o/",
                MessageMigration.OLD_DEFAULTS.get("start")), log::add);

        assertEquals(List.of("events.messages.upcoming"), moved);
        assertEquals(1, log.size());
        assertTrue(log.getFirst().contains("events.messages.upcoming"), log.getFirst());
        Messages messages = Messages.read(file, message -> {
            throw new AssertionError(message);
        });
        Component upcoming = messages.render("event.upcoming", "id", "friday", "arena", "dojo", "minutes", 10);
        assertEquals("Ninja night friday in 10 min at dojo <b>\"sharp\"</b> \\o/",
                PlainTextComponentSerializer.plainText().serialize(upcoming));
        assertEquals(NamedTextColor.GOLD, upcoming.color());
        assertEquals("Arena event friday has started at dojo.",
                messages.plain("event.start", "id", "friday", "arena", "dojo"));
        // Only the one line changed; comments and every other text are kept.
        List<String> was = before.lines().toList();
        List<String> now = Files.readString(file).lines().toList();
        assertEquals(was.size(), now.size());
        int changed = 0;
        for (int i = 0; i < was.size(); i++) {
            if (!was.get(i).equals(now.get(i))) {
                changed++;
                assertTrue(now.get(i).startsWith("  upcoming: "), now.get(i));
            }
        }
        assertEquals(1, changed);
    }

    @Test
    void defaultOrMissingEventTextsLeaveTheFileAlone() throws IOException {
        String before = Files.readString(file);
        assertEquals(List.of(), MessageMigration.migrate(file, config(MessageMigration.OLD_DEFAULTS.get("upcoming"),
                MessageMigration.OLD_DEFAULTS.get("start")), log::add));
        assertEquals(List.of(), MessageMigration.migrate(file, new YamlConfiguration(), log::add));
        assertEquals(before, Files.readString(file));
        assertEquals(List.of(), log);
    }

    @Test
    void theBundledEventDefaultsMatchTheOldConfigDefaults() {
        assertEquals(MessageMigration.OLD_DEFAULTS.get("upcoming").replace("{id}", "x").replace("{minutes}", "5")
                .replace("{arena}", "dojo"), Messages.bundled().plain("event.upcoming", "id", "x", "minutes", 5,
                "arena", "dojo"));
        assertEquals(MessageMigration.OLD_DEFAULTS.get("start").replace("{id}", "x").replace("{arena}", "dojo"),
                Messages.bundled().plain("event.start", "id", "x", "arena", "dojo"));
    }
}
