package com.ninja6.ninjaarena.arena;

import java.util.Optional;

/**
 * Where matches find the map they play on, by name. {@link ArenaRegistry} answers for the
 * hand-built arenas in {@code arenas.yml}; on a server a source that also knows the
 * generated maps in the slot cells stands in front of it, so the match engine, death
 * handling, spectating, enemy spawns and tools find either kind by the same name.
 *
 * <p>Admin edits, pickable maps and event schedules stay on {@link ArenaRegistry}: only
 * hand-built arenas can be edited, picked or scheduled. Server thread only.
 */
public interface MapSource {

    /** The map called {@code name}, hand-built or generated. */
    Optional<Arena> get(String name);

    /** Whether a match may start on the map now. */
    boolean isAvailable(String name);

    /** Whether a match holds the map. */
    boolean isHeld(String name);

    /**
     * Marks the map as in use by a match, from its start to its close.
     *
     * @throws ArenaException if it is not available
     */
    void hold(String name) throws ArenaException;

    /**
     * Puts the map back as it should be before or after a match.
     *
     * @throws ArenaException if that fails; the map is then not available
     */
    void reset(String name) throws ArenaException;

    /** Ends a {@link #hold}. Does nothing if the map is not held. */
    void release(String name);

    /**
     * What the placed map called {@code name} was generated from; empty for a hand-built
     * arena and for a name with no map placed.
     */
    default Optional<MapSeed> seed(String name) {
        return Optional.empty();
    }
}
