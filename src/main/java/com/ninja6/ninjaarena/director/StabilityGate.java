package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.match.MatchView;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

/**
 * The {@link SpawnGate} that keeps the server stable: enemy caps per match and across every
 * match, a server-wide limit on spawns per tick, and a guard on the average tick time.
 *
 * <p><b>Caps.</b> No enemy spawns in a match that has {@link StabilitySettings#matchMobCap()}
 * alive, or while {@link StabilitySettings#globalMobCap()} are alive across the running
 * matches. At most {@link StabilitySettings#spawnsPerTick()} enemies spawn in one server tick,
 * whichever matches they are for. A director held back by any of these tries again the next
 * second.
 *
 * <p><b>Tick-time guard.</b> Read at most once per server tick, when the gate is next asked.
 * Above {@link StabilitySettings#pauseAboveMspt()} spawning pauses; above
 * {@link StabilitySettings#dropTierAboveMspt()} the intensity also drops by
 * {@link StabilitySettings#intensityTier()}. Both hold until the average falls below
 * {@link StabilitySettings#resumeBelowMspt()}, so a server hovering near a threshold does not
 * flap. Spawning then resumes, but after a tier drop the intensity stays a tier lower for
 * {@link StabilitySettings#tierRecoverySeconds()}, so the directors refill to less than they
 * had before. A reading above the pause threshold during that time pauses spawning again and
 * keeps the tier dropped. A reading that is not a finite number changes nothing.
 *
 * <p>Time and tick time are passed in. Not thread-safe: call it from the server thread.
 */
public final class StabilityGate implements SpawnGate {

    static final int TICKS_PER_SECOND = 20;

    /** Where the tick-time guard stands. */
    public enum Guard {
        /** Spawning as usual. */
        NORMAL,
        /** No spawns until the tick time recovers. */
        PAUSED,
        /** No spawns, and the intensity one tier lower, until the tick time recovers. */
        TIER_DROPPED,
        /** Spawning again after a tier drop, with the intensity still a tier lower for a while. */
        RECOVERING
    }

    private final StabilitySettings settings;
    private final ToIntFunction<MatchView> aliveIn;
    private final Supplier<? extends Collection<? extends MatchView>> matches;
    private final IntSupplier currentTick;
    private final DoubleSupplier averageTickMillis;
    private final Logger logger;

    private boolean read;
    private int tick;
    private int spawnsThisTick;
    private Guard guard = Guard.NORMAL;
    /** The server tick spawning resumed after a tier drop. */
    private int recoveryStart;

    /**
     * @param aliveIn           enemies alive in a match; {@link #alive(MatchDirector)} of its director
     * @param matches           the running matches, for the global cap; {@code MatchService#matches}
     * @param currentTick       the server tick; {@code Bukkit#getCurrentTick}
     * @param averageTickMillis the average tick time in milliseconds; {@code Bukkit#getAverageTickTime}
     * @param logger            told when the guard pauses or resumes spawning
     */
    public StabilityGate(StabilitySettings settings, ToIntFunction<MatchView> aliveIn,
            Supplier<? extends Collection<? extends MatchView>> matches, IntSupplier currentTick,
            DoubleSupplier averageTickMillis, Logger logger) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.aliveIn = Objects.requireNonNull(aliveIn, "aliveIn");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.currentTick = Objects.requireNonNull(currentTick, "currentTick");
        this.averageTickMillis = Objects.requireNonNull(averageTickMillis, "averageTickMillis");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** The enemies of a director that are alive now. */
    public static int alive(MatchDirector director) {
        int alive = 0;
        for (MatchDirector.Enemy enemy : director.enemies()) {
            if (enemy.isAlive()) {
                alive++;
            }
        }
        return alive;
    }

    @Override
    public double intensity(MatchView match, double target) {
        refresh();
        boolean tierDown = guard == Guard.TIER_DROPPED || guard == Guard.RECOVERING;
        return tierDown ? Math.max(0, target - settings.intensityTier()) : target;
    }

    @Override
    public boolean maySpawn(MatchView match) {
        refresh();
        if (guard == Guard.PAUSED || guard == Guard.TIER_DROPPED || spawnsThisTick >= settings.spawnsPerTick()) {
            return false;
        }
        if (aliveIn.applyAsInt(match) >= settings.matchMobCap()) {
            return false;
        }
        int total = 0;
        for (MatchView running : matches.get()) {
            total += aliveIn.applyAsInt(running);
            if (total >= settings.globalMobCap()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void spawned(MatchView match, EnemyKind kind) {
        refresh();
        spawnsThisTick++;
    }

    /** Where the tick-time guard stood when last read. */
    public Guard guard() {
        return guard;
    }

    /** Starts a new server tick's spawn count and reads the tick time, once per tick. */
    private void refresh() {
        int now = currentTick.getAsInt();
        if (read && now == tick) {
            return;
        }
        read = true;
        tick = now;
        spawnsThisTick = 0;
        double mspt = averageTickMillis.getAsDouble();
        if (!Double.isFinite(mspt)) {
            if (guard == Guard.RECOVERING && recovered(now)) {
                change(Guard.NORMAL, "Intensity is back to normal.");
            }
            return;
        }
        String at = "Average tick time is " + String.format(Locale.ROOT, "%.1f", mspt) + " ms; ";
        boolean tierDown = guard == Guard.TIER_DROPPED || guard == Guard.RECOVERING;
        if (mspt > settings.dropTierAboveMspt() || (mspt > settings.pauseAboveMspt() && tierDown)) {
            change(Guard.TIER_DROPPED, at + "enemy spawning paused and intensity lowered one tier.");
        } else if (mspt > settings.pauseAboveMspt()) {
            change(Guard.PAUSED, at + "enemy spawning paused.");
        } else if (mspt < settings.resumeBelowMspt() && guard == Guard.PAUSED) {
            change(Guard.NORMAL, at + "enemy spawning resumed.");
        } else if (mspt < settings.resumeBelowMspt() && guard == Guard.TIER_DROPPED) {
            recoveryStart = now;
            change(Guard.RECOVERING, at + "enemy spawning resumed; intensity stays one tier lower for "
                    + settings.tierRecoverySeconds() + " s.");
        }
        if (guard == Guard.RECOVERING && recovered(now)) {
            change(Guard.NORMAL, at + "intensity is back to normal.");
        }
    }

    private boolean recovered(int now) {
        return now - recoveryStart >= settings.tierRecoverySeconds() * TICKS_PER_SECOND;
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
