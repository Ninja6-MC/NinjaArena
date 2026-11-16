package com.ninja6.ninjaarena.lobby;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One room pad of the generated lobby, in lobby-world coordinates. Pads are numbered
 * clockwise from the north-west: the north side west to east, then east, south and west.
 *
 * @param index the pad's number, from 0
 * @param side  the side of the lobby it is on
 * @param frame the pad with its gold frame, one block high at floor level
 * @param plate the diamond plate inside the frame, where a player stands on the pad
 * @param label where the pad's label stands: above the middle of the plate, facing the
 *              lobby's centre
 */
public record LobbyPad(int index, Side side, ArenaBounds frame, ArenaBounds plate, ArenaPoint label) {

    /** A side of the lobby, clockwise from north. */
    public enum Side { NORTH, EAST, SOUTH, WEST }

    public LobbyPad {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(plate, "plate");
        Objects.requireNonNull(label, "label");
        if (!frame.contains(plate)) {
            throw new IllegalArgumentException("the plate must be inside the frame");
        }
    }

    /**
     * The frame's outer ring, one block wide, as {x, y, z} floor blocks: clockwise from the
     * frame's north-west corner. This is the gold border round the plate, where the fog is
     * drawn; no block of the plate is in it while the frame is wider than the plate.
     */
    public List<int[]> ring() {
        return ring(frame);
    }

    /** The outer ring of a box's floor, one block wide, clockwise from its north-west corner. */
    static List<int[]> ring(ArenaBounds box) {
        List<int[]> ring = new ArrayList<>();
        int y = box.minY();
        if (box.minX() == box.maxX() || box.minZ() == box.maxZ()) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    ring.add(new int[] {x, y, z});
                }
            }
            return ring;
        }
        for (int x = box.minX(); x < box.maxX(); x++) {
            ring.add(new int[] {x, y, box.minZ()});
        }
        for (int z = box.minZ(); z < box.maxZ(); z++) {
            ring.add(new int[] {box.maxX(), y, z});
        }
        for (int x = box.maxX(); x > box.minX(); x--) {
            ring.add(new int[] {x, y, box.maxZ()});
        }
        for (int z = box.maxZ(); z > box.minZ(); z--) {
            ring.add(new int[] {box.minX(), y, z});
        }
        return ring;
    }
}
