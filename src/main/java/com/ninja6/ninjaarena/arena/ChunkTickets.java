package com.ninja6.ninjaarena.arena;

/**
 * Plugin chunk tickets in the arena world. On the server this is
 * {@code World#addPluginChunkTicket} and {@code World#removePluginChunkTicket} for this
 * plugin; tests record the calls instead.
 */
public interface ChunkTickets {

    void add(int chunkX, int chunkZ);

    void remove(int chunkX, int chunkZ);
}
