package com.ninja6.ninjaarena.tool;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * The ninja kit tools use vanilla materials with optional Java artwork, marked with the
 * {@link #KEY} data tag; display names and models are never used to recognise a tool.
 */
public enum NinjaTool {

    /** Thrown; leaves a cloud that blinds rival players inside it. */
    SMOKE_BOMB("smoke-bomb", Material.SNOWBALL, "Smoke Bomb", new NamespacedKey("ninjaarena", "smoke_bomb")),
    /** Thrown; deals low damage and makes a rival glow for a few seconds. */
    SHURIKEN("shuriken", Material.SNOWBALL, "Shuriken", new NamespacedKey("ninjaarena", "shuriken")),
    /** Used in place; a short forward burst with a cooldown. Never consumed. */
    DASH("dash", Material.FEATHER, "Dash", new NamespacedKey("ninjaarena", "dash"));

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
        return create(false, 0xFFFFFF);
    }

    /** Creates a tool with custom artwork only after its recipient has loaded the pack. */
    public ItemStack create(boolean custom, int color) {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.itemName(Component.text(displayName, NamedTextColor.GRAY));
            appearance(meta, custom, color);
            if (this == DASH) {
                meta.setMaxStackSize(1);
            }
        });
        item.editPersistentDataContainer(data -> data.set(KEY, PersistentDataType.STRING, key));
        return item;
    }

    /** Only the accent layer reads the first custom-model-data colour. */
    void appearance(org.bukkit.inventory.meta.ItemMeta meta, boolean custom, int color) {
        meta.setItemModel(custom ? model : null);
        if (custom) {
            var data = meta.getCustomModelDataComponent();
            data.setColors(java.util.List.of(Color.fromRGB(color)));
            data.setStrings(java.util.List.of(accentMarker(color)));
            meta.setCustomModelDataComponent(data);
        }
    }

    /** Presentation only. Geyser maps these names to precomposed accents; recognition never reads them. */
    static String accentMarker(int color) {
        return switch (color) {
            case 0xFF5555 -> "red";
            case 0x5555FF -> "blue";
            case 0x55FF55 -> "green";
            case 0xFFFF55 -> "yellow";
            default -> "white";
        };
    }
}
