package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchStandings;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TargetingTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);
    private static final UUID E = new UUID(0, 5);

    private final Map<Integer, Integer> points = new HashMap<>();
    private final MatchStandings standings = (match, team) -> points.getOrDefault(team, 0);
    private final FakeMatch match = new FakeMatch(List.of(List.of(A, C), List.of(B, D), List.of(E))).combat(0);

    @Test
    void noLeaderWhileEveryTeamIsLevel() {
        assertEquals(List.of(), Targeting.leaders(match, standings));
        points.put(1, 4);
        points.put(2, 4);
        points.put(3, 4);
        assertEquals(List.of(), Targeting.leaders(match, standings));
    }

    @Test
    void theTeamsWithTheMostPointsLead() {
        points.put(2, 7);
        assertEquals(List.of(2), Targeting.leaders(match, standings));
        points.put(3, 7);
        assertEquals(List.of(2, 3), Targeting.leaders(match, standings));
    }

    @Test
    void leadersWeighTheBiasAndOthersOne() {
        points.put(2, 5);
        Map<UUID, Double> weights = Targeting.weights(match, standings, 2.5, TargetWeigher.NONE);
        assertEquals(Map.of(A, 1.0, C, 1.0, B, 2.5, D, 2.5, E, 1.0), weights);
        assertEquals(List.of(A, C, B, D, E), List.copyOf(weights.keySet()));
    }

    @Test
    void onlyAlivePlayersAreWeighed() {
        points.put(1, 5);
        match.alive.remove(A);
        assertEquals(Map.of(C, 2.0, B, 1.0, D, 1.0, E, 1.0),
                Targeting.weights(match, standings, 2, TargetWeigher.NONE));
        match.phase = MatchPhase.ROUND_END;
        assertEquals(Map.of(), Targeting.weights(match, standings, 2, TargetWeigher.NONE));
    }

    @Test
    void theWeigherAdjustsAfterTheBiasAndCanLeavePlayersOut() {
        points.put(2, 5);
        TargetWeigher weigher = (view, player, weight) -> player.equals(B) ? weight / 4
                : player.equals(C) ? 0 : player.equals(E) ? Double.NaN : weight;
        assertEquals(Map.of(A, 1.0, B, 0.5, D, 2.0), Targeting.weights(match, standings, 2, weigher));
    }

    @Test
    void picksByWeight() {
        Map<UUID, Double> weights = new LinkedHashMap<>();
        weights.put(A, 1.0);
        weights.put(B, 2.0);
        weights.put(C, 1.0);
        assertEquals(A, Targeting.pick(weights, () -> 0));
        assertEquals(A, Targeting.pick(weights, () -> 0.24));
        assertEquals(B, Targeting.pick(weights, () -> 0.25));
        assertEquals(B, Targeting.pick(weights, () -> 0.74));
        assertEquals(C, Targeting.pick(weights, () -> 0.75));
        assertEquals(C, Targeting.pick(weights, () -> 1.0));
        assertEquals(A, Targeting.pick(weights, () -> -3));
    }

    @Test
    void leadingTeamIsDrawnMoreOften() {
        points.put(1, 10);
        Map<UUID, Double> weights = Targeting.weights(match, standings, 2, TargetWeigher.NONE);
        Map<Integer, Integer> draws = new HashMap<>();
        int steps = 700;
        for (int i = 0; i < steps; i++) {
            double roll = (i + 0.5) / steps;
            draws.merge(match.teamOf(Targeting.pick(weights, () -> roll)), 1, Integer::sum);
        }
        // Weights 2 + 2 against 1 + 1 and 1: team 1 gets four sevenths.
        assertEquals(400, draws.get(1));
        assertEquals(200, draws.get(2));
        assertEquals(100, draws.get(3));
    }

    @Test
    void nobodyToPickGivesNull() {
        assertNull(Targeting.pick(Map.of(), () -> 0.5));
    }
}
