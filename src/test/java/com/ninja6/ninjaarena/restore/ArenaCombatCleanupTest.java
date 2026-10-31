package com.ninja6.ninjaarena.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ArenaCombatCleanupTest {
    public interface HandledPlayer extends Player { FakeLiving getHandle(); }
    public static class FakeLiving { protected FakeDamage lastDamageSource; }
    public static class FakeEntity {
        final Entity bukkit;
        FakeEntity(String world) { bukkit = entity(world); }
        public Entity getBukkitEntity() { return bukkit; }
    }
    public static class FakeDamage {
        FakeEntity causing;
        FakeEntity direct;
        FakeEntity event;
        Block block;
        BlockState snapshot;
        public FakeEntity getEntity() { return causing; }
        public FakeEntity getDirectEntity() { return direct; }
        public FakeEntity eventEntityDamager() { return event; }
        public Block eventBlockDamager() { return block; }
        public BlockState causingBlockSnapshot() { return snapshot; }
    }

    @Test
    void nativeAdapterReleasesEveryArenaSourceShapeAndKeepsUnrelatedRecords() throws Exception {
        FakeLiving owner = new FakeLiving();
        Player player = proxy(HandledPlayer.class, (p, m, a) -> owner);
        var adapter = new ArenaCombatCleanup.PaperDamageSource(FakeLiving.class, FakeDamage.class, FakeEntity.class);
        for (int shape = 0; shape < 5; shape++) {
            FakeDamage damage = new FakeDamage();
            switch (shape) {
                case 0 -> damage.causing = new FakeEntity(ArenaWorld.NAME);
                case 1 -> damage.direct = new FakeEntity(ArenaWorld.NAME);
                case 2 -> damage.event = new FakeEntity(ArenaWorld.NAME);
                case 3 -> damage.block = proxy(Block.class, (p, m, a) -> world(ArenaWorld.NAME));
                case 4 -> damage.snapshot = proxy(BlockState.class, (p, m, a) -> world(ArenaWorld.NAME));
                default -> throw new AssertionError();
            }
            owner.lastDamageSource = damage;
            adapter.clear(player);
            assertNull(owner.lastDamageSource);
        }
        FakeDamage outside = new FakeDamage();
        outside.causing = new FakeEntity("world");
        outside.direct = new FakeEntity("world");
        outside.event = new FakeEntity("world");
        outside.block = proxy(Block.class, (p, m, a) -> world("world"));
        outside.snapshot = proxy(BlockState.class, (p, m, a) -> world("world"));
        owner.lastDamageSource = outside;
        adapter.clear(player);
        assertSame(outside, owner.lastDamageSource);
        owner.lastDamageSource = null;
        adapter.clear(player);
        assertNull(owner.lastDamageSource);
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static World world(String name) {
        return proxy(World.class, (p, m, a) -> {
            if (m.getName().equals("getName")) return name;
            throw new UnsupportedOperationException(m.getName());
        });
    }

    private static Entity entity(String name) {
        return proxy(Entity.class, (p, m, a) -> {
            if (m.getName().equals("getWorld")) return world(name);
            throw new UnsupportedOperationException(m.getName());
        });
    }

    @Test
    void ownershipUsesTheSourceWorldAndHandlesMissingSourcesAndBlockSnapshots() {
        assertTrue(ArenaCombatCleanup.arena(entity(ArenaWorld.NAME)));
        assertFalse(ArenaCombatCleanup.arena(entity("world")));
        assertFalse(ArenaCombatCleanup.arena(null));
        assertTrue(ArenaCombatCleanup.arenaBlock(proxy(Block.class, (p, m, a) -> world(ArenaWorld.NAME))));
        assertTrue(ArenaCombatCleanup.arenaBlock(proxy(BlockState.class, (p, m, a) -> world(ArenaWorld.NAME))));
        assertFalse(ArenaCombatCleanup.arenaBlock(proxy(Block.class, (p, m, a) -> world("world"))));
        assertFalse(ArenaCombatCleanup.arenaBlock(null));
    }

    @Test
    void clearsFreshArenaDamageButPreservesOutsideDamageEvenWhenPlayerIsInArena() {
        for (String sourceWorld : new String[] {ArenaWorld.NAME, "world"}) {
            Entity source = entity(sourceWorld);
            DamageSource damage = proxy(DamageSource.class, (p, m, a) -> switch (m.getName()) {
                case "getCausingEntity" -> source;
                case "getDirectEntity" -> null;
                default -> throw new UnsupportedOperationException(m.getName());
            });
            Player[] player = new Player[1];
            EntityDamageEvent[] event = new EntityDamageEvent[1];
            AtomicInteger clears = new AtomicInteger();
            player[0] = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
                case "getLastDamageCause" -> event[0];
                case "setLastDamageCause" -> { assertEquals(null, a[0]); clears.incrementAndGet(); yield null; }
                case "getWorld" -> world(ArenaWorld.NAME);
                default -> throw new UnsupportedOperationException(m.getName());
            });
            event[0] = new EntityDamageEvent(player[0], EntityDamageEvent.DamageCause.ENTITY_ATTACK, damage, 1);
            AtomicInteger internal = new AtomicInteger();
            new ArenaCombatCleanup(Logger.getAnonymousLogger(), p -> internal.incrementAndGet()).accept(player[0]);
            assertEquals(sourceWorld.equals(ArenaWorld.NAME) ? 1 : 0, clears.get());
            assertEquals(1, internal.get());
        }
    }

    @Test
    void clearsArenaEventDamagerWhenNativeSourceGettersAreNullButPreservesOutsideDamager() {
        DamageSource damage = proxy(DamageSource.class, (p, m, a) -> switch (m.getName()) {
            case "getCausingEntity", "getDirectEntity" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        for (String sourceWorld : new String[] {ArenaWorld.NAME, "world"}) {
            EntityDamageEvent[] event = new EntityDamageEvent[1];
            AtomicInteger clears = new AtomicInteger();
            Player player = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
                case "getLastDamageCause" -> event[0];
                case "setLastDamageCause" -> { assertNull(a[0]); clears.incrementAndGet(); yield null; }
                default -> throw new UnsupportedOperationException(m.getName());
            });
            event[0] = new EntityDamageByEntityEvent(entity(sourceWorld), player,
                    EntityDamageEvent.DamageCause.LIGHTNING, damage, 1);
            new ArenaCombatCleanup(Logger.getAnonymousLogger(), p -> { }).accept(player);
            assertEquals(sourceWorld.equals(ArenaWorld.NAME) ? 1 : 0, clears.get());
        }
    }

    @Test
    void unsupportedNativeLayoutLogsOnceWithoutBreakingRestoration() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        AtomicInteger warnings = new AtomicInteger();
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.incrementAndGet(); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Player player = proxy(Player.class, (p, m, a) -> null);
        ArenaCombatCleanup cleanup = new ArenaCombatCleanup(logger);
        cleanup.accept(player);
        cleanup.accept(player);
        assertEquals(1, warnings.get());
    }
}
