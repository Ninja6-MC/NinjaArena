package com.ninja6.ninjaarena.map;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One placement budget a tick ({@link PlacementBudget}, {@code stability.placement.budget-ms})
 * shared by generated maps and the generated lobby, so together they never spend more than
 * it. While both have work, the maps get half and the lobby what the maps leave; either one
 * alone gets the whole budget. As with the cell writer, a tick may run over by the end of
 * its last slice. Server thread only.
 */
public final class PlacementShare {

    /** Work that places blocks within a budget. */
    public interface Writer {

        /** Whether it has work this tick. */
        boolean busy();

        /** Works for up to this many nanoseconds. */
        void work(long budgetNanos);
    }

    private final LongSupplier budget;
    private final LongSupplier clock;

    /**
     * @param budget nanoseconds for this tick, asked once a tick with work
     * @param clock  nanoseconds, for measuring; {@link System#nanoTime()} on the server
     */
    public PlacementShare(LongSupplier budget, LongSupplier clock) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Works both for this tick.
     *
     * @return the nanoseconds spent
     */
    public long tick(Writer maps, Writer lobby) {
        boolean mapsBusy = maps.busy();
        boolean lobbyBusy = lobby.busy();
        if (!mapsBusy && !lobbyBusy) {
            return 0;
        }
        long total = budget.getAsLong();
        long start = clock.getAsLong();
        if (mapsBusy) {
            maps.work(lobbyBusy ? total / 2 : total);
        }
        if (lobbyBusy) {
            lobby.work(Math.max(0, total - (clock.getAsLong() - start)));
        }
        return clock.getAsLong() - start;
    }
}
