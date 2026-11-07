package com.ninja6.ninjaarena.texture;

import static com.ninja6.ninjaarena.texture.TextureFixture.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.support.TestItemStack;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TextureListenerTest {

    private TextureFixture fixture;
    private MenuHarness harness;
    private TextureListener listener;
    private MenuHarness.Screen screen;

    @BeforeEach
    void open() {
        fixture = new TextureFixture(true, true);
        harness = new MenuHarness(fixture);
        listener = new TextureListener(fixture.service, harness.menu);
        fixture.join(ALICE);
        fixture.loadBase(ALICE);
        fixture.settle();
        harness.menu.open(harness.player(ALICE));
        screen = harness.screen();
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    private InventoryClickEvent click(ClickType type, InventoryAction action, int rawSlot) {
        return new InventoryClickEvent(harness.view(ALICE, screen.inventory), InventoryType.SlotType.CONTAINER,
                rawSlot, type, action);
    }

    @ParameterizedTest
    @EnumSource(value = ClickType.class, names = {"SHIFT_LEFT", "SHIFT_RIGHT", "NUMBER_KEY", "DOUBLE_CLICK", "DROP",
            "CONTROL_DROP", "SWAP_OFFHAND", "MIDDLE", "CREATIVE", "WINDOW_BORDER_LEFT"})
    void everyNonPlainClickIsCancelledAndNeverChoosesAnything(ClickType type) {
        for (int slot : new int[] {TextureMenu.STANDARD_SLOT, TextureMenu.DETAILED_SLOT, 3, 30, 40, -999}) {
            var event = click(type, InventoryAction.MOVE_TO_OTHER_INVENTORY, slot);
            listener.onClick(event);
            assertTrue(event.isCancelled(), type + " at " + slot);
        }
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
    }

    @Test
    void aPlainClickOnAChoiceIsCancelledAndSavesIt() {
        var event = click(ClickType.LEFT, InventoryAction.PICKUP_ALL, TextureMenu.DETAILED_SLOT);
        listener.onClick(event);
        assertTrue(event.isCancelled());
        fixture.settle();
        assertEquals(1, fixture.backend.writes.get());
        var right = click(ClickType.RIGHT, InventoryAction.PICKUP_HALF, TextureMenu.STANDARD_SLOT);
        listener.onClick(right);
        assertTrue(right.isCancelled());
        fixture.settle();
        assertEquals(2, fixture.backend.writes.get());
    }

    @Test
    void clicksInThePlayersOwnInventoryWhileTheMenuIsOpenAreCancelledAndDoNothing() {
        for (int slot : new int[] {27, 40, 62}) {
            var event = click(ClickType.LEFT, InventoryAction.PICKUP_ALL, slot);
            listener.onClick(event);
            assertTrue(event.isCancelled(), "slot " + slot);
        }
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
    }

    @Test
    void aStaleMenuClickedThroughTheListenerIsCancelledAndRefused() {
        var stale = screen;
        harness.menu.open(harness.player(ALICE));
        var event = new InventoryClickEvent(harness.view(ALICE, stale.inventory), InventoryType.SlotType.CONTAINER,
                TextureMenu.DETAILED_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(event);
        assertTrue(event.isCancelled());
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
    }

    @Test
    void dragsAcrossTheMenuAreCancelled() {
        var item = new TestItemStack(Material.STONE, "drag", 1);
        var drag = new InventoryDragEvent(harness.view(ALICE, screen.inventory), item, item, false,
                Map.of(10, item, 11, item));
        listener.onDrag(drag);
        assertTrue(drag.isCancelled());
    }

    @Test
    void hopperStyleMovesInvolvingTheMenuAreCancelled() {
        Inventory other = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, (p, m, a) -> switch (m.getName()) {
                    case "getHolder" -> null;
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        var item = new TestItemStack(Material.STONE, "move", 1);
        var in = new InventoryMoveItemEvent(other, item, screen.inventory, true);
        listener.onMove(in);
        assertTrue(in.isCancelled());
        var out = new InventoryMoveItemEvent(screen.inventory, item, other, false);
        listener.onMove(out);
        assertTrue(out.isCancelled());
        var unrelated = new InventoryMoveItemEvent(other, item, other, true);
        listener.onMove(unrelated);
        assertFalse(unrelated.isCancelled());
    }

    @Test
    void otherInventoriesAreNotTouched() {
        Inventory chest = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, (p, m, a) -> switch (m.getName()) {
                    case "getHolder" -> (InventoryHolder) null;
                    case "getSize" -> 27;
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        var event = new InventoryClickEvent(harness.view(ALICE, chest), InventoryType.SlotType.CONTAINER, 10,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void closingTheMenuForgetsItAndQuitDropsTheSession() {
        listener.onClose(new InventoryCloseEvent(harness.view(ALICE, screen.inventory)));
        assertFalse(harness.menu.isCurrent(ALICE, screen.holder));
        var event = click(ClickType.LEFT, InventoryAction.PICKUP_ALL, TextureMenu.DETAILED_SLOT);
        listener.onClick(event);
        assertTrue(event.isCancelled());
        fixture.settle();
        assertTrue(fixture.backend.rows.isEmpty());
    }
}
