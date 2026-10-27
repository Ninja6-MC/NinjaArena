package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.damage.DamageSource;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

class TeamScoringListenerTest extends MatchFixture {

    private TeamScores scores;

    private void useScoring(MatchSettings matchSettings, ScoringSettings settings) {
        scores = new TeamScores();
        var scoring = new TeamScoringListener(scores, settings, plugin.getServer()::getPlayer);
        matches = new MatchService(plugin, arenas, () -> arenaWorld, matchSettings, player -> {
            restored.add(player.getUniqueId());
            return RestoreResult.RESTORED;
        }, event -> {
            events.add(event);
            switch (event) {
                case PlayerEliminatedEvent eliminated -> scoring.onEliminated(eliminated);
                case RoundEndEvent ended -> scoring.onRoundEnd(ended);
                case MatchEndEvent ended -> scoring.onMatchEnd(ended);
                default -> { }
            }
        }, player -> readied.add(player.getUniqueId()));
        matches.setStandings(scores);
    }

    private MatchView current() {
        return matches.match("dojo").orElseThrow();
    }

    private MatchResult result() {
        return events(MatchEndEvent.class).getLast().result();
    }

    private void winRound(UUID first, UUID second, UUID killer) {
        matches.died(player(first), player(killer));
        matches.died(player(second), player(killer));
        runNextTick();
    }

    @Test
    void killsAndWinsAccumulateAcrossRoundsAndResultsAppearOnceBeforeReturningHome() {
        useScoring(SETTINGS, ScoringSettings.DEFAULTS);
        startTwoOnTwo();
        ticks(2);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(CAROL));
        assertEquals(4, scores.score(current(), 1));
        runNextTick();
        assertEquals(9, scores.score(current(), 1));
        ticks(1 + 2);
        assertEquals(9, scores.score(current(), 1));
        winRound(BOB, DAVE, ALICE);
        assertEquals(18, scores.score(current(), 1));
        ticks(1);
        MatchView finished = current();
        assertEquals(List.of(1), result().winners());
        assertEquals(18, result().standings().getFirst().score());
        assertTrue(restored.isEmpty());
        for (var player : players.values()) {
            assertEquals(List.of("Final scores: Team 1: 18 points (2 rounds won), Team 2: 0 points (0 rounds won)."),
                    player.messages.stream().filter(message -> message.startsWith("Final scores:")).toList());
            assertFalse(player.messages.stream().anyMatch(message -> message.startsWith("Rounds won:")));
            assertTrue(player.messages.contains("Team 1 wins the match!"));
            // The table sits between the winner and the countdown.
            List<String> end = player.messages.subList(player.messages.indexOf("Team 1 wins the match!"),
                    player.messages.size());
            assertEquals(3, end.size(), end.toString());
            assertTrue(end.get(1).startsWith("Final scores:"), end.toString());
            assertTrue(end.get(2).startsWith("Returning in "), end.toString());
        }
        ticks(2);
        assertEquals(4, restored.size());
        assertTrue(matches.matches().isEmpty());
        assertEquals(18, scores.score(finished, 1));
    }

    @Test
    void teamkillsAwardNothingEvenWhenFriendlyFireAllowsTheDamage() {
        useScoring(SETTINGS, ScoringSettings.DEFAULTS);
        startTwoOnTwo();
        ticks(2);
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[] {DamageSource.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        var damage = new EntityDamageByEntityEvent(player(ALICE), player(CAROL), DamageCause.ENTITY_ATTACK,
                source, 20);
        new MatchProtectionListener(matches, plugin.getServer()::getPlayer, true, deaths::isSpectating).onDamage(damage);
        assertFalse(damage.isCancelled());
        matches.died(player(CAROL), player(ALICE));
        assertEquals(ALICE, events(PlayerEliminatedEvent.class).getFirst().killer().orElseThrow());
        assertEquals(0, scores.score(current(), 1));
        assertEquals(0, scores.score(current(), 2));
    }

    @Test
    void suicidesEnvironmentalDeathsAndLeavingAwardNoKillPoints() {
        useScoring(SETTINGS, ScoringSettings.DEFAULTS);
        startTwoOnTwo();
        MatchView match = current();
        matches.died(player(ALICE), player(ALICE));
        matches.died(player(CAROL), null);
        matches.leave(player(BOB), MatchService.Exit.COMMAND);
        matches.leave(player(DAVE), MatchService.Exit.QUIT);
        assertEquals(0, scores.score(match, 1));
        assertEquals(0, scores.score(match, 2));
        assertTrue(events(RoundEndEvent.class).isEmpty());
    }

    @Test
    void killsFromAnotherMatchDoNotEarnPointsAndSimultaneousMatchesStaySeparate() throws Exception {
        useScoring(SETTINGS, ScoringSettings.DEFAULTS);
        arenas.create("other", new GridCell(1, 0));
        arenas.update("other", a -> a.withBounds(new ArenaBounds(266, 60, 10, 346, 100, 90))
                .withWaitingRoom(new ArenaPoint(300, 64, 50, 0, 0))
                .withTeamSpawn(1, new ArenaPoint(280, 64, 20, 0, 0))
                .withTeamSpawn(2, new ArenaPoint(330, 64, 80, 0, 0))
                .withEnemySpawn(new ArenaPoint(300, 64, 20, 0, 0)));
        arenas.finishEdit("other");
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        matches.start("other", List.of(List.of(CAROL), List.of(DAVE)));
        matches.died(player(BOB), player(CAROL));
        MatchView other = matches.match("other").orElseThrow();
        assertEquals(0, scores.score(current(), 1));
        assertEquals(0, scores.score(other, 1));
        assertTrue(events(PlayerEliminatedEvent.class).getFirst().killer().isEmpty());
        scores.addPoints(other, 1, 11);
        assertEquals(0, scores.score(current(), 1));
        assertEquals(11, scores.score(other, 1));
    }

    @Test
    void externalAwardsCanOutrankMoreRoundsWonAndControlTheWinnerAndTableOrder() {
        useScoring(new MatchSettings(1, 3, 1, 1, 2, 1, 1), ScoringSettings.DEFAULTS);
        startTwoOnTwo();
        scores.addPoints(current(), 2, 10);
        ticks(1);
        winRound(BOB, DAVE, ALICE);
        ticks(1);
        assertEquals(List.of(2), result().winners());
        assertEquals(List.of(2, 1), result().standings().stream().map(MatchResult.Standing::team).toList());
        assertEquals(10, result().standings().getFirst().score());
        assertEquals(0, result().standings().getFirst().roundsWon());
        assertEquals(9, result().standings().getLast().score());
        assertTrue(players.get(ALICE).messages.contains("Team 2 wins the match!"));
    }

    @Test
    void roundWinPointsAreAppliedBeforeTheTieAndExtraRoundDecision() {
        useScoring(new MatchSettings(1, 3, 1, 1, 2, 1, 1), ScoringSettings.DEFAULTS);
        startTwoOnTwo();
        scores.addPoints(current(), 2, 4);
        winRound(BOB, DAVE, ALICE);
        assertEquals(9, scores.score(current(), 1));
        ticks(1);
        assertEquals(MatchPhase.MATCH_END, current().phase());
        assertEquals(List.of(1), result().winners());
        assertEquals(1, result().rounds().size());
    }

    @Test
    void tiedPointsTriggerAnExtraRoundEvenWhenRoundsWonAreNotTied() {
        useScoring(new MatchSettings(1, 3, 1, 1, 2, 1, 1), ScoringSettings.DEFAULTS);
        startTwoOnTwo();
        scores.addPoints(current(), 2, 9);
        winRound(BOB, DAVE, ALICE);
        ticks(1);
        assertTrue(current().isExtraRound());
        assertEquals(MatchPhase.PREP, current().phase());
        winRound(ALICE, CAROL, BOB);
        ticks(1);
        assertEquals(List.of(2), result().winners());
        assertEquals(18, result().standings().getFirst().score());
    }

    @Test
    void aDrawAwardsNoRoundPointsAndAnUnbrokenPointsTieSharesTheWin() {
        useScoring(new MatchSettings(1, 3, 1, 1, 2, 1, 1), ScoringSettings.DEFAULTS);
        startTwoOnTwo();
        scores.addPoints(current(), 2, 9);
        winRound(BOB, DAVE, ALICE);
        ticks(1);
        for (UUID id : List.of(ALICE, BOB, CAROL, DAVE)) {
            matches.died(player(id), null);
        }
        runNextTick();
        assertEquals(9, scores.score(current(), 1));
        assertEquals(9, scores.score(current(), 2));
        ticks(1);
        assertEquals(List.of(1, 2), result().winners());
        assertEquals(List.of(1, 2), result().standings().stream().map(MatchResult.Standing::team).toList());
        assertTrue(players.get(ALICE).messages.contains("Teams 1 and 2 share the win!"));
    }

    @Test
    void killsInTheSameTickBothEarnPointsEvenWhenBothTeamsGoDown() {
        useScoring(new MatchSettings(1, 3, 1, 1, 2, 1, 0), ScoringSettings.DEFAULTS);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        matches.died(player(ALICE), player(BOB));
        matches.died(player(BOB), player(ALICE));
        runNextTick();
        ticks(1);
        assertEquals(List.of(1, 2), result().winners());
        assertEquals(List.of(2, 2), result().standings().stream().map(MatchResult.Standing::score).toList());
        assertTrue(result().rounds().getFirst().isDraw());
    }

    @Test
    void configuredAwardsCanBeChangedOrDisabled() {
        useScoring(SETTINGS, new ScoringSettings(7, 3));
        startTwoOnTwo();
        winRound(BOB, DAVE, ALICE);
        assertEquals(13, scores.score(current(), 1));
        matches.close();
        useScoring(SETTINGS, new ScoringSettings(0, 0));
        startTwoOnTwo();
        winRound(BOB, DAVE, ALICE);
        assertEquals(0, scores.score(current(), 1));
    }

    @Test
    void aNewMatchInTheSameArenaStartsAtZeroAndDoesNotChangeTheOldResult() {
        useScoring(SETTINGS, ScoringSettings.DEFAULTS);
        startTwoOnTwo();
        MatchView old = current();
        scores.addPoints(old, 1, 15);
        matches.close();
        startTwoOnTwo();
        assertEquals(0, scores.score(current(), 1));
        assertEquals(15, scores.score(old, 1));
    }

    @Test
    void resultsReachOnlyOnlineMatchPlayersAndKeepPointsEarnedBeforeLeaving() {
        useScoring(new MatchSettings(1, 3, 1, 1, 2, 1, 0), ScoringSettings.DEFAULTS);
        matches.start("dojo", List.of(List.of(ALICE, CAROL), List.of(BOB)));
        matches.died(player(BOB), player(CAROL));
        matches.leave(player(CAROL), MatchService.Exit.COMMAND);
        runNextTick();
        players.get(BOB).online = false;
        ticks(1);
        assertEquals(7, result().standings().getFirst().score());
        assertTrue(players.get(ALICE).messages.stream().anyMatch(message -> message.startsWith("Final scores:")));
        for (UUID id : List.of(BOB, CAROL, DAVE)) {
            assertFalse(players.get(id).messages.stream().anyMatch(message -> message.startsWith("Final scores:")));
        }
    }
}
