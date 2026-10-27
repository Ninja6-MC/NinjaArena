package com.ninja6.ninjaarena.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KitDefinitionTest {
    private static final KitDefinition DEFAULT_KIT = new KitDefinition(List.of(
            new KitDefinition.Entry(1, null, "ninjaarena:shuriken", 8),
            new KitDefinition.Entry(2, null, "ninjaarena:smoke-bomb", 2),
            new KitDefinition.Entry(3, null, "ninjaarena:dash", 1)));
    @TempDir Path dir;
    private final List<String> warnings = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private Logger logger;

    @BeforeEach
    void logs() {
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                messages.add(record.getMessage());
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
    }

    private KitDefinition load(String text) throws Exception {
        Path file = dir.resolve("kits.yml");
        Files.writeString(file, text);
        return KitDefinition.load(file, logger);
    }

    @Test
    void vanillaAndNamedItemsHaveExplicitSlotsAndDefaultAmounts() throws Exception {
        var kit = load("""
                format: 1
                items:
                  - slot: 0
                    material: WOODEN_SWORD
                  - slot: 40
                    id: ninjaarena:smoke-bomb
                    amount: 3
                """);
        assertEquals(List.of(new KitDefinition.Entry(0, Material.WOODEN_SWORD, null, 1),
                new KitDefinition.Entry(40, null, "ninjaarena:smoke-bomb", 3)), kit.items());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingUnreadableAndMalformedFilesFallBackWithoutThrowing() throws Exception {
        assertEquals(KitDefinition.EMPTY, KitDefinition.load(dir.resolve("missing.yml"), logger));
        assertEquals(KitDefinition.EMPTY, KitDefinition.load(dir, logger));
        assertEquals(KitDefinition.EMPTY, load("items: [unterminated"));
        assertEquals(3, warnings.size());
        assertTrue(warnings.stream().allMatch(message -> message.contains("using an empty round kit")));
        assertTrue(warnings.getFirst().contains("missing.yml"));
        assertTrue(warnings.getFirst().startsWith("kits.yml not found at "));
    }

    @Test
    void invalidEntriesRejectTheWholeKitAndPreserveTheFileForRepair() throws Exception {
        for (String entry : List.of("slot: -1\nmaterial: STONE", "slot: 41\nmaterial: STONE",
                "slot: 1\nmaterial: NOT_A_MATERIAL", "slot: 1\nid: no_namespace",
                "slot: 1\nmaterial: STONE\nid: test:item", "slot: 1\namount: 1", "slot: 1\nmaterial: STONE\namount: 0",
                "slot: 1\nmaterial: STONE\namount: 100", "slot: 1\nmaterial: STONE\namount: 1.5",
                "slot: 1\nmaterial: STONE\nammount: 2", "slot: 0\nmaterial: STONE")) {
            String text = "format: 1\nitems:\n  - slot: 0\n    material: STONE\n  - "
                    + entry.replace("\n", "\n    ") + "\n";
            assertEquals(KitDefinition.EMPTY, load(text), entry);
            assertEquals(text, Files.readString(dir.resolve("kits.yml")));
        }
        assertEquals(11, warnings.size());
    }

    @Test
    void invalidRootAndNullKeysAreSafeEmptyKits() throws Exception {
        for (String text : List.of("", "format: 2\nitems: []", "format: 1\nitems: nope",
                "format: 1\nitems: []\nunknown: true", "format: 1\nitems: [null]",
                "format: 1\nitems:\n  - null: value\n")) {
            assertEquals(KitDefinition.EMPTY, load(text));
        }
        assertFalse(warnings.isEmpty());
    }

    @Test
    void bundledTemplateHandsOutTheNinjaToolsWithTheirDefaultCounts() throws Exception {
        try (var stream = getClass().getResourceAsStream("/kits.yml")) {
            assertNotNull(stream);
            Files.copy(stream, dir.resolve("kits.yml"));
        }
        assertEquals(DEFAULT_KIT, KitDefinition.load(dir.resolve("kits.yml"), logger));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void aFreshInstallWritesTheTemplateBeforeLoadingWithoutWarning() {
        Path file = dir.resolve("kits.yml");
        var kit = KitDefinition.open(file, logger, () -> {
            try (var stream = getClass().getResourceAsStream("/kits.yml")) {
                assertNotNull(stream);
                Files.copy(stream, file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        assertEquals(DEFAULT_KIT, kit);
        assertTrue(Files.exists(file));
        assertTrue(warnings.isEmpty());
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().startsWith("kits.yml not found; writing the default template"));
    }

    @Test
    void failureToWriteAMissingTemplateRemainsANonFatalEmptyKitWithClearLogs() {
        assertEquals(KitDefinition.EMPTY, KitDefinition.open(dir.resolve("kits.yml"), logger, () -> {
            throw new IllegalStateException("read-only directory");
        }));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("read-only directory")));
        assertTrue(warnings.stream().anyMatch(message -> message.startsWith("kits.yml not found at ")));
        assertFalse(warnings.stream().anyMatch(message -> message.startsWith("Could not load")));
    }

    @Test
    void nonItemMaterialsAreSkippedAtLoadWithoutDiscardingValidEntries() throws Exception {
        var kit = load("""
                format: 1
                items:
                  - slot: 0
                    material: STONE
                  - slot: 1
                    material: WATER
                  - slot: 1
                    material: FIRE
                  - slot: 1
                    material: NETHER_PORTAL
                  - slot: 1
                    material: AIR
                  - slot: 1
                    id: test:tool
                """);
        assertEquals(List.of(new KitDefinition.Entry(0, Material.STONE, null, 1),
                new KitDefinition.Entry(1, null, "test:tool", 1)), kit.items());
        assertEquals(4, warnings.size());
        for (int i = 0; i < 4; i++) {
            assertTrue(warnings.get(i).contains("kits.yml item " + (i + 2)));
        }
        for (String material : List.of("WATER", "FIRE", "NETHER_PORTAL", "AIR")) {
            assertTrue(warnings.stream().anyMatch(message -> message.contains(material)));
        }
    }
}
