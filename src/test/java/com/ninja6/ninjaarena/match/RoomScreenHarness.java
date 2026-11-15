package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.room.RoomMenu;
import com.ninja6.ninjaarena.room.RoomMenus;
import com.ninja6.ninjaarena.room.RoomScreen;
import com.ninja6.ninjaarena.room.RoomService;
import com.ninja6.ninjaarena.support.TestItemStack;
import com.ninja6.ninjaarena.texture.TextureMenu;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/** Runs the real chest menu or captures dialog content, with the same room service behind both. */
final class RoomScreenHarness {
    record Form(RoomScreen content, RoomScreen.Press press) { }
    final Map<UUID, Form> forms = new HashMap<>();
    final Map<UUID, Inventory> windows = new HashMap<>();
    final Map<Inventory, Map<Integer, String>> items = new HashMap<>();
    final Map<UUID, Player> players = new HashMap<>();
    final List<String> watched = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final List<Runnable> pending = new ArrayList<>();
    final RoomMenu chest;
    final RoomMenus menus;
    boolean failDialog;
    boolean refuseChest;
    boolean defer;
    final boolean nativeDialog;
    int shows;
    int closes;

    RoomScreenHarness(RoomService rooms, boolean nativeDialog) {
        this.nativeDialog = nativeDialog;
        chest = new RoomMenu(new TextureMenu.Screens() {
            @Override
            public Inventory create(InventoryHolder holder, int size, String title) {
                Map<Integer, String> labels = new HashMap<>();
                Inventory inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                        new Class<?>[] {Inventory.class}, (p, m, a) -> switch (m.getName()) {
                            case "getHolder" -> holder;
                            case "getSize" -> size;
                            case "getType" -> InventoryType.CHEST;
                            case "clear" -> { labels.clear(); yield null; }
                            case "setItem" -> { labels.put((Integer) a[0], ((TestItemStack) a[1]).marker()); yield null; }
                            case "equals" -> p == a[0];
                            case "hashCode" -> System.identityHashCode(p);
                            default -> throw new UnsupportedOperationException(m.getName());
                        });
                items.put(inventory, labels);
                return inventory;
            }

            @Override
            public ItemStack item(TextureMenu.Item item) {
                return new TestItemStack(item.icon(), item.name(), 1);
            }
        }, task -> {
            if (defer) { pending.add(task); } else { task.run(); }
        });
        menus = new RoomMenus(rooms, () -> nativeDialog ? "dialog" : "chest", new RoomScreen.Screens() {
            @Override
            public void show(Player player, RoomScreen content, RoomScreen.Press press) {
                if (failDialog) { throw new IllegalStateException("unavailable"); }
                shows++;
                forms.put(player.getUniqueId(), new Form(content, press));
            }

            @Override
            public void close(Player player) {
                closes++;
            }
        }, chest, (player, arena) -> watched.add(arena), warnings::add);
    }

    Player wrap(Player original) {
        return players.computeIfAbsent(original.getUniqueId(), id -> (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "openInventory":
                            shows++;
                            if (refuseChest) { return null; }
                            Inventory old = windows.put(id, (Inventory) a[0]);
                            if (old != null) { chest.closed((RoomMenu.Holder) old.getHolder()); }
                            return view((Player) p, (Inventory) a[0]);
                        case "closeInventory":
                            closes++;
                            Inventory closed = windows.remove(id);
                            if (closed != null) { chest.closed((RoomMenu.Holder) closed.getHolder()); }
                            return null;
                        case "getOpenInventory":
                            return windows.containsKey(id) ? view((Player) p, windows.get(id)) : original.getOpenInventory();
                        default:
                            try { return m.invoke(original, a); }
                            catch (InvocationTargetException e) { throw e.getCause(); }
                    }
                }));
    }

    InventoryView view(Player player, Inventory top) {
        return (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                new Class<?>[] {InventoryView.class}, (p, m, a) -> switch (m.getName()) {
                    case "getPlayer" -> player;
                    case "getTopInventory" -> top;
                    case "getBottomInventory" -> player.getInventory();
                    case "getType" -> InventoryType.CHEST;
                    case "convertSlot" -> a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    void press(Player player, String label) { press(player, label, null); }

    void press(Player player, String label, String value) {
        if (nativeDialog && !failDialog) {
            Form form = forms.get(player.getUniqueId());
            int index = -2;
            for (int i = 0; i < form.content().options().size(); i++) {
                if (form.content().options().get(i).label().equals(label)) { index = i; break; }
            }
            if (form.content().exit().equals(label)) { index = -1; }
            assertTrue(index >= -1, label);
            form.press().run(player, index, value == null ? Map.of() : Map.of("value", value));
        } else {
            Inventory inventory = windows.get(player.getUniqueId());
            var holder = (RoomMenu.Holder) inventory.getHolder();
            if (value != null) {
                for (int i = 0; i < 65 && !items.get(inventory).get(RoomMenu.INPUT).endsWith(": " + value); i++) {
                    chest.click(player, holder, RoomMenu.INPUT, false);
                }
                assertTrue(items.get(inventory).get(RoomMenu.INPUT).endsWith(": " + value));
            }
            int slot = items.get(inventory).entrySet().stream().filter(e -> e.getValue().equals(label))
                    .mapToInt(Map.Entry::getKey).findFirst().orElseThrow(() -> new AssertionError(label));
            chest.click(player, holder, slot, false);
        }
    }
}
