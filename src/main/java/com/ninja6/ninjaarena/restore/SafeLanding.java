package com.ninja6.ninjaarena.restore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Decides where a returning player can stand. Works on a {@link Terrain} of block cells
 * rather than a live world, so it has no server dependency and is tested on its own.
 *
 * <p>A saved spot is unsafe when anything solid, lava or fire overlaps the space the player
 * takes up, or when the first non-open block below is lava, fire or nothing at all (the
 * void). Solid blocks are judged by the top of their collision box, so a player standing on
 * a slab, carpet or snow layers is standing on that block, not buried in it. An unsafe spot
 * is replaced by the nearest spot within {@link #SEARCH_RADIUS} blocks on every axis where
 * the player stands on a solid block with nothing in the way. If there is none,
 * {@link #find} returns empty and the caller falls back to the respawn point.
 */
public final class SafeLanding {

    /** How far the search reaches from the saved block on each axis. */
    public static final int SEARCH_RADIUS = 8;

    /** How tall a standing player is, in blocks. */
    static final double PLAYER_HEIGHT = 1.8;

    /** Tolerance for comparing a position with a collision box face. */
    private static final double EPSILON = 1.0e-6;

    /** What a single block means to a player standing in or on it. */
    public enum Cell {
        /** Nothing to collide with and nothing that burns: air, plants, water. */
        OPEN,
        /** Something the player collides with. */
        SOLID,
        LAVA,
        /** Fire, campfires, magma: anything that sets a player alight or burns them. */
        FIRE
    }

    /** The blocks of one world, as cells. */
    public interface Terrain {

        /** The lowest block Y in the world. Below it is the void. */
        int minY();

        /** One above the highest block Y in the world. Above it is open air. */
        int maxY();

        /** The cell at a block position with {@code minY() <= y < maxY()}. */
        Cell at(int x, int y, int z);

        /**
         * The top of the collision box of a {@link Cell#SOLID} block, measured from the
         * bottom of the block: 1 for a full block, 0.5 for a bottom slab, 1.5 for a fence.
         */
        default double height(int x, int y, int z) {
            return 1;
        }
    }

    /** A position to put the player at; {@code moved} is false for the saved spot itself. */
    public record Spot(double x, double y, double z, boolean moved) {
    }

    /** Offsets in the search cube, nearest first. */
    private static final List<int[]> OFFSETS = searchOrder();

    private SafeLanding() {
    }

    /**
     * The saved position if it is safe, otherwise the nearest safe standing spot within
     * {@link #SEARCH_RADIUS}, centred on its block and on top of its ground.
     */
    public static Optional<Spot> find(Terrain terrain, double x, double y, double z) {
        if (isSafe(terrain, x, y, z)) {
            return Optional.of(new Spot(x, y, z, false));
        }
        int bx = floor(x);
        int by = floor(y);
        int bz = floor(z);
        for (int[] offset : OFFSETS) {
            Optional<Spot> spot = standingSpot(terrain, bx + offset[0], by + offset[1], bz + offset[2]);
            if (spot.isPresent()) {
                return spot;
            }
        }
        return Optional.empty();
    }

    /** Whether a player can be put exactly at this position. */
    public static boolean isSafe(Terrain terrain, double x, double y, double z) {
        int bx = floor(x);
        int by = floor(y);
        int bz = floor(z);
        double offset = y - by;
        Cell feet = cell(terrain, bx, by, bz);
        if (feet == Cell.SOLID) {
            // Standing on a partial block in the feet block, or buried in it.
            return terrain.height(bx, by, bz) <= offset + EPSILON && clearAbove(terrain, bx, by + 1, bz, y);
        }
        if (feet != Cell.OPEN || !clearAbove(terrain, bx, by + 1, bz, y)) {
            return false;
        }
        for (int below = by - 1; below >= terrain.minY(); below--) {
            Cell cell = terrain.at(bx, below, bz);
            if (cell == Cell.OPEN) {
                continue;
            }
            // A block taller than one, such as a fence, must not reach into the player.
            return cell == Cell.SOLID && below + terrain.height(bx, below, bz) <= y + EPSILON;
        }
        // Nothing but open blocks down to the bottom of the world.
        return false;
    }

    /**
     * The spot on top of the block under {@code (x, y, z)}, if that block is solid and the
     * player fits on it.
     */
    static Optional<Spot> standingSpot(Terrain terrain, int x, int y, int z) {
        int ground = y - 1;
        if (cell(terrain, x, ground, z) != Cell.SOLID) {
            return Optional.empty();
        }
        double top = ground + terrain.height(x, ground, z);
        if (!isSafe(terrain, x + 0.5, top, z + 0.5)) {
            return Optional.empty();
        }
        return Optional.of(new Spot(x + 0.5, top, z + 0.5, true));
    }

    /** Every block from {@code fromY} up to the player's head at {@code feetY} is open. */
    private static boolean clearAbove(Terrain terrain, int x, int fromY, int z, double feetY) {
        int headY = floor(feetY + PLAYER_HEIGHT - EPSILON);
        for (int y = fromY; y <= headY; y++) {
            if (cell(terrain, x, y, z) != Cell.OPEN) {
                return false;
            }
        }
        return true;
    }

    /** Below the world is the void ({@code null}); above it is open air. */
    private static Cell cell(Terrain terrain, int x, int y, int z) {
        if (y < terrain.minY()) {
            return null;
        }
        if (y >= terrain.maxY()) {
            return Cell.OPEN;
        }
        return terrain.at(x, y, z);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static List<int[]> searchOrder() {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    offsets.add(new int[] {dx, dy, dz});
                }
            }
        }
        // Nearest first; on a tie prefer the same height, then higher ground, so the player
        // is not dropped into a cave when a spot on the surface is just as close.
        offsets.sort(Comparator.<int[]>comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
                .thenComparingInt(o -> Math.abs(o[1]))
                .thenComparingInt(o -> -o[1]));
        return List.copyOf(offsets);
    }
}
