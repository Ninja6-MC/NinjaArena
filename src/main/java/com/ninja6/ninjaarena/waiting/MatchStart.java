package com.ninja6.ninjaarena.waiting;

import java.util.List;
import java.util.UUID;

/**
 * Takes over the players of a waiting room whose start countdown has finished. This is where
 * the match engine begins. By the time it is called the players are no longer in the waiting
 * room: leaving it no longer restores them, so whatever is called here owns them from now on,
 * including sending them home. Server thread only.
 */
@FunctionalInterface
public interface MatchStart {

    /**
     * @param arena the arena's name
     * @param teams the players on each team, team 1 first; all of them online, since quitting
     *              takes a player out of the waiting room
     */
    void start(String arena, List<List<UUID>> teams);
}
