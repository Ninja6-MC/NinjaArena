package com.ninja6.ninjaarena.map;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ChunkTickets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;

/**
 * Blocks in memory for the cell writer, stored by 16-block section so an empty world costs
 * nothing, plus the chunk tickets on it. Air unless set.
 */
public final class FakeBlocks implements BlockAccess {

    private final Map<Long, Material[]> sections = new HashMap<>();
    /** Chunk tickets held now, as {@code chunkX, chunkZ} keys. */
    public final Set<Long> tickets = new HashSet<>();
    public long writes;

    private static long section(int x, int y, int z) {
        return ((long) (x >> 4) & 0x3FFFFF) << 42 | ((long) (z >> 4) & 0x3FFFFF) << 20 | (long) (y >> 4) & 0xFFFFF;
    }

    private static int index(int x, int y, int z) {
        return (x & 15) << 8 | (z & 15) << 4 | y & 15;
    }

    @Override
    public Material get(int x, int y, int z) {
        Material[] blocks = sections.get(section(x, y, z));
        Material material = blocks == null ? null : blocks[index(x, y, z)];
        return material == null ? Material.AIR : material;
    }

    @Override
    public void set(int x, int y, int z, Material material) {
        writes++;
        Material[] blocks = sections.computeIfAbsent(section(x, y, z), key -> new Material[4096]);
        blocks[index(x, y, z)] = material == Material.AIR ? null : material;
    }

    /** Blocks that are not air inside the box, both corners inclusive. */
    public long countSolid(ArenaBounds box) {
        long count = 0;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    if (get(x, y, z) != Material.AIR) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Every block that is not air anywhere. */
    public long countSolid() {
        long count = 0;
        for (Material[] blocks : sections.values()) {
            for (Material material : blocks) {
                if (material != null) {
                    count++;
                }
            }
        }
        return count;
    }

    public ChunkTickets ticketsView() {
        return new ChunkTickets() {
            @Override
            public void add(int chunkX, int chunkZ) {
                tickets.add(key(chunkX, chunkZ));
            }

            @Override
            public void remove(int chunkX, int chunkZ) {
                tickets.remove(key(chunkX, chunkZ));
            }
        };
    }

    public static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }
}
