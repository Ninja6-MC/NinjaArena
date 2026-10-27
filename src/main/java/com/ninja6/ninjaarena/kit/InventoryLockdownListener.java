package com.ninja6.ninjaarena.kit;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.block.Campfire;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

/**
 * Locks the inventory of a player in a match or a waiting room: no drops, pickups, containers,
 * ender chests (as a block or through any command that opens one) or armour stands. Membership,
 * never world or edit mode, decides whether an inventory is locked.
 */
public final class InventoryLockdownListener implements Listener {

    private final Predicate<UUID> locked;

    /**
     * @param locked whether a player's inventory is locked; on a server, whether they are in a
     *               match or waiting for one
     */
    public InventoryLockdownListener(Predicate<UUID> locked) {
        this.locked = Objects.requireNonNull(locked, "locked");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked.test(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && locked.test(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        if (locked.test(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowPickup(PlayerPickupArrowEvent event) {
        if (locked.test(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (lockedView(event.getView())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!locked.test(event.getWhoClicked().getUniqueId())) {
            return;
        }
        switch (event.getAction()) {
            case DROP_ALL_CURSOR, DROP_ONE_CURSOR, DROP_ALL_SLOT, DROP_ONE_SLOT -> event.setCancelled(true);
            default -> {
                if (lockedView(event.getView())) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (lockedView(event.getView())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (!locked.test(event.getPlayer().getUniqueId()) || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }
        var block = event.getClickedBlock();
        var state = block.getState();
        if (block.getType() == Material.ENDER_CHEST || state instanceof InventoryHolder || state instanceof Campfire) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        var entity = event.getRightClicked();
        if (locked.test(event.getPlayer().getUniqueId())
                && (entity instanceof InventoryHolder || entity instanceof ArmorStand || entity instanceof ItemFrame)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        onInteractEntity(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmourStand(PlayerArmorStandManipulateEvent event) {
        if (locked.test(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private boolean lockedView(InventoryView view) {
        if (!locked.test(view.getPlayer().getUniqueId())) {
            return false;
        }
        var top = view.getTopInventory();
        boolean ownCrafting = top.getType() == InventoryType.CRAFTING && top.getHolder() instanceof Player owner
                && owner.getUniqueId().equals(view.getPlayer().getUniqueId());
        return !ownCrafting && !top.equals(view.getPlayer().getInventory());
    }
}
