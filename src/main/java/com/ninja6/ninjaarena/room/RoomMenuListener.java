package com.ninja6.ninjaarena.room;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

/** Cancels all item movement in room menus, even when inventory lockdown permits opening them. */
public final class RoomMenuListener implements Listener {
    private final RoomMenu menu;
    private final RoomMenus menus;

    public RoomMenuListener(RoomMenu menu, RoomMenus menus) {
        this.menu = menu;
        this.menus = menus;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RoomMenu.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if ((event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)
                && event.getRawSlot() >= 0 && event.getRawSlot() < RoomMenu.SIZE
                && event.getWhoClicked() instanceof Player player) {
            menu.click(player, holder, event.getRawSlot(), event.getClick() == ClickType.RIGHT);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (isMenu(event.getView().getTopInventory().getHolder())) {
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
        if (event.getInventory().getHolder() instanceof RoomMenu.Holder holder) {
            menu.closed(holder);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menu.forget(event.getPlayer().getUniqueId());
        menus.forget(event.getPlayer().getUniqueId());
    }

    private static boolean isMenu(InventoryHolder holder) {
        return holder instanceof RoomMenu.Holder;
    }
}
