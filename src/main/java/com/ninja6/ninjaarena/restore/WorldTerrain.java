package com.ninja6.ninjaarena.restore;

import com.ninja6.ninjaarena.restore.SafeLanding.Cell;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * A live world seen as {@link SafeLanding} cells. Reading a block loads its chunk, so this
 * is for the server thread only.
 */
final class WorldTerrain implements SafeLanding.Terrain {

    private final World world;

    WorldTerrain(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public int minY() {
        return world.getMinHeight();
    }

    @Override
    public int maxY() {
        return world.getMaxHeight();
    }

    @Override
    public Cell at(int x, int y, int z) {
        return classify(world.getBlockAt(x, y, z));
    }

    static Cell classify(Block block) {
        Material type = block.getType();
        if (type == Material.LAVA) {
            return Cell.LAVA;
        }
        if (Tag.FIRE.isTagged(type) || Tag.CAMPFIRES.isTagged(type) || type == Material.MAGMA_BLOCK) {
            return Cell.FIRE;
        }
        return block.isPassable() ? Cell.OPEN : Cell.SOLID;
    }
}
