package com.ninja6.ninjaarena.world;

import java.util.List;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * Generates empty chunks: no terrain, bedrock, caves, decorations, structures or mobs.
 * Bedrock comes from the noise stage, so switching noise off removes it too.
 * Arenas are built into the world by hand, so everything outside them stays air.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    /** Block height the world spawn is pinned to. Nothing stands there; it only needs a fixed point. */
    static final int SPAWN_Y = 64;

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of();
    }

    @Override
    public boolean canSpawn(World world, int x, int z) {
        return true;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, SPAWN_Y, 0.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new VoidBiomeProvider();
    }

    /** One biome everywhere, so the sky, fog and ambience never change between arenas. */
    static final class VoidBiomeProvider extends BiomeProvider {

        @Override
        public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
            return Biome.THE_VOID;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo worldInfo) {
            return List.of(Biome.THE_VOID);
        }
    }
}
