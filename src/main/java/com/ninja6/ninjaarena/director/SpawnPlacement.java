package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/** Selects registered enemy spawns for one arena. Call on the server thread. */
public final class SpawnPlacement {

    private static final double MIN_DISTANCE_SQUARED = 8 * 8;

    private final List<ArenaPoint> points;
    private int cursor;

    public SpawnPlacement(Arena arena) {
        points = Objects.requireNonNull(arena, "arena").enemySpawns();
    }

    /**
     * The next hidden, unoccupied point for a mob of the given width and height. Rotates
     * through registered points, rechecking players and blocks on every call. Empty means
     * the caller must skip spawning; no fallback position is invented or chunk loaded.
     */
    public Optional<Location> next(World world, double width, double height) {
        Objects.requireNonNull(world, "world");
        if (!Double.isFinite(width) || width <= 0 || !Double.isFinite(height) || height <= 0) {
            throw new IllegalArgumentException("mob dimensions must be finite and positive");
        }
        List<Player> players = world.getPlayers();
        for (int checked = 0; checked < points.size(); checked++) {
            ArenaPoint point = points.get(cursor);
            cursor = (cursor + 1) % points.size();
            Location location = new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
            if (fits(world, location, width, height) && hidden(location, players)) {
                return Optional.of(location);
            }
        }
        return Optional.empty();
    }

    private static boolean hidden(Location location, List<Player> players) {
        for (Player player : players) {
            if (player.getLocation().distanceSquared(location) <= MIN_DISTANCE_SQUARED
                    || player.hasLineOfSight(location)) {
                return false;
            }
        }
        return true;
    }

    private static boolean fits(World world, Location location, double width, double height) {
        double half = width / 2;
        BoundingBox body = new BoundingBox(location.getX() - half, location.getY(), location.getZ() - half,
                location.getX() + half, location.getY() + height, location.getZ() + half);
        if (body.getMinY() < world.getMinHeight() || body.getMaxY() > world.getMaxHeight()) {
            return false;
        }
        int minX = (int) Math.floor(body.getMinX());
        int maxX = (int) Math.floor(body.getMaxX());
        int minZ = (int) Math.floor(body.getMinZ());
        int maxZ = (int) Math.floor(body.getMaxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return false;
                }
            }
        }
        // Include the block below: fences and walls can extend above their own cell.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = Math.max(world.getMinHeight(), (int) Math.floor(body.getMinY()) - 1);
                        y < world.getMaxHeight() && y <= Math.floor(body.getMaxY()); y++) {
                    for (BoundingBox box : world.getBlockAt(x, y, z).getCollisionShape().getBoundingBoxes()) {
                        if (body.overlaps(box.clone().shift(x, y, z))) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
}
