package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.kit.RoundKitListener;
import com.ninja6.ninjaarena.match.MatchResult.Reason;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.shop.Shop;
import com.ninja6.ninjaarena.shop.ShopCatalogue;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Category;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Entry;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Potion;
import com.ninja6.ninjaarena.support.TestItemStack;
import com.ninja6.ninjaarena.tool.NinjaTools;
import com.ninja6.ninjaarena.tool.ToolSettings;
import com.ninja6.ninjaarena.world.CrossWorldTransfers;
import com.ninja6.ninjaarena.world.LobbyWorld;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shop phase end to end with the real match engine, a match from a waiting room or the
 * hub (no room, so no slot lease): the move to the shop area and back, buying, Ready, the
 * wait for the next map, and every way out during the shop.
 */
class ShopFlowTest extends MatchFixture {

    /** Shop 4 seconds, quick start 2, map wait 3. */
    private static final ShopSettings SHOP = new ShopSettings(true, 4, 2, 3);

    private final World lobbyWorld = world(LobbyWorld.NAME);
    private final Location shopSpot = new Location(lobbyWorld, 31, 65, 12.5, 180, 0);
    private final CrossWorldTransfers transfers = new CrossWorldTransfers();
    private final List<UUID> lobby = new ArrayList<>();
    private PlayerPoints points;
    private NinjaTools tools;
    private RoundKitListener kits;
    private Shop shop;

    @BeforeEach
    void setUpShop() {
        points = new PlayerPoints(PointsSettings.DEFAULTS);
        KitItems items = new KitItems(material -> new TestItemStack(material, "vanilla", 1));
        ShopCatalogue catalogue = new ShopCatalogue(Map.of("potion",
                new Entry("potion", 2, 1, Category.POTIONS, new Potion(PotionEffectType.SPEED, 0, 500))));
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, this::dispatch, player -> readied.add(player.getUniqueId()));
        matches.setShopSettings(SHOP);
        matches.setShopArea(() -> shopSpot);
        matches.setLobby(player -> {
            lobby.add(player.getUniqueId());
            players.get(player.getUniqueId()).location = new Location(lobbyWorld, 20.5, 65, 28.5);
            return true;
        });
        // As the plugin registers it.
        transfers.allow(matches::crossesWorlds);
        deaths = new MatchDeathListener(plugin, matches, arenas);
        listener = new MatchListener(matches, deaths, transfers);
        tools = new NinjaTools(matches, plugin.getServer()::getPlayer, name -> arenas.get(name).map(a -> a.bounds()),
                ToolSettings.DEFAULTS, () -> 0L, (victim, damage, projectile, thrower) -> { }, location -> null);
        // Buying is open only while the match is in SHOP, as the plugin sets it.
        shop = new Shop(matches, points, items, tools, plugin.getServer()::getPlayer, plugin.getLogger(), catalogue,
                match -> match.phase() == MatchPhase.SHOP);
        kits = new RoundKitListener(matches, plugin.getServer()::getPlayer, KitDefinition.EMPTY, items,
                plugin.getLogger());
        kits.setAfterKit(shop::apply);
        teleportEvents = event -> {
            deaths.onTeleport(event);
            if (!event.isCancelled()) {
                listener.onTeleport(event);
            }
        };
        players.values().forEach(fake -> fake.canShop = true);
    }

    private void dispatch(MatchEvent event) {
        events.add(event);
        switch (event) {
            case PlayerEliminatedEvent out -> {
                deaths.onEliminated(out);
                points.onEliminated(out);
            }
            case RoundStartEvent start -> {
                deaths.onRoundStart(start);
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
                deaths.onLeave(leave);
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
    }

    private MatchView match() {
        return matches.match("dojo").orElseThrow();
    }

    /** Starts two against two, fights, and team 1 wins round 1 by taking Bob and Dave out. */
    private void winFirstRound() {
        startTwoOnTwo();
        ticks(SETTINGS.prepSeconds());
        assertEquals(MatchPhase.COMBAT, match().phase());
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(CAROL));
        runNextTick();
        assertEquals(MatchPhase.ROUND_END, match().phase());
    }

    private void toShop() {
        winFirstRound();
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.SHOP, match().phase());
    }

    private List<MatchPhaseChangeEvent> phaseChanges() {
        return events(MatchPhaseChangeEvent.class);
    }

    private static boolean at(Location expected, Location actual) {
        return expected.getWorld() == actual.getWorld() && expected.getX() == actual.getX()
                && expected.getY() == actual.getY() && expected.getZ() == actual.getZ();
    }

    @Test
    void startReadyAndPurchaseCuesReachOnlyTheirIntendedAudience() {
        var sounds = new com.ninja6.ninjaarena.sound.Sounds(plugin.getServer()::getPlayer);
        matches.setSounds(sounds);
        shop.setSounds(sounds);
        startTwoOnTwo();
        for (var fake : players.values()) {
            assertEquals(1, fake.sounds.size());
            assertEquals("block.note_block.chime", fake.sounds.getLast().name().value());
            fake.sounds.clear();
        }
        ticks(SETTINGS.prepSeconds());
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(CAROL));
        runNextTick();
        ticks(SETTINGS.roundEndSeconds());
        matches.ready(player(ALICE));
        matches.ready(player(ALICE));
        for (var fake : players.values()) assertEquals(1, fake.sounds.size());
        points.refund(match(), ALICE, 4);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "potion"));
        assertEquals(Shop.Result.LIMIT_REACHED, shop.buy(match(), player(ALICE), "potion"));
        assertEquals(2, players.get(ALICE).sounds.size());
        assertEquals(1, players.get(BOB).sounds.size());
    }

    @Test
    void everyoneGoesToTheShopAndComesBackAtTheNextPrep() {
        startTwoOnTwo();
        ticks(SETTINGS.prepSeconds());
        // Bob is out and spectating with something in his kit; Dave is still on the death screen.
        ItemStack kept = new TestItemStack(Material.STICK, "vanilla", 1);
        players.get(BOB).storage = new ItemStack[] {kept};
        matches.died(player(BOB), player(ALICE));
        players.get(BOB).gameMode = GameMode.SPECTATOR;
        players.get(DAVE).dead = true;
        matches.died(player(DAVE), player(CAROL));
        runNextTick();
        copies.calls.clear();
        ticks(SETTINGS.roundEndSeconds());

        MatchView match = match();
        assertEquals(MatchPhase.SHOP, match.phase());
        assertEquals(List.of(MatchPhase.ROUND_END, MatchPhase.SHOP),
                List.of(phaseChanges().getLast().from(), phaseChanges().getLast().to()));
        for (UUID id : List.of(ALICE, BOB, CAROL)) {
            assertTrue(at(shopSpot, players.get(id).location), players.get(id).name + " is in the shop");
            assertTrue(players.get(id).ownMarks.getLast(), "moved by NinjaArena's own teleport");
            assertTrue(matches.isInMatch(id), "still in the match");
            assertTrue(matches.isSheltered(id));
        }
        assertEquals(GameMode.ADVENTURE, players.get(BOB).gameMode, "out of spectator mode");
        assertFalse(deaths.isSpectating(BOB));
        assertEquals(List.of(kept), List.of(players.get(BOB).storage), "the inventory stays as it was");
        assertTrue(PluginWorlds.isArena(players.get(DAVE).location), "the death screen is not teleported");
        assertTrue(at(shopSpot, matches.respawnPoint(DAVE).orElseThrow()), "Dave respawns in the shop");
        assertEquals(List.of(ALICE, CAROL, BOB, DAVE), match.players());
        assertTrue(players.get(ALICE).messages.contains(
                "Shop open: spend your points before round 2. It closes in 4 seconds; /narena shop ready when you are"
                        + " done."));

        // Dave's respawn and post-respawn teleport put him in the shop; he stays in the match.
        players.get(DAVE).dead = false;
        assertTrue(OwnTeleports.teleport(player(DAVE), matches.respawnPoint(DAVE).orElseThrow(),
                TeleportCause.PLUGIN));
        assertTrue(matches.isInMatch(DAVE));
        assertTrue(matches.crossesWorlds(DAVE));

        readied.clear();
        ticks(SHOP.maxSeconds());
        assertEquals(MatchPhase.PREP, match.phase());
        assertEquals(List.of(MatchPhase.SHOP, MatchPhase.PREP),
                List.of(phaseChanges().getLast().from(), phaseChanges().getLast().to()));
        assertEquals(2, events(RoundStartEvent.class).getLast().round());
        assertAt(SPAWN_1, players.get(ALICE).location);
        assertAt(SPAWN_1, players.get(CAROL).location);
        assertAt(SPAWN_2, players.get(BOB).location);
        assertAt(SPAWN_2, players.get(DAVE).location);
        assertEquals(List.of(ALICE, CAROL, BOB, DAVE), match.players(), "nobody left on the way back");
        assertEquals(List.of(ALICE, CAROL, BOB, DAVE), readied);
        assertFalse(matches.crossesWorlds(ALICE), "no more crossing once back in the arena");
        assertTrue(restored.isEmpty());
        assertFalse(copies.calls.contains("restore dojo"), "a ready map needs no reset");
    }

    @Test
    void buyingIsOpenOnlyDuringTheShopAndPurchasesComeWithTheNextRound() {
        startTwoOnTwo();
        ticks(SETTINGS.prepSeconds());
        points.enemyKilled(match(), ALICE, 5);
        assertEquals(Shop.Result.CLOSED, shop.buy(match(), player(ALICE), "potion"), "closed in combat");
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(CAROL));
        runNextTick();
        assertEquals(Shop.Result.CLOSED, shop.buy(match(), player(ALICE), "potion"), "closed at the round end");
        ticks(SETTINGS.roundEndSeconds());

        int before = points.points(match(), ALICE);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "potion"));
        assertEquals(before - 2, points.points(match(), ALICE));
        assertTrue(players.get(ALICE).effects.isEmpty(), "nothing is applied in the shop");

        ticks(SHOP.maxSeconds());
        assertEquals(MatchPhase.PREP, match().phase());
        assertEquals(PotionEffectType.SPEED, players.get(ALICE).effects.getFirst().getType());
        assertEquals(before - 2, points.points(match(), ALICE));
        assertEquals(Shop.Result.CLOSED, shop.buy(match(), player(BOB), "potion"), "closed again in prep");
    }

    @Test
    void anUndeliveredPurchaseIsRefundedAtTheNextRound() {
        toShop();
        points.refund(match(), CAROL, 4);
        int before = points.points(match(), CAROL);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(CAROL), "potion"));
        players.get(CAROL).dead = true;
        ticks(SHOP.maxSeconds());
        assertEquals(before, points.points(match(), CAROL), "refunded per the catalogue's rules");
    }

    @Test
    void disconnectingDuringTheShopLeavesTheMatchAndDropsThePurchase() {
        toShop();
        points.refund(match(), ALICE, 4);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "potion"));
        MatchView match = match();

        listener.onQuit(new PlayerQuitEvent(player(ALICE), Component.empty()));
        players.get(ALICE).online = false;
        assertFalse(matches.isInMatch(ALICE));
        assertEquals(0, points.points(match, ALICE), "leaving drops the ledger; a refund never recreates it");
        assertTrue(restored.isEmpty(), "restored on the next join");
        assertEquals(MatchPhase.SHOP, match.phase());

        ticks(SHOP.maxSeconds());
        assertEquals(MatchPhase.PREP, match.phase());
        assertEquals(List.of(CAROL, BOB, DAVE), match.players());
        assertTrue(players.get(ALICE).effects.isEmpty());
    }

    @Test
    void leavingByCommandDuringTheShopGoesToTheLobbyAndALastTeamForfeits() {
        toShop();
        MatchView match = match();
        assertTrue(matches.leave(player(BOB), MatchService.Exit.COMMAND));
        assertEquals(List.of(BOB), lobby);
        assertEquals(MatchPhase.SHOP, match.phase(), "Dave still plays for team 2");
        assertTrue(matches.leave(player(DAVE), MatchService.Exit.COMMAND));
        assertEquals(MatchPhase.MATCH_END, match.phase());
        assertEquals(Reason.FORFEIT, match.result().orElseThrow().reason());
        assertEquals(List.of(1), match.result().orElseThrow().winners());

        ticks(SETTINGS.matchEndSeconds());
        assertTrue(matches.match("dojo").isEmpty());
        assertEquals(List.of(BOB, DAVE, ALICE, CAROL), lobby, "the winners go to the lobby from the shop");
        assertTrue(arenas.isAvailable("dojo"), "the arena is reset and released");
    }

    @Test
    void anotherPluginsTeleportDuringTheShopTakesThePlayerOut() {
        toShop();
        assertTrue(player(ALICE).teleport(new Location(overworld, 0, 70, 0)));
        assertFalse(matches.isInMatch(ALICE));
        assertTrue(player(CAROL).teleport(new Location(lobbyWorld, 20.5, 65, 28.5)),
                "a teleport inside the lobby world that is not NinjaArena's");
        assertFalse(matches.isInMatch(CAROL));
        runNextTick();
        assertEquals(List.of(ALICE, CAROL), restored);
    }

    @Test
    void readyFromEveryoneStartsTheQuickStart() {
        startTwoOnTwo();
        assertEquals(MatchService.Ready.NOT_IN_SHOP, matches.ready(player(ALICE)));
        ticks(SETTINGS.prepSeconds());
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(CAROL));
        runNextTick();
        ticks(SETTINGS.roundEndSeconds());

        assertEquals(MatchService.Ready.READY, matches.ready(player(ALICE)));
        assertEquals(MatchService.Ready.ALREADY_READY, matches.ready(player(ALICE)));
        assertTrue(players.get(BOB).messages.contains("Alice is ready (1/4)."));
        matches.ready(player(BOB));
        matches.ready(player(CAROL));
        assertEquals(4, match().secondsLeft());
        matches.ready(player(DAVE));
        assertEquals(2, match().secondsLeft());
        assertTrue(players.get(ALICE).messages.contains("Everyone is ready: the next round starts in 2 seconds."));
        assertEquals(List.of(ALICE, CAROL, BOB, DAVE), match().readyPlayers());
        ticks(2);
        assertEquals(MatchPhase.PREP, match().phase());
        assertEquals(MatchService.Ready.NOT_IN_SHOP, matches.ready(player(ALICE)));
    }

    @Test
    void anUnreadyMapKeepsTheShopOpenThenTheRoundIsReplayedAfterAReset() {
        List<MatchView> asked = new ArrayList<>();
        matches.setMapReady(match -> {
            asked.add(match);
            return false;
        });
        toShop();
        copies.calls.clear();
        for (UUID id : List.of(ALICE, BOB, CAROL, DAVE)) {
            matches.ready(player(id));
        }
        ticks(2);
        assertEquals(MatchPhase.SHOP, match().phase(), "never ends before the map is ready");
        assertTrue(match().isWaitingForMap());
        assertEquals(3, match().secondsLeft());
        assertTrue(players.get(ALICE).messages.contains("Waiting for the next map, for at most 3 seconds."));
        assertFalse(asked.isEmpty());

        ticks(2);
        assertEquals(MatchPhase.SHOP, match().phase());
        assertTrue(copies.calls.isEmpty());
        ticks(1);
        assertEquals(MatchPhase.PREP, match().phase());
        assertEquals(List.of("restore dojo"), copies.calls, "the current arena is reset for the replay");
        assertTrue(players.get(ALICE).messages.contains(
                "The next map is not ready: the round is played on dojo again after a reset."));
        assertAt(SPAWN_1, players.get(ALICE).location);
        assertEquals(2, match().round());
    }

    @Test
    void aMapThatBecomesReadyEndsTheWait() {
        boolean[] ready = {false};
        matches.setMapReady(match -> ready[0]);
        toShop();
        copies.calls.clear();
        ticks(SHOP.maxSeconds());
        assertTrue(match().isWaitingForMap());
        ready[0] = true;
        ticks(1);
        assertEquals(MatchPhase.PREP, match().phase());
        assertTrue(copies.calls.isEmpty(), "no reset when the map is ready");
    }

    @Test
    void withTheShopOffRoundsFollowEachOtherDirectly() {
        matches.setShopSettings(ShopSettings.OFF);
        winFirstRound();
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.PREP, match().phase());
        assertEquals(List.of(MatchPhase.PREP, MatchPhase.COMBAT, MatchPhase.ROUND_END, MatchPhase.PREP),
                phaseChanges().stream().map(MatchPhaseChangeEvent::to).toList());
        for (Fake fake : players.values()) {
            assertTrue(fake.teleports.stream().noneMatch(PluginWorlds::isLobby), fake.name + " never left the arena");
        }
        assertEquals(MatchService.Ready.NOT_IN_SHOP, matches.ready(player(ALICE)));
        assertEquals(Shop.Result.CLOSED, shop.buy(match(), player(ALICE), "potion"));
    }

    @Test
    void aRunningMatchKeepsItsShopSettings() {
        startTwoOnTwo();
        matches.setShopSettings(ShopSettings.OFF);
        assertEquals(SHOP, match().shopSettings());
        ticks(SETTINGS.prepSeconds());
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(CAROL));
        runNextTick();
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.SHOP, match().phase());

        for (UUID id : List.of(ALICE, BOB, CAROL, DAVE)) {
            matches.leave(player(id), MatchService.Exit.COMMAND);
        }
        assertTrue(matches.match("dojo").isEmpty());
        // Back from home through a waiting room in the arena world.
        players.get(ALICE).location = new Location(arenaWorld, ROOM.x(), ROOM.y(), ROOM.z());
        players.get(BOB).location = new Location(arenaWorld, ROOM.x(), ROOM.y(), ROOM.z());
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        assertEquals(ShopSettings.OFF, match().shopSettings(), "the next match takes the new settings");
    }
}
