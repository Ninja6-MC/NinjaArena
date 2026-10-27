package com.ninja6.ninjaarena.arena;

import java.io.IOException;

/**
 * Saved copies of each arena's blocks, taken when an edit finishes. The arena world's own
 * save cannot be trusted to hold the built arena: the server's shutdown save writes
 * whatever a match left behind. The copy is what an arena is reset from. On the server
 * this is {@link StructureArenaCopies}; tests keep the copies in memory instead.
 */
public interface ArenaCopies {

    /** Replaces the arena's copy with the blocks now inside {@code bounds}. */
    void save(String name, ArenaBounds bounds) throws IOException;

    /**
     * Puts the arena's saved blocks back where they were taken from.
     *
     * @throws IOException if there is no copy or it cannot be read
     */
    void restore(String name) throws IOException;

    boolean exists(String name);

    /** Removes the arena's copy. Does nothing if there is none. */
    void delete(String name) throws IOException;
}
