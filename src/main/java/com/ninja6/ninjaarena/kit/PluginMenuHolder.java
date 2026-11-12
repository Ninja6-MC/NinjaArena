package com.ninja6.ninjaarena.kit;

import org.bukkit.inventory.InventoryHolder;

/**
 * Marks a plugin menu exempt from inventory lockdown. Its listener must cancel inventory
 * actions to protect the menu's contents.
 */
public interface PluginMenuHolder extends InventoryHolder {
}
