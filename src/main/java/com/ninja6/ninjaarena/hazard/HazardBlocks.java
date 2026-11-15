package com.ninja6.ninjaarena.hazard;

import org.bukkit.Material;

/**
 * Block changes in the arena world for {@link HazardService}. On the server this is the
 * arena world; tests keep the blocks in memory. Each method does nothing when the block is
 * not what it expects, so a change that runs late or twice is harmless. Server thread only.
 */
public interface HazardBlocks {

    Material get(int x, int y, int z);

    /**
     * Makes the block a lava source with physics, so it flows. Lava already there is set
     * again from air, so it flows even if it was placed still.
     */
    void startLava(int x, int y, int z);

    /** Sets lava to air with physics, so the lava it fed drains. */
    void stopLava(int x, int y, int z);

    /** Sets lava to air without physics, for when nothing may flow any more. */
    void removeLava(int x, int y, int z);

    /** Sets air to the block without physics: lava held still, or a collapsed block put back. */
    void restore(int x, int y, int z, Material material);

    /**
     * Sets the block to air without physics and lets a falling block of it drop from there.
     * The falling block never lands as a block and drops no item.
     *
     * @return whether the block was the one expected and collapsed
     */
    boolean collapse(int x, int y, int z, Material material);

    /** Removes every falling block {@link #collapse} let drop that is still falling. */
    void removeDebris();
}
