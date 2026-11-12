package com.ninja6.ninjaarena.shop;

import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.tool.NinjaTool;
import com.ninja6.ninjaarena.tool.ToolSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;

/** Immutable catalogue captured by match identity. Entry errors do not discard valid neighbours. */
public record ShopCatalogue(Map<String, Entry> items) {
    public static final String FILE = "shop.yml";
    public static final ShopCatalogue EMPTY = new ShopCatalogue(Map.of());

    public ShopCatalogue {
        items = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }

    public enum Category { ARMOUR, TOOLS, POTIONS, CONSUMABLES }
    public sealed interface Gift permits Armour, ToolUses, Potion, Consumable { }
    public record Armour(String tier) implements Gift { }
    public record ToolUses(NinjaTool tool, int count) implements Gift { }
    public record Potion(PotionEffectType type, int amplifier, int duration) implements Gift { }
    public record Consumable(KitDefinition.Entry item) implements Gift { }
    public record Entry(String id, int price, int limit, Category category, Gift gift) { }

    public static ShopCatalogue open(Path file, Logger logger, Runnable saveDefault,
            KitItems items, ToolSettings tools) {
        if (!Files.exists(file)) {
            try {
                saveDefault.run();
            } catch (RuntimeException e) {
                logger.warning("Could not write default shop.yml: " + e.getMessage());
            }
        }
        try {
            return load(file, logger, items, tools);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            logger.warning("Could not load shop.yml: " + e.getMessage() + "; using an empty catalogue.");
            return EMPTY;
        }
    }

    /** File-level failures are rejected by the reload caller, preserving its previous catalogue. */
    public static ShopCatalogue load(Path file, Logger logger, KitItems providers, ToolSettings tools)
            throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));
        if (!Integer.valueOf(1).equals(yaml.get("format")) || !yaml.isList("items")
                || !Set.of("format", "items").containsAll(yaml.getKeys(false))) {
            throw new IllegalArgumentException("shop.yml needs format: 1 and an items list");
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        int number = 0;
        for (Object value : yaml.getList("items")) {
            number++;
            try {
                if (!(value instanceof Map<?, ?> item)) {
                    throw new IllegalArgumentException("expected an entry");
                }
                Entry entry = parse(item, providers);
                if (entries.putIfAbsent(entry.id(), entry) != null) {
                    throw new IllegalArgumentException("duplicate id " + entry.id());
                }
                if (entry.gift() instanceof ToolUses bonus && tools.usesPerRound(bonus.tool()) == 0) {
                    logger.warning("shop.yml item " + number + ": " + bonus.tool().key() + " is unlimited.");
                }
            } catch (IllegalArgumentException e) {
                logger.warning("Skipping shop.yml item " + number + ": " + e.getMessage());
            }
        }
        return new ShopCatalogue(entries);
    }

    private static Entry parse(Map<?, ?> item, KitItems providers) {
        String id = name(item, "id");
        if (!id.matches("[a-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid catalogue id");
        }
        int price = integer(item, "price", 1, Integer.MAX_VALUE);
        int limit = integer(item, "limit", 1, Integer.MAX_VALUE);
        Category category;
        try {
            category = Category.valueOf(name(item, "category").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown category");
        }
        if (!(item.get("gives") instanceof Map<?, ?> gives)) {
            throw new IllegalArgumentException("missing gives entry");
        }
        keys(item, Set.of("id", "price", "limit", "category", "gives"));
        Gift gift = switch (category) {
            case ARMOUR -> {
                keys(gives, Set.of("tier"));
                String tier = name(gives, "tier");
                if (!Set.of("leather", "chain", "iron", "diamond").contains(tier)) {
                    throw new IllegalArgumentException("unknown armour tier");
                }
                yield new Armour(tier);
            }
            case TOOLS -> {
                keys(gives, Set.of("tool", "count"));
                String toolId = name(gives, "tool");
                NinjaTool found = null;
                for (NinjaTool tool : NinjaTool.values()) {
                    if (tool.thrown() && tool.kitId().equals(toolId)) {
                        found = tool;
                    }
                }
                if (found == null) {
                    throw new IllegalArgumentException("unknown thrown tool id");
                }
                yield new ToolUses(found, integer(gives, "count", 1, 64));
            }
            case POTIONS -> {
                keys(gives, Set.of("effect", "amplifier", "duration"));
                NamespacedKey key = NamespacedKey.fromString(name(gives, "effect"));
                PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
                if (type == null) {
                    throw new IllegalArgumentException("unknown potion effect");
                }
                yield new Potion(type, integer(gives, "amplifier", 0, 255),
                        integer(gives, "duration", 1, Integer.MAX_VALUE));
            }
            case CONSUMABLES -> {
                keys(gives, Set.of("material", "id", "amount"));
                if (gives.containsKey("material") == gives.containsKey("id")) {
                    throw new IllegalArgumentException("give exactly one of material or id");
                }
                Material material = null;
                String itemId = null;
                int maximum;
                if (gives.containsKey("material")) {
                    material = Material.matchMaterial(name(gives, "material"));
                    if (material == null || material.isAir() || !material.isItem()) {
                        throw new IllegalArgumentException("invalid item material");
                    }
                    maximum = material.getMaxStackSize();
                } else {
                    itemId = name(gives, "id");
                    maximum = providers.stackLimit(itemId);
                    if (maximum == 0) {
                        throw new IllegalArgumentException("unregistered item id");
                    }
                }
                yield new Consumable(new KitDefinition.Entry(0, material, itemId,
                        integer(gives, "amount", 1, maximum)));
            }
        };
        return new Entry(id, price, limit, category, gift);
    }

    private static String name(Map<?, ?> map, String key) {
        if (map.get(key) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalArgumentException(key + " must be text");
    }

    private static int integer(Map<?, ?> map, String key, int minimum, int maximum) {
        if (map.get(key) instanceof Integer value && value >= minimum && value <= maximum) {
            return value;
        }
        throw new IllegalArgumentException(key + " must be an integer from " + minimum + " to " + maximum);
    }

    private static void keys(Map<?, ?> map, Set<String> allowed) {
        if (!allowed.containsAll(map.keySet())) {
            throw new IllegalArgumentException("unknown key");
        }
    }
}
