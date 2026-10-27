package com.ninja6.ninjaarena.restore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Decides where a returning player can stand. Works on a {@link Terrain} of block cells
 * rather than a live world, so it has no server dependency and is tested on its own.
 *
 * <p>A saved spot is unsafe when the feet or head block is not open, or when the first
 * non-open block below is lava, fire or nothing at all (the void). An unsafe spot is
 * replaced by the nearest spot within {@link #SEARCH_RADIUS} blocks on every axis where
 * the player stands on a solid block with open feet and head. If there is none,
 * {@link #find} returns empty and the caller falls back to the respawn point.
 */
public final class SafeLanding {

    /** How far the search reaches from the saved block on each axis. */
    public static final int SEARCH_RADIUS = 8;

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
     * {@link #SEARCH_RADIUS}, centred on its block.
     */
    public static Optional<Spot> find(Terrain terrain, double x, double y, double z) {
        if (isSafe(terrain, x, y, z)) {
            return Optional.of(new Spot(x, y, z, false));
        }
        int bx = floor(x);
        int by = feetBlockY(y);
        int bz = floor(z);
        for (int[] offset : OFFSETS) {
            int cx = bx + offset[0];
            int cy = by + offset[1];
            int cz = bz + offset[2];
            if (isStandable(terrain, cx, cy, cz)) {
                return Optional.of(new Spot(cx + 0.5, cy, cz + 0.5, true));
            }
        }
        return Optional.empty();
    }

    /** Whether a player can be put exactly at this position. */
    public static boolean isSafe(Terrain terrain, double x, double y, double z) {
        int bx = floor(x);
        int by = feetBlockY(y);
        int bz = floor(z);
        if (cell(terrain, bx, by, bz) != Cell.OPEN || cell(terrain, bx, by + 1, bz) != Cell.OPEN) {
            return false;
        }
        for (int below = by - 1; below >= terrain.minY(); below--) {
            Cell cell = terrain.at(bx, below, bz);
            if (cell != Cell.OPEN) {
                return cell == Cell.SOLID;
            }
        }
        // Nothing but open blocks down to the bottom of the world.
        return false;
    }

    /** Feet and head open, solid ground directly underneath. */
    static boolean isStandable(Terrain terrain, int x, int y, int z) {
        return cell(terrain, x, y - 1, z) == Cell.SOLID
                && cell(terrain, x, y, z) == Cell.OPEN
                && cell(terrain, x, y + 1, z) == Cell.OPEN;
    }

    /**
     * The block holding the player's feet. Rounding rather than flooring puts a player
     * standing on a slab or farmland in the block above it, not inside the block they stand
     * on, so a normal spot on a partial block is not mistaken for a buried one.
     */
    static int feetBlockY(double y) {
        return floor(y + 0.5);
    }

    /** Below the world is the void; above it is open air. */
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
