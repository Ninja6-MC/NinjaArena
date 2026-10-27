package com.ninja6.ninjaarena.tool;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * The ninja kit tools. Each is a plain vanilla item, so Bedrock clients show the base item,
 * marked with the {@link #KEY} data tag; display names are never used to recognise a tool.
 */
public enum NinjaTool {

    /** Thrown; leaves a cloud that blinds rival players inside it. */
    SMOKE_BOMB("smoke-bomb", Material.SNOWBALL, "Smoke Bomb", NamespacedKey.minecraft("gunpowder")),
    /** Thrown; deals low damage and makes a rival glow for a few seconds. */
    SHURIKEN("shuriken", Material.SNOWBALL, "Shuriken", NamespacedKey.minecraft("nether_star")),
    /** Used in place; a short forward burst with a cooldown. Never consumed. */
    DASH("dash", Material.FEATHER, "Dash", null);

    /** The data tag holding a tool item's {@link #key()}. */
    public static final NamespacedKey KEY = new NamespacedKey("ninjaarena", "tool");

    private final String key;
    private final Material material;
    private final String displayName;
    private final NamespacedKey model;

    NinjaTool(String key, Material material, String displayName, NamespacedKey model) {
        this.key = key;
        this.material = material;
        this.displayName = displayName;
        this.model = model;
    }

    /** The value stored under {@link #KEY}, and the path of {@link #kitId()}. */
    public String key() {
        return key;
    }

    /** The id a kits.yml entry uses for this tool, such as {@code ninjaarena:shuriken}. */
    public String kitId() {
        return KEY.getNamespace() + ":" + key;
    }

    public Material material() {
        return material;
    }

    /** Whether the tool is thrown as a projectile rather than used in place. */
    public boolean thrown() {
        return this != DASH;
    }

    /** The tool an item is, by its data tag. Empty for any other item, including a renamed copy. */
    public static Optional<NinjaTool> of(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        String value = item.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (value == null) {
            return Optional.empty();
        }
        for (NinjaTool tool : values()) {
            if (tool.key.equals(value)) {
                return Optional.of(tool);
            }
        }
        return Optional.empty();
    }

    /** A new single tool item. Needs a running server. */
    public ItemStack create() {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.itemName(Component.text(displayName, NamedTextColor.GRAY));
            if (model != null) {
                meta.setItemModel(model);
            }
            if (this == DASH) {
                meta.setMaxStackSize(1);
            }
        });
        item.editPersistentDataContainer(data -> data.set(KEY, PersistentDataType.STRING, key));
        return item;
    }
}
