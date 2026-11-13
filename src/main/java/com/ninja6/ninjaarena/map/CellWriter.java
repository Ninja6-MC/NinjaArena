package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import java.util.concurrent.CompletableFuture;

/**
 * Writes generated maps into slot cells and clears them, spread over server ticks.
 * {@link GeneratedMaps} only asks for a layout to be placed or a region to be cleared in a
 * cell and waits for the {@link Work}.
 *
 * <p>Every future completes on the server thread, normally or exceptionally. Cancelling one
 * drops the work that is left; what was already written stays, so the caller clears the
 * region afterwards. Within a cell, work runs in the order it was asked for, so a clear
 * finishes before the placement asked after it starts. Work in different cells may run
 * side by side. Server thread only.
 */
public interface CellWriter {

    /**
     * Writes every block of the layout that is not air, turning each kind into a block with
     * {@code ThemePalette} for the layout's theme. Map {@code (0, 0, 0)} goes to the origin;
     * map x and z run along world x and z. Air is not written, so the region is cleared
     * first.
     *
     * @param cell the slot cell the layout is in, which orders it behind the cell's other work
     */
    Work place(GridCell cell, ArenaLayout layout, int originX, int originY, int originZ);

    /**
     * Sets every block inside the region, both corners inclusive, to air.
     *
     * @param cell the slot cell the region is in
     */
    Work clear(GridCell cell, ArenaBounds region);

    /** Drops every queued job, cancelling its future. Called on plugin disable. */
    void close();

    /**
     * A placement or clear: completes when it is done, and counts the blocks it has visited
     * out of the blocks in its box, written or not. Server thread only.
     */
    final class Work extends CompletableFuture<Void> {

        private final long total;
        private long done;

        /** @param total the blocks in the box, at least 0 */
        public Work(long total) {
            if (total < 0) {
                throw new IllegalArgumentException("total cannot be negative");
            }
            this.total = total;
        }

        /** Blocks visited so far, from 0 to {@link #total()}. */
        public long done() {
            return done;
        }

        /** Blocks in the box. */
        public long total() {
            return total;
        }

        /** Records blocks visited; for the writer. */
        public void advance(long blocks) {
            if (blocks < 0) {
                throw new IllegalArgumentException("blocks cannot be negative");
            }
            done = Math.min(total, done + blocks);
        }
    }
}
