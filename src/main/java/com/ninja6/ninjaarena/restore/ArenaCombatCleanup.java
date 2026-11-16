package com.ninja6.ninjaarena.restore;

import com.ninja6.ninjaarena.world.PluginWorlds;
import io.papermc.paper.world.damagesource.CombatEntry;
import io.papermc.paper.world.damagesource.CombatTracker;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Drops a player's references to entities and damage from the arena world when they leave
 * it, without changing health or combat from anywhere else.
 *
 * <p>Every way out of a match (its end and the return to the lobby, the leave command, a
 * kick, a room closing, a respawn elsewhere) moves the player to another world, and the arena
 * world is only retired once nobody is in it, so the world change is the one place this runs.
 * A disconnect from inside the arena world runs it too. Left alone, the server keeps the
 * last damage source, and through it the mob and its whole world, until the player is hurt
 * again.
 */
public final class ArenaCombatCleanup implements Consumer<Player>, Listener {
    @FunctionalInterface
    interface InternalCleanup {
        void clear(Player player);
    }

    private final Logger logger;
    private final InternalCleanup internal;
    private final Set<String> warned = new HashSet<>();

    public ArenaCombatCleanup(Logger logger) {
        this(logger, new Class<?>[0]);
    }

    /** With {@link PaperReferences} reading the given classes in place of the server's. */
    ArenaCombatCleanup(Logger logger, Class<?>... types) {
        this.logger = logger;
        this.internal = new PaperReferences(this::warnOnce, types);
    }

    ArenaCombatCleanup(Logger logger, InternalCleanup internal) {
        this.logger = logger;
        this.internal = internal;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (PluginWorlds.isArena(event.getFrom())) {
            accept(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (PluginWorlds.isArena(event.getPlayer().getWorld())) {
            accept(event.getPlayer());
        }
    }

    @Override
    public void accept(Player player) {
        try {
            clearDamageEvent(player);
        } catch (RuntimeException failure) {
            warnOnce("the last damage event", failure);
        }
        try {
            clearCombatTracker(player);
        } catch (RuntimeException failure) {
            warnOnce("the combat tracker", failure);
        }
        try {
            clearSpectatorTarget(player);
        } catch (RuntimeException failure) {
            warnOnce("the spectator target", failure);
        }
        try {
            internal.clear(player);
        } catch (RuntimeException failure) {
            warnOnce("the server's combat fields", failure);
        }
    }

    @SuppressWarnings("removal") // The public setter is still the supported way to release the event.
    private static void clearDamageEvent(Player player) {
        EntityDamageEvent event = player.getLastDamageCause();
        if (event != null && (arena(event.getDamageSource())
                || event instanceof EntityDamageByEntityEvent entity && arena(entity.getDamager())
                || event instanceof EntityDamageByBlockEvent block
                && (arenaBlock(block.getDamager()) || arenaBlock(block.getDamagerBlockState())))) {
            player.setLastDamageCause(null);
        }
    }

    /** The tracker forgets on its own after a few seconds out of combat; a match end may be sooner. */
    private static void clearCombatTracker(Player player) {
        CombatTracker tracker = player.getCombatTracker();
        if (tracker == null) {
            return;
        }
        for (CombatEntry entry : tracker.getCombatEntries()) {
            if (arena(entry.getDamageSource())) {
                tracker.resetCombatState();
                return;
            }
        }
    }

    private static void clearSpectatorTarget(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR && arena(player.getSpectatorTarget())) {
            player.setSpectatorTarget(null);
        }
    }

    private void warnOnce(String member, Throwable failure) {
        if (warned.add(member)) {
            logger.log(Level.WARNING, "Arena combat cleanup cannot use " + member + " on this server version;"
                    + " a retired arena world may stay in memory through players who fought in it until they"
                    + " are hurt again.", failure);
        }
    }

    static boolean arena(DamageSource source) {
        return source != null && (arena(source.getCausingEntity()) || arena(source.getDirectEntity()));
    }

    static boolean arena(Entity entity) {
        return entity != null && arenaWorld(entity.getWorld());
    }

    static boolean arenaBlock(Object block) {
        return block instanceof Block value && arenaWorld(value.getWorld())
                || block instanceof BlockState state && arenaWorld(state.getWorld());
    }

    private static boolean arenaWorld(World world) {
        return PluginWorlds.isArena(world);
    }

    /**
     * The server's own fields that point at another entity, which the API cannot reach. On
     * Paper 26.3 the heap shows {@code lastDamageSource} is the one that stays: its getter
     * stops returning it after two seconds but nothing clears it. The last mob hurt and the
     * mob that last hurt the player are dropped by the server a tick after that mob is
     * removed; they are cleared here too, so nothing waits on that. The cause of the last
     * explosion that hit the player stays until they next fall.
     *
     * <p>Each member is looked up once. One that is missing or has changed shape is reported
     * once by name and skipped; the others still run.
     */
    static final class PaperReferences implements InternalCleanup {
        static final String LAST_DAMAGE_SOURCE = "LivingEntity.lastDamageSource";
        static final String LAST_HURT_BY_MOB = "LivingEntity.lastHurtByMob";
        static final String LAST_HURT_MOB = "LivingEntity.lastHurtMob";
        static final String EXPLOSION_CAUSE = "ServerPlayer.currentExplosionCause";
        static final String HANDLE = "CraftPlayer.getHandle";

        /** Reports a member by name with the reason it was skipped. */
        interface Report {
            void missing(String member, Throwable reason);
        }

        private final Report report;
        private final Class<?>[] types;
        private boolean resolved;
        private Method handle;
        private Method bukkitEntity;
        private Field lastDamage;
        private Method[] sources;
        private Method block;
        private Method snapshot;
        private Method getLastHurtByMob;
        private Method setLastHurtByMob;
        private Method getLastHurtMob;
        private Method setLastHurtMob;
        private Field explosionCause;

        /**
         * @param types for tests: the living entity, damage source, entity and server player
         *              classes; empty for the server's own
         */
        PaperReferences(Report report, Class<?>... types) {
            this.report = report;
            this.types = types;
        }

        @Override
        public void clear(Player player) {
            if (!resolved) {
                resolved = true;
                resolve(player);
            }
            if (handle == null) {
                return;
            }
            Object owner;
            try {
                owner = handle.invoke(player);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                handle = null;
                report.missing(HANDLE, failure);
                return;
            }
            if (lastDamage != null) {
                try {
                    clearLastDamage(owner);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    lastDamage = null;
                    report.missing(LAST_DAMAGE_SOURCE, failure);
                }
            }
            if (getLastHurtByMob != null) {
                try {
                    if (arenaHandle(getLastHurtByMob.invoke(owner))) {
                        setLastHurtByMob.invoke(owner, (Object) null);
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    getLastHurtByMob = null;
                    report.missing(LAST_HURT_BY_MOB, failure);
                }
            }
            if (getLastHurtMob != null) {
                try {
                    if (arenaHandle(getLastHurtMob.invoke(owner))) {
                        setLastHurtMob.invoke(owner, (Object) null);
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    getLastHurtMob = null;
                    report.missing(LAST_HURT_MOB, failure);
                }
            }
            if (explosionCause != null) {
                try {
                    if (arenaHandle(explosionCause.get(owner))) {
                        explosionCause.set(owner, null);
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    explosionCause = null;
                    report.missing(EXPLOSION_CAUSE, failure);
                }
            }
        }

        private void clearLastDamage(Object owner) throws ReflectiveOperationException {
            Object damage = lastDamage.get(owner);
            if (damage == null) {
                return;
            }
            boolean arena = arenaBlock(block.invoke(damage)) || arenaBlock(snapshot.invoke(damage));
            for (int i = 0; !arena && i < sources.length; i++) {
                arena = arenaHandle(sources[i].invoke(damage));
            }
            if (arena) {
                lastDamage.set(owner, null);
            }
        }

        private boolean arenaHandle(Object entity) throws ReflectiveOperationException {
            return entity != null && arena((Entity) bukkitEntity.invoke(entity));
        }

        private void resolve(Player player) {
            Class<?> living;
            Class<?> damage;
            Class<?> entity;
            Class<?> serverPlayer;
            try {
                ClassLoader loader = player.getClass().getClassLoader();
                living = type(0, "net.minecraft.world.entity.LivingEntity", loader);
                damage = type(1, "net.minecraft.world.damagesource.DamageSource", loader);
                entity = type(2, "net.minecraft.world.entity.Entity", loader);
                serverPlayer = type(3, "net.minecraft.server.level.ServerPlayer", loader);
                Method candidateHandle = player.getClass().getMethod("getHandle");
                Method candidateBukkit = entity.getMethod("getBukkitEntity");
                if (!serverPlayer.isAssignableFrom(candidateHandle.getReturnType())
                        || !living.isAssignableFrom(serverPlayer) || !entity.isAssignableFrom(living)
                        || !Entity.class.isAssignableFrom(candidateBukkit.getReturnType())) {
                    throw new NoSuchMethodException("Unexpected player handle layout");
                }
                handle = candidateHandle;
                bukkitEntity = candidateBukkit;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                report.missing(HANDLE, failure);
                return;
            }
            try {
                Field candidate = living.getDeclaredField("lastDamageSource");
                Method candidateBlock = damage.getMethod("eventBlockDamager");
                Method candidateSnapshot = damage.getMethod("causingBlockSnapshot");
                Method[] candidateSources = {damage.getMethod("getEntity"), damage.getMethod("getDirectEntity"),
                    damage.getMethod("eventEntityDamager")};
                if (candidate.getType() != damage || !instanceField(candidate)
                        || candidateBlock.getReturnType() != Block.class
                        || candidateSnapshot.getReturnType() != BlockState.class) {
                    throw new NoSuchFieldException("Unexpected type of " + LAST_DAMAGE_SOURCE);
                }
                for (Method source : candidateSources) {
                    if (source.getReturnType() != entity || Modifier.isStatic(source.getModifiers())) {
                        throw new NoSuchMethodException("Unexpected damage source getter " + source.getName());
                    }
                }
                candidate.setAccessible(true);
                block = candidateBlock;
                snapshot = candidateSnapshot;
                sources = candidateSources;
                lastDamage = candidate;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                report.missing(LAST_DAMAGE_SOURCE, failure);
            }
            try {
                Method get = living.getMethod("getLastHurtByMob");
                Method set = living.getMethod("setLastHurtByMob", living);
                if (get.getReturnType() != living) {
                    throw new NoSuchMethodException("Unexpected type of " + LAST_HURT_BY_MOB);
                }
                setLastHurtByMob = set;
                getLastHurtByMob = get;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                report.missing(LAST_HURT_BY_MOB, failure);
            }
            try {
                Method get = living.getMethod("getLastHurtMob");
                Method set = living.getMethod("setLastHurtMob", entity);
                if (get.getReturnType() != living) {
                    throw new NoSuchMethodException("Unexpected type of " + LAST_HURT_MOB);
                }
                setLastHurtMob = set;
                getLastHurtMob = get;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                report.missing(LAST_HURT_MOB, failure);
            }
            try {
                Field candidate = serverPlayer.getDeclaredField("currentExplosionCause");
                if (candidate.getType() != entity || !instanceField(candidate)) {
                    throw new NoSuchFieldException("Unexpected type of " + EXPLOSION_CAUSE);
                }
                candidate.setAccessible(true);
                explosionCause = candidate;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                report.missing(EXPLOSION_CAUSE, failure);
            }
        }

        private Class<?> type(int index, String name, ClassLoader loader) throws ClassNotFoundException {
            return types.length == 0 ? Class.forName(name, false, loader) : types[index];
        }

        private static boolean instanceField(Field field) {
            return !Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers());
        }
    }
}
