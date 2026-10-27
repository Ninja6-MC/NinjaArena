package com.ninja6.ninjaarena.kit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** One shared round kit. A malformed definition is rejected as a whole. */
public record KitDefinition(List<Entry> items) {

    public static final KitDefinition EMPTY = new KitDefinition(List.of());

    /** Inventory slots 0-35, armour 36-39 (boots to helmet), and offhand 40. */
    public record Entry(int slot, Material material, String id, int amount) {
        public Entry {
            if (slot < 0 || slot > 40 || amount < 1 || amount > 99
                    || (material == null) == (id == null) || id != null && !KitItems.validId(id)) {
                throw new IllegalArgumentException("invalid kit entry at slot " + slot);
            }
        }
    }

    public KitDefinition {
        items = List.copyOf(items);
    }

    /** Logs missing, unreadable, malformed or invalid files and returns the safe empty kit. */
    public static KitDefinition load(Path file, Logger logger) {
        try {
            var yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file));
            if (!Integer.valueOf(1).equals(yaml.get("format")) || !yaml.isList("items")) {
                throw new IllegalArgumentException("expected format: 1 and an items list");
            }
            if (!Set.of("format", "items").containsAll(yaml.getKeys(false))) {
                throw new IllegalArgumentException("unknown top-level key");
            }
            List<Entry> entries = new ArrayList<>();
            Set<Integer> slots = new HashSet<>();
            for (Object value : yaml.getList("items")) {
                if (!(value instanceof Map<?, ?> item)
                        || !Set.of("slot", "material", "id", "amount").containsAll(item.keySet())) {
                    throw new IllegalArgumentException("item " + (entries.size() + 1) + " must be an item entry");
                }
                if (!(item.get("slot") instanceof Integer slot) || slot < 0 || slot > 40 || !slots.add(slot)) {
                    throw new IllegalArgumentException("item " + (entries.size() + 1) + " has an invalid or duplicate slot");
                }
                Object amountValue = item.containsKey("amount") ? item.get("amount") : Integer.valueOf(1);
                if (!(amountValue instanceof Integer amount) || amount < 1 || amount > 99) {
                    throw new IllegalArgumentException("slot " + slot + " amount must be an integer from 1 to 99");
                }
                if (item.containsKey("material") == item.containsKey("id")) {
                    throw new IllegalArgumentException("slot " + slot + " needs exactly one of material or id");
                }
                Material material = null;
                String id = null;
                if (item.containsKey("material")) {
                    if (!(item.get("material") instanceof String name)) {
                        throw new IllegalArgumentException("slot " + slot + " material must be a name");
                    }
                    material = Material.matchMaterial(name);
                    if (material == null || material == Material.AIR || material == Material.CAVE_AIR
                            || material == Material.VOID_AIR) {
                        throw new IllegalArgumentException("slot " + slot + " has an unknown or empty material: " + name);
                    }
                } else {
                    if (!(item.get("id") instanceof String name) || !KitItems.validId(name)) {
                        throw new IllegalArgumentException("slot " + slot + " id must be a namespaced item id");
                    }
                    id = name;
                }
                entries.add(new Entry(slot, material, id, amount));
            }
            return new KitDefinition(entries);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            logger.warning("Could not load " + file + ": " + e.getMessage() + "; using an empty round kit.");
            return EMPTY;
        }
    }
}
