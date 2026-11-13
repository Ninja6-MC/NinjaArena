package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import java.util.concurrent.CompletableFuture;

/**
 * Writes generated maps into slot cells and clears them, spread over server ticks. This is
 * the seam tick-budgeted placement fills in: {@link GeneratedMaps} only asks for a layout to
 * be placed or a region to be cleared and waits for the future.
 *
 * <p>Every future completes on the server thread, normally or exceptionally. Cancelling one
 * drops the work that is left; what was already written stays, so the caller clears the
 * region afterwards. Work runs in the order it was asked for. Server thread only.
 */
public interface CellWriter {

    /**
     * Writes every block of the layout that is not air, turning each kind into a block with
     * {@code ThemePalette} for the layout's theme. Map {@code (0, 0, 0)} goes to the origin;
     * map x and z run along world x and z. Air is not written, so the region is cleared
     * first.
     */
    CompletableFuture<Void> place(ArenaLayout layout, int originX, int originY, int originZ);

    /** Sets every block inside the region, both corners inclusive, to air. */
    CompletableFuture<Void> clear(ArenaBounds region);

    /** Drops every queued job, cancelling its future. Called on plugin disable. */
    void close();
}
