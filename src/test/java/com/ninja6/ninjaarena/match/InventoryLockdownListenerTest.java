package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.kit.InventoryLockdownListener;
import com.ninja6.ninjaarena.support.TestItemStack;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class InventoryLockdownListenerTest extends MatchFixture {
    @SuppressWarnings("unchecked")
    private <T> T inert(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (p, m, a) -> { throw new UnsupportedOperationException(m.getName()); });
    }

    private InventoryView view(Player player, InventoryType type) {
        Inventory top = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, (p, m, a) -> switch (m.getName()) {
                    case "getType" -> type;
                    case "equals" -> p == a[0];
                    case "getHolder" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                new Class<?>[] {InventoryView.class}, (p, m, a) -> switch (m.getName()) {
                    case "getPlayer" -> player;
                    case "getTopInventory" -> top;
                    case "getBottomInventory" -> player.getInventory();
                    case "getType" -> type;
                    case "convertSlot" -> a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private void assertDropAndPickup(UUID id, boolean locked) {
        var rules = new InventoryLockdownListener(matches::isInMatch);
        Item item = inert(Item.class);
        var drop = new PlayerDropItemEvent(player(id), item);
        rules.onDrop(drop);
        assertEquals(locked, drop.isCancelled());
        var pickup = new EntityPickupItemEvent(player(id), item, 0);
        rules.onPickup(pickup);
        assertEquals(locked, pickup.isCancelled());
        var attempt = new PlayerAttemptPickupItemEvent(player(id), item, 0);
        rules.onAttemptPickup(attempt);
        assertEquals(locked, attempt.isCancelled());
        var arrow = new PlayerPickupArrowEvent(player(id), item, inert(AbstractArrow.class));
        rules.onArrowPickup(arrow);
        assertEquals(locked, arrow.isCancelled());
    }

    @Test
    void dropsAndAllPickupPathsAreLockedOnlyForMatchPlayers() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        assertDropAndPickup(ALICE, true);
        assertDropAndPickup(CAROL, false);
        matches.leave(player(ALICE), MatchService.Exit.COMMAND);
        assertDropAndPickup(ALICE, false);
    }

    @Test
    void enderChestsContainersAndOtherExternalViewsAreBlockedWhilePersonalInventoryWorks() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        var rules = new InventoryLockdownListener(matches::isInMatch);
        for (InventoryType type : List.of(InventoryType.CHEST, InventoryType.ENDER_CHEST, InventoryType.SHULKER_BOX,
                InventoryType.BARREL, InventoryType.FURNACE, InventoryType.HOPPER, InventoryType.WORKBENCH,
                InventoryType.MERCHANT, InventoryType.ANVIL)) {
            var open = new InventoryOpenEvent(view(player(ALICE), type));
            rules.onOpen(open);
            assertTrue(open.isCancelled(), type.name());
            var admin = new InventoryOpenEvent(view(player(CAROL), type));
            rules.onOpen(admin);
            assertFalse(admin.isCancelled(), type.name());
        }
        var personal = new InventoryOpenEvent(players.get(ALICE).view);
        rules.onOpen(personal);
        assertFalse(personal.isCancelled());
    }

    @Test
    void inventoryDropActionsAndTransfersIntoAnAlreadyOpenContainerAreBlocked() {
        startTwoOnTwo();
        var rules = new InventoryLockdownListener(matches::isInMatch);
        for (var action : List.of(InventoryAction.DROP_ALL_CURSOR, InventoryAction.DROP_ONE_CURSOR,
                InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT)) {
            var click = new InventoryClickEvent(players.get(ALICE).view, InventoryType.SlotType.CONTAINER,
                    1, ClickType.DROP, action);
            rules.onClick(click);
            assertTrue(click.isCancelled());
        }
        var rearrange = new InventoryClickEvent(players.get(ALICE).view, InventoryType.SlotType.CONTAINER,
                1, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        rules.onClick(rearrange);
        assertFalse(rearrange.isCancelled());
        var external = view(player(ALICE), InventoryType.CHEST);
        var transfer = new InventoryClickEvent(external, InventoryType.SlotType.CONTAINER,
                1, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        rules.onClick(transfer);
        assertTrue(transfer.isCancelled());
        var item = new TestItemStack(Material.STONE, "drag", 1);
        var drag = new InventoryDragEvent(external, item, item, false, Map.of(1, item));
        rules.onDrag(drag);
        assertTrue(drag.isCancelled());
    }

    private PlayerInteractEvent interact(UUID id, Material type, boolean holder, boolean campfire) {
        Object state = campfire ? inert(Campfire.class)
                : Proxy.newProxyInstance(BlockState.class.getClassLoader(), holder
                        ? new Class<?>[] {BlockState.class, InventoryHolder.class} : new Class<?>[] {BlockState.class},
                        (p, m, a) -> { throw new UnsupportedOperationException(m.getName()); });
        Block block = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getType" -> type;
                    case "getState" -> state;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return new PlayerInteractEvent(player(id), Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP);
    }

    @Test
    void containerInteractionsIncludingDirectInsertionAreDeniedWithoutBlockingItemUse() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        var rules = new InventoryLockdownListener(matches::isInMatch);
        for (var event : List.of(interact(ALICE, Material.ENDER_CHEST, false, false),
                interact(ALICE, Material.CHISELED_BOOKSHELF, true, false),
                interact(ALICE, Material.CAMPFIRE, false, true))) {
            rules.onInteract(event);
            assertEquals(Event.Result.DENY, event.useInteractedBlock());
            assertEquals(Event.Result.DEFAULT, event.useItemInHand());
        }
        var admin = interact(CAROL, Material.CHEST, true, false);
        rules.onInteract(admin);
        assertFalse(admin.useInteractedBlock() == Event.Result.DENY);
    }

    @Test
    void adminsBuildingInEditModeHaveNoInventoryRestrictions() throws Exception {
        arenas.startEdit("dojo");
        assertDropAndPickup(ALICE, false);
        var rules = new InventoryLockdownListener(matches::isInMatch);
        var chest = new InventoryOpenEvent(view(player(ALICE), InventoryType.CHEST));
        rules.onOpen(chest);
        assertFalse(chest.isCancelled());
        var block = interact(ALICE, Material.CHEST, true, false);
        rules.onInteract(block);
        assertFalse(block.useInteractedBlock() == Event.Result.DENY);
        var click = new InventoryClickEvent(players.get(ALICE).view, InventoryType.SlotType.CONTAINER,
                1, ClickType.DROP, InventoryAction.DROP_ALL_SLOT);
        rules.onClick(click);
        assertFalse(click.isCancelled());
    }

    @Test
    void containerEntitiesAndEquipmentTransfersAreLockedOnlyForParticipants() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        var rules = new InventoryLockdownListener(matches::isInMatch);
        for (Entity entity : List.of(inert(StorageMinecart.class), inert(ArmorStand.class), inert(ItemFrame.class))) {
            var match = new PlayerInteractEntityEvent(player(ALICE), entity);
            rules.onInteractEntity(match);
            assertTrue(match.isCancelled());
            var at = new PlayerInteractAtEntityEvent(player(ALICE), entity, new Vector());
            rules.onInteractAtEntity(at);
            assertTrue(at.isCancelled());
            var admin = new PlayerInteractEntityEvent(player(CAROL), entity);
            rules.onInteractEntity(admin);
            assertFalse(admin.isCancelled());
        }
        var item = new TestItemStack(Material.LEATHER_HELMET, "armour", 1);
        var transfer = new PlayerArmorStandManipulateEvent(player(ALICE), inert(ArmorStand.class), item, item,
                EquipmentSlot.HEAD, EquipmentSlot.HAND);
        rules.onArmourStand(transfer);
        assertTrue(transfer.isCancelled());
    }
}
