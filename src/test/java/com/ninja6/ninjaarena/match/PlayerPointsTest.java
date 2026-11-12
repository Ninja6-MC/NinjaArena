package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerPointsTest extends MatchFixture {

    private PlayerPoints points;

    private void usePoints(PointsSettings settings) {
        points = new PlayerPoints(settings);
        matches = new MatchService(plugin, arenas, () -> arenaWorld, SETTINGS, player -> RestoreResult.RESTORED,
                event -> {
                    events.add(event);
                    switch (event) {
                        case PlayerEliminatedEvent out -> points.onEliminated(out);
                        case RoundEndEvent end -> points.onRoundEnd(end);
                        case MatchEndEvent end -> points.onMatchEnd(end);
                        case PlayerLeaveMatchEvent leave -> points.onLeave(leave);
                        default -> { }
                    }
                }, player -> { });
    }

    private MatchView current() {
        return matches.match("dojo").orElseThrow();
    }

    @Test
    void killsSurvivalAndWinSharesAccumulateAcrossRounds() {
        usePoints(PointsSettings.DEFAULTS);
        startTwoOnTwo();
        MatchView match = current();
        ticks(2);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(BOB), player(ALICE));
        assertEquals(2, points.points(match, ALICE));
        matches.died(player(DAVE), player(CAROL));
        runNextTick();
        assertEquals(7, points.points(match, ALICE));
        assertEquals(7, points.points(match, CAROL));
        assertEquals(0, points.points(match, BOB));
        ticks(3);
        assertEquals(7, points.points(match, ALICE));
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        assertEquals(16, points.points(match, ALICE));
        assertEquals(12, points.points(match, CAROL));
    }

    @Test
    void teamkillsSuicidesNoKillerOutsidersAndLeavingDoNotEarnKillPoints() {
        usePoints(PointsSettings.DEFAULTS);
        startTwoOnTwo();
        MatchView match = current();
        points.onEliminated(new PlayerEliminatedEvent(match, CAROL, 1, Cause.DIED, ALICE));
        points.onEliminated(new PlayerEliminatedEvent(match, ALICE, 1, Cause.DIED, ALICE));
        points.onEliminated(new PlayerEliminatedEvent(match, BOB, 2, Cause.DIED, null));
        points.onEliminated(new PlayerEliminatedEvent(match, BOB, 2, Cause.DIED, new UUID(0, 99)));
        matches.leave(player(BOB), MatchService.Exit.COMMAND);
        assertEquals(0, points.points(match, ALICE));
        assertEquals(0, points.points(match, BOB));
    }

    @Test
    void survivalOnADrawAwardsEverySurvivorAndNoWinPool() {
        usePoints(PointsSettings.DEFAULTS);
        startTwoOnTwo();
        MatchView match = current();
        ticks(7);
        assertTrue(events(RoundEndEvent.class).getFirst().result().isDraw());
        for (UUID id : match.players()) {
            assertEquals(2, points.points(match, id));
        }
    }

    @Test
    void winPoolRemainderFollowsRosterOrderAndIncludesEliminatedTeammates() {
        usePoints(new PointsSettings(0, 0, 0, 7));
        startTwoOnTwo();
        MatchView match = current();
        matches.died(player(ALICE), null);
        matches.died(player(BOB), null);
        matches.died(player(DAVE), null);
        runNextTick();
        assertEquals(4, points.points(match, ALICE));
        assertEquals(3, points.points(match, CAROL));
        assertEquals(0, points.points(match, BOB));
    }

    @Test
    void leavingErasesPointsAndExcludesPlayerFromTheWinPool() {
        usePoints(new PointsSettings(2, 1, 0, 7));
        startTwoOnTwo();
        MatchView match = current();
        matches.died(player(BOB), player(ALICE));
        assertEquals(2, points.points(match, ALICE));
        matches.leave(player(ALICE), MatchService.Exit.COMMAND);
        assertEquals(0, points.points(match, ALICE));
        matches.died(player(DAVE), null);
        runNextTick();
        assertEquals(0, points.points(match, ALICE));
        assertEquals(7, points.points(match, CAROL));
    }

    @Test
    void enemyCostUsesFactorOnlyForLivingPlayersDuringAFightAndSaturates() {
        usePoints(new PointsSettings(0, 3, 0, 0));
        startTwoOnTwo();
        MatchView match = current();
        points.enemyKilled(match, ALICE, 5);
        assertEquals(0, points.points(match, ALICE));
        ticks(2);
        points.enemyKilled(match, ALICE, 5);
        assertEquals(15, points.points(match, ALICE));
        points.enemyKilled(match, ALICE, Integer.MAX_VALUE);
        points.enemyKilled(match, ALICE, 1);
        assertEquals(Integer.MAX_VALUE, points.points(match, ALICE));
        matches.died(player(CAROL), null);
        points.enemyKilled(match, CAROL, 5);
        points.enemyKilled(match, new UUID(0, 99), 5);
        assertEquals(0, points.points(match, CAROL));
        assertEquals(0, points.points(match, new UUID(0, 99)));
    }

    @Test
    void zeroDisablesEveryAward() {
        usePoints(new PointsSettings(0, 0, 0, 0));
        startTwoOnTwo();
        MatchView match = current();
        ticks(2);
        points.enemyKilled(match, ALICE, 10);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        for (UUID id : match.players()) {
            assertEquals(0, points.points(match, id));
        }
    }

    @Test
    void reloadBeforeAnyAwardsKeepsAllOldValuesAndNextMatchUsesNewValues() {
        usePoints(PointsSettings.DEFAULTS);
        startTwoOnTwo();
        MatchView old = current();
        points.setSettings(new PointsSettings(10, 4, 8, 20), matches.matches());
        points.setSettings(new PointsSettings(20, 5, 10, 30), matches.matches());
        ticks(2);
        points.enemyKilled(old, ALICE, 3);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        assertEquals(12, points.points(old, ALICE));
        matches.close();
        assertEquals(0, points.points(old, ALICE));
        startTwoOnTwo();
        MatchView next = current();
        ticks(2);
        points.enemyKilled(next, ALICE, 3);
        matches.died(player(BOB), player(ALICE));
        matches.died(player(DAVE), player(ALICE));
        runNextTick();
        assertEquals(80, points.points(next, ALICE));
        assertEquals(0, points.points(old, ALICE));
    }

    @Test
    void pointsSurviveExtraRoundButResetAtNaturalMatchEnd() {
        usePoints(PointsSettings.DEFAULTS);
        matches.start("dojo", List.of(List.of(ALICE), List.of(BOB)));
        MatchView match = current();
        ticks(7);
        ticks(1);
        ticks(7);
        ticks(1);
        assertTrue(match.isExtraRound());
        assertEquals(4, points.points(match, ALICE));
        ticks(7);
        assertEquals(6, points.points(match, ALICE));
        ticks(1);
        assertEquals(MatchPhase.MATCH_END, match.phase());
        assertEquals(0, points.points(match, ALICE));
        assertEquals(0, points.points(match, BOB));
    }
}
