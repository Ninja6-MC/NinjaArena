package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.event.EventSettings.Bonus;
import com.ninja6.ninjaarena.match.MatchResult.Reason;
import com.ninja6.ninjaarena.match.MatchUpdate.MapReplay;
import com.ninja6.ninjaarena.match.MatchUpdate.MapWait;
import com.ninja6.ninjaarena.match.MatchUpdate.PhaseChanged;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerReady;
import com.ninja6.ninjaarena.match.MatchUpdate.QuickStart;
import com.ninja6.ninjaarena.match.MatchUpdate.RoundStarted;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The SHOP phase in the match engine: when it comes, how it ends, and what ends it early. */
class ShopPhaseTest {

    private static final UUID A1 = new UUID(1, 1);
    private static final UUID A2 = new UUID(1, 2);
    private static final UUID B1 = new UUID(2, 1);
    private static final UUID B2 = new UUID(2, 2);

    /** Prep 3, combat 5, overrun limit 4, round end 2, match end 3, 3 rounds, 1 extra. */
    private static final MatchSettings SHORT = new MatchSettings(3, 5, 4, 2, 3, 3, 1);
    /** Shop 10 seconds, quick start 2, map wait 4. */
    private static final ShopSettings SHOP = new ShopSettings(true, 10, 2, 4);

    private final AtomicBoolean mapReady = new AtomicBoolean(true);

    private Match match(ShopSettings shop) {
        Match match = new Match("dojo", List.of(List.of(A1, A2), List.of(B1, B2)), SHORT, MatchStandings.ROUND_WINS,
                Bonus.NONE, shop);
        match.setMapReady(mapReady::get);
        return match;
    }

    private static List<MatchUpdate> ticks(Match match, int count) {
        List<MatchUpdate> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            all.addAll(match.tick());
        }
        return all;
    }

    private static List<MatchUpdate> win(Match match, int team) {
        List<MatchUpdate> all = new ArrayList<>();
        for (UUID player : match.alivePlayers()) {
            if (match.teamOf(player) != team) {
                all.addAll(match.eliminate(player, null));
            }
        }
        all.addAll(match.settle());
        return all;
    }

    /** During a round: the team wins it, and ROUND_END runs out. */
    private static List<MatchUpdate> winAndEndRound(Match match, int team) {
        List<MatchUpdate> all = win(match, team);
        all.addAll(ticks(match, SHORT.roundEndSeconds()));
        return all;
    }

    private static List<MatchPhase> phases(List<MatchUpdate> updates) {
        return updates.stream().filter(PhaseChanged.class::isInstance).map(PhaseChanged.class::cast)
                .map(PhaseChanged::to).toList();
    }

    @Test
    void theShopComesBetweenRoundsAndLastsItsTimeLimit() {
        Match match = match(SHOP);
        match.begin();
        List<MatchUpdate> updates = winAndEndRound(match, 1);
        assertEquals(new PhaseChanged(MatchPhase.ROUND_END, MatchPhase.SHOP), updates.getLast());
        assertEquals(MatchPhase.SHOP, match.phase());
        assertEquals(10, match.secondsLeft());
        assertEquals(1, match.round(), "the next round has not started");
        assertTrue(match.alivePlayers().isEmpty());
        assertEquals(0, match.roundElapsedSeconds());
        assertEquals(SHOP, match.shopSettings());

        assertTrue(ticks(match, 9).isEmpty());
        assertEquals(1, match.secondsLeft());
        assertEquals(List.of(new PhaseChanged(MatchPhase.SHOP, MatchPhase.PREP), new RoundStarted(2, false)),
                match.tick());
        assertEquals(List.of(A1, A2, B1, B2), match.alivePlayers());
    }

    @Test
    void noShopAfterTheLastRound() {
        Match match = match(SHOP);
        match.begin();
        List<MatchPhase> seen = new ArrayList<>();
        for (int round = 1; round <= SHORT.rounds(); round++) {
            List<MatchUpdate> updates = winAndEndRound(match, 1);
            seen.addAll(phases(updates));
            if (match.phase() == MatchPhase.SHOP) {
                seen.addAll(phases(ticks(match, SHOP.maxSeconds())));
            }
        }
        assertEquals(List.of(MatchPhase.ROUND_END, MatchPhase.SHOP, MatchPhase.PREP, MatchPhase.ROUND_END,
                MatchPhase.SHOP, MatchPhase.PREP, MatchPhase.ROUND_END, MatchPhase.MATCH_END), seen);
        assertEquals(Reason.COMPLETED, match.result().orElseThrow().reason());
    }

    @Test
    void theExtraRoundHasAShopButTheFinalExtraRoundDoesNot() {
        Match match = match(SHOP);
        match.begin();
        winAndEndRound(match, 1);
        ticks(match, SHOP.maxSeconds());
        winAndEndRound(match, 2);
        ticks(match, SHOP.maxSeconds());
        // Round 3 is a draw: tied 1 to 1, so the one extra round follows, after a shop.
        for (UUID player : match.alivePlayers()) {
            match.eliminate(player, null);
        }
        match.settle();
        List<MatchUpdate> updates = ticks(match, SHORT.roundEndSeconds());
        assertEquals(new PhaseChanged(MatchPhase.ROUND_END, MatchPhase.SHOP), updates.getLast());
        updates = ticks(match, SHOP.maxSeconds());
        assertEquals(new RoundStarted(4, true), updates.getLast());
        assertTrue(match.isExtraRound());

        // The extra round is drawn too; no extra rounds are left, so no shop.
        for (UUID player : match.alivePlayers()) {
            match.eliminate(player, null);
        }
        match.settle();
        updates = ticks(match, SHORT.roundEndSeconds());
        assertEquals(List.of(MatchPhase.MATCH_END), phases(updates));
        assertEquals(List.of(1, 2), match.result().orElseThrow().winners());
    }

    @Test
    void noShopAfterAForfeit() {
        Match match = match(SHOP);
        match.begin();
        match.leave(B1);
        match.leave(B2);
        match.settle();
        List<MatchUpdate> updates = ticks(match, SHORT.roundEndSeconds());
        assertEquals(List.of(MatchPhase.MATCH_END), phases(updates));
        assertEquals(Reason.FORFEIT, match.result().orElseThrow().reason());
    }

    @Test
    void aTeamLeavingDuringTheShopForfeitsAtOnce() {
        Match match = match(SHOP);
        match.begin();
        winAndEndRound(match, 1);
        assertEquals(MatchPhase.SHOP, match.phase());
        match.leave(B1);
        assertEquals(MatchPhase.SHOP, match.phase(), "team 2 still has a player");
        List<MatchUpdate> updates = match.leave(B2);
        assertEquals(List.of(MatchPhase.MATCH_END), phases(updates));
        assertEquals(Reason.FORFEIT, match.result().orElseThrow().reason());
        assertEquals(List.of(1), match.result().orElseThrow().winners());
    }

    @Test
    void theLastPlayerLeavingTheShopAbandonsTheMatch() {
        Match match = new Match("dojo", List.of(List.of(A1)), SHORT, MatchStandings.ROUND_WINS, Bonus.NONE, SHOP);
        match.begin();
        match.eliminate(A1, null);
        match.settle();
        ticks(match, SHORT.roundEndSeconds());
        assertEquals(MatchPhase.SHOP, match.phase());
        match.leave(A1);
        assertTrue(match.isClosed());
        assertEquals(Reason.ABANDONED, match.result().orElseThrow().reason());
    }

    @Test
    void everyoneReadyStartsTheQuickStart() {
        Match match = match(SHOP);
        match.begin();
        winAndEndRound(match, 1);
        ticks(match, 3);
        assertEquals(7, match.secondsLeft());

        assertEquals(List.of(new PlayerReady(A1, 1, 4)), match.ready(A1));
        assertTrue(match.ready(A1).isEmpty(), "ready once");
        assertEquals(List.of(new PlayerReady(B1, 2, 4)), match.ready(B1));
        assertEquals(List.of(new PlayerReady(B2, 3, 4)), match.ready(B2));
        assertEquals(List.of(A1, B1, B2), match.readyPlayers());
        assertEquals(7, match.secondsLeft());
        assertEquals(List.of(new PlayerReady(A2, 4, 4), new QuickStart(2)), match.ready(A2));
        assertEquals(2, match.secondsLeft());

        assertTrue(match.tick().isEmpty());
        assertEquals(List.of(MatchPhase.PREP), phases(match.tick()));
        assertTrue(match.readyPlayers().isEmpty());
    }

    @Test
    void theQuickStartNeverLengthensTheShop() {
        Match match = match(SHOP);
        match.begin();
        winAndEndRound(match, 1);
        ticks(match, 9);
        match.ready(A1);
        match.ready(A2);
        match.ready(B1);
        assertEquals(List.of(new PlayerReady(B2, 4, 4)), match.ready(B2));
        assertEquals(1, match.secondsLeft());
    }

    @Test
    void theLastPlayerNotReadyLeavingStartsTheQuickStart() {
        Match match = match(SHOP);
        match.begin();
        winAndEndRound(match, 1);
        match.ready(A1);
        match.ready(B1);
        match.ready(B2);
        List<MatchUpdate> updates = match.leave(A2);
        assertEquals(new QuickStart(2), updates.getLast());
        assertEquals(2, match.secondsLeft());
    }

    @Test
    void readyDoesNothingOutsideTheShopOrForAnOutsider() {
        Match match = match(SHOP);
        assertTrue(match.ready(A1).isEmpty());
        match.begin();
        assertTrue(match.ready(A1).isEmpty());
        winAndEndRound(match, 1);
        assertTrue(match.ready(new UUID(9, 9)).isEmpty());
        match.leave(A2);
        assertTrue(match.ready(A2).isEmpty());
    }

    @Test
    void theShopNeverEndsBeforeTheMapIsReady() {
        Match match = match(SHOP);
        match.begin();
        winAndEndRound(match, 1);
        mapReady.set(false);
        List<MatchUpdate> updates = ticks(match, SHOP.maxSeconds());
        assertEquals(List.of(new MapWait(4)), updates);
        assertEquals(MatchPhase.SHOP, match.phase());
        assertTrue(match.isWaitingForMap());
        assertEquals(4, match.secondsLeft());

        // Ready makes no difference while it waits for the map.
        match.ready(A1);
        match.ready(A2);
        match.ready(B1);
        assertEquals(List.of(new PlayerReady(B2, 4, 4)), match.ready(B2));
        assertTrue(ticks(match, 2).isEmpty());
        mapReady.set(true);
        assertEquals(List.of(new PhaseChanged(MatchPhase.SHOP, MatchPhase.PREP), new RoundStarted(2, false)),
                match.tick());
        assertFalse(match.isWaitingForMap());
    }

    @Test
    void aQuickStartStillWaitsForTheMap() {
        Match match = match(SHOP);
        match.begin();
        winAndEndRound(match, 1);
        mapReady.set(false);
        for (UUID player : List.of(A1, A2, B1, B2)) {
            match.ready(player);
        }
        assertEquals(List.of(new MapWait(4)), ticks(match, 2));
        assertEquals(MatchPhase.SHOP, match.phase());
    }

    @Test
    void theRoundIsReplayedAfterTheMapWaitLimit() {
        Match match = match(SHOP);
        match.begin();
        winAndEndRound(match, 1);
        mapReady.set(false);
        ticks(match, SHOP.maxSeconds());
        assertTrue(ticks(match, 3).isEmpty());
        assertEquals(List.of(new MapReplay(), new PhaseChanged(MatchPhase.SHOP, MatchPhase.PREP),
                new RoundStarted(2, false)), match.tick());
        assertEquals(MatchPhase.PREP, match.phase());
    }

    @Test
    void aZeroMapWaitReplaysAtTheTimeLimit() {
        Match match = match(new ShopSettings(true, 10, 2, 0));
        match.begin();
        winAndEndRound(match, 1);
        mapReady.set(false);
        List<MatchUpdate> updates = ticks(match, 10);
        assertEquals(List.of(new MapReplay(), new PhaseChanged(MatchPhase.SHOP, MatchPhase.PREP),
                new RoundStarted(2, false)), updates);
    }

    @Test
    void aShopThatIsOffKeepsTodaysFlow() {
        Match on = match(ShopSettings.OFF);
        Match legacy = new Match("dojo", List.of(List.of(A1, A2), List.of(B1, B2)), SHORT, MatchStandings.ROUND_WINS);
        for (Match match : List.of(on, legacy)) {
            match.begin();
            List<MatchUpdate> updates = winAndEndRound(match, 1);
            assertEquals(List.of(MatchPhase.ROUND_END, MatchPhase.PREP), phases(updates));
            assertEquals(new RoundStarted(2, false), updates.getLast());
            assertTrue(match.ready(A1).isEmpty());
        }
        assertEquals(ShopSettings.OFF, legacy.shopSettings());
    }

    @Test
    void settingsAreReadAndClamped() {
        assertEquals(ShopSettings.DEFAULTS, ShopSettings.from(null));
        assertEquals(new ShopSettings(true, 45, 5, 30), ShopSettings.DEFAULTS);
        YamlConfiguration config = new YamlConfiguration();
        config.set("enabled", false);
        config.set("max-seconds", 0);
        config.set("quick-start-seconds", 99999);
        config.set("map-wait-limit-seconds", -5);
        assertEquals(new ShopSettings(false, 1, 3600, 0), ShopSettings.from(config));
        config.set("max-seconds", 60);
        config.set("quick-start-seconds", 10);
        config.set("map-wait-limit-seconds", 20);
        assertEquals(new ShopSettings(false, 60, 10, 20), ShopSettings.from(config));
    }
}
