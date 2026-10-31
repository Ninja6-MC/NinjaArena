package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchResult;
import com.ninja6.ninjaarena.match.TeamScores;
import com.ninja6.ninjaarena.support.TestItemStack;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

class EnemyLifecycleTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private final FakeMatch match = new FakeMatch(List.of(List.of(A), List.of(B))).combat(0);
    private final TeamScores scores = new TeamScores();
    private final EnemyCosts costs = new EnemyCosts(3, 5, 7, 11);
    private final List<Entity> entities = new ArrayList<>();
    private final List<Body> spawned = new ArrayList<>();
    private final World world = proxy(World.class, (object, method, args) -> switch (method.getName()) {
        case "getEntities" -> List.copyOf(entities);
        default -> throw new UnsupportedOperationException(method.getName());
    });
    private long now;
    private double roll;
    private boolean hasBounds = true;
    private boolean hasDirector = true;
    private final MatchDirector director = new MatchDirector(match, IntensitySettings.DEFAULTS, new ThreatBudget(100),
            costs, scores, (kind, target) -> {
                if (!spawned.isEmpty()) {
                    return Optional.empty();
                }
                Body body = body(Mob.class, 5, 5);
                spawned.add(body);
                return Optional.of(new MobSpawner.MobEnemy(kind, (Mob) body.entity, id -> null));
            }, SpawnGate.OPEN, TargetWeigher.NONE, () -> roll);
    private final EnemyLifecycle lifecycle = new EnemyLifecycle(() -> List.of(match),
            view -> hasDirector ? Optional.of(director) : Optional.empty(),
            name -> hasBounds ? Optional.of(new ArenaBounds(0, 0, 0, 10, 100, 10)) : Optional.empty(),
            () -> world, scores, costs, () -> now);

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private final class Body {
        Location at;
        Entity entity;
        Player killer;
        boolean removed;
    }

    private Body body(Class<? extends Entity> type, double x, double z) {
        Body body = new Body();
        body.at = new Location(world, x, 64, z);
        body.entity = proxy(type, (object, method, args) -> switch (method.getName()) {
            case "getLocation" -> body.at.clone();
            case "isValid" -> !body.removed;
            case "isDead" -> body.removed;
            case "getKiller" -> body.killer;
            case "getTarget", "setTarget" -> null;
            case "remove" -> {
                body.removed = true;
                yield null;
            }
            case "equals" -> object == args[0];
            case "hashCode" -> System.identityHashCode(object);
            default -> throw new UnsupportedOperationException(method.getName());
        });
        entities.add(body.entity);
        return body;
    }

    private Player player(UUID id) {
        return proxy(Player.class, (object, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private EntityDeathEvent death(Body body) {
        DamageSource source = proxy(DamageSource.class, (object, method, args) -> null);
        // A drop entry is enough to prove that the event's mutable drop list is cleared.
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new TestItemStack(Material.ROTTEN_FLESH, "drop", 1));
        return new EntityDeathEvent((Mob) body.entity, source, drops, 8);
    }

    @Test
    void daylightIgnitionIsCancelledForEveryTrackedEnemyKind() {
        for (EnemyKind kind : EnemyKind.values()) {
            spawned.clear();
            roll = (kind.ordinal() + 0.1) / EnemyKind.values().length;
            director.tick();
            assertEquals(kind, director.enemies().getFirst().kind());
            Body body = spawned.getFirst();
            EntityCombustEvent event = new EntityCombustEvent(body.entity, 8.0F);
            lifecycle.onCombust(event);
            assertTrue(event.isCancelled(), kind.name());
            body.removed = true;
        }
    }

    @Test
    void daylightProtectionFollowsOwnershipBetweenRoundsAndOutsideBounds() {
        director.tick();
        Body body = spawned.getFirst();
        body.at.setX(-50);
        match.phase = MatchPhase.ROUND_END;
        hasBounds = false;
        EntityCombustEvent event = new EntityCombustEvent(body.entity, 8.0F);
        lifecycle.onCombust(event);
        assertTrue(event.isCancelled());

        hasDirector = false;
        EntityCombustEvent afterDisposal = new EntityCombustEvent(body.entity, 8.0F);
        lifecycle.onCombust(afterDisposal);
        assertFalse(afterDisposal.isCancelled());
    }

    @Test
    void untrackedMobsAndPlayersKeepTheirIgnition() {
        director.tick();
        for (Class<? extends Entity> type : List.of(Mob.class, Player.class)) {
            EntityCombustEvent event = new EntityCombustEvent(body(type, 5, 5).entity, 8.0F);
            lifecycle.onCombust(event);
            assertFalse(event.isCancelled());
        }
    }

    @Test
    void blockAndEntityFireSourcesStillIgniteTrackedEnemies() {
        director.tick();
        Entity enemy = spawned.getFirst().entity;
        Entity attacker = body(Entity.class, 5, 5).entity;
        List<EntityCombustEvent> events = List.of(
                new EntityCombustByBlockEvent(null, enemy, 8.0F),
                new EntityCombustByEntityEvent(attacker, enemy, 8.0F));
        for (EntityCombustEvent event : events) {
            lifecycle.onCombust(event);
            assertFalse(event.isCancelled());
            event.setCancelled(true);
            lifecycle.onCombust(event);
            assertTrue(event.isCancelled(), "existing cancellations are preserved");
        }
    }

    @Test
    void stuckRemovalRefundsThroughTheExistingDirectorWithoutScoring() {
        director.tick();
        assertEquals(3, director.threatInPlay());
        lifecycle.tick();
        now = 9_000_000_000L;
        lifecycle.tick();
        assertFalse(spawned.getFirst().removed);
        now = 10_000_000_000L;
        lifecycle.tick();
        assertTrue(spawned.getFirst().removed);
        director.tick();
        assertEquals(0, director.threatInPlay());
        assertTrue(director.enemies().isEmpty());
        assertEquals(0, scores.score(match, 1));
    }

    @Test
    void movingEscapeesAreRemovedBetweenRoundsToo() {
        director.tick();
        Body body = spawned.getFirst();
        body.at.setX(-1);
        match.phase = MatchPhase.ROUND_END;
        lifecycle.tick();
        now = 10_000_000_000L;
        body.at.setX(-2);
        lifecycle.tick();
        assertTrue(body.removed);
    }

    @Test
    void killAwardsConfiguredCostOnceAndClearsAllDrops() {
        director.tick();
        Body body = spawned.getFirst();
        body.killer = player(B);
        EntityDeathEvent event = death(body);
        lifecycle.onDeath(event);
        lifecycle.onDeath(event);
        assertEquals(List.of(), event.getDrops());
        assertEquals(0, event.getDroppedExp());
        assertEquals(3, scores.score(match, 2), "zombie uses the configured cost");
        assertEquals(0, scores.score(match, 1));
    }

    @Test
    void everyEnemyKindUsesItsOwnConfiguredCost() {
        int total = 0;
        for (EnemyKind kind : EnemyKind.values()) {
            spawned.clear();
            roll = (kind.ordinal() + 0.1) / EnemyKind.values().length;
            director.tick();
            assertEquals(kind, director.enemies().getFirst().kind());
            Body body = spawned.getFirst();
            body.killer = player(A);
            lifecycle.onDeath(death(body));
            total += kind.cost(costs);
            assertEquals(total, scores.score(match, 1));
            body.removed = true;
        }
    }

    @Test
    void environmentalOutsiderEliminatedAndNonCombatKillsAwardNothing() {
        for (int scenario = 0; scenario < 4; scenario++) {
            spawned.clear();
            director.tick();
            Body body = spawned.getFirst();
            body.killer = switch (scenario) {
                case 0 -> null;
                case 1 -> player(new UUID(0, 99));
                default -> player(A);
            };
            if (scenario == 2) {
                match.alive.remove(A);
            }
            if (scenario == 3) {
                match.phase = MatchPhase.ROUND_END;
            }
            EntityDeathEvent event = death(body);
            lifecycle.onDeath(event);
            assertEquals(0, event.getDroppedExp());
            assertTrue(event.getDrops().isEmpty());
            body.removed = true;
        }
        assertEquals(0, scores.score(match, 1));
        assertEquals(0, scores.score(match, 2));
    }

    @Test
    void unrelatedDeathsKeepTheirDropsAndExperience() {
        Body body = body(Mob.class, 5, 5);
        body.killer = player(A);
        EntityDeathEvent event = death(body);
        lifecycle.onDeath(event);
        assertEquals(8, event.getDroppedExp());
        assertEquals(1, event.getDrops().size());
        assertEquals(0, scores.score(match, 1));
    }

    @Test
    void matchEndRemovesEscapeesAndAllCleanupKindsInsideOnly() {
        director.tick();
        spawned.getFirst().at.setX(-50);
        List<Body> inside = new ArrayList<>();
        List<Body> outside = new ArrayList<>();
        for (Class<? extends Entity> type : List.of(Mob.class, Projectile.class, Item.class, ExperienceOrb.class)) {
            inside.add(body(type, 10.99, 5));
            outside.add(body(type, 11, 5));
        }
        Body player = body(Player.class, 5, 5);
        Body decoration = body(Entity.class, 5, 5);
        for (MatchResult.Reason reason : MatchResult.Reason.values()) {
            lifecycle.onMatchEnd(new MatchEndEvent(match,
                    new MatchResult("dojo", reason, List.of(), List.of(), List.of())));
        }
        assertTrue(spawned.getFirst().removed);
        assertTrue(inside.stream().allMatch(body -> body.removed));
        assertTrue(outside.stream().noneMatch(body -> body.removed));
        assertFalse(player.removed);
        assertFalse(decoration.removed);
    }

    @Test
    void cleanupWorksBeforeTheFirstFightAndWithoutBoundsStillRemovesTrackedEnemies() {
        hasDirector = false;
        Body item = body(Item.class, 5, 5);
        lifecycle.teardown(match);
        assertTrue(item.removed);
        hasDirector = true;
        director.tick();
        hasBounds = false;
        lifecycle.teardown(match);
        assertTrue(spawned.getFirst().removed);
    }

    @Test
    void startIsIdempotentAndCloseCancelsTheServerTaskAndCleansEnemies() {
        List<Runnable> tasks = new ArrayList<>();
        boolean[] cancelled = {false};
        BukkitTask task = proxy(BukkitTask.class, (object, method, args) -> {
            assertEquals("cancel", method.getName());
            cancelled[0] = true;
            return null;
        });
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (object, method, args) -> {
            assertEquals("runTaskTimer", method.getName());
            assertEquals(20L, args[2]);
            assertEquals(20L, args[3]);
            tasks.add((Runnable) args[1]);
            return task;
        });
        Server server = proxy(Server.class, (object, method, args) -> scheduler);
        Plugin plugin = proxy(Plugin.class, (object, method, args) -> server);
        lifecycle.start(plugin);
        lifecycle.start(plugin);
        assertEquals(1, tasks.size());
        director.tick();
        tasks.getFirst().run();
        now = 10_000_000_000L;
        tasks.getFirst().run();
        assertTrue(spawned.getFirst().removed);
        Body item = body(Item.class, 5, 5);
        lifecycle.close();
        lifecycle.close();
        assertTrue(cancelled[0]);
        assertTrue(item.removed);
    }

    @Test
    void handlersRunBeforeRegistryDisposalAndSkipCancelledDeaths() throws Exception {
        EventHandler combust = EnemyLifecycle.class.getMethod("onCombust", EntityCombustEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.HIGHEST, combust.priority());
        assertTrue(combust.ignoreCancelled());
        EventHandler death = EnemyLifecycle.class.getMethod("onDeath", EntityDeathEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.HIGHEST, death.priority());
        assertTrue(death.ignoreCancelled());
        EventHandler end = EnemyLifecycle.class.getMethod("onMatchEnd", MatchEndEvent.class)
                .getAnnotation(EventHandler.class);
        assertTrue(end.priority().ordinal() < EventPriority.MONITOR.ordinal());
    }
}
