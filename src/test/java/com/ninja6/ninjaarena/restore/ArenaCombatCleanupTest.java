package com.ninja6.ninjaarena.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.world.ArenaWorld;
import com.ninja6.ninjaarena.world.LobbyWorld;
import io.papermc.paper.world.damagesource.CombatEntry;
import io.papermc.paper.world.damagesource.CombatTracker;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

class ArenaCombatCleanupTest {
    private static final String OUTSIDE = "world";

    public interface HandledPlayer extends Player { FakeServerPlayer getHandle(); }

    /** The server's entity: only what the cleanup reads. */
    public static class FakeEntity {
        final Entity bukkit;
        FakeEntity(String world) { bukkit = entity(world); }
        public Entity getBukkitEntity() { return bukkit; }
    }

    /** {@code LivingEntity} as on Paper 26.3. */
    public static class FakeLiving extends FakeEntity {
        protected FakeDamage lastDamageSource;
        FakeLiving lastHurtByMob;
        FakeLiving lastHurtMob;
        FakeLiving(String world) { super(world); }
        public FakeLiving getLastHurtByMob() { return lastHurtByMob; }
        public void setLastHurtByMob(FakeLiving mob) { lastHurtByMob = mob; }
        public FakeLiving getLastHurtMob() { return lastHurtMob; }
        public void setLastHurtMob(FakeEntity mob) { lastHurtMob = mob instanceof FakeLiving living ? living : null; }
    }

    /** {@code ServerPlayer} as on Paper 26.3. */
    public static class FakeServerPlayer extends FakeLiving {
        private FakeEntity currentExplosionCause;
        FakeServerPlayer() { super(LobbyWorld.NAME); }
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

    /** A server whose living entity and player have none of the members the cleanup uses. */
    public static class BareLiving extends FakeEntity {
        BareLiving() { super(LobbyWorld.NAME); }
    }

    public static class BareServerPlayer extends BareLiving { }

    public interface BarePlayer extends Player { BareServerPlayer getHandle(); }

    private static final Class<?>[] PAPER_26_3 = {FakeLiving.class, FakeDamage.class, FakeEntity.class,
        FakeServerPlayer.class};

    @Test
    void releasesEveryArenaShapeOfTheLastDamageSourceAndKeepsOutsideDamage() {
        FakeServerPlayer owner = new FakeServerPlayer();
        Player player = proxy(HandledPlayer.class, (p, m, a) -> owner);
        List<String> missing = new ArrayList<>();
        var adapter = new ArenaCombatCleanup.PaperReferences((member, reason) -> missing.add(member), PAPER_26_3);
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
        outside.causing = new FakeEntity(OUTSIDE);
        outside.direct = new FakeEntity(OUTSIDE);
        outside.event = new FakeEntity(OUTSIDE);
        outside.block = proxy(Block.class, (p, m, a) -> world(OUTSIDE));
        outside.snapshot = proxy(BlockState.class, (p, m, a) -> world(OUTSIDE));
        owner.lastDamageSource = outside;
        adapter.clear(player);
        assertSame(outside, owner.lastDamageSource);
        owner.lastDamageSource = null;
        adapter.clear(player);
        assertNull(owner.lastDamageSource);
        assertEquals(List.of(), missing);
    }

    @Test
    void releasesArenaMobsTheServerStillHoldsAndKeepsOutsideOnes() {
        FakeServerPlayer owner = new FakeServerPlayer();
        Player player = proxy(HandledPlayer.class, (p, m, a) -> owner);
        List<String> missing = new ArrayList<>();
        var adapter = new ArenaCombatCleanup.PaperReferences((member, reason) -> missing.add(member), PAPER_26_3);
        owner.lastHurtByMob = new FakeLiving(ArenaWorld.NAME);
        owner.lastHurtMob = new FakeLiving(ArenaWorld.NAME);
        owner.currentExplosionCause = new FakeEntity(ArenaWorld.NAME);
        adapter.clear(player);
        assertNull(owner.lastHurtByMob);
        assertNull(owner.lastHurtMob);
        assertNull(owner.currentExplosionCause);

        FakeLiving mob = new FakeLiving(OUTSIDE);
        FakeLiving hurt = new FakeLiving(OUTSIDE);
        FakeEntity creeper = new FakeEntity(OUTSIDE);
        owner.lastHurtByMob = mob;
        owner.lastHurtMob = hurt;
        owner.currentExplosionCause = creeper;
        adapter.clear(player);
        assertSame(mob, owner.lastHurtByMob);
        assertSame(hurt, owner.lastHurtMob);
        assertSame(creeper, owner.currentExplosionCause);
        assertEquals(List.of(), missing);
    }

    @Test
    void eachMissingServerMemberIsLoggedOnceByNameAndTheRestStillRun() {
        List<String> warnings = new ArrayList<>();
        Logger logger = capture(warnings);
        BareServerPlayer owner = new BareServerPlayer();
        Player bare = proxy(BarePlayer.class, (p, m, a) -> switch (m.getName()) {
            case "getHandle" -> owner;
            default -> null;
        });
        ArenaCombatCleanup cleanup = new ArenaCombatCleanup(logger, BareLiving.class, FakeDamage.class,
                FakeEntity.class, BareServerPlayer.class);
        cleanup.accept(bare);
        cleanup.accept(bare);
        assertEquals(4, warnings.size(), warnings.toString());
        for (String member : List.of(ArenaCombatCleanup.PaperReferences.LAST_DAMAGE_SOURCE,
                ArenaCombatCleanup.PaperReferences.LAST_HURT_BY_MOB, ArenaCombatCleanup.PaperReferences.LAST_HURT_MOB,
                ArenaCombatCleanup.PaperReferences.EXPLOSION_CAUSE)) {
            assertEquals(1, warnings.stream().filter(w -> w.contains(member)).count(), member);
        }
    }

    @Test
    void aServerWithoutThePlayerHandleIsLoggedOnceByName() {
        List<String> warnings = new ArrayList<>();
        ArenaCombatCleanup cleanup = new ArenaCombatCleanup(capture(warnings));
        Player player = proxy(Player.class, (p, m, a) -> null);
        cleanup.accept(player);
        cleanup.accept(player);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(ArenaCombatCleanup.PaperReferences.HANDLE), warnings.get(0));
    }

    @Test
    void ownershipUsesTheSourceWorldAndHandlesMissingSourcesAndBlockSnapshots() {
        assertTrue(ArenaCombatCleanup.arena(entity(ArenaWorld.NAME)));
        assertFalse(ArenaCombatCleanup.arena(entity(OUTSIDE)));
        assertFalse(ArenaCombatCleanup.arena((Entity) null));
        assertFalse(ArenaCombatCleanup.arena((DamageSource) null));
        assertTrue(ArenaCombatCleanup.arenaBlock(proxy(Block.class, (p, m, a) -> world(ArenaWorld.NAME))));
        assertTrue(ArenaCombatCleanup.arenaBlock(proxy(BlockState.class, (p, m, a) -> world(ArenaWorld.NAME))));
        assertFalse(ArenaCombatCleanup.arenaBlock(proxy(Block.class, (p, m, a) -> world(OUTSIDE))));
        assertFalse(ArenaCombatCleanup.arenaBlock(null));
    }

    @Test
    void clearsArenaDamageEventButKeepsOutsideDamage() {
        for (String sourceWorld : new String[] {ArenaWorld.NAME, OUTSIDE}) {
            FakePlayer fake = new FakePlayer(LobbyWorld.NAME);
            fake.event = new EntityDamageEvent(fake.player, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                    source(entity(sourceWorld), null), 1);
            AtomicInteger internal = new AtomicInteger();
            new ArenaCombatCleanup(Logger.getAnonymousLogger(), p -> internal.incrementAndGet()).accept(fake.player);
            assertEquals(sourceWorld.equals(ArenaWorld.NAME), fake.event == null);
            assertEquals(1, internal.get());
        }
    }

    @Test
    void clearsArenaEventDamagerWhenTheSourceGettersAreNullButKeepsAnOutsideDamager() {
        for (String sourceWorld : new String[] {ArenaWorld.NAME, OUTSIDE}) {
            FakePlayer fake = new FakePlayer(LobbyWorld.NAME);
            fake.event = new EntityDamageByEntityEvent(entity(sourceWorld), fake.player,
                    EntityDamageEvent.DamageCause.LIGHTNING, source(null, null), 1);
            new ArenaCombatCleanup(Logger.getAnonymousLogger(), p -> { }).accept(fake.player);
            assertEquals(sourceWorld.equals(ArenaWorld.NAME), fake.event == null);
        }
    }

    @Test
    void resetsTheCombatTrackerOnlyWhenItRecordsArenaDamage() {
        for (String sourceWorld : new String[] {ArenaWorld.NAME, OUTSIDE}) {
            FakePlayer fake = new FakePlayer(LobbyWorld.NAME);
            CombatEntry entry = proxy(CombatEntry.class, (p, m, a) -> m.getName().equals("getDamageSource")
                    ? source(null, entity(sourceWorld)) : null);
            fake.entries.add(entry);
            new ArenaCombatCleanup(Logger.getAnonymousLogger(), p -> { }).accept(fake.player);
            assertEquals(sourceWorld.equals(ArenaWorld.NAME) ? 1 : 0, fake.resets.get());
        }
    }

    @Test
    void dropsASpectatorTargetInTheArenaButNotOneElsewhere() {
        for (String targetWorld : new String[] {ArenaWorld.NAME, OUTSIDE}) {
            FakePlayer fake = new FakePlayer(LobbyWorld.NAME);
            fake.mode = GameMode.SPECTATOR;
            Entity target = entity(targetWorld);
            fake.target = target;
            new ArenaCombatCleanup(Logger.getAnonymousLogger(), p -> { }).accept(fake.player);
            assertSame(targetWorld.equals(ArenaWorld.NAME) ? null : target, fake.target);
        }
    }

    /**
     * Match end and the return to the lobby, the leave command, a room closing and a respawn
     * in the lobby all move the player from the arena world to the lobby world.
     */
    @Test
    void leavingTheArenaForTheLobbyReleasesTheArenaDamage() {
        assertReleasedOnWorldChange(LobbyWorld.NAME);
    }

    /** A kick, a leave the lobby cannot take, or a teleport away all send the player home. */
    @Test
    void leavingTheArenaForAnyOtherWorldReleasesTheArenaDamage() {
        assertReleasedOnWorldChange(OUTSIDE);
    }

    @Test
    void aWorldChangeThatDoesNotLeaveTheArenaDoesNothing() {
        for (String from : new String[] {LobbyWorld.NAME, OUTSIDE}) {
            FakePlayer fake = arenaHit(ArenaWorld.NAME);
            AtomicInteger internal = new AtomicInteger();
            new ArenaCombatCleanup(Logger.getAnonymousLogger(), p -> internal.incrementAndGet())
                    .onWorldChange(new PlayerChangedWorldEvent(fake.player, world(from)));
            assertTrue(fake.event != null, from);
            assertEquals(0, internal.get(), from);
        }
    }

    @Test
    void disconnectingFromTheArenaReleasesTheArenaDamage() {
        FakePlayer fake = arenaHit(ArenaWorld.NAME);
        AtomicInteger internal = new AtomicInteger();
        new ArenaCombatCleanup(Logger.getAnonymousLogger(), p -> internal.incrementAndGet())
                .onQuit(new PlayerQuitEvent(fake.player, Component.empty()));
        assertNull(fake.event);
        assertEquals(1, internal.get());

        FakePlayer lobby = arenaHit(LobbyWorld.NAME);
        AtomicInteger untouched = new AtomicInteger();
        new ArenaCombatCleanup(Logger.getAnonymousLogger(), p -> untouched.incrementAndGet())
                .onQuit(new PlayerQuitEvent(lobby.player, Component.empty()));
        assertTrue(lobby.event != null);
        assertEquals(0, untouched.get());
    }

    @Test
    void theCleanupIsAnEventListenerForBothExits() throws Exception {
        assertTrue(org.bukkit.event.Listener.class.isAssignableFrom(ArenaCombatCleanup.class));
        for (var entry : Map.of("onWorldChange", PlayerChangedWorldEvent.class, "onQuit", PlayerQuitEvent.class)
                .entrySet()) {
            var handler = ArenaCombatCleanup.class.getMethod(entry.getKey(), entry.getValue());
            assertTrue(handler.isAnnotationPresent(org.bukkit.event.EventHandler.class), entry.getKey());
        }
    }

    private static void assertReleasedOnWorldChange(String destination) {
        FakePlayer fake = arenaHit(destination);
        AtomicInteger internal = new AtomicInteger();
        new ArenaCombatCleanup(Logger.getAnonymousLogger(), p -> internal.incrementAndGet())
                .onWorldChange(new PlayerChangedWorldEvent(fake.player, world(ArenaWorld.NAME)));
        assertNull(fake.event);
        assertEquals(1, internal.get());
    }

    /** A player now in {@code world} whose last damage came from an arena zombie. */
    private static FakePlayer arenaHit(String world) {
        FakePlayer fake = new FakePlayer(world);
        Entity zombie = entity(ArenaWorld.NAME);
        fake.event = new EntityDamageByEntityEvent(zombie, fake.player, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                source(zombie, zombie), 1);
        return fake;
    }

    /** A Bukkit player with the state the cleanup reads and changes. */
    private static final class FakePlayer {
        final Player player;
        EntityDamageEvent event;
        final List<CombatEntry> entries = new ArrayList<>();
        final AtomicInteger resets = new AtomicInteger();
        GameMode mode = GameMode.SURVIVAL;
        Entity target;

        FakePlayer(String world) {
            CombatTracker tracker = proxy(CombatTracker.class, (p, m, a) -> switch (m.getName()) {
                case "getCombatEntries" -> List.copyOf(entries);
                case "resetCombatState" -> { resets.incrementAndGet(); entries.clear(); yield null; }
                default -> throw new UnsupportedOperationException(m.getName());
            });
            player = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
                case "getLastDamageCause" -> event;
                case "setLastDamageCause" -> { assertNull(a[0]); event = null; yield null; }
                case "getCombatTracker" -> tracker;
                case "getGameMode" -> mode;
                case "getSpectatorTarget" -> target;
                case "setSpectatorTarget" -> { target = (Entity) a[0]; yield null; }
                case "getWorld" -> world(world);
                case "hashCode" -> System.identityHashCode(p);
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    private static DamageSource source(Entity causing, Entity direct) {
        return proxy(DamageSource.class, (p, m, a) -> switch (m.getName()) {
            case "getCausingEntity" -> causing;
            case "getDirectEntity" -> direct;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static Logger capture(List<String> warnings) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        return logger;
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
}
