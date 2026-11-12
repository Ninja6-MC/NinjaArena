package com.ninja6.ninjaarena.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.config.ReloadFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MessagesTest {

    private final List<String> warnings = new ArrayList<>();

    private Messages with(String key, Object value) {
        Map<String, Object> values = new LinkedHashMap<>(Messages.defaults());
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        return Messages.of(values, warnings::add);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void aCustomTextIsUsedWithItsPlaceholders() {
        Messages messages = with("waiting.joined", "<aqua>Team <team> of <arena>!");
        Component joined = messages.render("waiting.joined", "arena", "dojo", "team", 2);
        assertEquals("Team 2 of dojo!", plain(joined));
        assertEquals(NamedTextColor.AQUA, joined.color());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void aMissingKeyFallsBackToTheDefaultWithOneWarning() {
        Messages messages = with("waiting.joined", null);
        assertEquals("You joined arena dojo on team 1.", messages.plain("waiting.joined", "arena", "dojo", "team", 1));
        assertEquals("You joined arena dojo on team 2.", messages.plain("waiting.joined", "arena", "dojo", "team", 2));
        assertEquals(List.of("messages.yml: waiting.joined is missing; using the default."), warnings);
    }

    @Test
    void aBrokenKeyFallsBackToTheDefaultWithOneWarning() {
        Messages messages = with("match.fight", "<red>Fight <colour:nope>now");
        assertEquals("Fight!", messages.plain("match.fight"));
        assertEquals("Fight!", messages.plain("match.fight"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("messages.yml: match.fight has a tag MiniMessage cannot read"),
                warnings.getFirst());
    }

    @Test
    void anUnknownPlaceholderOrAValueThatIsNotTextFallsBack() {
        Messages unknown = with("waiting.joined", "Joined <arenaa>.");
        assertEquals("You joined arena dojo on team 1.", unknown.plain("waiting.joined", "arena", "dojo", "team", 1));
        Messages list = with("match.fight", List.of("Fight!"));
        assertEquals("Fight!", list.plain("match.fight"));
        assertEquals(2, warnings.size());
        assertEquals("messages.yml: match.fight is not text; using the default.", warnings.getLast());
    }

    @Test
    void anEscapedTagIsShownAsTextAndIsNotAnError() {
        Messages messages = with("match.fight", "Type \\<team> to fight!");
        assertEquals("Type <team> to fight!", messages.plain("match.fight"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void userInputIsInsertedAsPlainTextNeverAsTags() {
        String name = "<red><click:run_command:'/op Mallory'><bold>Mallory";
        Component message = Messages.bundled().render("hosting.no-player", "player", name);
        assertEquals("No online player called " + name + ".", plain(message));
        assertNoClicks(message);
        assertEquals(NamedTextColor.YELLOW, message.color());
        assertFalse(message.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE);
        message.children().forEach(child -> assertFalse(child.decoration(TextDecoration.BOLD)
                == TextDecoration.State.TRUE));
    }

    private static void assertNoClicks(Component component) {
        assertNull(component.clickEvent());
        assertNull(component.hoverEvent());
        component.children().forEach(MessagesTest::assertNoClicks);
    }

    @Test
    void readsAServerFileAndComparesByContent(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path file = dir.resolve(Messages.FILE);
        Files.writeString(file, "match:\n  fight: \"<gold>Go!\"\n");
        Messages read = Messages.read(file, warnings::add);
        assertEquals("Go!", read.plain("match.fight"));
        assertEquals(read, Messages.read(file, warnings::add));
        assertFalse(read.equals(Messages.bundled()));
        Files.writeString(file, "match: [\n");
        try {
            Messages.read(file, warnings::add);
            throw new AssertionError("invalid YAML was read");
        } catch (IOException expected) {
            assertTrue(expected.getCause() instanceof org.bukkit.configuration.InvalidConfigurationException,
                    String.valueOf(expected.getCause()));
        }
    }

    /** Every key the plugin names exists in the bundled file, so no message can be missing. */
    @Test
    void everyKeyTheCodeUsesIsBundled() throws IOException {
        Pattern literal = Pattern.compile("\"([a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+)\"");
        Set<String> sections = new HashSet<>();
        Messages.defaults().keySet().forEach(key -> sections.add(key.substring(0, key.indexOf('.'))));
        Set<String> missing = new TreeSet<>();
        // Literals that name config.yml settings are not message keys.
        YamlConfiguration config = ReloadFiles.defaults();
        int found = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!source.contains("Messages")) {
                    continue;
                }
                Matcher matcher = literal.matcher(source);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!sections.contains(key.substring(0, key.indexOf('.'))) || config.contains(key)) {
                        continue;
                    }
                    found++;
                    if (!Messages.defaults().containsKey(key) && Messages.defaults().keySet().stream()
                            .noneMatch(bundled -> bundled.startsWith(key + "."))) {
                        missing.add(file.getFileName() + ": " + key);
                    }
                }
            }
        }
        assertTrue(found > 200, "found " + found);
        assertEquals(Set.of(), missing);
    }

    /** Defaults hold only tags MiniMessage knows, so a copy of the file never warns. */
    @Test
    void theBundledTextsAreReadableAsTheirOwnServerCopy() {
        Pattern tag = Pattern.compile("<([a-z][a-z0-9_-]*)>");
        Set<String> styles = Set.of("red", "gold", "yellow", "green", "gray", "aqua", "dark_red");
        Map<String, Object[]> placeholders = new HashMap<>();
        Messages.defaults().forEach((key, template) -> {
            Set<String> names = new LinkedHashSet<>();
            Matcher matcher = tag.matcher(template);
            while (matcher.find()) {
                if (!styles.contains(matcher.group(1))
                        && (matcher.start() == 0 || template.charAt(matcher.start() - 1) != '\\')) {
                    names.add(matcher.group(1));
                }
            }
            placeholders.put(key, names.stream().flatMap(name -> Stream.of(name, "x")).toArray());
        });
        Messages copy = Messages.of(new LinkedHashMap<>(Messages.defaults()), warnings::add);
        placeholders.forEach(copy::plain);
        assertEquals(List.of(), warnings);
    }
}
