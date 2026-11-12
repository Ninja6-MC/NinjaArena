package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.kit.RoundKitListener;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.shop.Shop;
import com.ninja6.ninjaarena.shop.ShopCatalogue;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Armour;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Category;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Consumable;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Entry;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Potion;
import com.ninja6.ninjaarena.shop.ShopCatalogue.ToolUses;
import com.ninja6.ninjaarena.support.TestItemStack;
import com.ninja6.ninjaarena.tool.NinjaTool;
import com.ninja6.ninjaarena.tool.NinjaTools;
import com.ninja6.ninjaarena.tool.ToolSettings;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

class ShopTest extends MatchFixture {
    private PlayerPoints points;
    private Shop shop;
    private NinjaTools tools;
    private RoundKitListener kits;
    private KitItems items;
    private ShopCatalogue definition;
    private boolean brokenProvider;

    private void setup(Predicate<MatchView> open) {
        points = new PlayerPoints(PointsSettings.DEFAULTS);
        items = new KitItems(material -> new TestItemStack(material, "vanilla", 1));
        items.register("test:food", 64, player -> {
            if (brokenProvider) { throw new IllegalStateException("unavailable item"); }
            return new TestItemStack(Material.COOKED_BEEF, "food", 1);
        });
        definition = new ShopCatalogue(Map.of(
                "armour", new Entry("armour", 2, 1, Category.ARMOUR, new Armour("iron")),
                "tools", new Entry("tools", 2, 1, Category.TOOLS, new ToolUses(NinjaTool.SHURIKEN, 2)),
                "potion", new Entry("potion", 2, 1, Category.POTIONS, new Potion(PotionEffectType.SPEED, 0, 500)),
                "food", new Entry("food", 2, 2, Category.CONSUMABLES,
                        new Consumable(new KitDefinition.Entry(0, null, "test:food", 1)))));
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> RestoreResult.RESTORED,
                event -> {
                    events.add(event);
                    switch (event) {
                        case RoundStartEvent start -> {
                            tools.onRoundStart(start);
                            kits.onRoundStart(start);
                            shop.onRoundStart(start);
                        }
                        case RoundEndEvent end -> {
                            points.onRoundEnd(end);
                            tools.onRoundEnd(end);
                            shop.onRoundEnd(end);
                        }
                        case PlayerLeaveMatchEvent leave -> {
                            points.onLeave(leave);
                            tools.onLeave(leave);
                            shop.onLeave(leave);
                        }
                        case MatchEndEvent end -> {
                            points.onMatchEnd(end);
                            tools.onMatchEnd(end);
                            shop.onMatchEnd(end);
                        }
                        default -> { }
                    }
                }, player -> { });
        tools = new NinjaTools(matches, plugin.getServer()::getPlayer, name -> arenas.get(name).map(a -> a.bounds()),
                ToolSettings.DEFAULTS, () -> 0L, (victim, damage, projectile, thrower) -> { }, location -> null);
        shop = open == null ? new Shop(matches, points, items, tools, plugin.getServer()::getPlayer,
                plugin.getLogger(), definition) : new Shop(matches, points, items, tools,
                plugin.getServer()::getPlayer, plugin.getLogger(), definition, open);
        kits = new RoundKitListener(matches, plugin.getServer()::getPlayer, KitDefinition.EMPTY, items, plugin.getLogger());
        kits.setAfterKit(shop::apply);
        players.values().forEach(player -> player.canShop = true);
        startTwoOnTwo();
        ticks(2);
    }

    private MatchView match() { return matches.matchOf(ALICE).orElseThrow(); }
    private void earn(int amount) { points.enemyKilled(match(), ALICE, amount); }

    @Test
    void everyBuyResultAndAtomicSpending() {
        setup(match -> true);
        assertEquals(Shop.Result.UNKNOWN_ITEM, shop.buy(match(), player(ALICE), "missing"));
        assertEquals(Shop.Result.UNKNOWN_ITEM, shop.buy(match(), player(ALICE), null));
        assertEquals(Shop.Result.NOT_ENOUGH_POINTS, shop.buy(match(), player(ALICE), "food"));
        earn(5);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "food"));
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "food"));
        assertEquals(1, points.points(match(), ALICE));
        assertEquals(Shop.Result.LIMIT_REACHED, shop.buy(match(), player(ALICE), "food"));
        assertEquals(Shop.Result.NOT_ENOUGH_POINTS, shop.buy(match(), player(ALICE), "armour"));
        players.get(ALICE).canShop = false;
        assertEquals(Shop.Result.NO_PERMISSION, shop.buy(match(), player(ALICE), "armour"));
        assertEquals(0, points.points(match(), BOB));
        assertFalse(points.spend(match(), ALICE, 2));
        assertFalse(points.spend(match(), ALICE, 0));
        assertFalse(points.spend(match(), ALICE, -1));
        assertEquals(1, points.points(match(), ALICE));
        for (Shop.Result result : Shop.Result.values()) {
            assertNotNull(com.ninja6.ninjaarena.message.Messages.text(result.messageKey()));
        }
    }

    @Test
    void buyingIsClosedByDefault() {
        setup(null);
        earn(10);
        assertEquals(Shop.Result.CLOSED, shop.buy(match(), player(ALICE), "food"));
        assertEquals(10, points.points(match(), ALICE));
    }

    @Test
    void catalogueReloadKeepsRunningMatchesEvenBeforeTheirFirstPurchase() {
        setup(match -> true);
        shop.setDefinition(ShopCatalogue.EMPTY);
        assertSame(definition, shop.catalogue(match()));
        earn(2);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "food"));
        matches.close();
        startTwoOnTwo();
        assertSame(ShopCatalogue.EMPTY, shop.catalogue(match()));
    }

    @Test
    void purchasesAppearAfterRoundKitAndEffectsAreRemovedAtRoundEnd() {
        setup(match -> true);
        earn(20);
        for (String id : List.of("armour", "tools", "potion", "food")) {
            assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), id));
        }
        var start = new RoundStartEvent(match(), match().round(), false);
        tools.onRoundStart(start);
        kits.onRoundStart(start);
        shop.onRoundStart(start);
        assertEquals(Material.IRON_BOOTS, players.get(ALICE).armour[0].getType());
        assertEquals(Material.IRON_HELMET, players.get(ALICE).armour[3].getType());
        assertEquals("food", ((TestItemStack) players.get(ALICE).storage[0]).marker());
        assertEquals(PotionEffectType.SPEED, players.get(ALICE).effects.getFirst().getType());
        matches.died(player(BOB), null);
        matches.died(player(DAVE), null);
        runNextTick();
        assertTrue(players.get(ALICE).effects.isEmpty());
        ticks(1);
        assertNull(players.get(ALICE).armour[0]);
        assertNull(players.get(ALICE).storage[0]);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "armour"));
    }

    @Test
    void delayedRespawnHookDeliversAfterKitAndDoesNotReplayPurchase() {
        setup(match -> true);
        earn(10);
        for (String id : List.of("food", "armour", "potion", "tools")) {
            assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), id));
        }
        var post = new PlayerPostRespawnEvent(player(ALICE), player(ALICE).getLocation(), false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH);
        tools.onPostRespawn(post);
        kits.onPostRespawn(post);
        assertEquals(Material.IRON_BOOTS, players.get(ALICE).armour[0].getType());
        assertFalse(players.get(ALICE).effects.isEmpty());
        assertEquals("food", ((TestItemStack) players.get(ALICE).storage[0]).marker());
        kits.onPostRespawn(post);
        assertNull(players.get(ALICE).storage[0]);
        assertEquals(2, points.points(match(), ALICE));
    }

    @Test
    void purchasesQueuedBetweenRoundsArriveAtTheActualNextRoundStart() {
        setup(match -> true);
        earn(20);
        matches.died(player(BOB), null);
        matches.died(player(DAVE), null);
        runNextTick();
        assertEquals(MatchPhase.ROUND_END, match().phase());
        for (String id : List.of("food", "armour", "potion", "tools")) {
            assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), id));
        }
        assertTrue(players.get(ALICE).effects.isEmpty());
        ticks(1);
        assertEquals(2, match().round());
        assertEquals("food", ((TestItemStack) players.get(ALICE).storage[0]).marker());
        assertEquals(Material.IRON_HELMET, players.get(ALICE).armour[3].getType());
        assertFalse(players.get(ALICE).effects.isEmpty());
    }

    @Test
    void deadOrOfflineAtRoundStartRefundsOnceAndDoesNotApplyOnLaterRespawn() {
        setup(match -> true);
        earn(6);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "food"));
        players.get(ALICE).dead = true;
        var start = new RoundStartEvent(match(), 2, false);
        kits.onRoundStart(start);
        shop.onRoundStart(start);
        shop.onRoundStart(start);
        assertEquals(6, points.points(match(), ALICE));
        players.get(ALICE).dead = false;
        kits.onRoundStart(start);
        assertNull(players.get(ALICE).storage[0]);
    }

    @Test
    void leavingErasesLedgerAndPendingPurchasesAndDoesNotRecreateLedger() {
        setup(match -> true);
        earn(6);
        MatchView old = match();
        assertEquals(Shop.Result.BOUGHT, shop.buy(old, player(ALICE), "food"));
        matches.leave(player(ALICE), MatchService.Exit.COMMAND);
        points.refund(old, ALICE, 2);
        assertEquals(0, points.points(old, ALICE));
        assertEquals(Shop.Result.CLOSED, shop.buy(old, player(ALICE), "food"));
        shop.apply(old, player(ALICE));
        assertTrue(java.util.Arrays.stream(players.get(ALICE).storage).allMatch(item -> item == null));
    }

    @Test
    void providerFailureRefundsOnceWithoutAffectingOtherPurchases() {
        setup(match -> true);
        earn(6);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "food"));
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "armour"));
        brokenProvider = true;
        kits.onRoundStart(new RoundStartEvent(match(), 2, false));
        assertEquals(4, points.points(match(), ALICE));
        assertEquals(Material.IRON_BOOTS, players.get(ALICE).armour[0].getType());
        assertNull(players.get(ALICE).storage[0]);
        shop.onRoundStart(new RoundStartEvent(match(), 2, false));
        assertEquals(4, points.points(match(), ALICE));
    }

    @Test
    void fullInventoryRefundsWithoutPartialDelivery() {
        setup(match -> true);
        earn(4);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "food"));
        players.get(ALICE).storage = new org.bukkit.inventory.ItemStack[36];
        java.util.Arrays.fill(players.get(ALICE).storage, new TestItemStack(Material.STONE, "full", 64));
        shop.apply(match(), player(ALICE));
        assertEquals(4, points.points(match(), ALICE));
        assertEquals("full", ((TestItemStack) players.get(ALICE).storage[0]).marker());
    }
}
