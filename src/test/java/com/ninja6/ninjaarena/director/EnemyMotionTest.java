package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.lang.reflect.Proxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class EnemyMotionTest {

    private final World world = world();
    private final ArenaBounds bounds = new ArenaBounds(0, 0, 0, 10, 100, 10);
    private final EnemyMotion motion = new EnemyMotion();

    static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private boolean sample(double x, double z, long seconds) {
        return motion.expired(new Location(world, x, 64, z), world, bounds, seconds * 1_000_000_000L);
    }

    @Test
    void tenSecondsStuckIgnoresSmallJitters() {
        assertFalse(sample(5, 5, 0));
        assertFalse(sample(5.1, 5, 9));
        assertTrue(sample(5.05, 5, 10));
    }

    @Test
    void meaningfulMovementResetsTheStuckTimer() {
        assertFalse(sample(5, 5, 0));
        assertFalse(sample(5.25, 5, 9));
        assertFalse(sample(5.25, 5, 18));
        assertTrue(sample(5.25, 5, 19));
    }

    @Test
    void movingOutsideForTenSecondsStillExpires() {
        assertFalse(sample(-1, 5, 0));
        assertFalse(sample(-2, 5, 9));
        assertTrue(sample(-3, 5, 10));
    }

    @Test
    void reentryResetsTheOutsideTimerAndWholeBoundaryBlocksAreInside() {
        assertFalse(sample(-1, 5, 0));
        assertFalse(sample(10.99, 5, 9));
        assertFalse(sample(11, 5, 10));
        assertFalse(sample(12, 5, 19));
        assertTrue(sample(13, 5, 20));
    }

    @Test
    void anotherWorldIsOutsideEvenWithMatchingCoordinates() {
        World other = world();
        assertFalse(motion.expired(new Location(other, 5, 64, 5), world, bounds, 0));
        assertTrue(motion.expired(new Location(other, 6, 64, 5), world, bounds, 10_000_000_000L));
    }

    @Test
    void verticalEscapeIsOutside() {
        assertFalse(motion.expired(new Location(world, 5, 101, 5), world, bounds, 0));
        assertTrue(motion.expired(new Location(world, 5, 102, 5), world, bounds, 10_000_000_000L));
    }
}
