package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchView;
import java.util.UUID;

/**
 * Adjusts a player's targeting weight after the leading-team bias, for example to cut it for a
 * hidden player or raise it for a marked one. Called on the server thread.
 */
@FunctionalInterface
public interface TargetWeigher {

    /** Leaves every weight as it is. */
    TargetWeigher NONE = (match, player, weight) -> weight;

    /**
     * @param weight the weight so far: 1, or the leader bias for a player on the leading team
     * @return the new weight; 0 or less leaves the player out
     */
    double weigh(MatchView match, UUID player, double weight);
}
