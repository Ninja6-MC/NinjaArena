package com.ninja6.ninjaarena.director;

import java.util.Objects;

/**
 * One Shadow Stalker's blink timing, in server ticks: the cooldown, the particle tell before
 * a blink, and how often it may look for a walking path. A Stalker waits a full cooldown after
 * it spawns. A tell that ends without a blink (the target lost, or nowhere to land) is tried
 * again after {@value #RETRY_TICKS} ticks instead of a whole cooldown. Pure.
 */
final class BlinkCycle {

    static final long RETRY_TICKS = 20;
    static final long PATH_CHECK_TICKS = 20;

    private final long cooldown;
    private final int tell;
    private long readyAt;
    private boolean telling;
    private long tellEndsAt;
    private long pathCheckAt = Long.MIN_VALUE;

    BlinkCycle(StalkerSettings settings, long now) {
        Objects.requireNonNull(settings, "settings");
        cooldown = settings.cooldownTicks();
        tell = settings.tellTicks();
        readyAt = now + cooldown;
    }

    /** Whether a tell may start now. */
    boolean ready(long now) {
        return !telling && now >= readyAt;
    }

    boolean telling() {
        return telling;
    }

    /** Starts the tell. */
    void tell(long now) {
        telling = true;
        tellEndsAt = now + tell;
    }

    /** Whether the tell has run its length, so the Stalker blinks now. */
    boolean tellOver(long now) {
        return telling && now >= tellEndsAt;
    }

    /** Ends the tell with a blink; the cooldown starts. */
    void blinked(long now) {
        telling = false;
        readyAt = now + cooldown;
    }

    /** Ends the tell without a blink; another may start shortly. */
    void missed(long now) {
        telling = false;
        readyAt = now + RETRY_TICKS;
    }

    /** Whether a walking path may be looked for now; at most once every {@value #PATH_CHECK_TICKS} ticks. */
    boolean mayCheckPath(long now) {
        if (now < pathCheckAt) {
            return false;
        }
        pathCheckAt = now + PATH_CHECK_TICKS;
        return true;
    }
}
