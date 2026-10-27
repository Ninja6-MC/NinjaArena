package com.ninja6.ninjaarena.arena;

import java.util.Locale;

/**
 * A position in the arena world with a facing, used for spawns and the waiting room.
 */
public record ArenaPoint(double x, double y, double z, float yaw, float pitch) {

    public ArenaPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("coordinates must be finite");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("yaw and pitch must be finite");
        }
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    /** Coordinates to one decimal place, for chat. */
    public String describe() {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", x, y, z);
    }
}
