package com.ninja6.ninjaarena.match;

import java.util.UUID;

/**
 * Something that happened in a {@link Match}, returned by its methods in the order it
 * happened. {@link MatchService} turns each into teleports, messages and a Bukkit event. A
 * phase change always comes before the update it belongs to: {@link PhaseChanged} to PREP
 * before {@link RoundStarted}, to ROUND_END before {@link RoundEnded}, to MATCH_END before
 * {@link MatchEnded}.
 */
public sealed interface MatchUpdate {

    /** The match moved from one phase to another. */
    record PhaseChanged(MatchPhase from, MatchPhase to) implements MatchUpdate {
    }

    /** A round started; every player in the match is alive again and in PREP. */
    record RoundStarted(int round, boolean extra) implements MatchUpdate {
    }

    /** Why a player was taken out of a round. */
    enum Cause {
        /** They died. They stay in the match and are back in the next round. */
        DIED,
        /** They left the match: by command, by being teleported out, or by disconnecting. */
        LEFT,
        /**
         * They disconnected and may rejoin within the grace period. They stay in the match and
         * are back in a later round if they rejoin; if not, {@link PlayerLeft} follows.
         */
        DISCONNECTED
    }

    /**
     * A player alive in the current round was taken out of it.
     *
     * @param killer the player in the same match who killed them, or {@code null}
     */
    record PlayerEliminated(UUID player, int team, Cause cause, UUID killer) implements MatchUpdate {
    }

    /** A player left the match. Comes after {@link PlayerEliminated} if they were alive in a round. */
    record PlayerLeft(UUID player, int team) implements MatchUpdate {
    }

    /**
     * A player disconnected and may rejoin within the grace period; they are still in the
     * match. Comes after {@link PlayerEliminated} if they were alive in a round.
     */
    record PlayerDisconnected(UUID player, int team) implements MatchUpdate {
    }

    /**
     * A disconnected player rejoined the match. They are not alive in the current round until
     * they are placed in it during PREP; otherwise they wait for the next round.
     */
    record PlayerRejoined(UUID player, int team) implements MatchUpdate {
    }

    /** A round was decided. */
    record RoundEnded(RoundResult result) implements MatchUpdate {
    }

    /**
     * A player in the shop pressed Ready.
     *
     * @param ready   players ready now
     * @param players players in the match
     */
    record PlayerReady(UUID player, int ready, int players) implements MatchUpdate {
    }

    /** Everyone in the shop is ready: the next round comes in {@code seconds}, the map permitting. */
    record QuickStart(int seconds) implements MatchUpdate {
    }

    /** The shop's time is up but the next map is not ready; it waits up to {@code seconds} more. */
    record MapWait(int seconds) implements MatchUpdate {
    }

    /**
     * The next map was still not ready at the end of the wait: the next round is played on
     * the current arena, which is reset first. Comes just before the phase change to PREP.
     */
    record MapReplay() implements MatchUpdate {
    }

    /** The match ended; {@link MatchPhase#MATCH_END} has begun. */
    record MatchEnded(MatchResult result) implements MatchUpdate {
    }

    /**
     * The match is finished with: MATCH_END ran out, every player left, or it was stopped.
     * Always the last update; the service then sends every remaining player home, resets the
     * arena and releases it.
     */
    record Closed() implements MatchUpdate {
    }
}
