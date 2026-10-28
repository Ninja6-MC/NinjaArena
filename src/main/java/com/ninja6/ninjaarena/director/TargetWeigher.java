package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchView;
import java.util.UUID;

/**
 * Adjusts a player's targeting weight after the leading-team bias, for example to cut it for a
 * hidden player or raise it for a marked one, and decides once a second whether each enemy
 * keeps its target. Called on the server thread.
 */
@FunctionalInterface
public interface TargetWeigher {

    /** Leaves every weight as it is and every target in place. */
    TargetWeigher NONE = (match, player, weight) -> weight;

    /**
     * @param weight the weight so far: 1, or the leader bias for a player on the leading team
     * @return the new weight; 0 or less leaves the player out
     */
    double weigh(MatchView match, UUID player, double weight);

    /**
     * The weight for an enemy of the given kind; by default the same for every kind.
     *
     * @param weight the weight so far: 1, or the leader bias for a player on the leading team
     * @return the new weight; 0 or less leaves the player out
     */
    default double weigh(MatchView match, UUID player, double weight, EnemyKind kind) {
        return weigh(match, player, weight);
    }

    /**
     * Whether the enemy may go for anyone this second. False clears its target and leaves it
     * without one until a later second allows it again.
     */
    default boolean mayTarget(MatchView match, MatchDirector.Enemy enemy) {
        return true;
    }

    /**
     * Whether the enemy keeps chasing its target, a player alive in the round. False makes
     * the director draw it another player.
     */
    default boolean keepsTarget(MatchView match, MatchDirector.Enemy enemy, UUID target) {
        return true;
    }
}
