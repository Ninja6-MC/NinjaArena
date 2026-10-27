package com.ninja6.ninjaarena.arena;

import java.util.ArrayList;
import java.util.List;

/**
 * An axis-aligned box of whole blocks, both corners inclusive. The constructor orders the
 * corners, so any two opposite corners describe the same box.
 */
public record ArenaBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public ArenaBounds {
        int x1 = Math.min(minX, maxX);
        int x2 = Math.max(minX, maxX);
        int y1 = Math.min(minY, maxY);
        int y2 = Math.max(minY, maxY);
        int z1 = Math.min(minZ, maxZ);
        int z2 = Math.max(minZ, maxZ);
        minX = x1;
        maxX = x2;
        minY = y1;
        maxY = y2;
        minZ = z1;
        maxZ = z2;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Whether the block the point stands in is inside the box. */
    public boolean contains(ArenaPoint point) {
        return contains(point.blockX(), point.blockY(), point.blockZ());
    }

    /** Whether the other box lies wholly inside this one. */
    public boolean contains(ArenaBounds other) {
        return contains(other.minX, other.minY, other.minZ) && contains(other.maxX, other.maxY, other.maxZ);
    }

    public int minChunkX() {
        return Math.floorDiv(minX, 16);
    }

    public int maxChunkX() {
        return Math.floorDiv(maxX, 16);
    }

    public int minChunkZ() {
        return Math.floorDiv(minZ, 16);
    }

    public int maxChunkZ() {
        return Math.floorDiv(maxZ, 16);
    }

    /**
     * The box split along chunk borders: one part per chunk column it touches, each the full
     * height of the box. Together the parts cover the box exactly once.
     */
    public List<ArenaBounds> chunkColumns() {
        List<ArenaBounds> columns = new ArrayList<>();
        for (int chunkX = minChunkX(); chunkX <= maxChunkX(); chunkX++) {
            for (int chunkZ = minChunkZ(); chunkZ <= maxChunkZ(); chunkZ++) {
                columns.add(new ArenaBounds(
                        Math.max(minX, chunkX * 16), minY, Math.max(minZ, chunkZ * 16),
                        Math.min(maxX, chunkX * 16 + 15), maxY, Math.min(maxZ, chunkZ * 16 + 15)));
            }
        }
        return columns;
    }

    public String describe() {
        return minX + ", " + minY + ", " + minZ + " to " + maxX + ", " + maxY + ", " + maxZ;
    }
}
