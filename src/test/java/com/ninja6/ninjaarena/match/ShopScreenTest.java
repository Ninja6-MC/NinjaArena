package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.kit.InventoryLockdownListener;
import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.resourcepack.BedrockIntegration;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.shop.Shop;
import com.ninja6.ninjaarena.shop.ShopCatalogue;
import com.ninja6.ninjaarena.shop.ShopListener;
import com.ninja6.ninjaarena.shop.ShopMenu;
import com.ninja6.ninjaarena.shop.ShopMenus;
import com.ninja6.ninjaarena.shop.ShopMenus.Action;
import com.ninja6.ninjaarena.shop.ShopMenus.Button;
import com.ninja6.ninjaarena.shop.ShopMenus.Content;
import com.ninja6.ninjaarena.shop.ShopMenus.Kind;
import com.ninja6.ninjaarena.shop.ShopMenus.Press;
import com.ninja6.ninjaarena.shop.ShopMenus.Surface;
import com.ninja6.ninjaarena.support.TestItemStack;
import com.ninja6.ninjaarena.tool.NinjaTools;
import com.ninja6.ninjaarena.tool.ToolSettings;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real match, points and buying paths with captured dialogs and chest inventories. */
class ShopScreenTest extends MatchFixture {
    private record Shown(Player player, Content content, Press press) { }
    private final class Dialogs implements Surface {
        final List<Shown> shown = new ArrayList<>();
        int closes;
        boolean fail;
        @Override public void show(Player player, Content content, Press press) {
            if (fail) { throw new IllegalStateException("unavailable"); }
            shown.add(new Shown(player, content, press));
        }
        @Override public void close(Player player) { closes++; }
    }
    private record Window(Inventory inventory, Map<Integer, String> labels) { }
    private final class Chests implements ShopMenu.Screens {
        final List<Window> windows = new ArrayList<>();
        final Map<UUID, InventoryView> views = new HashMap<>();
        final List<Runnable> pending = new ArrayList<>();
        final ShopMenu menu = new ShopMenu(this, pending::add);
        @Override public Inventory create(InventoryHolder holder, String title) {
            Map<Integer, String> labels = new HashMap<>();
            Inventory inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                    new Class<?>[] {Inventory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getHolder" -> holder;
                        case "getSize" -> 54;
                        case "getType" -> InventoryType.CHEST;
                        case "setItem" -> { labels.put((Integer) args[0], ((TestItemStack) args[1]).marker()); yield null; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            windows.add(new Window(inventory, labels));
            return inventory;
        }
        @Override public ItemStack item(String label, List<String> lore) {
            return new TestItemStack(Material.PAPER, label + String.join("", lore), 1);
        }
        Player wrap(UUID id) {
            Player real = player(id);
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("openInventory")) {
                            Inventory top = (Inventory) args[0];
                            InventoryView previous = views.get(id);
                            if (previous != null) { menu.onClose(new InventoryCloseEvent(previous)); }
                            InventoryView view = view((Player) proxy, top);
                            var event = new InventoryOpenEvent(view);
                            lockdown.onOpen(event);
                            assertFalse(event.isCancelled());
                            views.put(id, view);
                            return view;
                        }
                        if (method.getName().equals("closeInventory")) {
                            InventoryView previous = views.remove(id);
                            if (previous != null) { menu.onClose(new InventoryCloseEvent(previous)); }
                            return null;
                        }
                        try { return method.invoke(real, args); }
                        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
        }
        InventoryView view(Player player, Inventory top) {
            return (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                    new Class<?>[] {InventoryView.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getTopInventory" -> top;
                        case "getBottomInventory" -> player.getInventory();
                        case "getPlayer" -> player;
                        case "getType" -> InventoryType.CHEST;
                        case "convertSlot" -> args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
        void click(UUID id, int slot, ClickType type) {
            var event = new InventoryClickEvent(views.get(id), InventoryType.SlotType.CONTAINER,
                    slot, type, InventoryAction.PICKUP_ALL);
            lockdown.onClick(event);
            menu.onClick(event);
            assertTrue(event.isCancelled());
            List<Runnable> tasks = List.copyOf(pending);
            pending.clear();
            tasks.forEach(Runnable::run);
        }
    }

    private PlayerPoints points;
    private Shop shop;
    private ShopMenus menus;
    private Dialogs dialogs;
    private Chests chests;
    private InventoryLockdownListener lockdown;
    private Object mode = "dialog";
    private final List<String> warnings = new ArrayList<>();
    private final BedrockIntegration integration = new BedrockIntegration() {
        @Override public boolean isBedrock(UUID id) { return id.equals(CAROL); }
        @Override public boolean customHeld(UUID id) { return false; }
        @Override public void close() { }
    };

    @BeforeEach
    void setupShop() {
        points = new PlayerPoints(PointsSettings.DEFAULTS);
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, p -> RestoreResult.RESTORED,
                event -> {
                    if (event instanceof RoundEndEvent end) {
                        points.onRoundEnd(end);
                        if (shop != null) { shop.onRoundEnd(end); }
                    }
                }, p -> { });
        matches.setShopSettings(new ShopSettings(true, 30, 5, 5));
        KitItems items = new KitItems(material -> new TestItemStack(material, "vanilla", 1));
        var tools = new NinjaTools(matches, plugin.getServer()::getPlayer,
                name -> arenas.get(name).map(a -> a.bounds()), ToolSettings.DEFAULTS,
                () -> 0L, (victim, damage, projectile, thrower) -> { }, location -> null);
        // Initialise effect constants before the catalogue resolves one through the test registry.
        org.bukkit.potion.PotionEffectType.SPEED.getKey();
        var catalogue = ShopCatalogue.open(dir.resolve("shop.yml"), plugin.getLogger(), () -> {
            try (var in = getClass().getClassLoader().getResourceAsStream("shop.yml")) {
                java.nio.file.Files.copy(in, dir.resolve("shop.yml"));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }, items, ToolSettings.DEFAULTS);
        shop = new Shop(matches, points, items, tools, plugin.getServer()::getPlayer, plugin.getLogger(),
                catalogue, match -> match.phase() == MatchPhase.SHOP);
        lockdown = new InventoryLockdownListener(id -> true);
        dialogs = new Dialogs();
        chests = new Chests();
        menus = new ShopMenus(matches, shop, points, () -> mode, integration, dialogs, chests.menu, warnings::add);
        players.values().forEach(p -> { p.canShop = true; p.permissions.add("ninjaarena.play"); });
    }

    private MatchView match() { return matches.matchOf(ALICE).orElseThrow(); }
    private void toShop() {
        startTwoOnTwo();
        ticks(SETTINGS.prepSeconds());
        points.enemyKilled(match(), ALICE, 20);
        points.enemyKilled(match(), CAROL, 20);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(CAROL));
        runNextTick();
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.SHOP, match().phase());
    }
    private Shown last() { return dialogs.shown.getLast(); }
    private void press(Shown shown, Kind kind) {
        var action = shown.content().buttons().stream().map(Button::action)
                .filter(value -> value.kind() == kind).findFirst().orElseThrow();
        shown.press().pressed(shown.player(), action);
    }
    private void category(Shown shown, String category) {
        shown.press().pressed(shown.player(), new Action(Kind.CATEGORY, category));
    }

    @Test void callbackLifetimeCoversTheMatchesLongestConfiguredShopAndMapWait() {
        matches.setShopSettings(new ShopSettings(true, 3600, 5, 3600));
        toShop();
        matches.setShopSettings(ShopSettings.DEFAULTS);
        menus.open(player(ALICE));
        assertEquals(java.time.Duration.ofSeconds(7260), last().content().callbackLifetime());
        category(last(), "CONSUMABLES");
        assertEquals(java.time.Duration.ofSeconds(7260), last().content().callbackLifetime());
    }

    @Test void javaAndReportedBedrockBuyAndReadyWithExactlyOneFreshDialogPerPurchase() {
        toShop();
        for (UUID id : List.of(ALICE, CAROL)) {
            menus.open(player(id));
            assertTrue(last().content().lines().getFirst().startsWith("Balance: "));
            category(last(), "CONSUMABLES");
            Shown buying = last();
            assertEquals(id.equals(CAROL) ? "Cooked beef: 1 pts, 2 left" : "Cooked beef | 1 pts | 2 left",
                    buying.content().buttons().getFirst().label());
            int before = points.points(match(), id);
            int shown = dialogs.shown.size();
            press(buying, Kind.BUY);
            assertEquals(shown + 1, dialogs.shown.size());
            assertEquals(before - 1, points.points(match(), id));
            assertEquals(1, shop.remaining(match(), id, "cooked-beef"));
            assertTrue(last().content().lines().contains("Purchase queued for the next round."));
            press(buying, Kind.BUY); // A replayed old form cannot spend again or enqueue another screen.
            assertEquals(shown + 1, dialogs.shown.size());
            assertEquals(before - 1, points.points(match(), id));
            press(last(), Kind.READY);
            assertTrue(match().readyPlayers().contains(id));
        }
        assertEquals(2, dialogs.closes);
    }

    @Test void javaAndReportedBedrockBuyAndReadyFromChestThroughInventoryLockdown() {
        toShop();
        mode = "chest";
        for (UUID id : List.of(ALICE, CAROL)) {
            Player player = chests.wrap(id);
            menus.open(player);
            Window root = chests.windows.getLast();
            int slot = root.labels().entrySet().stream().filter(e -> e.getValue().equals("Consumables"))
                    .findFirst().orElseThrow().getKey();
            chests.click(id, slot, ClickType.LEFT);
            assertEquals(id.equals(CAROL) ? "Cooked beef: 1 pts, 2 left" : "Cooked beef | 1 pts | 2 left",
                    chests.windows.getLast().labels().get(0));
            int balance = points.points(match(), id);
            chests.click(id, 0, ClickType.SHIFT_LEFT);
            assertEquals(balance, points.points(match(), id));
            chests.click(id, 0, ClickType.LEFT);
            assertEquals(balance - 1, points.points(match(), id));
            assertEquals(id.equals(CAROL) ? "Cooked beef: 1 pts, 1 left" : "Cooked beef | 1 pts | 1 left",
                    chests.windows.getLast().labels().get(0));
            var ready = chests.windows.getLast().labels().entrySet().stream()
                    .filter(e -> e.getValue().equals("Ready")).findFirst().orElseThrow().getKey();
            chests.click(id, ready, ClickType.RIGHT);
            assertTrue(match().readyPlayers().contains(id));
            assertFalse(chests.views.containsKey(id));
        }
        assertTrue(dialogs.shown.isEmpty());
    }

    @Test void defaultItemLabelsCarryNamePriceAndRemainingWithinClientLimits() {
        toShop();
        menus.open(player(ALICE));
        Shown root = last();
        assertTrue(root.content().title().length() < 55);
        for (var button : root.content().buttons()) {
            if (button.action().kind() != Kind.CATEGORY) { continue; }
            menus.open(player(ALICE));
            category(last(), button.action().value());
            for (Button item : last().content().buttons()) {
                assertTrue(item.label().length() < 40, item.label());
                if (item.action().kind() == Kind.BUY) {
                    var entry = shop.catalogue(match()).items().get(item.action().value());
                    assertTrue(item.label().contains(entry.price() + " pts"));
                    assertTrue(item.label().contains(entry.limit() + " left"));
                }
            }
        }
    }

    @Test void refusedOutsideShopAndForNonMatchPlayersInEitherMode() {
        for (String selected : List.of("dialog", "chest")) {
            mode = selected;
            menus.open(player(ALICE));
            assertEquals("The shop is only open between rounds.", players.get(ALICE).messages.getLast());
        }
        startTwoOnTwo();
        menus.open(player(ALICE));
        assertTrue(chests.windows.isEmpty());
        assertTrue(dialogs.shown.isEmpty());
    }

    @Test void permissionsOwnershipAndPhaseAreRecheckedOnCallback() {
        toShop();
        menus.open(player(ALICE));
        category(last(), "CONSUMABLES");
        Shown shown = last();
        var buy = shown.content().buttons().getFirst().action();
        int before = points.points(match(), ALICE);
        shown.press().pressed(player(CAROL), buy);
        assertEquals(before, points.points(match(), ALICE));
        players.get(ALICE).canShop = false;
        press(shown, Kind.BUY);
        assertEquals(before, points.points(match(), ALICE));
        assertTrue(last().content().lines().contains("You do not have permission to use the shop."));
        menus.open(player(ALICE));
        players.get(ALICE).permissions.clear();
        press(last(), Kind.READY);
        assertFalse(match().readyPlayers().contains(ALICE));
        players.get(ALICE).permissions.add("ninjaarena.play");
        menus.open(player(ALICE));
        category(last(), "CONSUMABLES");
        shown = last();
        ticks(30);
        press(shown, Kind.BUY);
        assertEquals(before, points.points(match(), ALICE));
        assertEquals("The shop is only open between rounds.", players.get(ALICE).messages.getLast());
    }

    @Test void readyAfterTheShopClosesKeepsTheReadyRefusalMessage() {
        toShop();
        menus.open(player(ALICE));
        Shown shown = last();
        ticks(30);
        press(shown, Kind.READY);
        assertEquals("You can only get ready while your match is in the shop.", players.get(ALICE).messages.getLast());
    }

    @Test void exitAndLifecycleInvalidateOldCallbacksWithoutOpeningAnything() {
        toShop();
        menus.open(player(ALICE));
        Shown old = last();
        old.press().pressed(player(ALICE), new Action(Kind.CLOSE));
        press(old, Kind.READY);
        assertFalse(match().readyPlayers().contains(ALICE));
        assertEquals(1, dialogs.shown.size());
        menus.open(player(ALICE));
        Shown next = last();
        var lifecycle = new ShopListener(menus, plugin.getServer()::getPlayer);
        lifecycle.onPhase(new com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent(match(), MatchPhase.SHOP,
                MatchPhase.PREP));
        press(next, Kind.READY);
        assertFalse(match().readyPlayers().contains(ALICE));
        assertEquals(2, dialogs.shown.size());
    }

    @Test void failedDialogFallsBackToUsableChest() {
        dialogs.fail = true;
        toShop();
        menus.open(chests.wrap(ALICE));
        assertEquals(1, chests.windows.size());
        assertTrue(chests.windows.getFirst().labels().containsValue("Ready"));
    }
    @Test void largeSingleCategoryCataloguesPageAndAnEmptyShopStillHasReady() {
        Map<String, ShopCatalogue.Entry> entries = new LinkedHashMap<>();
        for (int i = 0; i < 80; i++) {
            String id = "food-" + i;
            entries.put(id, new ShopCatalogue.Entry(id, 1, 2, ShopCatalogue.Category.CONSUMABLES,
                    new ShopCatalogue.Consumable(new KitDefinition.Entry(0, Material.COOKED_BEEF, null, 1))));
        }
        shop.setDefinition(new ShopCatalogue(entries));
        toShop();
        menus.open(player(ALICE));
        assertEquals(36, last().content().buttons().stream().filter(b -> b.action().kind() == Kind.BUY).count());
        assertEquals("food-0", last().content().buttons().getFirst().action().value());
        press(last(), Kind.NEXT);
        assertEquals("food-36", last().content().buttons().getFirst().action().value());
        press(last(), Kind.NEXT);
        assertEquals(8, last().content().buttons().stream().filter(b -> b.action().kind() == Kind.BUY).count());
        press(last(), Kind.PREVIOUS);
        assertEquals("food-36", last().content().buttons().getFirst().action().value());
    }

    @Test void emptyShopHasBalanceAndReadyWithoutCategoryOrPurchaseButtons() {
        shop.setDefinition(ShopCatalogue.EMPTY);
        toShop();
        menus.open(player(ALICE));
        assertEquals(List.of(Kind.READY), last().content().buttons().stream().map(b -> b.action().kind()).toList());
        assertTrue(last().content().lines().contains("No items available. You can get ready."));
        press(last(), Kind.READY);
        assertTrue(match().readyPlayers().contains(ALICE));
    }

    @Test void purchaseFailuresRefreshOnceWithTheRealBuyResultAndNoSpending() {
        toShop();
        menus.open(player(ALICE));
        category(last(), "CONSUMABLES");
        press(last(), Kind.BUY);
        press(last(), Kind.BUY);
        int balance = points.points(match(), ALICE);
        int shows = dialogs.shown.size();
        press(last(), Kind.BUY);
        assertEquals(shows + 1, dialogs.shown.size());
        assertEquals(balance, points.points(match(), ALICE));
        assertTrue(last().content().lines().contains("You reached this item's buy limit for the round."));
        assertTrue(points.spend(match(), ALICE, balance));
        press(last(), Kind.BACK);
        category(last(), "ARMOUR");
        press(last(), Kind.BUY);
        assertTrue(last().content().lines().contains("You do not have enough points."));
        assertEquals(0, points.points(match(), ALICE));
    }

    @Test void invalidModeAndDialogFallbackEachWarnOnce() {
        toShop();
        mode = "invalid";
        dialogs.fail = true;
        menus.open(chests.wrap(ALICE));
        menus.open(chests.wrap(CAROL));
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("shop.menu must be dialog or chest"));
        assertTrue(warnings.get(1).contains("The shop dialog could not be shown"));
    }

    @Test void dialogFallbackDoesNotSuppressALaterInvalidModeWarning() {
        toShop();
        dialogs.fail = true;
        menus.open(chests.wrap(ALICE));
        mode = "invalid";
        menus.open(chests.wrap(CAROL));
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("The shop dialog could not be shown"));
        assertTrue(warnings.get(1).contains("shop.menu must be dialog or chest"));
    }

}
