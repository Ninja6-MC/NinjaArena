package com.ninja6.ninjaarena.hazard;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;

/**
 * {@link HazardBlocks} in the arena world, while it is open. With the world closed every
 * change does nothing. It keeps no blocks or entities between calls, so it never holds a
 * retired world. Server thread only.
 */
public final class WorldHazardBlocks implements HazardBlocks {

    private final Supplier<Optional<World>> world;

    /** @param world the arena world while it is open; {@code ArenaWorld#currentWorld} */
    public WorldHazardBlocks(Supplier<Optional<World>> world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    private Optional<Block> block(int x, int y, int z) {
        return world.get().map(current -> current.getBlockAt(x, y, z));
    }

    @Override
    public Material get(int x, int y, int z) {
        return block(x, y, z).map(Block::getType).orElse(Material.AIR);
    }

    @Override
    public void startLava(int x, int y, int z) {
        block(x, y, z).ifPresent(block -> {
            Material type = block.getType();
            if (type != Material.AIR && type != Material.LAVA) {
                return;
            }
            // From air, so the lava is placed anew and schedules its flow.
            block.setType(Material.AIR, false);
            block.setType(Material.LAVA, true);
        });
    }

    @Override
    public void stopLava(int x, int y, int z) {
        block(x, y, z).filter(block -> block.getType() == Material.LAVA)
                .ifPresent(block -> block.setType(Material.AIR, true));
    }

    @Override
    public void removeLava(int x, int y, int z) {
        block(x, y, z).filter(block -> block.getType() == Material.LAVA)
                .ifPresent(block -> block.setType(Material.AIR, false));
    }

    @Override
    public void restore(int x, int y, int z, Material material) {
        block(x, y, z).filter(block -> block.getType() == Material.AIR)
                .ifPresent(block -> block.setType(material, false));
    }

    @Override
    public Optional<UUID> collapse(int x, int y, int z, Material material) {
        Optional<Block> found = block(x, y, z).filter(block -> block.getType() == material);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Block block = found.get();
        BlockData data = block.getBlockData();
        block.setType(Material.AIR, false);
        Location at = block.getLocation().add(0.5, 0, 0.5);
        FallingBlock falling = block.getWorld().spawn(at, FallingBlock.class, spawned -> {
            spawned.setBlockData(data);
            spawned.setCancelDrop(true);
            spawned.setDropItem(false);
            spawned.setHurtEntities(false);
            spawned.setPersistent(false);
        });
        return Optional.of(falling.getUniqueId());
    }

    @Override
    public void removeDebris(Collection<UUID> debris) {
        world.get().ifPresent(current -> {
            for (UUID id : debris) {
                if (current.getEntity(id) instanceof FallingBlock falling && falling.isValid()) {
                    falling.remove();
                }
            }
        });
    }
}
