package com.ninja6.ninjaarena.stats;

import java.util.Objects;
import java.util.UUID;

/**
 * One player's stats in one scope, or one match's contribution to them in a
 * {@link MatchRecord}.
 *
 * @param player the player
 * @param name   the last name recorded for the player, or {@code null} if none is known
 */
public record PlayerStats(UUID player, String name, int wins, int roundsWon, int playerKills, int enemyKills,
        int deaths, int bestOverrunSeconds, int matchesPlayed) {

    public PlayerStats {
        Objects.requireNonNull(player, "player");
        if (wins < 0 || roundsWon < 0 || playerKills < 0 || enemyKills < 0 || deaths < 0 || bestOverrunSeconds < 0
                || matchesPlayed < 0) {
            throw new IllegalArgumentException("stats cannot be negative");
        }
    }

    /** A player with nothing recorded. */
    public static PlayerStats empty(UUID player) {
        return new PlayerStats(player, null, 0, 0, 0, 0, 0, 0, 0);
    }

    public int value(Stat stat) {
        return switch (stat) {
            case WINS -> wins;
            case ROUNDS_WON -> roundsWon;
            case PLAYER_KILLS -> playerKills;
            case ENEMY_KILLS -> enemyKills;
            case DEATHS -> deaths;
            case BEST_OVERRUN_SECONDS -> bestOverrunSeconds;
            case MATCHES_PLAYED -> matchesPlayed;
        };
    }

    /**
     * Totals added, the best value kept and the later name preferred. Totals stop at
     * {@link Integer#MAX_VALUE}.
     */
    public PlayerStats plus(PlayerStats other) {
        if (!player.equals(other.player)) {
            throw new IllegalArgumentException("different players");
        }
        return new PlayerStats(player, other.name != null ? other.name : name, add(wins, other.wins),
                add(roundsWon, other.roundsWon), add(playerKills, other.playerKills),
                add(enemyKills, other.enemyKills), add(deaths, other.deaths),
                Math.max(bestOverrunSeconds, other.bestOverrunSeconds), add(matchesPlayed, other.matchesPlayed));
    }

    private static int add(int a, int b) {
        long sum = (long) a + b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
