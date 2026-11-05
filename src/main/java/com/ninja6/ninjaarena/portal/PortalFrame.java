package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.util.Optional;

/**
 * The frame of a player-built portal: the shapes a nether portal takes, in any frame block.
 * The opening is a flat rectangle of empty blocks, {@value #MIN_WIDTH} to {@value #MAX_SIZE}
 * wide and {@value #MIN_HEIGHT} to {@value #MAX_SIZE} tall, standing along the X or the Z
 * axis. A frame block sits below and above each column of it and beside each row; the four
 * corners are optional, as in vanilla.
 *
 * <p>Works on a {@link Blocks} view, so it reads only the blocks it needs and never touches
 * the world directly. Finding a frame reads at most a few hundred blocks; checking a known
 * one reads its frame only.
 */
public final class PortalFrame {

    public static final int MIN_WIDTH = 2;
    public static final int MIN_HEIGHT = 3;
    public static final int MAX_SIZE = 21;

    /** What a block is, as far as a frame is concerned. */
    public enum Cell {
        FRAME,
        /** Air, or fire someone lit in the opening. */
        EMPTY,
        OTHER,
        /** In a chunk that is not loaded. */
        UNKNOWN
    }

    @FunctionalInterface
    public interface Blocks {
        Cell at(int x, int y, int z);
    }

    private PortalFrame() {
    }

    /**
     * The opening of the frame around an empty block, if the block is in one. A frame along X
     * is tried first, as vanilla does. Any unloaded block on the way fails the search.
     */
    public static Optional<ArenaBounds> find(Blocks blocks, int x, int y, int z) {
        Optional<ArenaBounds> alongX = find(new Plane(blocks, true, z), x, y);
        return alongX.isPresent() ? alongX : find(new Plane(blocks, false, x), z, y);
    }

    /**
     * Whether every frame block around the opening is still there. A block in an unloaded
     * chunk counts as there, so a portal is never removed for what cannot be seen.
     */
    public static boolean intact(Blocks blocks, ArenaBounds opening) {
        Plane plane = Plane.of(blocks, opening);
        int min = plane.min(opening);
        int max = plane.max(opening);
        for (int a = min; a <= max; a++) {
            if (gone(plane.at(a, opening.minY() - 1)) || gone(plane.at(a, opening.maxY() + 1))) {
                return false;
            }
        }
        for (int y = opening.minY(); y <= opening.maxY(); y++) {
            if (gone(plane.at(min - 1, y)) || gone(plane.at(max + 1, y))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the block is one of the opening's frame blocks. Corners are not. */
    public static boolean isFrame(ArenaBounds opening, int x, int y, int z) {
        boolean alongX = alongX(opening);
        if ((alongX ? z : x) != (alongX ? opening.minZ() : opening.minX())) {
            return false;
        }
        int a = alongX ? x : z;
        int min = alongX ? opening.minX() : opening.minZ();
        int max = alongX ? opening.maxX() : opening.maxZ();
        boolean column = a >= min && a <= max && (y == opening.minY() - 1 || y == opening.maxY() + 1);
        boolean row = y >= opening.minY() && y <= opening.maxY() && (a == min - 1 || a == max + 1);
        return column || row;
    }

    /** Whether the opening's width runs along X; otherwise it runs along Z. */
    static boolean alongX(ArenaBounds opening) {
        return opening.minZ() == opening.maxZ() && opening.maxX() > opening.minX();
    }

    private static boolean gone(Cell cell) {
        return cell == Cell.EMPTY || cell == Cell.OTHER;
    }

    private static Optional<ArenaBounds> find(Plane plane, int start, int startY) {
        if (plane.at(start, startY) != Cell.EMPTY) {
            return Optional.empty();
        }
        int bottom = startY;
        while (plane.at(start, bottom - 1) == Cell.EMPTY) {
            bottom--;
            if (startY - bottom >= MAX_SIZE) {
                return Optional.empty();
            }
        }
        if (plane.at(start, bottom - 1) != Cell.FRAME) {
            return Optional.empty();
        }
        int min = start;
        while (plane.at(min - 1, bottom) == Cell.EMPTY) {
            min--;
            if (start - min >= MAX_SIZE) {
                return Optional.empty();
            }
        }
        if (plane.at(min - 1, bottom) != Cell.FRAME) {
            return Optional.empty();
        }
        int max = start;
        while (plane.at(max + 1, bottom) == Cell.EMPTY) {
            max++;
            if (max - min + 1 > MAX_SIZE) {
                return Optional.empty();
            }
        }
        if (plane.at(max + 1, bottom) != Cell.FRAME || max - min + 1 < MIN_WIDTH) {
            return Optional.empty();
        }
        for (int a = min; a <= max; a++) {
            if (plane.at(a, bottom - 1) != Cell.FRAME) {
                return Optional.empty();
            }
        }
        for (int y = bottom; y <= bottom + MAX_SIZE; y++) {
            if (fullRow(plane, min, max, y)) {
                if (y - bottom < MIN_HEIGHT) {
                    return Optional.empty();
                }
                return Optional.of(plane.opening(min, max, bottom, y - 1));
            }
            if (plane.at(min - 1, y) != Cell.FRAME || plane.at(max + 1, y) != Cell.FRAME) {
                return Optional.empty();
            }
            for (int a = min; a <= max; a++) {
                if (plane.at(a, y) != Cell.EMPTY) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static boolean fullRow(Plane plane, int min, int max, int y) {
        for (int a = min; a <= max; a++) {
            if (plane.at(a, y) != Cell.FRAME) {
                return false;
            }
        }
        return true;
    }

    /**
     * The vertical plane a frame stands in: along X at a fixed Z, or along Z at a fixed X.
     * {@code a} is the position along the plane.
     */
    private record Plane(Blocks blocks, boolean alongX, int fixed) {

        static Plane of(Blocks blocks, ArenaBounds opening) {
            boolean alongX = PortalFrame.alongX(opening);
            return new Plane(blocks, alongX, alongX ? opening.minZ() : opening.minX());
        }

        Cell at(int a, int y) {
            return alongX ? blocks.at(a, y, fixed) : blocks.at(fixed, y, a);
        }

        int min(ArenaBounds opening) {
            return alongX ? opening.minX() : opening.minZ();
        }

        int max(ArenaBounds opening) {
            return alongX ? opening.maxX() : opening.maxZ();
        }

        ArenaBounds opening(int min, int max, int bottom, int top) {
            return alongX ? new ArenaBounds(min, bottom, fixed, max, top, fixed)
                    : new ArenaBounds(fixed, bottom, min, fixed, top, max);
        }
    }
}
