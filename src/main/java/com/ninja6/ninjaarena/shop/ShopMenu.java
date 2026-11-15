package com.ninja6.ninjaarena.shop;

import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.kit.PluginMenuHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Chest fallback. Holders identify plugin menus to the inventory lockdown. */
public final class ShopMenu implements ShopMenus.Surface, Listener {
    public interface Screens {
        Inventory create(InventoryHolder holder, String title);
        ItemStack item(String label, List<String> lore);
    }
    public static final class Holder implements PluginMenuHolder {
        private final List<ShopMenus.Button> buttons;
        private final ShopMenus.Press press;
        private Inventory inventory;
        private Holder(List<ShopMenus.Button> buttons, ShopMenus.Press press) {
            this.buttons = buttons;
            this.press = press;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final Screens screens;
    private final Executor mainThread;

    public ShopMenu(Screens screens, Executor mainThread) {
        this.screens = screens;
        this.mainThread = mainThread;
    }

    @Override
    public void show(Player player, ShopMenus.Content content, ShopMenus.Press press) {
        List<ShopMenus.Button> buttons = new ArrayList<>(content.buttons());
        buttons.add(new ShopMenus.Button(text("shop.menu.close"), new ShopMenus.Action(ShopMenus.Kind.CLOSE)));
        Holder holder = new Holder(List.copyOf(buttons), press);
        holder.inventory = screens.create(holder, content.title());
        for (int i = 0; i < buttons.size(); i++) {
            holder.inventory.setItem(i, screens.item(buttons.get(i).label(), List.of()));
        }
        holder.inventory.setItem(53, screens.item(content.lines().getFirst(), content.lines().subList(1,
                content.lines().size())));
        if (player.openInventory(holder.inventory) == null) {
            throw new IllegalStateException("Shop inventory was refused");
        }
    }

    @Override public void close(Player player) { player.closeInventory(); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) { return; }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if ((event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)
                && slot >= 0 && slot < holder.buttons.size() && event.getWhoClicked() instanceof Player player) {
            // Opening or closing inventories inside InventoryClickEvent is unsafe.
            mainThread.execute(() -> holder.press.pressed(player, holder.buttons.get(slot).action()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof Holder || event.getDestination().getHolder() instanceof Holder
                || event.getInitiator().getHolder() instanceof Holder) { event.setCancelled(true); }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder && event.getPlayer() instanceof Player player) {
            // Old close events cannot invalidate a newly opened inventory's token.
            holder.press.pressed(player, new ShopMenus.Action(ShopMenus.Kind.DISMISS));
        }
    }

    public static final class BukkitScreens implements Screens {
        @Override public Inventory create(InventoryHolder holder, String title) {
            return Bukkit.createInventory(holder, 54, Component.text(title));
        }
        @Override public ItemStack item(String label, List<String> lore) {
            ItemStack stack = new ItemStack(Material.PAPER);
            stack.editMeta(meta -> {
                meta.displayName(Component.text(label));
                meta.lore(lore.stream().map(Component::text).toList());
            });
            return stack;
        }
    }
}
