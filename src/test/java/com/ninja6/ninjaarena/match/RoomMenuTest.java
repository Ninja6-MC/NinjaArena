package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.kit.InventoryLockdownListener;
import com.ninja6.ninjaarena.message.Messages;
import com.ninja6.ninjaarena.room.RoomMenu;
import com.ninja6.ninjaarena.room.RoomMenuListener;
import com.ninja6.ninjaarena.room.RoomMenuMode;
import com.ninja6.ninjaarena.room.RoomScreen;
import com.ninja6.ninjaarena.support.TestItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

class RoomMenuTest extends MatchFixture {
    private RoomScreen screen(int count) {
        List<RoomScreen.Option> options = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            options.add(new RoomScreen.Option("Room " + i, Material.OAK_DOOR, (p, values) -> { }));
        }
        return new RoomScreen("Rooms", List.of(), options, null, "Close");
    }

    @Test
    void allHundredEntriesAreReachableAndPagingDoesNotRunAnAction() {
        var h = new RoomScreenHarness(null, false);
        Player alice = h.wrap(player(ALICE));
        List<Integer> pressed = new ArrayList<>();
        h.chest.show(alice, screen(100), (p, index, values) -> pressed.add(index));
        h.press(alice, "Next page");
        h.press(alice, "Next page");
        assertTrue(pressed.isEmpty());
        h.press(alice, "Room 99");
        assertEquals(List.of(99), pressed);
        h.press(alice, "Previous page");
        h.press(alice, "Room 45");
        assertEquals(List.of(99, 45), pressed);
    }

    @Test
    void clicksAreDeferredCancelledAndRejectedAfterTheWindowCloses() {
        var h = new RoomScreenHarness(null, false);
        h.defer = true;
        Player alice = h.wrap(player(ALICE));
        List<Integer> pressed = new ArrayList<>();
        h.chest.show(alice, screen(1), (p, index, values) -> pressed.add(index));
        Inventory top = h.windows.get(ALICE);
        var listener = new RoomMenuListener(h.chest, h.menus);
        var view = h.view(alice, top);
        for (var click : ClickType.values()) {
            if (click == ClickType.LEFT || click == ClickType.RIGHT) { continue; }
            var event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                    0, click, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            listener.onClick(event);
            assertTrue(event.isCancelled());
        }
        assertTrue(h.pending.isEmpty());
        for (int slot : new int[] {54, 80, -999}) {
            var event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                    slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            listener.onClick(event);
            assertTrue(event.isCancelled());
        }
        assertTrue(h.pending.isEmpty());
        var event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(event);
        assertTrue(event.isCancelled());
        assertEquals(1, h.pending.size());
        assertTrue(pressed.isEmpty());
        listener.onClose(new InventoryCloseEvent(view));
        h.pending.removeFirst().run();
        assertTrue(pressed.isEmpty());
    }

    @Test
    void dragsHoppersAndWrongOwnersCannotMoveItemsOrPressActions() {
        var h = new RoomScreenHarness(null, false);
        Player alice = h.wrap(player(ALICE));
        Player bob = h.wrap(player(BOB));
        List<Integer> pressed = new ArrayList<>();
        h.chest.show(alice, screen(1), (p, index, values) -> pressed.add(index));
        Inventory top = h.windows.get(ALICE);
        var listener = new RoomMenuListener(h.chest, h.menus);
        var item = new TestItemStack(Material.STONE, "stone", 1);
        var drag = new InventoryDragEvent(h.view(alice, top), item, item, false, Map.of(0, item));
        listener.onDrag(drag);
        assertTrue(drag.isCancelled());
        var move = new InventoryMoveItemEvent(top, item, top, true);
        listener.onMove(move);
        assertTrue(move.isCancelled());
        h.chest.click(bob, (RoomMenu.Holder) top.getHolder(), 0, false);
        assertTrue(pressed.isEmpty());
    }

    @Test
    void roomChestHolderPassesInventoryLockdownDuringAnActualMatch() {
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        assertTrue(matches.isInMatch(ALICE));
        var h = new RoomScreenHarness(null, false);
        Player alice = h.wrap(player(ALICE));
        h.chest.show(alice, screen(1), (p, index, values) -> { });
        var lockdown = new InventoryLockdownListener(matches::isInMatch);
        var event = new InventoryOpenEvent(h.view(alice, h.windows.get(ALICE)));
        lockdown.onOpen(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void settingsCycleBothWaysAndOnlySubmitTheValueOnSave() {
        var h = new RoomScreenHarness(null, false);
        Player alice = h.wrap(player(ALICE));
        List<String> submitted = new ArrayList<>();
        var input = new RoomScreen.Input("Players", List.of("1", "2", "3"), List.of("1", "2", "3"), "2");
        var screen = new RoomScreen("Settings", List.of(), List.of(
                new RoomScreen.Option("Save", Material.LIME_WOOL, (p, values) -> { })), input, "Cancel");
        h.chest.show(alice, screen, (p, index, values) -> submitted.add(values.get("value")));
        Inventory top = h.windows.get(ALICE);
        h.chest.click(alice, (RoomMenu.Holder) top.getHolder(), RoomMenu.INPUT, true);
        assertTrue(h.items.get(top).get(RoomMenu.INPUT).endsWith(": 1"));
        assertTrue(submitted.isEmpty());
        h.chest.click(alice, (RoomMenu.Holder) top.getHolder(), RoomMenu.INPUT, false);
        h.press(alice, "Save");
        assertEquals(List.of("2"), submitted);
    }

    @Test
    void configDefaultsAndInvalidValuesUseDialogsAndChestIsIndependent() {
        List<String> warnings = new ArrayList<>();
        assertEquals(RoomMenuMode.DIALOG, RoomMenuMode.parse(null, warnings::add));
        assertEquals(RoomMenuMode.CHEST, RoomMenuMode.parse(" CHEST ", warnings::add));
        assertTrue(warnings.isEmpty());
        assertEquals(RoomMenuMode.DIALOG, RoomMenuMode.parse(12, warnings::add));
        assertEquals(1, warnings.size());
    }

    @Test
    void defaultTitlesAndLabelsFitBothClientsWithLongNamesAndCounts() {
        var body = java.util.Set.of("hint", "create-info", "settings-info", "save-info", "start-info",
                "summary", "stale", "refused", "cycle", "creator", "cohosts-line", "host-away", "cohosts-info",
                "cohosts-empty", "request-info", "privacy-line");
        for (String key : Messages.defaults().keySet()) {
            if (!key.startsWith("room.screen.")) { continue; }
            String tail = key.substring("room.screen.".length());
            if (body.contains(tail)) { continue; }
            String value = Messages.text(key, "host", "a".repeat(20), "size", 64,
                    "player", "b".repeat(20), "map", "m".repeat(20), "label", "Players needed", "value", "64");
            assertTrue(value.length() < (tail.startsWith("title.") ? 55 : 40), key + ": " + value);
        }
    }
}
