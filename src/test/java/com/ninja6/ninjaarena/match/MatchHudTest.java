package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.director.EnemyKind;
import com.ninja6.ninjaarena.director.SpawnGate;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class MatchHudTest extends MatchFixture {

    private MatchHud hud;
    private TeamScores scores;
    private int enemies;

    private void useHud(MatchSettings settings) {
        scores = new TeamScores();
        var scoring = new TeamScoringListener(scores, ScoringSettings.DEFAULTS, plugin.getServer()::getPlayer);
        matches = new MatchService(plugin, arenas, () -> arenaWorld, settings, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, event -> {
            events.add(event);
            switch (event) {
                case PlayerEliminatedEvent out -> scoring.onEliminated(out);
                case RoundStartEvent start -> hud.onRoundStart(start);
                case MatchPhaseChangeEvent phase -> hud.onPhase(phase);
                case RoundEndEvent end -> { scoring.onRoundEnd(end); hud.onRoundEnd(end); }
                case MatchEndEvent end -> { scoring.onMatchEnd(end); hud.onMatchEnd(end); }
                case PlayerLeaveMatchEvent leave -> hud.onLeave(leave);
                default -> { }
            }
        }, player -> readied.add(player.getUniqueId()));
        matches.setStandings(scores);
        hud = new MatchHud(matches::matches, plugin.getServer()::getPlayer, scores, match -> enemies);
    }

    private void advance(int seconds) {
        for (int i = 0; i < seconds; i++) {
            matches.tick();
            hud.tick();
        }
    }

    private BossBar bar() {
        return players.get(ALICE).bossBars.getFirst();
    }

    private static String text(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void timerTracksPrepCombatAndFiniteOverrunWithoutAdvancingTheRound() {
        useHud(SETTINGS);
        startTwoOnTwo();
        assertEquals("Round 1 - Prep 0:02", text(bar().name()));
        assertEquals(1f, bar().progress());
        assertEquals("Round 1", text(players.get(ALICE).titles.getFirst().title()));
        hud.tick();
        assertEquals(2, matches.match("dojo").orElseThrow().secondsLeft());
        advance(1);
        assertEquals(0.5f, bar().progress());
        advance(1);
        assertEquals("Round 1 - 0:03", text(bar().name()));
        assertEquals("Fight!", text(players.get(ALICE).titles.getLast().title()));
        advance(1);
        assertEquals(2f / 3, bar().progress());
        advance(2);
        assertEquals("Round 1 - OVERRUN 0:02", text(bar().name()));
        assertEquals(BossBar.Color.RED, bar().color());
        assertEquals(1f, bar().progress());
        advance(1);
        assertEquals(0.5f, bar().progress());
        advance(1);
        assertEquals("Next round in 1s", text(bar().name()));
        assertEquals(BossBar.Color.YELLOW, bar().color());
        assertEquals("Round drawn", text(players.get(ALICE).titles.getLast().title()));
        assertEquals("entity.player.levelup", players.get(ALICE).sounds.getLast().name().value());
    }

    @Test
    void unlimitedOverrunKeepsAFullBarAndExtraRoundsHaveTheirOwnLabel() {
        useHud(new MatchSettings(1, 1, 0, 1, 1, 1, 1));
        startTwoOnTwo();
        advance(2);
        assertEquals("Round 1 - OVERRUN", text(bar().name()));
        advance(10);
        assertEquals(1f, bar().progress());
        assertEquals(MatchPhase.OVERRUN, matches.match("dojo").orElseThrow().phase());
        for (var id : List.of(ALICE, BOB, CAROL, DAVE)) {
            matches.died(player(id), null);
        }
        runNextTick();
        advance(1);
        assertEquals("Extra round 2 - Prep 0:01", text(bar().name()));
        assertEquals("Extra round 2", text(players.get(ALICE).titles.getLast().title()));
    }

    @Test
    void actionbarShowsCurrentPointsAndLivingEnemiesToEliminatedPlayersToo() {
        useHud(SETTINGS);
        startTwoOnTwo();
        advance(2);
        scores.addPoints(matches.match("dojo").orElseThrow(), 2, 13);
        enemies = 7;
        matches.died(player(BOB), player(ALICE));
        hud.tick();
        assertEquals("T1: 2  T2: 13  |  Enemies left: 7", players.get(BOB).actionBars.getLast());
        assertEquals(1, players.get(BOB).bossBars.size());
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        assertEquals("T1: 9  T2: 13  |  Enemies left: 7", players.get(ALICE).actionBars.getLast());
        assertEquals("Team 1 wins!", text(players.get(ALICE).titles.getLast().title()));
        enemies = 0;
        hud.tick();
        assertTrue(players.get(ALICE).actionBars.getLast().endsWith("Enemies left: 0"));
    }

    @Test
    void cuesPreserveGateDecisionsAndCountersAndReachOnlyOnlineMembers() {
        useHud(SETTINGS);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB, CAROL)));
        players.get(CAROL).online = false;
        int[] spawned = {0};
        SpawnGate gated = hud.withSpawnCues(new SpawnGate() {
            @Override
            public double intensity(MatchView match, double target) { return target / 2; }
            @Override
            public boolean maySpawn(MatchView match) { return false; }
            @Override
            public void spawned(MatchView match, EnemyKind kind) { spawned[0]++; }
        });
        MatchView match = matches.match("dojo").orElseThrow();
        assertFalse(gated.maySpawn(match));
        assertEquals(0.4, gated.intensity(match, 0.8));
        assertTrue(players.get(ALICE).sounds.isEmpty());
        gated.spawned(match, EnemyKind.ZOMBIE);
        assertEquals(1, spawned[0]);
        assertEquals("entity.enderman.teleport", players.get(ALICE).sounds.getFirst().name().value());
        assertEquals(1, players.get(BOB).sounds.size());
        assertTrue(players.get(CAROL).sounds.isEmpty());
        assertTrue(players.get(DAVE).sounds.isEmpty());
        hud.close();
        gated.spawned(match, EnemyKind.ZOMBIE);
        assertEquals(2, spawned[0]);
        assertEquals(1, players.get(ALICE).sounds.size());
    }

    @Test
    void leaveQuitAndOfflineRefreshRemoveTheirFeedbackImmediately() {
        useHud(SETTINGS);
        startTwoOnTwo();
        matches.leave(player(ALICE), MatchService.Exit.COMMAND);
        matches.leave(player(BOB), MatchService.Exit.QUIT);
        players.get(CAROL).online = false;
        hud.tick();
        for (var id : List.of(ALICE, BOB, CAROL)) {
            assertTrue(players.get(id).bossBars.isEmpty());
            assertEquals("", players.get(id).actionBars.getLast());
            assertTrue(players.get(id).titles.isEmpty());
        }
        assertEquals(1, players.get(DAVE).bossBars.size());
    }

    @Test
    void completionForfeitAbandonAndStopClearDisplaysAndAllowArenaReuse() {
        useHud(new MatchSettings(1, 1, 1, 1, 1, 1, 0));
        startTwoOnTwo();
        advance(4);
        assertEquals(MatchPhase.MATCH_END, matches.match("dojo").orElseThrow().phase());
        assertTrue(players.get(ALICE).bossBars.isEmpty());
        advance(1);
        startTwoOnTwo();
        matches.leave(player(BOB), MatchService.Exit.QUIT);
        matches.leave(player(DAVE), MatchService.Exit.QUIT);
        runNextTick();
        advance(1);
        assertTrue(players.get(ALICE).bossBars.isEmpty());
        advance(1);
        matches.start("dojo", List.of(List.of(ALICE), List.of()));
        matches.leave(player(ALICE), MatchService.Exit.QUIT);
        runNextTick();
        assertTrue(players.get(ALICE).bossBars.isEmpty());
        startTwoOnTwo();
        assertEquals(1, players.get(ALICE).bossBars.size());
        matches.close();
        hud.close();
        hud.close();
        for (var fake : players.values()) {
            assertTrue(fake.bossBars.isEmpty());
            assertEquals("", fake.actionBars.getLast());
        }
        hud.tick();
        assertTrue(players.get(ALICE).bossBars.isEmpty());
    }

    @Test
    void oneSynchronousTickerIsCancelledOnDisableAndCannotRestart() {
        useHud(SETTINGS);
        hud.start(plugin);
        hud.start(plugin);
        assertEquals(1, repeating.size());
        startTwoOnTwo();
        int updates = players.get(ALICE).actionBars.size();
        repeating.getFirst().run();
        assertEquals(updates + 1, players.get(ALICE).actionBars.size());
        hud.close();
        assertEquals(1, cancellations);
        assertTrue(players.get(ALICE).bossBars.isEmpty());
        int cleared = players.get(ALICE).actionBars.size();
        repeating.getFirst().run();
        hud.start(plugin);
        hud.close();
        assertEquals(cleared, players.get(ALICE).actionBars.size());
        assertEquals(1, cancellations);
        assertEquals(1, repeating.size());
    }
}
