package com.ninja6.ninjaarena.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.tool.ToolSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShopCatalogueTest {
    @TempDir Path dir;
    private final List<String> warnings = new ArrayList<>();
    private final Logger logger = Logger.getAnonymousLogger();
    private final KitItems items = new KitItems();

    ShopCatalogueTest() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
    }

    @Test
    void defaultCatalogueInstallsAndLoadsWithNoWarnings() throws Exception {
        Path file = dir.resolve("shop.yml");
        var catalogue = ShopCatalogue.open(file, logger, () -> {
            try (var input = getClass().getResourceAsStream("/shop.yml")) {
                Files.copy(input, file);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }, items, ToolSettings.DEFAULTS);
        assertEquals(3, catalogue.items().size());
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertThrows(UnsupportedOperationException.class, () -> catalogue.items().clear());
    }

    @Test
    void eachInvalidEntryWarnsOnceAndItsNeighboursLoad() throws Exception {
        String valid = "{id: food, price: 2, limit: 1, category: consumables, gives: {material: STONE, amount: 1}}";
        List<String> invalid = List.of(
                "no-entry",
                valid.replace("price: 2", "price: 0"),
                valid.replace("price: 2", "price: 1.5"),
                valid.replace("limit: 1", "limit: 0"),
                valid.replace("id: food", "id: BAD"),
                valid.replace("category: consumables", "category: unknown"),
                valid.replace("material: STONE", "material: NOT_A_MATERIAL"),
                valid.replace("material: STONE", "material: AIR"),
                valid.replace("material: STONE", "material: WATER"),
                valid.replace("material: STONE", "id: test:unknown"),
                valid.replace("material: STONE", "material: STONE, id: test:unknown"),
                valid.replace("amount: 1", "amount: 65"),
                valid.replace("price: 2", "price: 2, surprise: 1"),
                "{id: bad, price: 1, limit: 1, category: armour, gives: {tier: gold}}",
                "{id: bad, price: 1, limit: 1, category: tools, gives: {tool: ninjaarena:dash, count: 1}}",
                "{id: bad, price: 1, limit: 1, category: tools, gives: {tool: ninjaarena:shuriken, count: 0}}",
                "{id: bad, price: 1, limit: 1, category: potions, gives: {effect: '!', amplifier: 0, duration: 1}}",
                "{id: bad, price: 1, limit: 1, category: potions, gives: {effect: speed, amplifier: -1, duration: 1}}",
                "{id: bad, price: 1, limit: 1, category: potions, gives: {effect: speed, amplifier: 0, duration: 0}}",
                "{id: bad, price: 1, limit: 1, category: armour}");
        for (String entry : invalid) {
            warnings.clear();
            Path file = dir.resolve("shop.yml");
            Files.writeString(file, "format: 1\nitems:\n  - " + entry + "\n  - " + valid + "\n");
            assertEquals(1, ShopCatalogue.load(file, logger, items, ToolSettings.DEFAULTS).items().size(), entry);
            assertEquals(1, warnings.size(), entry + warnings);
        }
    }

    @Test
    void unlimitedToolLoadsWithOneWarningAndDuplicateIdIsSkipped() throws Exception {
        Path file = dir.resolve("shop.yml");
        String entry = "{id: more, price: 1, limit: 1, category: tools, gives: {tool: ninjaarena:shuriken, count: 2}}";
        Files.writeString(file, "format: 1\nitems:\n  - " + entry + "\n  - " + entry);
        var catalogue = ShopCatalogue.load(file, logger, items, ToolSettings.DEFAULTS);
        assertEquals(1, catalogue.items().size());
        assertEquals(2, warnings.size());
    }

    @Test
    void malformedMissingAndWrongFormatFilesAreSafeAtStartupAndRejectOnReload() throws Exception {
        Path file = dir.resolve("shop.yml");
        for (String content : List.of("items: [", "format: 2\nitems: []", "format: 1\nitems: {}")) {
            Files.writeString(file, content);
            warnings.clear();
            assertSame(ShopCatalogue.EMPTY, ShopCatalogue.open(file, logger, () -> { }, items, ToolSettings.DEFAULTS));
            assertEquals(1, warnings.size());
            assertThrows(Exception.class, () -> ShopCatalogue.load(file, logger, items, ToolSettings.DEFAULTS));
        }
        Files.delete(file);
        assertSame(ShopCatalogue.EMPTY, ShopCatalogue.open(file, logger, () -> { }, items, ToolSettings.DEFAULTS));
    }
}
