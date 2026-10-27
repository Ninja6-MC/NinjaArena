package com.ninja6.ninjaarena.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamScoresTest {

    private final TeamScores scores = new TeamScores();

    private Match match() {
        return new Match("dojo", List.of(List.of(new UUID(0, 1)), List.of(new UUID(0, 2)), List.of()),
                MatchSettings.DEFAULTS, scores);
    }

    @Test
    void awardsFromAnySourceAccumulateOnOneTeamWithoutAffectingOtherMatches() {
        Match first = match();
        Match other = match();
        first.begin();
        other.begin();
        assertEquals(0, scores.score(first, 1));
        assertTrue(scores.addPoints(first, 1, 13));
        assertTrue(scores.addPoints(first, 1, 4));
        assertTrue(scores.addPoints(first, 2, 3));
        assertEquals(17, scores.score(first, 1));
        assertEquals(3, scores.score(first, 2));
        assertEquals(0, scores.score(other, 1));
    }

    @Test
    void invalidTeamsAndInactiveMatchesDoNotAcceptPoints() {
        Match match = match();
        assertFalse(scores.addPoints(match, 1, 10));
        match.begin();
        for (int team : List.of(-1, 0, 3, 4)) {
            assertFalse(scores.addPoints(match, team, 10));
            assertEquals(0, scores.score(match, team));
        }
        assertThrows(IllegalArgumentException.class, () -> scores.addPoints(match, 1, -1));
        assertTrue(scores.addPoints(match, 1, 0));
        assertEquals(0, scores.score(match, 1));
        match.stop();
        assertFalse(scores.addPoints(match, 1, 10));
    }

    @Test
    void largeAwardsSaturateInsteadOfMakingTheLeadingTeamNegative() {
        Match match = match();
        match.begin();
        scores.addPoints(match, 1, Integer.MAX_VALUE - 1);
        scores.addPoints(match, 1, 10);
        scores.addPoints(match, 1, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, scores.score(match, 1));
        assertEquals(0, scores.score(match, 2));
    }

    @Test
    void endedMatchesKeepFrozenScoresAfterTheirLiveTableIsDiscarded() {
        Match match = match();
        match.begin();
        scores.addPoints(match, 2, 15);
        match.stop();
        scores.finish(match);
        assertEquals(15, scores.score(match, 2));
        assertFalse(scores.addPoints(match, 2, 1));
        assertEquals(15, match.result().orElseThrow().standings().getFirst().score());
        Match next = match();
        next.begin();
        assertEquals(0, scores.score(next, 2));
    }
}
