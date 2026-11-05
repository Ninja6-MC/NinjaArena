package com.ninja6.ninjaarena.portal;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/** A world of blocks for frame tests: air unless set, with chunks loaded unless marked. */
final class BlockWorld {

    final String name;
    final Map<String, Material> blocks = new HashMap<>();
    final Set<String> unloaded = new HashSet<>();
    /** Particle positions, as x, y, z. */
    final List<double[]> particles = new ArrayList<>();
    final List<String> sounds = new ArrayList<>();
    final World world;

    BlockWorld(String name) {
        this.name = name;
        world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName", "toString" -> name;
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "isChunkLoaded" -> !unloaded.contains(args[0] + "," + args[1]);
                    case "getType" -> type((int) args[0], (int) args[1], (int) args[2]);
                    case "spawnParticle" -> {
                        particles.add(new double[] {(double) args[1], (double) args[2], (double) args[3]});
                        yield null;
                    }
                    case "playSound" -> {
                        sounds.add((String) args[1]);
                        yield null;
                    }
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    Material type(int x, int y, int z) {
        return blocks.getOrDefault(x + "," + y + "," + z, Material.AIR);
    }

    void set(int x, int y, int z, Material type) {
        blocks.put(x + "," + y + "," + z, type);
    }

    void unload(int chunkX, int chunkZ) {
        unloaded.add(chunkX + "," + chunkZ);
    }

    /**
     * Builds a frame without corners around an opening whose lower corner is at {@code a, y}
     * along the plane, with the plane at {@code fixed} on the other axis.
     */
    BlockWorld frame(boolean alongX, int a, int y, int fixed, int width, int height, Material frame) {
        for (int i = -1; i <= width; i++) {
            for (int j = -1; j <= height; j++) {
                boolean edgeA = i == -1 || i == width;
                boolean edgeY = j == -1 || j == height;
                if (edgeA == edgeY) {
                    continue;
                }
                if (alongX) {
                    set(a + i, y + j, fixed, frame);
                } else {
                    set(fixed, y + j, a + i, frame);
                }
            }
        }
        return this;
    }

    Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    Block block(int x, int y, int z) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "getType" -> type(x, y, z);
                    case "getLocation" -> new Location(world, x, y, z);
                    case "getRelative" -> {
                        BlockFace face = (BlockFace) args[0];
                        yield block(x + face.getModX(), y + face.getModY(), z + face.getModZ());
                    }
                    case "hashCode" -> (x * 31 + y) * 31 + z;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "block " + x + "," + y + "," + z;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
