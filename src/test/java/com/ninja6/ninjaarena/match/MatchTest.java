package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.match.MatchResult.Reason;
import com.ninja6.ninjaarena.match.MatchUpdate.Cause;
import com.ninja6.ninjaarena.match.MatchUpdate.Closed;
import com.ninja6.ninjaarena.match.MatchUpdate.MatchEnded;
import com.ninja6.ninjaarena.match.MatchUpdate.PhaseChanged;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerEliminated;
import com.ninja6.ninjaarena.match.MatchUpdate.PlayerLeft;
import com.ninja6.ninjaarena.match.MatchUpdate.RoundEnded;
import com.ninja6.ninjaarena.match.MatchUpdate.RoundStarted;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchTest {

    private static final UUID A1 = new UUID(1, 1);
    private static final UUID A2 = new UUID(1, 2);
    private static final UUID B1 = new UUID(2, 1);
    private static final UUID B2 = new UUID(2, 2);
    private static final UUID C1 = new UUID(3, 1);

    /** Prep 3, combat 5, overrun limit 4, round end 2, match end 3, 3 rounds, 1 extra. */
    private static final MatchSettings SHORT = new MatchSettings(3, 5, 4, 2, 3, 3, 1);

    private static Match twoTeams(MatchSettings settings) {
        return new Match("dojo", List.of(List.of(A1, A2), List.of(B1, B2)), settings, MatchStandings.ROUND_WINS);
    }

    private static List<MatchUpdate> ticks(Match match, int count) {
        List<MatchUpdate> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            all.addAll(match.tick());
        }
        return all;
    }

    /** Takes every player of the losing team out and settles, so the other team wins the round. */
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

    /** Ticks through ROUND_END and the next PREP into COMBAT. */
    private static void nextRoundToCombat(Match match) {
        ticks(match, SHORT.roundEndSeconds() + SHORT.prepSeconds());
        assertEquals(MatchPhase.COMBAT, match.phase());
    }

    @Test
    void phasesFollowTheConfiguredTimings() {
        Match match = twoTeams(SHORT);
        assertEquals(MatchPhase.WAITING, match.phase());
        assertTrue(match.tick().isEmpty());

        assertEquals(List.of(new PhaseChanged(MatchPhase.WAITING, MatchPhase.PREP), new RoundStarted(1, false)),
                match.begin());
        assertEquals(3, match.secondsLeft());
        assertEquals(List.of(A1, A2, B1, B2), match.alivePlayers());

        assertTrue(ticks(match, 2).isEmpty());
        assertEquals(List.of(new PhaseChanged(MatchPhase.PREP, MatchPhase.COMBAT)), match.tick());
        assertEquals(5, match.secondsLeft());
        assertFalse(match.isMaxIntensity());

        assertTrue(ticks(match, 4).isEmpty());
        assertEquals(List.of(new PhaseChanged(MatchPhase.COMBAT, MatchPhase.OVERRUN)), match.tick());
        assertTrue(match.isMaxIntensity());
        assertEquals(8, match.roundElapsedSeconds());

        assertTrue(ticks(match, 3).isEmpty());
        List<MatchUpdate> end = match.tick();
        assertEquals(new PhaseChanged(MatchPhase.OVERRUN, MatchPhase.ROUND_END), end.get(0));
        RoundResult result = ((RoundEnded) end.get(1)).result();
        assertTrue(result.isDraw());
        assertTrue(result.reachedOverrun());
        assertEquals(12, result.seconds());
        assertEquals(List.of(A1, A2, B1, B2), result.survivors());
        assertTrue(match.alivePlayers().isEmpty());

        assertTrue(match.tick().isEmpty());
        assertEquals(List.of(new PhaseChanged(MatchPhase.ROUND_END, MatchPhase.PREP), new RoundStarted(2, false)),
                match.tick());
        assertEquals(2, match.round());
        assertEquals(4, match.alivePlayers().size());
    }

    @Test
    void overrunWithoutALimitLastsUntilOneTeamIsLeft() {
        Match match = twoTeams(new MatchSettings(1, 1, 0, 1, 1, 1, 0));
        match.begin();
        ticks(match, 2);
        assertEquals(MatchPhase.OVERRUN, match.phase());
        assertEquals(0, match.secondsLeft());
        assertTrue(ticks(match, 1000).isEmpty());
        assertEquals(MatchPhase.OVERRUN, match.phase());

        List<MatchUpdate> updates = win(match, 2);
        assertEquals(2, ((RoundEnded) updates.getLast()).result().winner());
    }

    @Test
    void theLastTeamWithPlayersAliveWinsTheRound() {
        Match match = twoTeams(SHORT);
        match.begin();
        ticks(match, 3);

        assertEquals(List.of(new PlayerEliminated(A1, 1, Cause.DIED, B1)), match.eliminate(A1, B1));
        assertTrue(match.settle().isEmpty());
        assertEquals(List.of(1, 2), match.aliveTeams());
        assertTrue(match.eliminate(A1, null).isEmpty());

        match.eliminate(A2, null);
        List<MatchUpdate> updates = match.settle();
        assertEquals(new PhaseChanged(MatchPhase.COMBAT, MatchPhase.ROUND_END), updates.get(0));
        RoundResult result = ((RoundEnded) updates.get(1)).result();
        assertEquals(2, result.winner());
        assertFalse(result.reachedOverrun());
        assertEquals(List.of(B1, B2), result.survivors());
        assertEquals(1, match.roundsWon(2));
        assertTrue(match.contains(A1));
        assertEquals(1, match.teamOf(A1));
    }

    @Test
    void theTickSettlesARoundNobodySettled() {
        Match match = twoTeams(SHORT);
        match.begin();
        ticks(match, 3);
        match.eliminate(B1, null);
        match.eliminate(B2, null);
        List<MatchUpdate> updates = match.tick();
        assertEquals(1, ((RoundEnded) updates.get(1)).result().winner());
        assertEquals(SHORT.roundEndSeconds(), match.secondsLeft());
    }

    @Test
    void teamsThatGoDownTogetherDrawTheRound() {
        Match match = twoTeams(SHORT);
        match.begin();
        ticks(match, 3);
        match.eliminate(A1, null);
        match.eliminate(B1, null);
        assertTrue(match.settle().isEmpty());

        match.eliminate(A2, B2);
        match.eliminate(B2, A2);
        List<MatchUpdate> updates = match.settle();
        RoundResult result = ((RoundEnded) updates.get(1)).result();
        assertTrue(result.isDraw());
        assertTrue(result.survivors().isEmpty());
        assertEquals(0, match.roundsWon(1));
        assertEquals(0, match.roundsWon(2));
    }

    @Test
    void deathsOutsideARoundAndAfterItAreIgnored() {
        Match match = twoTeams(SHORT);
        assertTrue(match.eliminate(A1, null).isEmpty());
        match.begin();
        ticks(match, 3);
        win(match, 1);
        assertEquals(MatchPhase.ROUND_END, match.phase());
        assertTrue(match.eliminate(A1, null).isEmpty());
        assertTrue(match.settle().isEmpty());
    }

    @Test
    void aKillerOutsideTheMatchOrTheVictimThemselvesIsNotCredited() {
        Match match = twoTeams(SHORT);
        match.begin();
        assertEquals(List.of(new PlayerEliminated(A1, 1, Cause.DIED, null)), match.eliminate(A1, C1));
        assertEquals(List.of(new PlayerEliminated(A2, 1, Cause.DIED, null)), match.eliminate(A2, A2));
    }

    @Test
    void theTeamWithMostRoundsWinsAfterTheLastRound() {
        Match match = twoTeams(SHORT);
        match.begin();
        ticks(match, 3);
        win(match, 1);
        nextRoundToCombat(match);
        win(match, 2);
        nextRoundToCombat(match);
        win(match, 1);

        ticks(match, 1);
        List<MatchUpdate> updates = match.tick();
        assertEquals(new PhaseChanged(MatchPhase.ROUND_END, MatchPhase.MATCH_END), updates.get(0));
        MatchResult result = ((MatchEnded) updates.get(1)).result();
        assertEquals(Reason.COMPLETED, result.reason());
        assertEquals(List.of(1), result.winners());
        assertEquals(1, result.standings().get(0).team());
        assertEquals(2, result.standings().get(0).roundsWon());
        assertEquals(List.of(A1, A2), result.standings().get(0).players());
        assertEquals(3, result.rounds().size());
        assertEquals(result, match.result().orElseThrow());

        assertTrue(ticks(match, 2).isEmpty());
        assertEquals(List.of(new Closed()), match.tick());
        assertTrue(match.isClosed());
        assertTrue(match.tick().isEmpty());
    }

    @Test
    void aTieAfterTheLastRoundPlaysAnExtraRound() {
        Match match = twoTeams(new MatchSettings(3, 5, 4, 2, 3, 2, 1));
        match.begin();
        ticks(match, 3);
        win(match, 1);
        nextRoundToCombat(match);
        win(match, 2);

        ticks(match, 1);
        assertEquals(List.of(new PhaseChanged(MatchPhase.ROUND_END, MatchPhase.PREP), new RoundStarted(3, true)),
                match.tick());
        assertTrue(match.isExtraRound());
        ticks(match, 3);
        win(match, 2);
        ticks(match, 2);
        MatchResult result = match.result().orElseThrow();
        assertEquals(List.of(2), result.winners());
        assertTrue(result.rounds().getLast().extra());
    }

    @Test
    void aTieThatExtraRoundsDoNotBreakIsAShareWin() {
        Match match = twoTeams(new MatchSettings(1, 1, 1, 1, 1, 2, 2));
        match.begin();
        // Every round runs out in overrun as a draw: two rounds, then two extra rounds.
        int rounds = 0;
        while (match.result().isEmpty()) {
            if (!match.tick().isEmpty() && match.phase() == MatchPhase.PREP) {
                rounds++;
            }
        }
        assertEquals(4, match.round());
        assertEquals(3, rounds);
        MatchResult result = match.result().orElseThrow();
        assertEquals(Reason.COMPLETED, result.reason());
        assertEquals(List.of(1, 2), result.winners());
        assertTrue(result.isSharedWin());
    }

    @Test
    void customStandingsDecideTheTie() {
        Map<Integer, Integer> points = new HashMap<>(Map.of(1, 7, 2, 3));
        Match match = new Match("dojo", List.of(List.of(A1), List.of(B1)), new MatchSettings(1, 1, 1, 1, 1, 1, 3),
                (view, team) -> points.get(team));
        match.begin();
        ticks(match, 4);
        assertTrue(match.results().getFirst().isDraw());
        ticks(match, 1);
        MatchResult result = match.result().orElseThrow();
        assertEquals(List.of(1), result.winners());
        assertEquals(7, result.standings().get(0).score());
        assertEquals(3, result.standings().get(1).score());
    }

    @Test
    void leavingMidRoundTakesThePlayerOutOfRoundAndMatch() {
        Match match = twoTeams(SHORT);
        match.begin();
        ticks(match, 3);
        assertEquals(List.of(new PlayerEliminated(B1, 2, Cause.LEFT, null), new PlayerLeft(B1, 2)), match.leave(B1));
        assertFalse(match.contains(B1));
        assertEquals(0, match.teamOf(B1));
        assertEquals(List.of(B2), match.playersOf(2));
        assertTrue(match.leave(B1).isEmpty());

        match.eliminate(B2, null);
        win(match, 1);
        nextRoundToCombat(match);
        assertEquals(List.of(A1, A2, B2), match.alivePlayers());
        assertEquals(List.of(List.of(A1, A2), List.of(B1, B2)), match.roster());
    }

    @Test
    void aWholeTeamLeavingWinsTheRoundAndForfeitsTheMatch() {
        Match match = twoTeams(SHORT);
        match.begin();
        match.leave(B1);
        match.leave(B2);
        List<MatchUpdate> settled = match.settle();
        assertEquals(1, ((RoundEnded) settled.get(1)).result().winner());

        ticks(match, 1);
        List<MatchUpdate> updates = match.tick();
        MatchResult result = ((MatchEnded) updates.get(1)).result();
        assertEquals(Reason.FORFEIT, result.reason());
        assertEquals(List.of(1), result.winners());
        assertEquals(2, result.standings().size());
    }

    @Test
    void leavingBetweenRoundsIsNotAnElimination() {
        Match match = twoTeams(SHORT);
        match.begin();
        ticks(match, 3);
        win(match, 1);
        assertEquals(List.of(new PlayerLeft(A1, 1)), match.leave(A1));
    }

    @Test
    void theLastPlayerLeavingAbandonsAndClosesTheMatchAtOnce() {
        Match match = new Match("dojo", List.of(List.of(A1), List.of(B1)), SHORT, MatchStandings.ROUND_WINS);
        match.begin();
        match.leave(A1);
        List<MatchUpdate> updates = match.leave(B1);
        assertEquals(new PlayerLeft(B1, 2), updates.get(1));
        assertEquals(new PhaseChanged(MatchPhase.PREP, MatchPhase.MATCH_END), updates.get(2));
        MatchResult result = ((MatchEnded) updates.get(3)).result();
        assertEquals(Reason.ABANDONED, result.reason());
        assertTrue(result.winners().isEmpty());
        assertEquals(new Closed(), updates.getLast());
        assertTrue(match.isClosed());
    }

    @Test
    void theLastPlayerLeavingDuringMatchEndClosesItAtOnce() {
        Match match = new Match("dojo", List.of(List.of(A1), List.of(B1)), SHORT, MatchStandings.ROUND_WINS);
        match.begin();
        match.stop();
        assertTrue(match.isClosed());

        match = new Match("dojo", List.of(List.of(A1), List.of(B1)), new MatchSettings(1, 1, 1, 1, 9, 1, 0),
                MatchStandings.ROUND_WINS);
        match.begin();
        match.tick();
        win(match, 1);
        match.tick();
        assertEquals(MatchPhase.MATCH_END, match.phase());
        match.leave(B1);
        assertFalse(match.isClosed());
        assertEquals(List.of(new PlayerLeft(A1, 1), new Closed()), match.leave(A1));
        assertEquals(Reason.COMPLETED, match.result().orElseThrow().reason());
    }

    @Test
    void stoppingEndsTheMatchAsStoppedAndClosesIt() {
        Match match = twoTeams(SHORT);
        match.begin();
        List<MatchUpdate> updates = match.stop();
        assertEquals(new PhaseChanged(MatchPhase.PREP, MatchPhase.MATCH_END), updates.get(0));
        assertEquals(Reason.STOPPED, ((MatchEnded) updates.get(1)).result().reason());
        assertEquals(new Closed(), updates.get(2));
        assertTrue(match.stop().isEmpty());
        assertTrue(match.leave(A1).isEmpty());
    }

    @Test
    void aSingleTeamPlaysUntilItIsDownAndNeverForfeits() {
        Match match = new Match("dojo", List.of(List.of(A1, A2), List.of()), SHORT, MatchStandings.ROUND_WINS);
        assertEquals(List.of(1), match.startingTeams());
        assertEquals(2, match.teamCount());
        match.begin();
        ticks(match, 3);
        assertTrue(match.settle().isEmpty());
        match.eliminate(A1, null);
        assertTrue(match.settle().isEmpty());
        match.eliminate(A2, null);
        assertTrue(((RoundEnded) match.settle().get(1)).result().isDraw());
        ticks(match, 2);
        assertEquals(MatchPhase.PREP, match.phase());
        assertEquals(2, match.round());
    }

    @Test
    void threeTeamsPlayOnUntilOneIsLeft() {
        Match match = new Match("dojo", List.of(List.of(A1), List.of(B1), List.of(C1)), SHORT,
                MatchStandings.ROUND_WINS);
        match.begin();
        ticks(match, 3);
        match.eliminate(B1, A1);
        assertTrue(match.settle().isEmpty());
        match.leave(C1);
        assertEquals(1, ((RoundEnded) match.settle().get(1)).result().winner());
        // Teams 1 and 2 still have players, so the match goes on.
        ticks(match, 2);
        assertEquals(2, match.round());
    }

    @Test
    void badRostersAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new Match("dojo", List.of(), SHORT, MatchStandings.ROUND_WINS));
        assertThrows(IllegalArgumentException.class,
                () -> new Match("dojo", List.of(List.of(), List.of()), SHORT, MatchStandings.ROUND_WINS));
        assertThrows(IllegalArgumentException.class,
                () -> new Match("dojo", List.of(List.of(A1), List.of(A1)), SHORT, MatchStandings.ROUND_WINS));
        Match match = twoTeams(SHORT);
        match.begin();
        assertThrows(IllegalStateException.class, match::begin);
    }
}
