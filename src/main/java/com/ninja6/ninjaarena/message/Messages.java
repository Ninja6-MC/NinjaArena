package com.ninja6.ninjaarena.message;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The player-facing text, read from {@code messages.yml} in MiniMessage format. The bundled
 * file holds every key; the server's copy may reword any of them.
 *
 * <p>Placeholders are named tags such as {@code <arena>}. A value is inserted as plain text,
 * never parsed, so a player name or other input cannot add formatting or click actions. A
 * {@link Component} value is inserted as it is.
 *
 * <p>A key that is missing from the server's file, is not text, or holds a tag MiniMessage
 * cannot read falls back to the bundled default, with one warning per key.
 */
public final class Messages {

    public static final String FILE = "messages.yml";

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** A tag left as literal text after parsing: MiniMessage did not recognise it. */
    private static final Pattern TAG = Pattern.compile("<[^<>\\s][^<>]*>");
    private static final Map<String, String> BUNDLED = readBundled();
    private static final Messages DEFAULTS = new Messages(BUNDLED, Set.of(), message -> { });

    private static volatile Messages active = DEFAULTS;

    private final Map<String, String> templates;
    private final Set<String> notText;
    private final Consumer<String> warnings;
    private final Map<String, Boolean> usable = new ConcurrentHashMap<>();

    private Messages(Map<String, String> templates, Set<String> notText, Consumer<String> warnings) {
        this.templates = templates;
        this.notText = notText;
        this.warnings = warnings;
    }

    /** The bundled text only. */
    public static Messages bundled() {
        return DEFAULTS;
    }

    /** The bundled key/template pairs, in file order. */
    public static Map<String, String> defaults() {
        return BUNDLED;
    }

    /**
     * Messages from flattened values, keyed by dotted path. Sections are skipped; any other
     * value that is not text counts as broken.
     */
    public static Messages of(Map<String, ?> values, Consumer<String> warnings) {
        Map<String, String> templates = new LinkedHashMap<>();
        Set<String> notText = new HashSet<>();
        values.forEach((key, value) -> {
            if (value instanceof String text) {
                templates.put(key, text);
            } else if (!(value instanceof Map<?, ?>) && !(value instanceof ConfigurationSection)) {
                notText.add(key);
            }
        });
        return new Messages(Collections.unmodifiableMap(templates), Set.copyOf(notText),
                Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * Reads a messages file, the one rule for startup and reload. Throws if it cannot be read
     * or is not valid YAML, with the reader's own message. Any key name is accepted; one the
     * bundled file does not have is ignored with a warning.
     */
    public static Messages read(Path file, Consumer<String> warnings) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> values = flatten(reader);
            for (String key : values.keySet()) {
                if (!BUNDLED.containsKey(key)) {
                    warnings.accept(FILE + ": " + key + " is not a message this version knows; it is ignored.");
                }
            }
            return of(values, warnings);
        } catch (InvalidConfigurationException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The messages in use. */
    public static Messages active() {
        return active;
    }

    /** Makes these the messages in use, for startup and reload. */
    public static void use(Messages messages) {
        active = Objects.requireNonNull(messages, "messages");
    }

    /** The message in use for {@code key}, with placeholders given as name, value pairs. */
    public static Component msg(String key, Object... placeholders) {
        return active.render(key, placeholders);
    }

    /** The message in use for {@code key} as plain text: formatting is dropped. */
    public static String text(String key, Object... placeholders) {
        return active.plain(key, placeholders);
    }

    public Component render(String key, Object... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders come in name, value pairs: " + key);
        }
        TagResolver.Builder resolver = TagResolver.builder();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < placeholders.length; i += 2) {
            String name = (String) placeholders[i];
            Object value = placeholders[i + 1];
            names.add(name);
            resolver.resolver(value instanceof Component component ? Placeholder.component(name, component)
                    : Placeholder.unparsed(name, String.valueOf(value)));
        }
        return MINI.deserialize(template(key, names), resolver.build());
    }

    public String plain(String key, Object... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(render(key, placeholders));
    }

    private String template(String key, List<String> names) {
        String fallback = BUNDLED.get(key);
        if (fallback == null) {
            throw new IllegalArgumentException("No bundled message " + key);
        }
        if (templates == BUNDLED) {
            return fallback;
        }
        return usable.computeIfAbsent(key, k -> check(k, names)) ? templates.get(key) : fallback;
    }

    private boolean check(String key, List<String> names) {
        String template = templates.get(key);
        if (template == null) {
            warnings.accept(FILE + ": " + key + (notText.contains(key) ? " is not text" : " is missing")
                    + "; using the default.");
            return false;
        }
        String unread;
        try {
            TagResolver.Builder empty = TagResolver.builder();
            for (String name : names) {
                empty.resolver(Placeholder.unparsed(name, ""));
            }
            unread = unreadTag(template, PlainTextComponentSerializer.plainText()
                    .serialize(MINI.deserialize(template, empty.build())));
        } catch (RuntimeException e) {
            unread = e.getMessage();
        }
        if (unread != null) {
            warnings.accept(FILE + ": " + key + " has a tag MiniMessage cannot read (" + unread
                    + "); using the default.");
            return false;
        }
        return true;
    }

    /** A tag the template wrote, unescaped, that survived parsing as literal text; null if none. */
    private static String unreadTag(String template, String rendered) {
        Matcher tags = TAG.matcher(rendered);
        while (tags.find()) {
            String tag = tags.group();
            for (int at = template.indexOf(tag); at >= 0; at = template.indexOf(tag, at + 1)) {
                if (at == 0 || template.charAt(at - 1) != '\\') {
                    return tag;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> flatten(Reader reader) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(reader);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String path : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(path)) {
                values.put(path, yaml.get(path));
            }
        }
        return values;
    }

    private static Map<String, String> readBundled() {
        try (InputStream stream = Messages.class.getClassLoader().getResourceAsStream(FILE)) {
            if (stream == null) {
                throw new IllegalStateException("missing bundled " + FILE);
            }
            Map<String, String> templates = new LinkedHashMap<>();
            flatten(new InputStreamReader(stream, StandardCharsets.UTF_8)).forEach((key, value) -> {
                if (!(value instanceof String text)) {
                    throw new IllegalStateException("bundled " + FILE + ": " + key + " is not text");
                }
                templates.put(key, text);
            });
            return Collections.unmodifiableMap(templates);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("cannot read bundled " + FILE, e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Messages messages && templates.equals(messages.templates)
                && notText.equals(messages.notText);
    }

    @Override
    public int hashCode() {
        return templates.hashCode();
    }
}
