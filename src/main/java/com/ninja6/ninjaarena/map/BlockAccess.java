package com.ninja6.ninjaarena.map;

import org.bukkit.Material;

/**
 * Block reads and writes in the arena world for {@link TickCellWriter}. On the server this
 * is the arena world, writing without physics; tests keep the blocks in memory.
 * Server thread only.
 */
public interface BlockAccess {

    Material get(int x, int y, int z);

    /** Sets the block without physics, so placed lava and loose blocks stay put. */
    void set(int x, int y, int z, Material material);
}
