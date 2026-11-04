package com.ninja6.ninjaarena.director;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.match.MatchView;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Readies the Shadow Stalker, a vanilla wither skeleton changed only through attributes,
 * equipment, particles and Paper Mob Goals, so Java and Bedrock clients both show it without
 * a resource pack.
 *
 * <ul>
 *   <li><b>Body:</b> health, melee damage, speed and size come from {@link StalkerSettings}.
 *   It holds a netherite sword with no attribute modifiers, so the damage setting is all a hit
 *   deals; {@code EnemyLifecycle} also keeps its hits from withering.
 *   <li><b>Target:</b> its vanilla target goals are removed, so only the director picks whom
 *   it hunts. Stealth does not fool it through the {@code ignores-stealth} setting.
 *   <li><b>Blink:</b> a custom goal. When its target is out of reach but within the blink
 *   range, it stands still with a particle tell, then lands behind the target, inside the
 *   arena, and makes them glow.
 * </ul>
 *
 * <p>Holds no mob between calls; each goal holds its own mob and goes with it when the mob is
 * removed. Call on the server thread.
 */
public final class ShadowStalker {

    static final GoalKey<Mob> BLINK = GoalKey.of(Mob.class, new NamespacedKey("ninjaarena", "shadow_stalker_blink"));
    /** Ahead of the skeleton's melee goal, so a tell holds the Stalker still. */
    static final int BLINK_PRIORITY = 1;
    /** Enough to walk across an arena cell after its target. */
    static final double FOLLOW_RANGE = 64;

    private final StalkerSettings settings;
    private final MobGoals goals;
    private final LongSupplier ticks;
    private final Supplier<ItemStack> blade;

    /**
     * @param goals {@code Bukkit#getMobGoals}
     * @param ticks the current server tick, {@code Bukkit#getCurrentTick}
     */
    public ShadowStalker(StalkerSettings settings, MobGoals goals, LongSupplier ticks) {
        this(settings, goals, ticks, ShadowStalker::blade);
    }

    ShadowStalker(StalkerSettings settings, MobGoals goals, LongSupplier ticks, Supplier<ItemStack> blade) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.goals = Objects.requireNonNull(goals, "goals");
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        this.blade = Objects.requireNonNull(blade, "blade");
    }

    public StalkerSettings settings() {
        return settings;
    }

    /** The size multiplier of an enemy of the kind, for its spawn hitbox. */
    double scale(EnemyKind kind) {
        return kind == EnemyKind.SHADOW_STALKER ? settings.scale() : 1;
    }

    /** A netherite sword stripped of its attribute modifiers: looks the part, adds no damage. */
    static ItemStack blade() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.unsetData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        return sword;
    }

    /** Sets the attributes and equipment, before the mob joins the world. */
    void prepare(Mob mob) {
        base(mob, Attribute.MAX_HEALTH, settings.health());
        mob.setHealth(settings.health());
        base(mob, Attribute.ATTACK_DAMAGE, settings.damage());
        base(mob, Attribute.MOVEMENT_SPEED, settings.speed());
        base(mob, Attribute.SCALE, settings.scale());
        base(mob, Attribute.FOLLOW_RANGE, FOLLOW_RANGE);
        EntityEquipment equipment = mob.getEquipment();
        // A skeleton picks its melee goal from what it holds, so the sword also makes it fight close.
        equipment.setItemInMainHand(blade.get());
        equipment.setItemInMainHandDropChance(0);
    }

    /**
     * Replaces the target goals and adds the blink, once the mob is in the world. Without
     * bounds there is nowhere it may land, so it gets no blink.
     */
    void arm(Mob mob, MatchView match, ArenaBounds bounds) {
        goals.removeAllGoals(mob, GoalType.TARGET);
        if (bounds == null) {
            return;
        }
        goals.addGoal(mob, BLINK_PRIORITY, new BlinkGoal(mob, match, bounds,
                new BlinkCycle(settings, ticks.getAsLong())));
    }

    private static void base(Mob mob, Attribute attribute, double value) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** The blink: a tell while standing still, then a jump behind the target. */
    final class BlinkGoal implements Goal<Mob> {

        private final Mob mob;
        private final MatchView match;
        private final ArenaBounds bounds;
        private final BlinkCycle cycle;
        private Player chased;

        BlinkGoal(Mob mob, MatchView match, ArenaBounds bounds, BlinkCycle cycle) {
            this.mob = mob;
            this.match = match;
            this.bounds = bounds;
            this.cycle = cycle;
        }

        BlinkCycle cycle() {
            return cycle;
        }

        @Override
        public boolean shouldActivate() {
            long now = ticks.getAsLong();
            if (!cycle.ready(now)) {
                return false;
            }
            Player target = target();
            if (target == null) {
                return false;
            }
            double distance = mob.getLocation().distance(target.getLocation());
            return BlinkPlan.outOfReach(distance, settings, () -> cycle.mayCheckPath(now) && !walkable(target));
        }

        @Override
        public boolean shouldStayActive() {
            return cycle.telling() && chased != null && chased.equals(target());
        }

        @Override
        public void start() {
            chased = target();
            if (chased == null) {
                return;
            }
            cycle.tell(ticks.getAsLong());
            mob.getPathfinder().stopPathfinding();
            tell();
        }

        @Override
        public void tick() {
            if (chased == null || !cycle.telling()) {
                return;
            }
            mob.lookAt(chased);
            long now = ticks.getAsLong();
            if (cycle.tellOver(now)) {
                blink(now);
            } else {
                tell();
            }
        }

        @Override
        public void stop() {
            if (cycle.telling()) {
                cycle.missed(ticks.getAsLong());
            }
            chased = null;
        }

        @Override
        public GoalKey<Mob> getKey() {
            return BLINK;
        }

        @Override
        public EnumSet<GoalType> getTypes() {
            return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
        }

        /** Its target, while it is a player alive in the round of a fight in the Stalker's world. */
        private Player target() {
            if (!match.phase().isFighting() || !(mob.getTarget() instanceof Player player) || !player.isOnline()
                    || player.isDead() || !match.isAlive(player.getUniqueId())
                    || !player.getWorld().equals(mob.getWorld())) {
                return null;
            }
            return player;
        }

        private boolean walkable(Player target) {
            Pathfinder.PathResult path = mob.getPathfinder().findPath(target);
            if (path == null) {
                return false;
            }
            Location end = path.getFinalPoint();
            double endToTarget = end == null ? Double.POSITIVE_INFINITY : end.distance(target.getLocation());
            return BlinkPlan.reaches(true, path.canReachFinalPoint(), endToTarget);
        }

        private void blink(long now) {
            World world = mob.getWorld();
            Location from = mob.getLocation();
            Location at = chased.getLocation();
            double width = EnemyKind.SHADOW_STALKER.width() * settings.scale();
            double height = EnemyKind.SHADOW_STALKER.height() * settings.scale();
            Optional<BlinkPlan.Spot> spot = BlinkPlan.land(at.getX(), at.getY(), at.getZ(), at.getYaw(),
                    settings.behindDistance(), candidate -> BlinkPlan.inside(bounds, candidate, width, height)
                            && clear(world, candidate, width, height));
            if (spot.isEmpty()) {
                cycle.missed(now);
                return;
            }
            Location to = new Location(world, spot.get().x(), spot.get().y(), spot.get().z(), spot.get().yaw(), 0);
            if (!mob.teleport(to)) {
                cycle.missed(now);
                return;
            }
            puff(world, from, height);
            puff(world, to, height);
            chased.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, settings.glowTicks(), 0, false, false,
                    true));
            mob.setTarget(chased);
            cycle.blinked(now);
        }

        /** Room for the body, nothing liquid at its feet, and something to stand on. */
        private static boolean clear(World world, BlinkPlan.Spot spot, double width, double height) {
            Location feet = new Location(world, spot.x(), spot.y(), spot.z());
            if (SpawnPlacement.fits(world, feet, width, height) != null) {
                return false;
            }
            int x = feet.getBlockX();
            int y = feet.getBlockY();
            int z = feet.getBlockZ();
            if (y - 1 < world.getMinHeight() || world.getBlockAt(x, y, z).isLiquid()) {
                return false;
            }
            Block ground = world.getBlockAt(x, y - 1, z);
            return !ground.getCollisionShape().getBoundingBoxes().isEmpty();
        }

        private void tell() {
            Location body = mob.getLocation().add(0, EnemyKind.SHADOW_STALKER.height() * settings.scale() / 2, 0);
            mob.getWorld().spawnParticle(Particle.PORTAL, body, 6, 0.3, 0.6, 0.3, 0.4);
        }

        private static void puff(World world, Location at, double height) {
            world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, height / 2, 0), 16, 0.3, height / 3, 0.3,
                    0.02);
        }
    }
}
