package com.ninja6.ninjaarena.texture;

import com.ninja6.ninjaarena.support.TestItemStack;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/** Players, inventories and views as proxies, so the menu runs without a server. */
final class MenuHarness {

    final TextureFixture fixture;
    final List<String> menuTold = new ArrayList<>();
    final TextureMenu menu;
    final List<Opened> opened = new ArrayList<>();
    final Map<UUID, Set<String>> permissions = new HashMap<>();
    final Map<UUID, Integer> closes = new HashMap<>();
    boolean refuseOpen;

    record Opened(UUID player, Inventory inventory) { }

    /** Item text recorded by slot: name, then lore lines, joined by a bar. */
    final class Screen {
        final Map<Integer, String> items = new HashMap<>();
        final Map<Integer, Material> icons = new HashMap<>();
        int sets;
        TextureMenu.Holder holder;
        Inventory inventory;
    }

    final List<Screen> screens = new ArrayList<>();

    MenuHarness(TextureFixture fixture) {
        this.fixture = fixture;
        menu = new TextureMenu(fixture.service, new TextureMenu.Screens() {
            @Override
            public Inventory create(InventoryHolder holder, int size, String title) {
                Screen screen = new Screen();
                screen.holder = (TextureMenu.Holder) holder;
                screen.inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                        new Class<?>[] {Inventory.class}, (p, m, a) -> switch (m.getName()) {
                            case "getSize" -> size;
                            case "getHolder" -> holder;
                            case "getType" -> InventoryType.CHEST;
                            case "setItem" -> {
                                screen.sets++;
                                screen.items.put((Integer) a[0], ((TestItemStack) a[1]).marker());
                                screen.icons.put((Integer) a[0], ((TestItemStack) a[1]).getType());
                                yield null;
                            }
                            case "equals" -> p == a[0];
                            case "hashCode" -> System.identityHashCode(p);
                            default -> throw new UnsupportedOperationException(m.getName());
                        });
                screens.add(screen);
                if (!com.ninja6.ninjaarena.message.Messages.text("texture.title").equals(title)) {
                    throw new AssertionError(title);
                }
                return screen.inventory;
            }

            @Override
            public ItemStack item(TextureMenu.Item item) {
                return new TestItemStack(item.icon(), item.name() + "|" + String.join("|", item.lore()), 1);
            }
        }, (id, message) -> menuTold.add(message));
    }

    Screen screen() {
        return screens.getLast();
    }

    InventoryView view(UUID owner, Inventory top) {
        Player player = player(owner);
        return (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                new Class<?>[] {InventoryView.class}, (p, m, a) -> switch (m.getName()) {
                    case "getPlayer" -> player;
                    case "getTopInventory" -> top;
                    case "getBottomInventory" -> bottom();
                    case "getType" -> InventoryType.CHEST;
                    case "convertSlot" -> a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private Inventory bottom() {
        return (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, (p, m, a) -> switch (m.getName()) {
                    case "getSize" -> 36;
                    case "getHolder" -> null;
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private final Map<UUID, Player> players = new HashMap<>();

    Player player(UUID id) {
        return players.computeIfAbsent(id, key -> (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (p, m, a) -> switch (m.getName()) {
                    case "getUniqueId" -> key;
                    case "hasPermission" -> permissions.getOrDefault(key, new HashSet<>(Set.of(TextureMenu.PERMISSION)))
                            .contains((String) a[0]);
                    case "closeInventory" -> {
                        closes.merge(key, 1, Integer::sum);
                        yield null;
                    }
                    case "openInventory" -> {
                        if (refuseOpen) {
                            yield null;
                        }
                        opened.add(new Opened(key, (Inventory) a[0]));
                        yield view(key, (Inventory) a[0]);
                    }
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> throw new UnsupportedOperationException(m.getName());
                }));
    }

    void revokePermission(UUID id) {
        permissions.put(id, new HashSet<>());
    }
}
