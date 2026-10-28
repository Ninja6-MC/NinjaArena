package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchStandings;
import com.ninja6.ninjaarena.match.MatchView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Picks the player an enemy goes for: a weighted draw over the players alive in the round, in
 * which the team with the most points weighs more. Pure apart from the random source.
 */
public final class Targeting {

    private Targeting() {
    }

    /**
     * The teams with the most points, when they have more than some other starting team;
     * empty while every team is level, so nobody is singled out.
     */
    public static List<Integer> leaders(MatchView match, MatchStandings standings) {
        Map<Integer, Integer> scores = new LinkedHashMap<>();
        for (int team : match.startingTeams()) {
            scores.put(team, standings.score(match, team));
        }
        if (scores.isEmpty()) {
            return List.of();
        }
        int top = scores.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        int bottom = scores.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
        if (top == bottom) {
            return List.of();
        }
        return scores.entrySet().stream().filter(entry -> entry.getValue() == top).map(Map.Entry::getKey).toList();
    }

    /** Each alive player's weight, team 1 first; players weighed at 0 or less are left out. */
    public static Map<UUID, Double> weights(MatchView match, MatchStandings standings, double leaderBias,
            TargetWeigher weigher) {
        Objects.requireNonNull(weigher, "weigher");
        List<Integer> leaders = leaders(match, standings);
        Map<UUID, Double> weights = new LinkedHashMap<>();
        for (UUID player : match.alivePlayers()) {
            double base = leaders.contains(match.teamOf(player)) ? leaderBias : 1;
            double weight = weigher.weigh(match, player, base);
            if (Double.isFinite(weight) && weight > 0) {
                weights.put(player, weight);
            }
        }
        return weights;
    }

    /**
     * Draws a player by weight.
     *
     * @param random a number from 0 (inclusive) to 1 (exclusive)
     * @return null when no player has any weight
     */
    public static UUID pick(Map<UUID, Double> weights, DoubleSupplier random) {
        double total = 0;
        for (double weight : weights.values()) {
            total += weight;
        }
        if (!(total > 0)) {
            return null;
        }
        double roll = Math.clamp(random.getAsDouble(), 0, Math.nextDown(1.0)) * total;
        List<UUID> players = new ArrayList<>(weights.keySet());
        for (UUID player : players) {
            roll -= weights.get(player);
            if (roll < 0) {
                return player;
            }
        }
        return players.getLast();
    }
}
