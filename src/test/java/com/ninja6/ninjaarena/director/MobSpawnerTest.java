package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.support.TestItemStack;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Stray;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Zombie;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.Test;

class MobSpawnerTest {

    private static final Supplier<ItemStack> BOW = () -> new TestItemStack(Material.BOW, "bow", 1);

    /** setShouldBurnInDay calls per mob, and whether the mob was readied before joining the world. */
    private final Map<Mob, List<Boolean>> burnCalls = new HashMap<>();
    private final Map<Mob, List<String>> lifecycleCalls = new HashMap<>();
    private final List<Mob> added = new ArrayList<>();
    private final Map<Mob, List<Material>> mainHands = new HashMap<>();
    /** Whether spawned mobs stay valid; false as when another plugin cancels the spawn. */
    private boolean valid = true;
    private final SpawnDiagnostics diagnostics = new SpawnDiagnostics(
            new FakeMatch(List.of(List.of(), List.of())).combat(0), SpawnSettings.DEFAULTS, Logger.getAnonymousLogger());

    private Mob mob(Class<? extends Mob> type) {
        Mob[] self = new Mob[1];
        self[0] = (Mob) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setShouldBurnInDay" -> {
                        assertFalse(added.contains(self[0]), "readied before it joins the world");
                        burnCalls.computeIfAbsent(self[0], ignored -> new ArrayList<>()).add((Boolean) args[0]);
                        yield null;
                    }
                    case "setPersistent", "setCanPickupItems" -> {
                        assertFalse(added.contains(self[0]), "readied before it joins the world");
                        lifecycleCalls.computeIfAbsent(self[0], ignored -> new ArrayList<>())
                                .add(method.getName() + "=" + args[0]);
                        yield null;
                    }
                    case "getEquipment" -> Proxy.newProxyInstance(EntityEquipment.class.getClassLoader(),
                            new Class<?>[] {EntityEquipment.class}, (equipment, call, items) -> {
                                if (call.getName().equals("setItemInMainHand")) {
                                    mainHands.computeIfAbsent(self[0], ignored -> new ArrayList<>())
                                            .add(((ItemStack) items[0]).getType());
                                    return null;
                                }
                                throw new UnsupportedOperationException(call.getName());
                            });
                    case "isValid" -> valid;
                    case "isDead" -> false;
                    case "setTarget", "getTarget" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return self[0];
    }

    @SuppressWarnings("unchecked")
    private final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
            new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getMinHeight" -> -64;
                case "getMaxHeight" -> 320;
                case "getPlayers" -> List.of();
                case "isChunkLoaded" -> true;
                case "getBlockAt" -> emptyBlock();
                case "spawn" -> {
                    Mob mob = mob((Class<? extends Mob>) args[1]);
                    assertFalse((Boolean) args[2], "no randomized riders or mounts");
                    ((Consumer<Mob>) args[3]).accept(mob);
                    added.add(mob);
                    yield mob;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });

    private static Block emptyBlock() {
        VoxelShape shape = (VoxelShape) Proxy.newProxyInstance(VoxelShape.class.getClassLoader(),
                new Class<?>[] {VoxelShape.class}, (proxy, method, args) -> List.of());
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, args) -> shape);
    }

    @Test
    void spawnedUndeadDoNotBurnUnderTheFixedNoonSky() {
        var arena = new Arena("dojo", new GridCell(0, 0), null, null, List.of(),
                List.of(new ArenaPoint(10.5, 64, 10.5, 0, 0)));
        var spawner = new MobSpawner(world, new SpawnPlacement(arena), id -> null, diagnostics, BOW);
        for (EnemyKind kind : EnemyKind.values()) {
            assertTrue(spawner.spawn(kind, null).isPresent(), kind.name());
        }
        assertEquals(3, added.size());
        for (Mob mob : added) {
            assertEquals(List.of("setPersistent=false", "setCanPickupItems=false"), lifecycleCalls.get(mob));
        }
        assertEquals(List.of(false), burnCalls.get(added.get(0)), "zombie");
        assertEquals(List.of(false), burnCalls.get(added.get(1)), "skeleton");
        assertFalse(burnCalls.containsKey(added.get(2)), "spiders never burn");
        assertEquals(List.of(Material.BOW), mainHands.get(added.get(1)), "skeleton holds a bow");
        assertFalse(mainHands.containsKey(added.get(0)), "zombie is left as spawned");
    }

    @Test
    void refusedAndCancelledSpawnsAreExplained() {
        var empty = new MobSpawner(world, new SpawnPlacement(new Arena("dojo", new GridCell(0, 0), null, null,
                List.of(), List.of())), id -> null, diagnostics, BOW);
        assertTrue(empty.lastRefusal().isEmpty());
        assertTrue(empty.spawn(EnemyKind.ZOMBIE, null).isEmpty());
        assertEquals("round 1, no enemy spawn point for a zombie: the arena has no enemy spawn points",
                empty.lastRefusal().orElseThrow());

        valid = false;
        var arena = new Arena("dojo", new GridCell(0, 0), null, null, List.of(),
                List.of(new ArenaPoint(10.5, 64, 10.5, 0, 0)));
        var spawner = new MobSpawner(world, new SpawnPlacement(arena), id -> null, diagnostics, BOW);
        assertTrue(spawner.spawn(EnemyKind.SPIDER, null).isEmpty());
        assertEquals("round 1, the world cancelled a spider spawn at 10.5, 64.0, 10.5",
                spawner.lastRefusal().orElseThrow());
    }

    @Test
    void everyZombieAndSkeletonVariantIsKeptFromBurning() {
        for (Class<? extends Mob> type : List.of(Zombie.class, Husk.class, Drowned.class, Skeleton.class,
                Stray.class, WitherSkeleton.class)) {
            Mob mob = mob(type);
            MobSpawner.prepare(mob, BOW);
            assertEquals(List.of(false), burnCalls.get(mob), type.getSimpleName());
        }
        Mob spider = mob(Spider.class);
        MobSpawner.prepare(spider, BOW);
        assertFalse(burnCalls.containsKey(spider));
    }
}
