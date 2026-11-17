package com.ninja6.ninjaarena.hazard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.junit.jupiter.api.Test;

class WorldHazardBlocksTest {

    private final Map<String, Material> types = new HashMap<>();
    /** Every block set, as {@code x,y,z=TYPE/physics}. */
    private final List<String> sets = new ArrayList<>();
    private final List<Map<String, Object>> falling = new ArrayList<>();
    private final Map<UUID, FallingBlock> entities = new HashMap<>();
    private final BlockData data = inert(BlockData.class);
    private boolean open = true;
    private final World world = world();
    private final WorldHazardBlocks hazards = new WorldHazardBlocks(() -> open ? Optional.of(world) : Optional.empty());

    @SuppressWarnings("unchecked")
    private static <T> T inert(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            throw new UnsupportedOperationException(m.getName());
        });
    }

    private World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "arena";
                    case "getBlockAt" -> block((World) proxy, (int) args[0], (int) args[1], (int) args[2]);
                    case "spawn" -> spawn(args);
                    case "getEntity" -> entities.get((UUID) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private Object spawn(Object[] args) {
        assertEquals(FallingBlock.class, args[1]);
        Map<String, Object> state = new HashMap<>();
        state.put("at", args[0]);
        state.put("valid", true);
        UUID id = UUID.randomUUID();
        state.put("id", id);
        FallingBlock entity = (FallingBlock) Proxy.newProxyInstance(FallingBlock.class.getClassLoader(),
                new Class<?>[] {FallingBlock.class}, (proxy, method, call) -> switch (method.getName()) {
                    case "setBlockData", "setCancelDrop", "setDropItem", "setHurtEntities", "setPersistent" -> {
                        state.put(method.getName(), call[0]);
                        yield null;
                    }
                    case "isValid" -> state.get("valid");
                    case "getUniqueId" -> id;
                    case "remove" -> {
                        state.put("valid", false);
                        state.put("removed", true);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ((Consumer<FallingBlock>) args[2]).accept(entity);
        falling.add(state);
        entities.put(id, entity);
        return entity;
    }

    private Block block(World world, int x, int y, int z) {
        String key = x + "," + y + "," + z;
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> types.getOrDefault(key, Material.AIR);
                    case "setType" -> {
                        types.put(key, (Material) args[0]);
                        sets.add(key + "=" + args[0] + "/" + args[1]);
                        yield null;
                    }
                    case "getBlockData" -> data;
                    case "getWorld" -> world;
                    case "getLocation" -> new Location(world, x, y, z);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void lavaStartsFromAirWithPhysicsSoItFlows() {
        types.put("1,2,3", Material.LAVA);
        hazards.startLava(1, 2, 3);
        assertEquals(List.of("1,2,3=AIR/false", "1,2,3=LAVA/true"), sets);
        sets.clear();
        types.put("1,2,3", Material.STONE);
        hazards.startLava(1, 2, 3);
        assertEquals(List.of(), sets, "never over a solid block");
    }

    @Test
    void stoppingLetsTheLavaDrainAndRemovingDoesNot() {
        types.put("1,2,3", Material.LAVA);
        hazards.stopLava(1, 2, 3);
        types.put("4,5,6", Material.LAVA);
        hazards.removeLava(4, 5, 6);
        hazards.stopLava(7, 8, 9);
        hazards.removeLava(7, 8, 9);
        assertEquals(List.of("1,2,3=AIR/true", "4,5,6=AIR/false"), sets, "only lava is taken away");
    }

    @Test
    void restoringFillsOnlyAir() {
        hazards.restore(1, 2, 3, Material.LAVA);
        types.put("4,5,6", Material.STONE);
        hazards.restore(4, 5, 6, Material.LAVA);
        assertEquals(List.of("1,2,3=LAVA/false"), sets);
    }

    @Test
    void aCollapseDropsAFallingBlockThatNeverLandsAsABlock() {
        assertEquals(Optional.empty(), hazards.collapse(1, 2, 3, Material.CALCITE), "nothing to collapse");
        types.put("1,2,3", Material.CALCITE);
        assertEquals(Optional.empty(), hazards.collapse(1, 2, 3, Material.STONE), "not the block expected");
        Optional<UUID> id = hazards.collapse(1, 2, 3, Material.CALCITE);
        assertEquals(List.of("1,2,3=AIR/false"), sets);
        assertEquals(1, falling.size());
        Map<String, Object> debris = falling.getFirst();
        assertEquals(Optional.of(debris.get("id")), id, "only the id is handed out");
        assertSame(data, debris.get("setBlockData"));
        assertEquals(true, debris.get("setCancelDrop"), "it never lands as a block");
        assertEquals(false, debris.get("setDropItem"));
        assertEquals(false, debris.get("setHurtEntities"));
        assertEquals(false, debris.get("setPersistent"));
        Location at = (Location) debris.get("at");
        assertEquals(1.5, at.getX());
        assertEquals(2, at.getY());
        assertEquals(3.5, at.getZ());
        hazards.removeDebris(List.of(id.orElseThrow()));
        assertEquals(false, debris.get("valid"));
    }

    @Test
    void removingDebrisRemovesOnlyTheGivenBlocksStillFalling() {
        for (int x = 1; x <= 3; x++) {
            types.put(x + ",2,3", Material.CALCITE);
        }
        UUID landed = hazards.collapse(1, 2, 3, Material.CALCITE).orElseThrow();
        UUID falling2 = hazards.collapse(2, 2, 3, Material.CALCITE).orElseThrow();
        UUID other = hazards.collapse(3, 2, 3, Material.CALCITE).orElseThrow();
        falling.get(0).put("valid", false);
        hazards.removeDebris(List.of(landed, falling2, UUID.randomUUID()));
        assertEquals(null, falling.get(0).get("removed"), "a landed block is not removed again");
        assertEquals(true, falling.get(1).get("removed"));
        assertEquals(true, falling.get(2).get("valid"), "another map's block keeps falling");
        assertEquals(other, falling.get(2).get("id"));
    }

    @Test
    void nothingIsKeptBetweenCallsThatCouldHoldTheWorld() {
        for (var field : WorldHazardBlocks.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertEquals("world", field.getName(), "only the supplier of the open world is kept");
        }
        types.put("1,2,3", Material.CALCITE);
        UUID id = hazards.collapse(1, 2, 3, Material.CALCITE).orElseThrow();
        open = false;
        hazards.removeDebris(List.of(id));
        assertEquals(true, falling.getFirst().get("valid"), "a retired world's blocks went with it");
    }

    @Test
    void withTheArenaWorldClosedNothingChanges() {
        open = false;
        hazards.startLava(1, 2, 3);
        hazards.stopLava(1, 2, 3);
        hazards.restore(1, 2, 3, Material.LAVA);
        assertEquals(Optional.empty(), hazards.collapse(1, 2, 3, Material.AIR));
        assertEquals(Material.AIR, hazards.get(1, 2, 3));
        assertEquals(List.of(), sets);
    }
}
