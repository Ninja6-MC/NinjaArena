package com.ninja6.ninjaarena.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.junit.jupiter.api.Test;

class VoidChunkGeneratorTest {

    private final VoidChunkGenerator generator = new VoidChunkGenerator();

    /** A chunk that records every write made to it. */
    private static ChunkData recordingChunk(List<String> writes) {
        return (ChunkData) Proxy.newProxyInstance(
                ChunkData.class.getClassLoader(),
                new Class<?>[] {ChunkData.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("set")) {
                        writes.add(method.getName());
                    }
                    return switch (method.getName()) {
                        case "getMinHeight" -> -64;
                        case "getMaxHeight" -> 320;
                        default -> null;
                    };
                });
    }

    @Test
    void everyGenerationStageLeavesTheChunkEmpty() {
        List<String> writes = new ArrayList<>();
        ChunkData chunk = recordingChunk(writes);
        Random random = new Random(0);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                generator.generateNoise(null, random, x, z, chunk);
                generator.generateSurface(null, random, x, z, chunk);
                generator.generateBedrock(null, random, x, z, chunk);
                generator.generateCaves(null, random, x, z, chunk);
            }
        }
        assertEquals(List.of(), writes);
    }

    @Test
    void vanillaGenerationIsSwitchedOff() {
        Random random = new Random(0);
        assertFalse(generator.shouldGenerateNoise());
        assertFalse(generator.shouldGenerateNoise(null, random, 0, 0));
        assertFalse(generator.shouldGenerateSurface());
        assertFalse(generator.shouldGenerateSurface(null, random, 0, 0));
        assertFalse(generator.shouldGenerateCaves());
        assertFalse(generator.shouldGenerateCaves(null, random, 0, 0));
        assertFalse(generator.shouldGenerateDecorations());
        assertFalse(generator.shouldGenerateDecorations(null, random, 0, 0));
        assertFalse(generator.shouldGenerateMobs());
        assertFalse(generator.shouldGenerateMobs(null, random, 0, 0));
        assertFalse(generator.shouldGenerateStructures());
        assertFalse(generator.shouldGenerateStructures(null, random, 0, 0));
    }

    @Test
    void noPopulatorsRun() {
        assertTrue(generator.getDefaultPopulators(null).isEmpty());
    }

    @Test
    void spawnIsPinnedAboveTheOrigin() {
        assertTrue(generator.canSpawn(null, 0, 0));
        Location spawn = generator.getFixedSpawnLocation(null, new Random(0));
        assertNull(spawn.getWorld());
        assertEquals(0.5, spawn.getX());
        assertEquals(VoidChunkGenerator.SPAWN_Y, spawn.getY());
        assertEquals(0.5, spawn.getZ());
    }
}
