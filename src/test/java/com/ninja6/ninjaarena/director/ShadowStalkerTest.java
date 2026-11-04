package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.support.TestItemStack;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShadowStalkerTest {

    private static final double DELTA = 1e-9;
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final ArenaBounds BOUNDS = new ArenaBounds(0, 60, 0, 40, 80, 40);

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private final FakeMatch match = new FakeMatch(List.of(List.of(A), List.of(B))).combat(0);
    private long now;
    /** Block columns walled from y 64 to 66. */
    private final Set<List<Integer>> walls = new HashSet<>();
    private final List<Particle> particles = new ArrayList<>();
    private final World world = proxy(World.class, (object, method, args) -> switch (method.getName()) {
        case "getMinHeight" -> -64;
        case "getMaxHeight" -> 320;
        case "isChunkLoaded" -> true;
        case "getBlockAt" -> block((Integer) args[0], (Integer) args[1], (Integer) args[2]);
        case "spawnParticle" -> {
            particles.add((Particle) args[0]);
            yield null;
        }
        case "equals" -> object == args[0];
        case "hashCode" -> System.identityHashCode(object);
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private Block block(int x, int y, int z) {
        boolean solid = y <= 63 || y <= 66 && walls.contains(List.of(x, z));
        VoxelShape shape = proxy(VoxelShape.class, (object, method, args) ->
                solid ? List.of(new BoundingBox(0, 0, 0, 1, 1, 1)) : List.of());
        return proxy(Block.class, (object, method, args) -> switch (method.getName()) {
            case "getCollisionShape" -> shape;
            case "isLiquid" -> false;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    // The target: Alice, on team 1, looking towards +z.
    private Location targetAt = new Location(null, 10.5, 64, 20.5, 0, 0);
    private boolean online = true;
    private final List<PotionEffect> effects = new ArrayList<>();
    private final Player alice = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
        case "getUniqueId" -> A;
        case "isOnline" -> online;
        case "isDead" -> false;
        case "getWorld" -> world;
        case "getLocation" -> targetAt.clone();
        case "addPotionEffect" -> {
            effects.add((PotionEffect) args[0]);
            yield true;
        }
        case "equals" -> object == args[0];
        case "hashCode" -> System.identityHashCode(object);
        default -> throw new UnsupportedOperationException(method.getName());
    });

    // The Stalker, and what it was asked to do.
    private Location stalkerAt = new Location(null, 10.5, 64, 5.5);
    private LivingEntity target;
    private final List<LivingEntity> targetsSet = new ArrayList<>();
    private final List<Location> teleports = new ArrayList<>();
    private final List<String> pathCalls = new ArrayList<>();
    private Pathfinder.PathResult path;
    private int lookedAt;
    private final Pathfinder pathfinder = proxy(Pathfinder.class, (object, method, args) -> {
        pathCalls.add(method.getName());
        return switch (method.getName()) {
            case "stopPathfinding" -> null;
            case "findPath" -> path;
            default -> throw new UnsupportedOperationException(method.getName());
        };
    });
    private final WitherSkeleton mob = proxy(WitherSkeleton.class, (object, method, args) -> switch (method.getName()) {
        case "getTarget" -> target;
        case "setTarget" -> {
            target = (LivingEntity) args[0];
            targetsSet.add(target);
            yield null;
        }
        case "getLocation" -> stalkerAt.clone();
        case "getWorld" -> world;
        case "getPathfinder" -> pathfinder;
        case "lookAt" -> {
            lookedAt++;
            yield null;
        }
        case "teleport" -> {
            stalkerAt = ((Location) args[0]).clone();
            teleports.add(stalkerAt.clone());
            yield true;
        }
        case "equals" -> object == args[0];
        case "hashCode" -> System.identityHashCode(object);
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final List<String> goalCalls = new ArrayList<>();
    private Goal<?> goal;
    private final MobGoals goals = proxy(MobGoals.class, (object, method, args) -> {
        assertSame(mob, args[0]);
        goalCalls.add(method.getName() + "=" + args[1]);
        if (method.getName().equals("addGoal")) {
            goal = (Goal<?>) args[2];
        }
        return null;
    });

    @BeforeEach
    void setUp() {
        targetAt = new Location(world, 10.5, 64, 20.5, 0, 0);
        stalkerAt = new Location(world, 10.5, 64, 5.5);
        target = alice;
        new ShadowStalker(StalkerSettings.DEFAULTS, goals, () -> now,
                () -> new TestItemStack(Material.NETHERITE_SWORD, "blade", 1)).arm(mob, match, BOUNDS);
    }

    private Location blinkThroughTheTell() {
        now = 160;
        assertTrue(goal.shouldActivate());
        goal.start();
        for (now = 161; now < 180; now++) {
            assertTrue(goal.shouldStayActive());
            goal.tick();
        }
        assertTrue(teleports.isEmpty(), "it stands still for the whole tell");
        now = 180;
        assertTrue(goal.shouldStayActive());
        goal.tick();
        return teleports.isEmpty() ? null : teleports.getLast();
    }

    @Test
    void armingReplacesTheTargetGoalsWithTheBlinkAheadOfMelee() {
        assertEquals(List.of("removeAllGoals=TARGET", "addGoal=1"), goalCalls);
        assertEquals(ShadowStalker.BLINK, goal.getKey());
        assertEquals(EnumSet.of(GoalType.MOVE, GoalType.LOOK), goal.getTypes(), "holds it still during the tell");
    }

    @Test
    void aFarTargetIsBlinkedBehindAfterTheTellAndGlows() {
        now = 159;
        assertFalse(goal.shouldActivate(), "a new Stalker waits a full cooldown");

        Location landed = blinkThroughTheTell();
        assertEquals(List.of("stopPathfinding"), pathCalls, "far enough without asking for a path");
        assertEquals(20, lookedAt, "it watches its target through the tell");
        assertEquals(10.5, landed.getX(), DELTA);
        assertEquals(64, landed.getY(), DELTA);
        assertEquals(18.5, landed.getZ(), DELTA, "two blocks behind Alice, who faces +z");
        assertEquals(0, landed.getYaw(), DELTA, "facing her back");
        assertEquals(1, effects.size());
        assertEquals(PotionEffectType.GLOWING, effects.getFirst().getType());
        assertEquals(60, effects.getFirst().getDuration(), "three seconds");
        assertEquals(List.of(alice), targetsSet, "still on her after the blink");
        assertTrue(particles.contains(Particle.PORTAL), "the tell");
        assertEquals(2, particles.stream().filter(Particle.LARGE_SMOKE::equals).count(), "a puff where it left and landed");

        assertFalse(goal.shouldStayActive());
        goal.stop();
        now = 339;
        stalkerAt = new Location(world, 10.5, 64, 5.5);
        assertFalse(goal.shouldActivate(), "cooling down");
        now = 340;
        assertTrue(goal.shouldActivate());
    }

    @Test
    void aNearTargetIsBlinkedToOnlyWhenNoWalkingPathReachesThem() {
        targetAt = new Location(world, 10.5, 64, 9.5, 0, 0);
        now = 160;
        path = proxy(Pathfinder.PathResult.class, (object, method, args) -> switch (method.getName()) {
            case "getFinalPoint" -> new Location(world, 10.5, 64, 9);
            case "canReachFinalPoint" -> true;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        assertFalse(goal.shouldActivate(), "walkable: melee takes it");
        assertEquals(List.of("findPath"), pathCalls);

        path = null;
        now = 161;
        assertFalse(goal.shouldActivate(), "paths are looked for at most once a second");
        assertEquals(List.of("findPath"), pathCalls);
        now = 180;
        assertTrue(goal.shouldActivate(), "no path: up a pillar or across a gap");
    }

    @Test
    void aTargetLostDuringTheTellEndsItWithoutABlinkAndItTriesAgainSoon() {
        now = 160;
        assertTrue(goal.shouldActivate());
        goal.start();
        now = 170;
        match.alive.remove(A);
        assertFalse(goal.shouldStayActive(), "eliminated mid-tell");
        goal.stop();
        assertTrue(teleports.isEmpty());
        assertTrue(effects.isEmpty());

        match.alive.add(A);
        now = 189;
        assertFalse(goal.shouldActivate());
        now = 190;
        assertTrue(goal.shouldActivate(), "a retry, not a whole cooldown");
    }

    @Test
    void aWallAtTheTargetsBackMovesTheLandingRoundThem() {
        // Straight behind and the closer spot behind are both walled.
        walls.add(List.of(10, 18));
        walls.add(List.of(10, 19));
        Location landed = blinkThroughTheTell();
        assertEquals(30, landed.getYaw(), DELTA, "30 degrees round");
        double dx = targetAt.getX() - landed.getX();
        double dz = targetAt.getZ() - landed.getZ();
        assertEquals(2, Math.hypot(dx, dz), DELTA);
    }

    @Test
    void withNowhereToLandItMissesAndRetries() {
        // Alice stands against the arena's north edge, facing out of it, boxed in at her sides.
        targetAt = new Location(world, 20.5, 64, 1.5, 180, 0);
        for (int x = 17; x <= 24; x++) {
            for (int z = 0; z <= 5; z++) {
                if (x != 20 || z != 1) {
                    walls.add(List.of(x, z));
                }
            }
        }
        stalkerAt = new Location(world, 20.5, 64, 15.5);
        assertNull(blinkThroughTheTell());
        assertTrue(effects.isEmpty(), "no glow without a blink");
        assertFalse(goal.shouldStayActive());
        goal.stop();
        now = 199;
        assertFalse(goal.shouldActivate());
        now = 200;
        assertTrue(goal.shouldActivate());
    }

    @Test
    void neverBlinksOutsideAFightBeyondRangeOrAtSomeoneOutOfTheRound() {
        now = 160;
        match.phase = MatchPhase.ROUND_END;
        assertFalse(goal.shouldActivate(), "between rounds");
        match.combat(0);

        targetAt = new Location(world, 10.5, 64, 30, 0, 0);
        assertFalse(goal.shouldActivate(), "24.5 blocks: beyond the blink range");
        targetAt = new Location(world, 10.5, 64, 20.5, 0, 0);

        online = false;
        assertFalse(goal.shouldActivate(), "logged off");
        online = true;

        target = null;
        assertFalse(goal.shouldActivate(), "nobody to hunt");
        target = alice;
        assertTrue(goal.shouldActivate());
        assertTrue(pathCalls.isEmpty());
    }
}
