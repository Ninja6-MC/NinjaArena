package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * When the Shadow Stalker blinks and where it lands. Pure: the world is asked only through the
 * predicates and suppliers passed in.
 */
final class BlinkPlan {

    /** Directions tried around the spot straight behind the target, in degrees, best first. */
    static final List<Integer> ANGLES = List.of(0, 30, -30, 60, -60, 90, -90);
    /** Heights tried around the target's feet, in blocks, best first. */
    static final List<Integer> HEIGHTS = List.of(0, 1, -1);
    /** How far a walking path may end from the target and still count as reaching it. */
    static final double REACH_SLACK = 2;

    /** A landing spot: feet position and the yaw that faces the target. */
    record Spot(double x, double y, double z, float yaw) {
    }

    private BlinkPlan() {
    }

    /**
     * Whether a target at the distance is out of reach and close enough to blink to: beyond
     * the blink range never, at or beyond the blink distance always, and nearer only when no
     * walking path gets there.
     *
     * @param unreachable asked only for a target nearer than the blink distance, since
     *                    finding a path is the costly part
     */
    static boolean outOfReach(double distance, StalkerSettings settings, BooleanSupplier unreachable) {
        Objects.requireNonNull(settings, "settings");
        if (!Double.isFinite(distance) || distance > settings.blinkRange()) {
            return false;
        }
        return distance >= settings.blinkDistance() || unreachable.getAsBoolean();
    }

    /**
     * Whether a walking path reaches the target.
     *
     * @param found       whether the pathfinder found any path
     * @param reachesEnd  whether the path reaches its own final point
     * @param endToTarget distance from the path's final point to the target
     */
    static boolean reaches(boolean found, boolean reachesEnd, double endToTarget) {
        return found && reachesEnd && endToTarget <= REACH_SLACK;
    }

    /**
     * Landing spots around a target, best first: straight behind them at the behind distance,
     * then at wider angles, then closer, at the target's feet level, a block above or below.
     * Each spot faces the target.
     *
     * @param yaw the target's yaw in Minecraft degrees: 0 faces +z, 90 faces -x
     */
    static List<Spot> candidates(double x, double y, double z, float yaw, double behind) {
        if (!Double.isFinite(behind) || behind <= 0) {
            throw new IllegalArgumentException("behind distance must be finite and positive");
        }
        double feet = Math.floor(y);
        List<Double> distances = behind > 1 ? List.of(behind, Math.max(1, behind / 2)) : List.of(behind);
        List<Spot> spots = new ArrayList<>();
        for (int angle : ANGLES) {
            // The direction from the target to the spot is their facing turned round, then by the angle.
            double radians = Math.toRadians(yaw + 180 + angle);
            double dx = -Math.sin(radians);
            double dz = Math.cos(radians);
            float facing = wrap(yaw + angle);
            for (double distance : distances) {
                for (int height : HEIGHTS) {
                    spots.add(new Spot(x + dx * distance, feet + height, z + dz * distance, facing));
                }
            }
        }
        return spots;
    }

    /** The first candidate the predicate accepts. */
    static Optional<Spot> land(double x, double y, double z, float yaw, double behind, Predicate<Spot> fits) {
        Objects.requireNonNull(fits, "fits");
        for (Spot spot : candidates(x, y, z, yaw, behind)) {
            if (fits.test(spot)) {
                return Optional.of(spot);
            }
        }
        return Optional.empty();
    }

    /** Whether a mob of the width and height standing at the spot lies wholly inside the arena. */
    static boolean inside(ArenaBounds bounds, Spot spot, double width, double height) {
        double half = width / 2;
        int minX = (int) Math.floor(spot.x() - half);
        int maxX = (int) Math.floor(spot.x() + half);
        int minZ = (int) Math.floor(spot.z() - half);
        int maxZ = (int) Math.floor(spot.z() + half);
        int minY = (int) Math.floor(spot.y());
        int maxY = (int) Math.floor(spot.y() + height);
        return bounds.contains(minX, minY, minZ) && bounds.contains(maxX, maxY, maxZ);
    }

    /** The yaw in the range -180 (exclusive) to 180 (inclusive). */
    static float wrap(float yaw) {
        float wrapped = yaw % 360;
        if (wrapped > 180) {
            wrapped -= 360;
        } else if (wrapped <= -180) {
            wrapped += 360;
        }
        return wrapped;
    }
}
