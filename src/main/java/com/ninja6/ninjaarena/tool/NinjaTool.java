package com.ninja6.ninjaarena.tool;

import static com.ninja6.ninjaarena.message.Messages.msg;

import java.util.Optional;
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
    SMOKE_BOMB("smoke-bomb", Material.SNOWBALL, new NamespacedKey("ninjaarena", "smoke_bomb")),
    /** Thrown; deals low damage and makes a rival glow for a few seconds. */
    SHURIKEN("shuriken", Material.SNOWBALL, new NamespacedKey("ninjaarena", "shuriken")),
    /** Used in place; a short forward burst with a cooldown. Never consumed. */
    DASH("dash", Material.FEATHER, new NamespacedKey("ninjaarena", "dash"));

    /** The data tag holding a tool item's {@link #key()}. */
    public static final NamespacedKey KEY = new NamespacedKey("ninjaarena", "tool");

    private final String key;
    private final Material material;
    private final NamespacedKey model;

    NinjaTool(String key, Material material, NamespacedKey model) {
        this.key = key;
        this.material = material;
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

    /** Independent throw groups; dash keeps the vanilla feather group for Geyser. */
    public NamespacedKey cooldownGroup() {
        return this == DASH ? NamespacedKey.minecraft("feather") : model;
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
            meta.itemName(msg("tool." + key));
            appearance(meta, custom, color);
            configureUse(meta);
        });
        item.editPersistentDataContainer(data -> data.set(KEY, PersistentDataType.STRING, key));
        return item;
    }

    /** Adds throw groups without changing dash's vanilla cooldown component. */
    void configureUse(org.bukkit.inventory.meta.ItemMeta meta) {
        meta.setMaxStackSize(this == DASH ? 1 : 64);
        if (thrown()) {
            var cooldown = meta.getUseCooldown();
            cooldown.setCooldownGroup(cooldownGroup());
            // Paper requires a positive value; below one tick rounds to no automatic wait.
            // Each accepted throw sets the configured duration explicitly by group.
            cooldown.setCooldownSeconds(0.01f);
            meta.setUseCooldown(cooldown);
        }
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
