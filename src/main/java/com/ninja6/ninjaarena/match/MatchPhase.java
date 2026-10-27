package com.ninja6.ninjaarena.match;

/**
 * Where a {@link Match} is in its round loop. A match goes WAITING, then PREP, COMBAT,
 * optionally OVERRUN, and ROUND_END for each round, then MATCH_END.
 */
public enum MatchPhase {

    /**
     * Created but not begun. The players are still being handed over from the waiting room;
     * {@link Match#begin()} moves on to the first PREP.
     */
    WAITING,
    /** Teams are at their spawns getting ready. No damage is taken and no enemies spawn. */
    PREP,
    /** The round is being fought, with the round timer running. */
    COMBAT,
    /**
     * The round timer ran out and the fight goes on at maximum intensity until one team is
     * left (or the optional overrun limit runs out). {@link #isMaxIntensity()} is true.
     */
    OVERRUN,
    /** A round has been decided; a short pause before the next round or the match end. */
    ROUND_END,
    /** The match is over and its result known; players are sent home when it runs out. */
    MATCH_END;

    /** Whether a round is in progress: PREP, COMBAT or OVERRUN. Deaths and departures count. */
    public boolean isInRound() {
        return this == PREP || this == COMBAT || this == OVERRUN;
    }

    /** Whether players fight and can be hurt: COMBAT or OVERRUN. */
    public boolean isFighting() {
        return this == COMBAT || this == OVERRUN;
    }

    /** Whether the enemy director should run at maximum intensity: OVERRUN only. */
    public boolean isMaxIntensity() {
        return this == OVERRUN;
    }
}
