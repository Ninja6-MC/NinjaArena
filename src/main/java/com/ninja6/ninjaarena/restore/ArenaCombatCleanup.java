package com.ninja6.ninjaarena.restore;

import com.ninja6.ninjaarena.world.ArenaWorld;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/** Drops completed arena combat references without changing health or unrelated combat. */
public final class ArenaCombatCleanup implements Consumer<Player> {
    @FunctionalInterface
    interface InternalCleanup {
        void clear(Player player) throws ReflectiveOperationException;
    }

    private final Logger logger;
    private final InternalCleanup internal;
    private boolean warned;

    public ArenaCombatCleanup(Logger logger) {
        this(logger, new PaperDamageSource());
    }

    ArenaCombatCleanup(Logger logger, InternalCleanup internal) {
        this.logger = logger;
        this.internal = internal;
    }

    @Override
    @SuppressWarnings("removal") // The public setter is still the supported way to release the event.
    public void accept(Player player) {
        try {
            EntityDamageEvent event = player.getLastDamageCause();
            if (event != null && (arena(event.getDamageSource().getCausingEntity())
                    || arena(event.getDamageSource().getDirectEntity())
                    || event instanceof EntityDamageByBlockEvent block
                    && (arenaBlock(block.getDamager()) || arenaBlock(block.getDamagerBlockState())))) {
                player.setLastDamageCause(null);
            }
            internal.clear(player);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (!warned) {
                warned = true;
                logger.log(Level.WARNING, "Arena combat reference cleanup is unavailable; retired worlds may"
                        + " remain retained by player combat history on this server version.", failure);
            }
        }
    }

    static boolean arena(Entity entity) {
        return entity != null && arenaWorld(entity.getWorld());
    }

    static boolean arenaBlock(Object block) {
        return block instanceof Block value && arenaWorld(value.getWorld())
                || block instanceof BlockState state && arenaWorld(state.getWorld());
    }

    private static boolean arenaWorld(World world) {
        return world != null && ArenaWorld.NAME.equals(world.getName());
    }

    /** Paper 26.3's expiry getter hides this field without clearing its strong entity references. */
    static final class PaperDamageSource implements InternalCleanup {
        private final Class<?>[] types;
        private Field lastDamage;
        private Method handle;
        private Method bukkitEntity;
        private Method[] sources;
        private Method block;
        private Method snapshot;

        PaperDamageSource(Class<?>... types) {
            this.types = types;
        }

        @Override
        public void clear(Player player) throws ReflectiveOperationException {
            if (lastDamage == null) {
                ClassLoader loader = player.getClass().getClassLoader();
                Class<?> living = types.length == 0
                        ? Class.forName("net.minecraft.world.entity.LivingEntity", false, loader) : types[0];
                Class<?> damage = types.length == 0
                        ? Class.forName("net.minecraft.world.damagesource.DamageSource", false, loader) : types[1];
                Class<?> entity = types.length == 0
                        ? Class.forName("net.minecraft.world.entity.Entity", false, loader) : types[2];
                Method candidateHandle = player.getClass().getMethod("getHandle");
                Field candidateField = living.getDeclaredField("lastDamageSource");
                Method candidateBukkit = entity.getMethod("getBukkitEntity");
                Method candidateBlock = damage.getMethod("eventBlockDamager");
                Method candidateSnapshot = damage.getMethod("causingBlockSnapshot");
                Method[] candidateSources = {damage.getMethod("getEntity"), damage.getMethod("getDirectEntity"),
                    damage.getMethod("eventEntityDamager")};
                if (!living.isAssignableFrom(candidateHandle.getReturnType()) || candidateField.getType() != damage
                        || Modifier.isStatic(candidateField.getModifiers())
                        || Modifier.isFinal(candidateField.getModifiers())
                        || !Entity.class.isAssignableFrom(candidateBukkit.getReturnType())
                        || candidateBlock.getReturnType() != Block.class
                        || candidateSnapshot.getReturnType() != BlockState.class) {
                    throw new NoSuchFieldException("Unsupported Paper combat reference layout");
                }
                for (Method source : candidateSources) {
                    if (source.getReturnType() != entity || Modifier.isStatic(source.getModifiers())) {
                        throw new NoSuchMethodException("Unsupported Paper damage source layout");
                    }
                }
                candidateField.setAccessible(true);
                handle = candidateHandle;
                bukkitEntity = candidateBukkit;
                sources = candidateSources;
                block = candidateBlock;
                snapshot = candidateSnapshot;
                lastDamage = candidateField;
            }
            Object owner = handle.invoke(player);
            Object damage = lastDamage.get(owner);
            if (damage == null) {
                return;
            }
            if (arenaBlock(block.invoke(damage)) || arenaBlock(snapshot.invoke(damage))) {
                lastDamage.set(owner, null);
                return;
            }
            for (Method source : sources) {
                Object entity = source.invoke(damage);
                if (entity != null && arena((Entity) bukkitEntity.invoke(entity))) {
                    lastDamage.set(owner, null);
                    return;
                }
            }
        }
    }
}
