package com.ninja6.ninjaarena.kit;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

/** Discards arena items before applying a fresh kit or attempting a snapshot restore. */
public final class KitInventory {
    private KitInventory() {
    }

    public static void clear(Player player) {
        clearCrafting(player);
        player.setItemOnCursor(null);
        player.closeInventory();
        clearCrafting(player);
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);
    }

    private static void clearCrafting(Player player) {
        var top = player.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING && top.getHolder() instanceof Player owner
                && owner.getUniqueId().equals(player.getUniqueId())) {
            top.clear();
        }
    }
}
