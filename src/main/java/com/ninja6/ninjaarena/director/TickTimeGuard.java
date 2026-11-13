package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.director.StabilityGate.Guard;
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

/**
 * The tick-time guard: where the server's average tick time stands against the thresholds of
 * {@link StabilitySettings}, read at most once per server tick. One guard is shared by
 * everything that backs off when the server is slow: {@link StabilityGate} for enemy spawns
 * and the placement budget for generated maps, so both act on the same reading.
 *
 * <p>Above {@link StabilitySettings#pauseAboveMspt()} the guard is {@link Guard#PAUSED};
 * above {@link StabilitySettings#dropTierAboveMspt()} it is {@link Guard#TIER_DROPPED}. Both
 * hold until the average falls below {@link StabilitySettings#resumeBelowMspt()}, so a server
 * hovering near a threshold does not flap. After a tier drop the guard is
 * {@link Guard#RECOVERING} for {@link StabilitySettings#tierRecoverySeconds()}, then
 * {@link Guard#NORMAL}; a reading above the pause threshold meanwhile drops the tier again.
 * A reading that is not a finite number changes nothing.
 *
 * <p>Time and tick time are passed in. Not thread-safe: call it from the server thread.
 */
public final class TickTimeGuard {

    private final StabilitySettings settings;
    private final IntSupplier currentTick;
    private final DoubleSupplier averageTickMillis;
    private final Logger logger;

    private boolean read;
    private int tick;
    private Guard guard = Guard.NORMAL;
    /** The server tick spawning resumed after a tier drop. */
    private int recoveryStart;

    /**
     * @param currentTick       the server tick; {@code Bukkit#getCurrentTick}
     * @param averageTickMillis the average tick time in milliseconds; {@code Bukkit#getAverageTickTime}
     * @param logger            told when the guard pauses or resumes
     */
    public TickTimeGuard(StabilitySettings settings, IntSupplier currentTick, DoubleSupplier averageTickMillis,
            Logger logger) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.currentTick = Objects.requireNonNull(currentTick, "currentTick");
        this.averageTickMillis = Objects.requireNonNull(averageTickMillis, "averageTickMillis");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public StabilitySettings settings() {
        return settings;
    }

    /** Where the guard stands this tick, reading the tick time if this tick has not yet. */
    public Guard read() {
        int now = currentTick.getAsInt();
        if (read && now == tick) {
            return guard;
        }
        read = true;
        tick = now;
        update(now, averageTickMillis.getAsDouble());
        return guard;
    }

    /** Where the guard stood when last read. */
    public Guard guard() {
        return guard;
    }

    /** Whether the guard holds spawning back now: {@link Guard#PAUSED} or {@link Guard#TIER_DROPPED}. */
    public static boolean holdsBack(Guard guard) {
        return guard == Guard.PAUSED || guard == Guard.TIER_DROPPED;
    }

    private void update(int now, double mspt) {
        if (!Double.isFinite(mspt)) {
            if (guard == Guard.RECOVERING && recovered(now)) {
                change(Guard.NORMAL, "Intensity is back to normal.");
            }
            return;
        }
        String at = "Average tick time is " + String.format(Locale.ROOT, "%.1f", mspt) + " ms; ";
        boolean tierDown = guard == Guard.TIER_DROPPED || guard == Guard.RECOVERING;
        if (mspt > settings.dropTierAboveMspt() || (mspt > settings.pauseAboveMspt() && tierDown)) {
            change(Guard.TIER_DROPPED, at + "enemy spawning paused, intensity lowered one tier and map placement"
                    + " slowed.");
        } else if (mspt > settings.pauseAboveMspt()) {
            change(Guard.PAUSED, at + "enemy spawning paused and map placement slowed.");
        } else if (mspt < settings.resumeBelowMspt() && guard == Guard.PAUSED) {
            change(Guard.NORMAL, at + "enemy spawning and map placement resumed.");
        } else if (mspt < settings.resumeBelowMspt() && guard == Guard.TIER_DROPPED) {
            recoveryStart = now;
            change(Guard.RECOVERING, at + "enemy spawning and map placement resumed; intensity stays one tier"
                    + " lower for " + settings.tierRecoverySeconds() + " s.");
        }
        if (guard == Guard.RECOVERING && recovered(now)) {
            change(Guard.NORMAL, at + "intensity is back to normal.");
        }
    }

    private boolean recovered(int now) {
        return now - recoveryStart >= settings.tierRecoverySeconds() * StabilityGate.TICKS_PER_SECOND;
    }

    private void change(Guard next, String message) {
        if (next == guard) {
            return;
        }
        if (next == Guard.NORMAL || next == Guard.RECOVERING) {
            logger.info(message);
        } else {
            logger.warning(message);
        }
        guard = next;
    }
}
