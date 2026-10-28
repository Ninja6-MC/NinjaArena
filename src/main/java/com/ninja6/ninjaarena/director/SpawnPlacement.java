package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Selects registered enemy spawns for one arena. A point qualifies when it is in loaded
 * chunks, free of collisions, more than 8 blocks from every player in the world, and hidden
 * from each of them: out of their line of sight, or more than
 * {@link SpawnSettings#viewAngle()} degrees off the way they look. Call on the server thread.
 */
public final class SpawnPlacement {

    /** Why a registered point was passed over. */
    public enum Refusal {
        TOO_CLOSE("too close"),
        IN_VIEW("in view"),
        CHUNK_NOT_LOADED("chunk not loaded"),
        BLOCKED("blocked space");

        private final String text;

        Refusal(String text) {
            this.text = text;
        }

        /** How the reason reads in the log and the director status. */
        public String text() {
            return text;
        }
    }

    /**
     * A point passed over by the last call to {@link #next}.
     *
     * @param point  the point's number in registration order, from 1
     * @param reason the first check it failed
     */
    public record Refused(int point, Refusal reason) {
    }

    private static final double MIN_DISTANCE_SQUARED = 8 * 8;

    private final List<ArenaPoint> points;
    private final boolean viewConeOff;
    /** Cosine of the view angle; a direction at or above it is inside a player's view. */
    private final double viewCosine;
    private List<Refused> refusals = List.of();
    private int cursor;

    public SpawnPlacement(Arena arena) {
        this(arena, SpawnSettings.DEFAULTS);
    }

    public SpawnPlacement(Arena arena, SpawnSettings settings) {
        points = Objects.requireNonNull(arena, "arena").enemySpawns();
        Objects.requireNonNull(settings, "settings");
        viewConeOff = settings.viewAngle() >= SpawnSettings.MAX_VIEW_ANGLE;
        viewCosine = Math.cos(Math.toRadians(settings.viewAngle()));
    }

    /**
     * The next hidden, unoccupied point for a mob of the given width and height. Rotates
     * through registered points, rechecking players and blocks on every call. Empty means
     * the caller must skip spawning; no fallback position is invented or chunk loaded, and
     * {@link #refusals()} then says why each point was passed over.
     */
    public Optional<Location> next(World world, double width, double height) {
        Objects.requireNonNull(world, "world");
        if (!Double.isFinite(width) || width <= 0 || !Double.isFinite(height) || height <= 0) {
            throw new IllegalArgumentException("mob dimensions must be finite and positive");
        }
        List<Player> players = world.getPlayers();
        List<Refused> refused = new ArrayList<>();
        for (int checked = 0; checked < points.size(); checked++) {
            int index = cursor;
            ArenaPoint point = points.get(index);
            cursor = (cursor + 1) % points.size();
            Location location = new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
            Refusal reason = fits(world, location, width, height);
            if (reason == null) {
                reason = visibility(location, height, players);
            }
            if (reason == null) {
                refusals = List.of();
                return Optional.of(location);
            }
            refused.add(new Refused(index + 1, reason));
        }
        refused.sort(Comparator.comparingInt(Refused::point));
        refusals = List.copyOf(refused);
        return Optional.empty();
    }

    /**
     * Why each point was passed over in the last call to {@link #next}, by point number; empty
     * when that call found a point or the arena has none.
     */
    public List<Refused> refusals() {
        return refusals;
    }

    /** Null when the point is far enough from, and hidden from, every player. */
    private Refusal visibility(Location location, double height, List<Player> players) {
        Location upper = location.clone().add(0, height * 0.85, 0);
        Refusal reason = null;
        for (Player player : players) {
            if (player.getLocation().distanceSquared(location) <= MIN_DISTANCE_SQUARED) {
                return Refusal.TOO_CLOSE;
            }
            if (reason == null && seen(player, location, upper)) {
                reason = Refusal.IN_VIEW;
            }
        }
        return reason;
    }

    /** Whether the player looks towards the feet or the upper body and has a line of sight to it. */
    private boolean seen(Player player, Location feet, Location upper) {
        Location eye = player.getEyeLocation();
        return (inView(eye, feet) && player.hasLineOfSight(feet))
                || (inView(eye, upper) && player.hasLineOfSight(upper));
    }

    private boolean inView(Location eye, Location target) {
        if (viewConeOff) {
            return true;
        }
        Vector to = target.toVector().subtract(eye.toVector());
        double length = to.length();
        return length == 0 || eye.getDirection().dot(to) / length >= viewCosine;
    }

    /** Null when the mob's box is in loaded chunks, inside the world height and collision free. */
    private static Refusal fits(World world, Location location, double width, double height) {
        double half = width / 2;
        BoundingBox body = new BoundingBox(location.getX() - half, location.getY(), location.getZ() - half,
                location.getX() + half, location.getY() + height, location.getZ() + half);
        if (body.getMinY() < world.getMinHeight() || body.getMaxY() > world.getMaxHeight()) {
            return Refusal.BLOCKED;
        }
        int minX = (int) Math.floor(body.getMinX());
        int maxX = (int) Math.floor(body.getMaxX());
        int minZ = (int) Math.floor(body.getMinZ());
        int maxZ = (int) Math.floor(body.getMaxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return Refusal.CHUNK_NOT_LOADED;
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
                            return Refusal.BLOCKED;
                        }
                    }
                }
            }
        }
        return null;
    }
}
