package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.director.MatchDirectorTest.FakeEnemy;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityTargetEvent;
import org.junit.jupiter.api.Test;

class WorldSensesTest {

    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);

    private final World world = world("arena");
    private final World other = world("other");
    private final Map<UUID, Player> online = new HashMap<>();
    private final Set<UUID> sneaking = new HashSet<>();
    private final Map<UUID, World> worlds = new HashMap<>();
    private final Set<UUID> visible = new HashSet<>();
    private boolean dead;
    private final Location mobAt = new Location(world, 10, 64, 10);
    private final Set<Location> smoky = new HashSet<>();
    private int light = 12;

    private final WorldSenses senses = new WorldSenses(online::get,
            at -> smoky.stream().anyMatch(spot -> spot.distanceSquared(at) < 1));

    private World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "getBlockAt" -> block();
                    case "equals" -> p == a[0];
                    case "hashCode" -> name.hashCode();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private Block block() {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getLightLevel" -> (byte) light;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private Player player(UUID id) {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getUniqueId" -> id;
                    case "isSneaking" -> sneaking.contains(id);
                    case "getWorld" -> worlds.getOrDefault(id, world);
                    case "getLocation" -> new Location(worlds.getOrDefault(id, world), 0, 64, 0);
                    case "isOnline" -> true;
                    case "equals" -> p == a[0];
                    case "hashCode" -> id.hashCode();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        online.put(id, player);
        return player;
    }

    private Mob mob() {
        return (Mob) Proxy.newProxyInstance(Zombie.class.getClassLoader(), new Class<?>[] {Zombie.class},
                (p, m, a) -> switch (m.getName()) {
                    case "isValid" -> !dead;
                    case "isDead" -> dead;
                    case "getWorld" -> world;
                    case "getLocation" -> mobAt.clone();
                    case "hasLineOfSight" -> visible.contains(((Player) a[0]).getUniqueId());
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private MobSpawner.MobEnemy enemy(Mob mob) {
        return new MobSpawner.MobEnemy(EnemyKind.ZOMBIE, mob, online::get);
    }

    @Test
    void readsCrouchingAndLightFromOnlinePlayers() {
        player(ALICE);
        assertFalse(senses.crouching(ALICE));
        sneaking.add(ALICE);
        assertTrue(senses.crouching(ALICE));
        assertEquals(12, senses.light(ALICE));
        light = 3;
        assertEquals(3, senses.light(ALICE));

        assertFalse(senses.crouching(BOB), "offline");
        assertEquals(15, senses.light(BOB), "offline counts as lit");
    }

    @Test
    void seesByLineOfSightInTheSameWorld() {
        player(ALICE);
        player(BOB);
        var zombie = enemy(mob());
        assertFalse(senses.sees(zombie, ALICE));
        visible.add(ALICE);
        assertTrue(senses.sees(zombie, ALICE));
        worlds.put(ALICE, other);
        assertFalse(senses.sees(zombie, ALICE), "another world");
    }

    @Test
    void anEnemyItCannotFindSeesEveryoneAndIsNeverInSmoke() {
        player(ALICE);
        smoky.add(mobAt);
        assertTrue(senses.sees(new FakeEnemy(EnemyKind.ZOMBIE, null), ALICE));
        assertFalse(senses.inSmoke(new FakeEnemy(EnemyKind.ZOMBIE, null)));
        var zombie = enemy(mob());
        dead = true;
        assertTrue(senses.sees(zombie, ALICE));
        assertFalse(senses.inSmoke(zombie));
        assertTrue(senses.sees(enemy(mob()), BOB), "the player is offline");
    }

    @Test
    void findsAnEnemyInSmoke() {
        var zombie = enemy(mob());
        assertFalse(senses.inSmoke(zombie));
        smoky.add(mobAt);
        assertTrue(senses.inSmoke(zombie));
    }

    @Test
    void aMobInSmokeTakesNoNewTarget() {
        Player alice = player(ALICE);
        Mob mob = mob();
        var acquire = new EntityTargetEvent(mob, alice, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        senses.onTarget(acquire);
        assertFalse(acquire.isCancelled());

        smoky.add(mobAt);
        acquire = new EntityTargetEvent(mob, alice, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        senses.onTarget(acquire);
        assertTrue(acquire.isCancelled());

        var drop = new EntityTargetEvent(mob, null, EntityTargetEvent.TargetReason.FORGOT_TARGET);
        senses.onTarget(drop);
        assertFalse(drop.isCancelled(), "dropping a target is always allowed");
    }
}
