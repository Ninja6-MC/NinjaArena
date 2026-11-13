package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.director.StabilityGate.Guard;
import com.ninja6.ninjaarena.director.TickTimeGuard;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The time a {@link BudgetedCellWriter} may spend in one server tick, from the tick-time
 * guard that also holds enemy spawning back ({@link TickTimeGuard}). While the guard is
 * {@link Guard#NORMAL} the budget is {@link PlacementSettings#budgetMillis()}. While it holds
 * spawning back ({@link Guard#PAUSED} or {@link Guard#TIER_DROPPED}) the budget is
 * {@link PlacementSettings#slowBudgetMillis()}. Once the guard lets go, the budget climbs in
 * a straight line back to the full one over {@link PlacementSettings#recoverySeconds()}; a
 * guard that holds back again meanwhile drops it to the slow budget again. Hysteresis is the
 * guard's: it lets go only below {@code stability.resume-below-mspt}.
 *
 * <p>Server thread only.
 */
public final class PlacementBudget implements LongSupplier {

    static final int TICKS_PER_SECOND = 20;

    private final PlacementSettings settings;
    private final Supplier<Guard> guard;
    private final IntSupplier currentTick;
    private boolean slowed;
    /** The last server tick the guard held back. */
    private int lastSlow;

    /**
     * @param guard       the guard this tick; {@link TickTimeGuard#read()}
     * @param currentTick the server tick; {@code Bukkit#getCurrentTick}
     */
    public PlacementBudget(PlacementSettings settings, Supplier<Guard> guard, IntSupplier currentTick) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.currentTick = Objects.requireNonNull(currentTick, "currentTick");
    }

    /** Nanoseconds of placement for this tick. */
    @Override
    public long getAsLong() {
        int now = currentTick.getAsInt();
        long full = settings.budgetNanos();
        long slow = settings.slowBudgetNanos();
        if (TickTimeGuard.holdsBack(guard.get())) {
            slowed = true;
            lastSlow = now;
            return slow;
        }
        if (!slowed) {
            return full;
        }
        long ramp = (long) settings.recoverySeconds() * TICKS_PER_SECOND;
        long since = (long) now - lastSlow;
        if (since >= ramp) {
            slowed = false;
            return full;
        }
        return slow + (full - slow) * since / ramp;
    }
}
