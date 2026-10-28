package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import org.bukkit.Location;
import org.bukkit.World;

/** Consecutive time stuck or outside the arena, sampled on the server thread. */
final class EnemyMotion {

    private static final long TIMEOUT_NANOS = 10_000_000_000L;
    private static final double MOVEMENT_SQUARED = 0.25 * 0.25;

    private Location anchor;
    private long movedAt;
    private Long outsideAt;

    /** Small jitters do not reset the stuck timer; moving a quarter block does. */
    boolean expired(Location at, World world, ArenaBounds bounds, long now) {
        if (anchor == null || anchor.getWorld() != at.getWorld() || anchor.distanceSquared(at) >= MOVEMENT_SQUARED) {
            anchor = at.clone();
            movedAt = now;
        }
        boolean inside = at.getWorld() == world && bounds.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (inside) {
            outsideAt = null;
        } else if (outsideAt == null) {
            outsideAt = now;
        }
        return now - movedAt >= TIMEOUT_NANOS || outsideAt != null && now - outsideAt >= TIMEOUT_NANOS;
    }
}
