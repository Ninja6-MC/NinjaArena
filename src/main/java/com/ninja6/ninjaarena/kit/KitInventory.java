package com.ninja6.ninjaarena.kit;

import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

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

    /**
     * Readies a player who has just arrived in a waiting room: {@linkplain #clear(Player) clears}
     * their inventory, removes their potion effects and fire, and sets adventure mode. Call it
     * only once their snapshot is saved, since the restore gives all of it back from there.
     */
    public static void emptyForWaiting(Player player) {
        clear(player);
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        player.setFireTicks(0);
        player.setGameMode(GameMode.ADVENTURE);
    }

    private static void clearCrafting(Player player) {
        var top = player.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING && top.getHolder() instanceof Player owner
                && owner.getUniqueId().equals(player.getUniqueId())) {
            top.clear();
        }
    }
}
