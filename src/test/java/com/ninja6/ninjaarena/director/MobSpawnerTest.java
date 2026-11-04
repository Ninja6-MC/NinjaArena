package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.entity.ai.MobGoals;
import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.support.TestItemStack;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
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
    private static final Supplier<ItemStack> BLADE = () -> new TestItemStack(Material.NETHERITE_SWORD, "blade", 1);
    private static final ArenaBounds BOUNDS = new ArenaBounds(0, -64, 0, 100, 319, 100);

    /** setShouldBurnInDay calls per mob, and whether the mob was readied before joining the world. */
    private final Map<Mob, List<Boolean>> burnCalls = new HashMap<>();
    private final Map<Mob, List<String>> lifecycleCalls = new HashMap<>();
    private final List<Mob> added = new ArrayList<>();
    private final Map<Mob, List<Material>> mainHands = new HashMap<>();
    /** Attribute base values and health set per mob, before it joins the world. */
    private final Map<Mob, List<String>> body = new HashMap<>();
    /** Mob Goals calls, in order, with whether the mob had joined the world. */
    private final List<String> goalCalls = new ArrayList<>();
    private final MobGoals goals = (MobGoals) Proxy.newProxyInstance(MobGoals.class.getClassLoader(),
            new Class<?>[] {MobGoals.class}, (proxy, method, args) -> {
                assertTrue(added.contains((Mob) args[0]), "goals change once the mob is in the world");
                goalCalls.add(method.getName() + "=" + args[1]);
                return null;
            });
    private StalkerSettings stalkerSettings = StalkerSettings.DEFAULTS;

    private ShadowStalker stalkers() {
        return new ShadowStalker(stalkerSettings, goals, () -> 0, BLADE);
    }

    private static String attribute(Attribute attribute) {
        if (attribute == Attribute.MAX_HEALTH) {
            return "max-health";
        }
        if (attribute == Attribute.ATTACK_DAMAGE) {
            return "damage";
        }
        if (attribute == Attribute.MOVEMENT_SPEED) {
            return "speed";
        }
        if (attribute == Attribute.SCALE) {
            return "scale";
        }
        return attribute == Attribute.FOLLOW_RANGE ? "follow-range" : "other";
    }
    /** Whether spawned mobs stay valid; false as when another plugin cancels the spawn. */
    private boolean valid = true;
    private static final UUID ALIVE = new UUID(0, 1);
    private static final UUID ELIMINATED = new UUID(0, 2);
    private static final UUID OTHER_MATCH = new UUID(0, 3);
    private final FakeMatch match = new FakeMatch(List.of(List.of(ALIVE), List.of(ELIMINATED))).combat(0);
    private final SpawnDiagnostics diagnostics = new SpawnDiagnostics(
            match, SpawnSettings.DEFAULTS, Logger.getAnonymousLogger());

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
                                if (call.getName().equals("setItemInMainHandDropChance")) {
                                    body.computeIfAbsent(self[0], ignored -> new ArrayList<>())
                                            .add("drop-chance=" + items[0]);
                                    return null;
                                }
                                throw new UnsupportedOperationException(call.getName());
                            });
                    case "getAttribute" -> Proxy.newProxyInstance(AttributeInstance.class.getClassLoader(),
                            new Class<?>[] {AttributeInstance.class}, (instance, call, values) -> {
                                assertEquals("setBaseValue", call.getName());
                                assertFalse(added.contains(self[0]), "readied before it joins the world");
                                body.computeIfAbsent(self[0], ignored -> new ArrayList<>())
                                        .add(attribute((Attribute) args[0]) + "=" + values[0]);
                                return null;
                            });
                    case "setHealth" -> {
                        body.computeIfAbsent(self[0], ignored -> new ArrayList<>()).add("health=" + args[0]);
                        yield null;
                    }
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
    void directorLimitsReachTheExistingStatusDiagnostics() {
        var arena = new Arena("dojo", new GridCell(0, 0), null, null, List.of(), List.of());
        var spawner = new MobSpawner(world, new SpawnPlacement(arena), BOUNDS, match, id -> null, diagnostics,
                stalkers(), BOW);
        spawner.blocked(MatchDirector.Blockage.BUDGET, 0.5, 0);
        assertEquals("round 1, the threat budget cannot afford another enemy (target 0.50, in play 0.00)",
                spawner.lastRefusal().orElseThrow());
        spawner.blocked(MatchDirector.Blockage.GATE, 3, 0);
        assertEquals("round 1, the spawn gate is holding back enemies", spawner.lastRefusal().orElseThrow());
        assertTrue(added.isEmpty());
    }

    @Test
    void spawnedUndeadDoNotBurnUnderTheFixedNoonSky() {
        var arena = new Arena("dojo", new GridCell(0, 0), null, null, List.of(),
                List.of(new ArenaPoint(10.5, 64, 10.5, 0, 0)));
        var spawner = new MobSpawner(world, new SpawnPlacement(arena), BOUNDS, match, id -> null, diagnostics,
                stalkers(), BOW);
        for (EnemyKind kind : EnemyKind.values()) {
            assertTrue(spawner.spawn(kind, null).isPresent(), kind.name());
        }
        assertEquals(4, added.size());
        for (Mob mob : added) {
            assertEquals(List.of("setPersistent=false", "setCanPickupItems=false"), lifecycleCalls.get(mob));
        }
        assertEquals(List.of(false), burnCalls.get(added.get(0)), "zombie");
        assertEquals(List.of(false), burnCalls.get(added.get(1)), "skeleton");
        assertFalse(burnCalls.containsKey(added.get(2)), "spiders never burn");
        assertEquals(List.of(false), burnCalls.get(added.get(3)), "the stalker is a wither skeleton");
        assertEquals(List.of(Material.BOW), mainHands.get(added.get(1)), "skeleton holds a bow");
        assertFalse(mainHands.containsKey(added.get(0)), "zombie is left as spawned");
        assertFalse(body.containsKey(added.get(0)), "only the stalker is resized and rearmed");
    }

    @Test
    void theShadowStalkerIsAWitherSkeletonWithItsBodyAndGoals() {
        stalkerSettings = new StalkerSettings(1, 60, 9, 0.35, 1.25, 8, 20, 8, 24, 2, 3);
        var arena = new Arena("dojo", new GridCell(0, 0), null, null, List.of(),
                List.of(new ArenaPoint(10.5, 64, 10.5, 0, 0)));
        var spawner = new MobSpawner(world, new SpawnPlacement(arena), BOUNDS, match, id -> null, diagnostics,
                stalkers(), BOW);
        var enemy = spawner.spawn(EnemyKind.SHADOW_STALKER, null).orElseThrow();
        assertEquals(EnemyKind.SHADOW_STALKER, enemy.kind());
        Mob stalker = added.getFirst();
        assertTrue(stalker instanceof WitherSkeleton);
        assertEquals(List.of("max-health=60.0", "health=60.0", "damage=9.0", "speed=0.35", "scale=1.25",
                "follow-range=64.0", "drop-chance=0.0"), body.get(stalker));
        assertEquals(List.of(Material.NETHERITE_SWORD), mainHands.get(stalker), "a sword, not a bow");
        assertEquals(List.of("removeAllGoals=TARGET", "addGoal=1"), goalCalls,
                "only the director picks its target, and the blink runs ahead of melee");
    }

    @Test
    void aStalkerWithoutArenaBoundsGetsNoBlink() {
        var arena = new Arena("dojo", new GridCell(0, 0), null, null, List.of(),
                List.of(new ArenaPoint(10.5, 64, 10.5, 0, 0)));
        var spawner = new MobSpawner(world, new SpawnPlacement(arena), null, match, id -> null, diagnostics,
                stalkers(), BOW);
        assertTrue(spawner.spawn(EnemyKind.SHADOW_STALKER, null).isPresent());
        assertEquals(List.of("removeAllGoals=TARGET"), goalCalls);
    }

    @Test
    void theStalkerNeedsRoomForItsScaledBody() {
        // Five blocks below the build limit: room for every kind at scale 1.1, not for a Stalker at 2.
        var arena = new Arena("dojo", new GridCell(0, 0), null, null, List.of(),
                List.of(new ArenaPoint(10.5, 316, 10.5, 0, 0)));
        var spawner = new MobSpawner(world, new SpawnPlacement(arena), BOUNDS, match, id -> null, diagnostics,
                stalkers(), BOW);
        assertTrue(spawner.spawn(EnemyKind.SHADOW_STALKER, null).isPresent());
        stalkerSettings = new StalkerSettings(1, 40, 6, 0.3, 2, 8, 20, 8, 24, 2, 3);
        var large = new MobSpawner(world, new SpawnPlacement(arena), BOUNDS, match, id -> null, diagnostics,
                stalkers(), BOW);
        assertTrue(large.spawn(EnemyKind.ZOMBIE, null).isPresent());
        assertTrue(large.spawn(EnemyKind.SHADOW_STALKER, null).isEmpty());
        assertEquals("round 1, no enemy spawn point for a shadow stalker: point 1 blocked space",
                large.lastRefusal().orElseThrow());
    }

    @Test
    void refusedAndCancelledSpawnsAreExplained() {
        var empty = new MobSpawner(world, new SpawnPlacement(new Arena("dojo", new GridCell(0, 0), null, null,
                List.of(), List.of())), BOUNDS, match, id -> null, diagnostics, stalkers(), BOW);
        assertTrue(empty.lastRefusal().isEmpty());
        assertTrue(empty.spawn(EnemyKind.ZOMBIE, null).isEmpty());
        assertEquals("round 1, no enemy spawn point for a zombie: the arena has no enemy spawn points",
                empty.lastRefusal().orElseThrow());

        valid = false;
        var arena = new Arena("dojo", new GridCell(0, 0), null, null, List.of(),
                List.of(new ArenaPoint(10.5, 64, 10.5, 0, 0)));
        var spawner = new MobSpawner(world, new SpawnPlacement(arena), BOUNDS, match, id -> null, diagnostics,
                stalkers(), BOW);
        assertTrue(spawner.spawn(EnemyKind.SPIDER, null).isEmpty());
        assertEquals("round 1, the world cancelled a spider spawn at 10.5, 64.0, 10.5",
                spawner.lastRefusal().orElseThrow());
    }

    /** An online player in the arena world standing at x, looking towards -x, who sees everything. */
    private Player standing(double x) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOnline", "hasLineOfSight" -> true;
                    case "getWorld" -> world;
                    case "getLocation" -> new Location(world, x, 64, 10.5);
                    case "getEyeLocation" -> new Location(world, x, 65.62, 10.5, 90, 0);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void onlyPlayersAliveInTheMatchCanRefuseThePoint() {
        var arena = new Arena("dojo", new GridCell(0, 0), null, null, List.of(),
                List.of(new ArenaPoint(10.5, 64, 10.5, 0, 0)));
        Map<UUID, Player> online = new HashMap<>();
        online.put(ELIMINATED, standing(12.5));
        online.put(OTHER_MATCH, standing(11.5));
        match.alive.remove(ELIMINATED);
        var spawner = new MobSpawner(world, new SpawnPlacement(arena), BOUNDS, match, online::get, diagnostics,
                stalkers(), BOW);
        assertTrue(spawner.spawn(EnemyKind.ZOMBIE, null).isPresent(),
                "a spectator and a player of another match next to the only point do not count");

        online.put(ALIVE, standing(12.5));
        assertTrue(spawner.spawn(EnemyKind.ZOMBIE, null).isEmpty(), "a player alive in the match does");
        assertEquals("round 1, no enemy spawn point for a zombie: point 1 too close",
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
