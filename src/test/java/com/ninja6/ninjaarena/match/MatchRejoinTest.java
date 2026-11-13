package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.event.EventSettings.Bonus;
import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.kit.RoundKitListener;
import com.ninja6.ninjaarena.lobby.LobbyReturns;
import com.ninja6.ninjaarena.match.MatchResult.Reason;
import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerDisconnected;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerEliminated;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerReady;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerRejoined;
import com.ninja6.ninjaarena.match.MatchUpdate.QuickStart;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.PlayerRejoinMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.RestoreListener;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.shop.Shop;
import com.ninja6.ninjaarena.shop.ShopCatalogue;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Category;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Entry;
import com.ninja6.ninjaarena.shop.ShopCatalogue.Potion;
import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.snapshot.SnapshotStore;
import com.ninja6.ninjaarena.support.TestItemStack;
import com.ninja6.ninjaarena.tool.NinjaTools;
import com.ninja6.ninjaarena.tool.ToolSettings;
import com.ninja6.ninjaarena.world.CrossWorldTransfers;
import com.ninja6.ninjaarena.world.LobbyWorld;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rejoining a match after a disconnect, end to end with the real match engine, death
 * handling, points, the shop, round kits and the join restore: a disconnect and rejoin
 * within the grace in every phase, the grace running out, and what ends a hold early.
 */
class MatchRejoinTest extends MatchFixture {

    private static final int GRACE = 5;
    /** Shop 4 seconds, quick start 2, map wait 3. */
    private static final ShopSettings SHOP = new ShopSettings(true, 4, 2, 3);

    private final World lobbyWorld = world(LobbyWorld.NAME);
    private final Location shopSpot = new Location(lobbyWorld, 31, 65, 12.5, 180, 0);
    private final CrossWorldTransfers transfers = new CrossWorldTransfers();
    /** Players the lobby took: the end of a match, the leave command, and a rejoin to the lobby. */
    private final List<UUID> lobby = new ArrayList<>();
    /** Players the join restore sent home from their snapshot. */
    private final List<UUID> home = new ArrayList<>();
    private final List<UUID> kitted = new ArrayList<>();
    private SnapshotStore snapshots;
    private LobbyReturns returns;
    private RestoreListener joins;
    private PlayerPoints points;
    private NinjaTools tools;
    private RoundKitListener kits;
    private Shop shop;

    @BeforeEach
    void setUpRejoining() throws Exception {
        points = new PlayerPoints(PointsSettings.DEFAULTS);
        KitItems items = new KitItems(material -> new TestItemStack(material, "vanilla", 1));
        ShopCatalogue catalogue = new ShopCatalogue(Map.of("potion",
                new Entry("potion", 2, 1, Category.POTIONS, new Potion(PotionEffectType.SPEED, 0, 500))));
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> {
            restored.add(player.getUniqueId());
            return com.ninja6.ninjaarena.restore.RestoreService.RestoreResult.RESTORED;
        }, this::dispatch, player -> readied.add(player.getUniqueId()));
        matches.setRejoinGraceSeconds(GRACE);
        matches.setShopArea(() -> shopSpot);
        matches.setLobby(this::toLobby);
        // As the plugin registers it.
        transfers.allow(matches::crossesWorlds);
        deaths = new MatchDeathListener(plugin, matches, arenas);
        listener = new MatchListener(matches, deaths, transfers);
        tools = new NinjaTools(matches, plugin.getServer()::getPlayer, name -> arenas.get(name).map(a -> a.bounds()),
                ToolSettings.DEFAULTS, () -> 0L, (victim, damage, projectile, thrower) -> { }, location -> null);
        shop = new Shop(matches, points, items, tools, plugin.getServer()::getPlayer, plugin.getLogger(), catalogue,
                match -> match.phase() == MatchPhase.SHOP);
        kits = new RoundKitListener(matches, plugin.getServer()::getPlayer, KitDefinition.EMPTY, items,
                plugin.getLogger());
        kits.setAfterKit((match, player) -> {
            kitted.add(player.getUniqueId());
            shop.apply(match, player);
        });
        teleportEvents = event -> {
            deaths.onTeleport(event);
            if (!event.isCancelled()) {
                listener.onTeleport(event);
            }
        };
        // Everyone entered through the lobby, so everyone has a snapshot on disk.
        snapshots = new SnapshotStore(dir.resolve("snapshots"));
        for (Fake fake : players.values()) {
            fake.canShop = true;
            snapshots.save(snapshot(fake.id));
        }
        RestoreService restores = new RestoreService(new SnapshotService(snapshots, plugin.getLogger()),
                (player, snapshot) -> home.add(player.getUniqueId()), plugin.getLogger());
        returns = new LobbyReturns(matches::isInMatch, this::toLobby);
        joins = new RestoreListener(plugin, restores, matches::isInMatch, returns);
    }

    private boolean toLobby(org.bukkit.entity.Player player) {
        lobby.add(player.getUniqueId());
        players.get(player.getUniqueId()).location = new Location(lobbyWorld, 20.5, 65, 28.5);
        return true;
    }

    private PlayerSnapshot snapshot(UUID id) {
        return new PlayerSnapshot(id, players.get(id).name, Instant.EPOCH,
                new SnapshotLocation(overworld.getUID(), "world", 100, 64, 100, 0, 0),
                new byte[0], new byte[0], new byte[0], 0, 0, 0, 20, 20, 0, 0,
                List.of(), GameMode.SURVIVAL, 0, 0);
    }

    private void dispatch(MatchEvent event) {
        events.add(event);
        switch (event) {
            case PlayerEliminatedEvent out -> {
                deaths.onEliminated(out);
                points.onEliminated(out);
                tools.onEliminated(out);
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
            case PlayerRejoinMatchEvent back -> {
                deaths.onRejoin(back);
                kits.onRejoin(back);
            }
            case MatchEndEvent end -> {
                points.onMatchEnd(end);
                tools.onMatchEnd(end);
                shop.onMatchEnd(end);
                kits.onMatchEnd(end);
            }
            default -> { }
        }
    }

    /** Disconnects a player as the server does: the lobby returns first, the match, then the restore. */
    private void quit(UUID id) {
        PlayerQuitEvent event = new PlayerQuitEvent(player(id), Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED);
        returns.onQuit(event);
        listener.onQuit(event);
        joins.onQuit(event);
        players.get(id).online = false;
    }

    /** Joins a player again and runs the tick after, when they are placed or restored. */
    private void join(UUID id) {
        players.get(id).online = true;
        PlayerJoinEvent event = new PlayerJoinEvent(player(id), Component.empty());
        listener.onJoin(event);
        joins.onJoin(event);
        runNextTick();
    }

    private MatchView match() {
        return matches.match("dojo").orElseThrow();
    }

    /** Team 1 wins the round in progress: Bob and Dave go down. */
    private void team1WinsTheRound() {
        for (UUID id : List.of(BOB, DAVE)) {
            if (match().isAlive(id)) {
                matches.died(player(id), null);
            }
        }
        runNextTick();
        assertEquals(MatchPhase.ROUND_END, match().phase());
    }

    private void toCombat() {
        startTwoOnTwo();
        ticks(SETTINGS.prepSeconds());
        assertEquals(MatchPhase.COMBAT, match().phase());
    }

    private void toShop() {
        matches.setShopSettings(SHOP);
        toCombat();
        team1WinsTheRound();
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.SHOP, match().phase());
    }

    private void assertHeld(UUID id) {
        assertTrue(matches.isInMatch(id), "still in the match");
        assertTrue(matches.isHeld(id));
        assertTrue(match().isDisconnected(id));
        assertTrue(match().players().contains(id));
        assertFalse(match().isAlive(id), "out of the round");
        assertTrue(events(PlayerLeaveMatchEvent.class).isEmpty(), "no leave while held");
    }

    private void assertBack(UUID id) {
        assertTrue(matches.isInMatch(id));
        assertFalse(matches.isHeld(id));
        assertFalse(match().isDisconnected(id));
        assertEquals(1, events(PlayerRejoinMatchEvent.class).size());
        assertTrue(home.isEmpty(), "never restored home");
        assertTrue(lobby.isEmpty(), "never sent to the lobby");
        assertTrue(snapshots.exists(id), "the snapshot is kept for the final restore");
    }

    // ---- The grace off ----

    @Test
    void withNoGraceADisconnectIsALeaveAsBefore() throws Exception {
        matches.setRejoinGraceSeconds(0);
        toCombat();
        quit(CAROL);
        assertFalse(matches.isInMatch(CAROL));
        assertFalse(matches.isHeld(CAROL));
        assertEquals(1, events(PlayerLeaveMatchEvent.class).size());
        assertEquals(Cause.LEFT, events(PlayerEliminatedEvent.class).getFirst().cause());
        assertTrue(players.get(ALICE).messages.contains("Carol left the match."));

        join(CAROL);
        assertEquals(List.of(CAROL), lobby, "back in the lobby, as after any quit");
        assertTrue(home.isEmpty());
        assertTrue(snapshots.exists(CAROL));
        assertFalse(matches.isInMatch(CAROL));
    }

    @Test
    void theGraceIsClampedAndAppliesToNewDisconnectsOnly() {
        matches.setRejoinGraceSeconds(-4);
        assertEquals(0, matches.rejoinGraceSeconds());
        matches.setRejoinGraceSeconds(100_000);
        assertEquals(MatchService.MAX_REJOIN_GRACE_SECONDS, matches.rejoinGraceSeconds());

        matches.setRejoinGraceSeconds(3);
        toCombat();
        quit(CAROL);
        // A reload to a longer grace, then to none: Carol keeps her 3 seconds, Dave leaves at once.
        matches.setRejoinGraceSeconds(60);
        matches.setRejoinGraceSeconds(0);
        quit(DAVE);
        assertFalse(matches.isInMatch(DAVE));
        ticks(2);
        assertTrue(matches.isHeld(CAROL));
        ticks(1);
        assertFalse(matches.isInMatch(CAROL));
    }

    // ---- A disconnect and rejoin in every phase ----

    @Test
    void inPrepARejoinIsAliveAtTheTeamSpawnWithTheKit() {
        startTwoOnTwo();
        players.get(CAROL).storage = new ItemStack[] {new TestItemStack(Material.STICK, "vanilla", 1)};
        players.get(CAROL).gameMode = GameMode.SPECTATOR;
        quit(CAROL);
        assertHeld(CAROL);
        assertEquals(Cause.DISCONNECTED, events(PlayerEliminatedEvent.class).getFirst().cause());
        assertTrue(players.get(ALICE).messages.contains("Carol disconnected; they have 5 seconds to rejoin."));
        assertNull(players.get(CAROL).storage[0], "the kit is cleared before player data is saved");
        assertEquals(GameMode.ADVENTURE, players.get(CAROL).gameMode, "never saved as a spectator");
        runNextTick();
        assertEquals(MatchPhase.PREP, match().phase(), "Alice still plays for team 1");

        kitted.clear();
        readied.clear();
        players.get(CAROL).location = new Location(arenaWorld, 40, 64, 40);
        join(CAROL);
        assertBack(CAROL);
        assertTrue(match().isAlive(CAROL), "alive in this round: the fight has not started");
        assertAt(SPAWN_1, players.get(CAROL).location);
        assertEquals(List.of(CAROL), readied);
        assertEquals(List.of(CAROL), kitted, "the round kit");
        assertEquals("You are back in the match, at your team spawn.", players.get(CAROL).messages.getLast());
        assertTrue(players.get(ALICE).messages.contains("Carol rejoined the match."));
        assertFalse(players.get(CAROL).messages.contains("Carol rejoined the match."));
    }

    @Test
    void inCombatARejoinSpectatesUntilTheNextRound() {
        toCombat();
        quit(CAROL);
        assertHeld(CAROL);
        join(CAROL);
        assertBack(CAROL);
        assertFalse(match().isAlive(CAROL));
        assertTrue(matches.isDisarmed(CAROL));
        assertTrue(deaths.isSpectating(CAROL));
        assertEquals(GameMode.SPECTATOR, players.get(CAROL).gameMode);
        assertEquals("You are back in the match and play again from the next round.",
                players.get(CAROL).messages.getLast());

        team1WinsTheRound();
        assertTrue(deaths.isSpectating(CAROL), "spectating through the round end");
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.PREP, match().phase());
        assertTrue(match().isAlive(CAROL), "alive in round 2");
        assertEquals(GameMode.ADVENTURE, players.get(CAROL).gameMode);
        assertAt(SPAWN_1, players.get(CAROL).location);
    }

    @Test
    void inOverrunARejoinSpectatesUntilTheNextRound() {
        toCombat();
        quit(CAROL);
        ticks(SETTINGS.roundSeconds());
        assertEquals(MatchPhase.OVERRUN, match().phase());
        join(CAROL);
        assertBack(CAROL);
        assertFalse(match().isAlive(CAROL));
        assertTrue(deaths.isSpectating(CAROL));
    }

    @Test
    void inTheRoundEndARejoinSpectatesUntilTheNextRound() {
        toCombat();
        team1WinsTheRound();
        quit(CAROL);
        assertHeld(CAROL);
        assertTrue(events(PlayerEliminatedEvent.class).stream().noneMatch(out -> out.player().equals(CAROL)),
                "nobody is alive in a round end");
        join(CAROL);
        assertBack(CAROL);
        assertTrue(deaths.isSpectating(CAROL));
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.PREP, match().phase());
        assertTrue(match().isAlive(CAROL));
    }

    @Test
    void inTheShopPointsAndPurchasesAreKeptAndARejoinIsBackInTheShop() {
        toShop();
        points.refund(match(), ALICE, 4);
        int before = points.points(match(), ALICE);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "potion"));
        players.get(ALICE).location = shopSpot.clone();
        quit(ALICE);
        assertHeld(ALICE);
        assertEquals(before - 2, points.points(match(), ALICE), "points kept while held");

        // Everyone connected is ready: the quick start does not wait for Alice.
        for (UUID id : List.of(CAROL, BOB, DAVE)) {
            matches.ready(player(id));
        }
        assertEquals(SHOP.quickStartSeconds(), match().secondsLeft());
        assertEquals(List.of(CAROL, BOB, DAVE), match().readyPlayers());

        players.get(ALICE).location = new Location(arenaWorld, 30, 64, 30);
        join(ALICE);
        assertBack(ALICE);
        assertTrue(PluginWorlds.isLobby(players.get(ALICE).location), "back in the shop area");
        assertEquals(GameMode.ADVENTURE, players.get(ALICE).gameMode);
        assertEquals("You are back in the match, in the shop.", players.get(ALICE).messages.getLast());
        assertEquals(MatchService.Ready.READY, matches.ready(player(ALICE)));

        ticks(SHOP.quickStartSeconds());
        assertEquals(MatchPhase.PREP, match().phase());
        assertTrue(match().isAlive(ALICE));
        assertAt(SPAWN_1, players.get(ALICE).location);
        assertEquals(PotionEffectType.SPEED, players.get(ALICE).effects.getFirst().getType(), "the purchase arrives");
        assertEquals(before - 2, points.points(match(), ALICE));
    }

    @Test
    void aDisconnectInTheShopAndARejoinInPrepCrossBackToTheTeamSpawn() {
        toShop();
        players.get(BOB).location = shopSpot.clone();
        quit(BOB);
        ticks(SHOP.maxSeconds());
        assertEquals(MatchPhase.PREP, match().phase());
        assertFalse(match().isAlive(BOB), "away at the round start");
        join(BOB);
        assertBack(BOB);
        assertTrue(matches.isInMatch(BOB), "the move out of the lobby world keeps the match");
        assertTrue(match().isAlive(BOB));
        assertAt(SPAWN_2, players.get(BOB).location);
    }

    @Test
    void aDisconnectInCombatAndARejoinInTheShopGoToTheShopArea() {
        matches.setShopSettings(SHOP);
        toCombat();
        quit(CAROL);
        team1WinsTheRound();
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.SHOP, match().phase());
        join(CAROL);
        assertBack(CAROL);
        assertTrue(PluginWorlds.isLobby(players.get(CAROL).location));
        assertFalse(deaths.isSpectating(CAROL));
    }

    @Test
    void inAnExtraRoundADisconnectIsHeldAndARejoinPlaysTheNextOne() {
        // Round 1 to team 1, round 2 to team 2: tied, so the extra round follows.
        toCombat();
        team1WinsTheRound();
        ticks(SETTINGS.roundEndSeconds() + SETTINGS.prepSeconds());
        matches.died(player(ALICE), null);
        matches.died(player(CAROL), null);
        runNextTick();
        ticks(SETTINGS.roundEndSeconds());
        assertTrue(match().isExtraRound());
        assertEquals(MatchPhase.PREP, match().phase());

        quit(DAVE);
        assertHeld(DAVE);
        ticks(SETTINGS.prepSeconds());
        join(DAVE);
        assertBack(DAVE);
        assertTrue(deaths.isSpectating(DAVE));
        assertTrue(match().result().isEmpty());
    }

    // ---- The phase moving on between the join and the placement ----

    /**
     * Joins a player and lets the once-a-second match ticker run before the placement on the
     * next tick, as it does when both are due on the same server tick.
     */
    private void joinAsTheMatchTicks(UUID id) {
        players.get(id).online = true;
        PlayerJoinEvent event = new PlayerJoinEvent(player(id), Component.empty());
        listener.onJoin(event);
        joins.onJoin(event);
        matches.tick();
        runNextTick();
    }

    @Test
    void aRejoinOnTheLastSecondOfPrepSpectatesTheFightInsteadOfStayingInTheShop() {
        matches.setRejoinGraceSeconds(60);
        toShop();
        players.get(BOB).location = shopSpot.clone();
        quit(BOB);
        ticks(SHOP.maxSeconds());
        ticks(SETTINGS.prepSeconds() - 1);
        assertEquals(MatchPhase.PREP, match().phase());
        kitted.clear();
        joinAsTheMatchTicks(BOB);
        assertEquals(MatchPhase.COMBAT, match().phase());
        assertTrue(matches.isInMatch(BOB));
        assertFalse(match().isAlive(BOB), "the fight started before he was placed");
        assertTrue(deaths.isSpectating(BOB));
        assertTrue(PluginWorlds.isArena(players.get(BOB).location), "out of the shop area, into the arena");
        assertFalse(kitted.contains(BOB));
        assertEquals("You are back in the match and play again from the next round.",
                players.get(BOB).messages.getLast());

        // Dave is the last of team 2 alive: taking him out wins the round for team 1.
        matches.died(player(DAVE), null);
        runNextTick();
        assertEquals(MatchPhase.ROUND_END, match().phase());
        assertEquals(1, match().results().getLast().winner());
    }

    @Test
    void aRejoinOnTheLastSecondOfTheShopIsAliveAtTheTeamSpawn() {
        matches.setRejoinGraceSeconds(60);
        toShop();
        players.get(BOB).location = shopSpot.clone();
        quit(BOB);
        ticks(SHOP.maxSeconds() - 1);
        joinAsTheMatchTicks(BOB);
        assertEquals(MatchPhase.PREP, match().phase());
        assertTrue(match().isAlive(BOB), "back before the round started");
        assertAt(SPAWN_2, players.get(BOB).location);
        assertTrue(kitted.contains(BOB));
        assertEquals("You are back in the match, at your team spawn.", players.get(BOB).messages.getLast());
    }

    @Test
    void aRejoinOnTheLastSecondOfTheRoundEndIsAliveInTheNextPrep() {
        toCombat();
        team1WinsTheRound();
        quit(CAROL);
        joinAsTheMatchTicks(CAROL);
        assertEquals(MatchPhase.PREP, match().phase());
        assertTrue(match().isAlive(CAROL));
        assertFalse(deaths.isSpectating(CAROL));
        assertEquals(GameMode.ADVENTURE, players.get(CAROL).gameMode);
        assertAt(SPAWN_1, players.get(CAROL).location);
    }

    @Test
    void aRejoinOnTheLastSecondOfTheRoundEndBeforeAShopGoesToTheShopArea() {
        matches.setShopSettings(SHOP);
        toCombat();
        team1WinsTheRound();
        quit(CAROL);
        joinAsTheMatchTicks(CAROL);
        assertEquals(MatchPhase.SHOP, match().phase());
        assertFalse(match().isAlive(CAROL));
        assertFalse(deaths.isSpectating(CAROL));
        assertTrue(PluginWorlds.isLobby(players.get(CAROL).location), "in the shop area");
        assertEquals(GameMode.ADVENTURE, players.get(CAROL).gameMode);
        assertEquals("You are back in the match, in the shop.", players.get(CAROL).messages.getLast());
    }

    @Test
    void aRejoinOnTheLastSecondOfOverrunSpectatesThroughTheRoundEnd() {
        toCombat();
        quit(CAROL);
        ticks(SETTINGS.roundSeconds() + SETTINGS.overrunLimitSeconds() - 1);
        assertEquals(MatchPhase.OVERRUN, match().phase());
        joinAsTheMatchTicks(CAROL);
        assertEquals(MatchPhase.ROUND_END, match().phase());
        assertFalse(match().isAlive(CAROL));
        assertTrue(deaths.isSpectating(CAROL));
    }

    // ---- The grace running out ----

    @Test
    void whenTheGraceRunsOutThePlayerLeavesAsBeforeAndRejoinsInTheLobby() {
        toShop();
        points.refund(match(), ALICE, 4);
        assertEquals(Shop.Result.BOUGHT, shop.buy(match(), player(ALICE), "potion"));
        matches.setRejoinGraceSeconds(2);
        quit(ALICE);
        ticks(1);
        assertTrue(matches.isHeld(ALICE));
        ticks(1);
        assertFalse(matches.isInMatch(ALICE));
        assertFalse(matches.isHeld(ALICE));
        assertEquals(List.of(ALICE), events(PlayerLeaveMatchEvent.class).stream().map(PlayerLeaveMatchEvent::player)
                .toList());
        assertEquals(0, points.points(match(), ALICE), "points dropped and the purchase refunded into nothing");
        assertTrue(players.get(CAROL).messages.contains("Alice left the match."));
        assertEquals(List.of(CAROL), match().playersOf(1));

        join(ALICE);
        assertFalse(matches.isInMatch(ALICE));
        assertEquals(List.of(ALICE), lobby, "the lobby, as after any quit");
        assertTrue(snapshots.exists(ALICE));
        assertTrue(players.get(ALICE).effects.isEmpty());
    }

    @Test
    void aTeamWhoseLastPlayerIsHeldLosesRoundsButForfeitsOnlyWhenTheGraceRunsOut() {
        matches.setRejoinGraceSeconds(60);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        ticks(SETTINGS.prepSeconds());
        quit(BOB);
        runNextTick();
        assertEquals(MatchPhase.ROUND_END, match().phase());
        assertEquals(1, match().results().getFirst().winner(), "Bob is out of the round");
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.PREP, match().phase(), "no forfeit while Bob may come back");
        assertTrue(match().result().isEmpty());
    }

    @Test
    void theLastPlayerOfATeamForfeitsWhenTheirGraceRunsOut() {
        matches.setRejoinGraceSeconds(1);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        ticks(SETTINGS.prepSeconds());
        quit(BOB);
        runNextTick();
        assertEquals(MatchPhase.ROUND_END, match().phase());
        assertTrue(match().result().isEmpty());
        ticks(1);
        assertFalse(matches.isInMatch(BOB));
        assertEquals(Reason.FORFEIT, match().result().orElseThrow().reason());
        assertEquals(List.of(1), match().result().orElseThrow().winners());
    }

    @Test
    void whileNobodyIsConnectedTheMatchWaitsThenIsAbandoned() {
        matches.setRejoinGraceSeconds(3);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        MatchView match = match();
        ticks(SETTINGS.prepSeconds());
        quit(ALICE);
        quit(BOB);
        runNextTick();
        assertEquals(MatchPhase.ROUND_END, match.phase(), "both went down together: a draw");
        int left = match.secondsLeft();
        ticks(2);
        assertEquals(MatchPhase.ROUND_END, match.phase(), "the timers wait");
        assertEquals(left, match.secondsLeft());
        ticks(1);
        assertTrue(matches.matches().isEmpty());
        assertEquals(Reason.ABANDONED, events(MatchEndEvent.class).getFirst().result().reason());
        assertFalse(arenas.isHeld("dojo"));
    }

    // ---- What ends a hold early ----

    @Test
    void aRejoinAfterTheMatchEndedIsANormalRestore() {
        matches.setRejoinGraceSeconds(60);
        toCombat();
        quit(CAROL);
        team1WinsTheRound();
        ticks(SETTINGS.roundEndSeconds() + SETTINGS.prepSeconds());
        team1WinsTheRound();
        ticks(SETTINGS.roundEndSeconds());
        assertEquals(MatchPhase.MATCH_END, match().phase());
        assertTrue(matches.isHeld(CAROL), "still held, and on the winning team");
        assertTrue(match().result().orElseThrow().winners().contains(1));

        join(CAROL);
        assertFalse(matches.isInMatch(CAROL));
        assertFalse(matches.isHeld(CAROL));
        assertEquals(List.of(CAROL), lobby);
        assertTrue(events(PlayerRejoinMatchEvent.class).isEmpty());
    }

    @Test
    void aRejoinAfterTheMatchClosedIsANormalRestore() {
        matches.setRejoinGraceSeconds(60);
        matches.start("dojo", List.of(List.of(ALICE, CAROL), List.of(BOB)));
        quit(CAROL);
        matches.leave(player(BOB), MatchService.Exit.COMMAND);
        for (int i = 0; i < 20 && !matches.matches().isEmpty(); i++) {
            runNextTick();
            matches.tick();
        }
        assertTrue(matches.matches().isEmpty());
        assertFalse(matches.isHeld(CAROL));
        assertFalse(matches.isInMatch(CAROL));
        lobby.clear();

        join(CAROL);
        assertEquals(List.of(CAROL), lobby);
        assertTrue(snapshots.exists(CAROL));
    }

    @Test
    void disablingThePluginDuringTheGraceIsALeave() {
        toCombat();
        quit(CAROL);
        matches.close();
        assertFalse(matches.isHeld(CAROL));
        assertFalse(matches.isInMatch(CAROL));
        assertEquals(List.of(CAROL), events(PlayerLeaveMatchEvent.class).stream()
                .map(PlayerLeaveMatchEvent::player).toList());
        assertTrue(matches.matches().isEmpty());
        assertFalse(restored.contains(CAROL), "offline: restored on the next join");
    }

    @Test
    void aSecondDisconnectAfterARejoinIsHeldAgain() {
        toCombat();
        quit(CAROL);
        ticks(3);
        join(CAROL);
        quit(CAROL);
        ticks(GRACE - 1);
        assertTrue(matches.isHeld(CAROL), "a new grace");
        ticks(1);
        assertFalse(matches.isInMatch(CAROL));
    }

    // ---- The match engine ----

    private static final UUID A1 = new UUID(1, 1);
    private static final UUID A2 = new UUID(1, 2);
    private static final UUID B1 = new UUID(2, 1);

    private static Match engine(ShopSettings shop) {
        return new Match("dojo", List.of(List.of(A1, A2), List.of(B1)), SETTINGS, MatchStandings.ROUND_WINS,
                Bonus.NONE, shop);
    }

    @Test
    void theEngineTakesADisconnectedPlayerOutOfTheRoundButNotTheMatch() {
        Match match = engine(ShopSettings.OFF);
        match.begin();
        assertEquals(List.of(new PlayerEliminated(A1, 1, Cause.DISCONNECTED, null), new PlayerDisconnected(A1, 1)),
                match.disconnect(A1));
        assertTrue(match.disconnect(A1).isEmpty(), "once");
        assertEquals(List.of(A1, A2, B1), match.players());
        assertEquals(List.of(A1), match.disconnectedPlayers());
        assertEquals(1, match.teamOf(A1));
        assertEquals(List.of(new PlayerRejoined(A1, 1)), match.reconnect(A1));
        assertFalse(match.isAlive(A1), "not alive until placed");
        assertTrue(match.revive(A1), "placed during PREP: alive in the round");
        assertTrue(match.isAlive(A1));
        assertTrue(match.reconnect(A1).isEmpty());
        match.disconnect(B1);
        assertFalse(match.revive(B1), "a disconnected player is never placed");
        match.reconnect(B1);
        // Team 2 has nobody alive: the next second decides the round.
        match.tick();
        assertEquals(MatchPhase.ROUND_END, match.phase());
        assertFalse(match.revive(B1), "never outside PREP");
    }

    @Test
    void theEngineCountsOnlyConnectedPlayersForTheShopsReadyCheck() {
        Match match = engine(new ShopSettings(true, 10, 2, 3));
        match.begin();
        for (int i = 0; i < SETTINGS.prepSeconds(); i++) {
            match.tick();
        }
        match.eliminate(B1, null);
        match.settle();
        for (int i = 0; i < SETTINGS.roundEndSeconds(); i++) {
            match.tick();
        }
        assertEquals(MatchPhase.SHOP, match.phase());
        match.ready(A1);
        assertEquals(List.of(new PlayerReady(B1, 2, 3)), match.ready(B1));
        assertEquals(List.of(new PlayerDisconnected(A2, 1), new QuickStart(2)), match.disconnect(A2));
        assertTrue(match.ready(A2).isEmpty(), "a disconnected player cannot be ready");
        assertEquals(List.of(new PlayerRejoined(A2, 1)), match.reconnect(A2));
        assertFalse(match.revive(A2), "not in a SHOP");
        assertTrue(match.readyPlayers().containsAll(List.of(A1, B1)));
    }

    @Test
    void theEngineDoesNotHoldAPlayerOnceTheMatchHasEnded() {
        Match match = engine(ShopSettings.OFF);
        match.begin();
        match.stop();
        assertTrue(match.disconnect(A1).isEmpty());
    }
}
