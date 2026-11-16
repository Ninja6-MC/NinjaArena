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
     * The plate's outer ring, one block wide, as {x, y, z} floor blocks: clockwise from the
     * plate's north-west corner. The middle of the plate is not in it.
     */
    public List<int[]> ring() {
        List<int[]> ring = new ArrayList<>();
        int y = plate.minY();
        for (int x = plate.minX(); x < plate.maxX(); x++) {
            ring.add(new int[] {x, y, plate.minZ()});
        }
        for (int z = plate.minZ(); z < plate.maxZ(); z++) {
            ring.add(new int[] {plate.maxX(), y, z});
        }
        for (int x = plate.maxX(); x > plate.minX(); x--) {
            ring.add(new int[] {x, y, plate.maxZ()});
        }
        for (int z = plate.maxZ(); z > plate.minZ(); z--) {
            ring.add(new int[] {plate.minX(), y, z});
        }
        return ring;
    }
}
