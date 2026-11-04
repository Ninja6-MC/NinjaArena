package com.ninja6.ninjaarena.stats;

/**
 * A recorded player statistic. Every stat except {@link #BEST_OVERRUN_SECONDS} is a running
 * total; that one keeps the best single value.
 */
public enum Stat {
    /** Matches won, a shared win included. */
    WINS("wins"),
    /** Rounds won by the player's team while they were in the match. */
    ROUNDS_WON("rounds_won"),
    /** Opposing players killed in the same match. Teamkills and suicides do not count. */
    PLAYER_KILLS("player_kills"),
    /** Director enemies killed while alive in a fight. */
    ENEMY_KILLS("enemy_kills"),
    /** Deaths in a round. Leaving does not count. */
    DEATHS("deaths"),
    /** The longest time, in seconds, the player stayed alive in one round's OVERRUN. */
    BEST_OVERRUN_SECONDS("best_overrun_seconds"),
    /** Matches the player started in, whether or not they finished. */
    MATCHES_PLAYED("matches_played");

    private final String column;

    Stat(String column) {
        this.column = column;
    }

    /** The column the stat is stored in; fixed, so it is safe to place in SQL. */
    String column() {
        return column;
    }

    /** Whether the stat keeps the best value instead of adding up. */
    public boolean isBest() {
        return this == BEST_OVERRUN_SECONDS;
    }
}
