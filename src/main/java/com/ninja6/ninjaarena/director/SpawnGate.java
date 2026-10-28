package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchView;

/**
 * Limits what the director does each second, for mob caps, a spawn rate limit or a tick-time
 * guard. Called on the server thread. {@link #OPEN} limits nothing.
 */
public interface SpawnGate {

    /** Lets every spawn through at the intensity asked for. */
    SpawnGate OPEN = new SpawnGate() {
    };

    /** The intensity to run at, from 0 to 1, given the director's target; a guard may lower it. */
    default double intensity(MatchView match, double target) {
        return target;
    }

    /** Whether one more enemy may spawn in the match now. Asked before every spawn. */
    default boolean maySpawn(MatchView match) {
        return true;
    }

    /** One enemy spawned in the match, so counters can be kept. */
    default void spawned(MatchView match, EnemyKind kind) {
    }
}
