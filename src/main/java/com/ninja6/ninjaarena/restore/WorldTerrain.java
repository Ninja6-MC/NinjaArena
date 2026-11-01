package com.ninja6.ninjaarena.restore;

import com.ninja6.ninjaarena.restore.SafeLanding.Cell;
import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.ArenaExtent;
import java.util.Objects;
import java.util.function.BiPredicate;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/**
 * A live world seen as {@link SafeLanding} cells. A block is solid when it has a collision
 * box, and its height is the top of that box. Reading a block loads its chunk, so this is
 * for the server thread only.
 */
final class WorldTerrain implements SafeLanding.Terrain {

    private final World world;
    private final BiPredicate<Double, Double> arenaLocation;
    private final boolean arena;

    WorldTerrain(World world) {
        this(world, (x, z) -> true);
    }

    WorldTerrain(World world, BiPredicate<Double, Double> arenaLocation) {
        this.world = Objects.requireNonNull(world, "world");
        this.arenaLocation = arenaLocation;
        this.arena = ArenaWorld.NAME.equals(world.getName());
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
        if (outside(x, z)) {
            return Cell.LAVA;
        }
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
        return block.getCollisionShape().getBoundingBoxes().isEmpty() ? Cell.OPEN : Cell.SOLID;
    }

    @Override
    public double height(int x, int y, int z) {
        if (outside(x, z)) {
            return 1;
        }
        double top = 0;
        for (BoundingBox box : world.getBlockAt(x, y, z).getCollisionShape().getBoundingBoxes()) {
            top = Math.max(top, box.getMaxY());
        }
        return top;
    }

    private boolean outside(int x, int z) {
        return arena && (!ArenaExtent.contains(x, z) || !arenaLocation.test((double) x, (double) z));
    }
}
