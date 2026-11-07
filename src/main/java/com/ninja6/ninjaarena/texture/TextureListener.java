package com.ninja6.ninjaarena.texture;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Connection lifecycle for preferences, and the menu's input. Every inventory action that
 * touches the menu is cancelled, whatever its kind: shift-clicks from the player's own
 * inventory, number keys, double-click collection, drops and drags all arrive as events whose
 * top inventory is the menu.
 */
public final class TextureListener implements Listener {

    private final TextureService service;
    private final TextureMenu menu;

    public TextureListener(TextureService service, TextureMenu menu) {
        this.service = service;
        this.menu = menu;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.join(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.quit(event.getPlayer().getUniqueId());
        menu.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TextureMenu.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        int top = event.getView().getTopInventory().getSize();
        int slot = event.getRawSlot();
        boolean plain = event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT;
        if (plain && slot >= 0 && slot < top && event.getWhoClicked() instanceof Player player) {
            menu.click(player, holder, slot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TextureMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(InventoryMoveItemEvent event) {
        if (isMenu(event.getSource().getHolder()) || isMenu(event.getDestination().getHolder())
                || isMenu(event.getInitiator().getHolder())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof TextureMenu.Holder holder) {
            menu.closed(holder.owner(), holder);
        }
    }

    private static boolean isMenu(InventoryHolder holder) {
        return holder instanceof TextureMenu.Holder;
    }
}
