package com.ninja6.ninjaarena.world;

import com.ninja6.ninjaarena.arena.ArenaBounds;

/** Hard coordinate checks made before a terrain read, chunk request or structure placement. */
public final class ArenaExtent {
    private ArenaExtent() {
    }

    public static boolean contains(double x, double z, double diameter) {
        double half = Math.min(diameter, ArenaWorldSettings.MAX_BORDER_SIZE) / 2;
        return Double.isFinite(x) && Double.isFinite(z) && x >= -half && x < half && z >= -half && z < half;
    }

    public static boolean contains(double x, double z) {
        return contains(x, z, ArenaWorldSettings.MAX_BORDER_SIZE);
    }

    public static boolean contains(ArenaBounds bounds) {
        return contains(bounds.minX(), bounds.minZ()) && contains(bounds.maxX(), bounds.maxZ());
    }
}
