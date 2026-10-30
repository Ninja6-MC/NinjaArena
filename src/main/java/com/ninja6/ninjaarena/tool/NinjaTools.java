package com.ninja6.ninjaarena.tool;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundEndEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * The smoke bomb, shuriken and dash ({@link NinjaTool}). A tool works only for a player who
 * is in a match, alive in the round and not sheltered (COMBAT or OVERRUN); any other use is
 * cancelled and the item kept. Kits hand the tools out every round, and the inventory
 * lockdown keeps them in the arena.
 *
 * <ul>
 *   <li><b>Smoke bomb:</b> where it lands, a cloud blinds rival players who are alive in the
 *   round. Teammates, the thrower, eliminated players and anyone outside the match are never
 *   affected, whatever the friendly-fire setting.
 *   <li><b>Shuriken:</b> a hit is applied through the server's damage path, so the
 *   sheltered, out-of-round and friendly-fire rules decide it as for any other attack. A rival
 *   it damages glows and is {@linkplain #isMarked(UUID) marked} for a few seconds.
 *   <li><b>Dash:</b> sets, never adds to, the player's velocity: forward along their facing,
 *   shortened so it cannot carry them out of the arena, and refused while gliding, flying,
 *   riptiding or riding. The vanilla item cooldown shows when it is ready again.
 * </ul>
 *
 * <p>Flying tools, clouds, cooldowns and marks are discarded when a round or match ends, when
 * the player leaves or is eliminated, and on {@link #close()}.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class NinjaTools implements Listener {

    /** Applies a shuriken hit; {@link NinjaTools#damage} on a server. */
    @FunctionalInterface
    public interface Strike {
        void hit(LivingEntity victim, double damage, Projectile shuriken, Player thrower);
    }

    static final int TICKS_PER_SECOND = 20;
    /** How often a cloud renews Blindness on a rival who stays in it. */
    static final int SMOKE_REAPPLY_TICKS = 10;
    /** How far above or below its centre a smoke cloud hides an enemy's view, in blocks. */
    public static final double SMOKE_REACH = 2;
    /** Horizontal speed kept per tick in the air; a burst of {@code v} covers under {@code v / 0.09} blocks. */
    private static final double AIR_DRAG = 0.91;
    /** Room kept between the end of a dash and the arena's edge. */
    private static final double EDGE_MARGIN = 0.5;

    private record Thrown(NinjaTool tool, UUID thrower, String arena, Projectile projectile) {
    }

    private record Smoke(UUID thrower, String arena, int team, AreaEffectCloud cloud) {
    }

    private final MatchService matches;
    private final Function<UUID, Player> players;
    private final Function<String, Optional<ArenaBounds>> bounds;
    private ToolSettings settings;
    private final LongSupplier ticks;
    private final Strike strike;
    private final Function<Location, AreaEffectCloud> clouds;

    private final Map<UUID, Thrown> thrown = new HashMap<>();
    private final Map<UUID, Smoke> smoke = new HashMap<>();
    /** The tick at which each player may dash again. */
    private final Map<UUID, Long> dashReady = new HashMap<>();
    /** The tick at which each marked player's mark runs out. */
    private final Map<UUID, Long> marks = new HashMap<>();

    /**
     * @param players {@code Server#getPlayer}
     * @param bounds  the bounds of an arena by name
     * @param ticks   the current server tick, {@code Bukkit#getCurrentTick}
     * @param strike  applies a shuriken hit, {@link #damage} on a server
     * @param clouds  spawns an area effect cloud at a location
     */
    public NinjaTools(MatchService matches, Function<UUID, Player> players,
            Function<String, Optional<ArenaBounds>> bounds, ToolSettings settings, LongSupplier ticks, Strike strike,
            Function<Location, AreaEffectCloud> clouds) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.players = Objects.requireNonNull(players, "players");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        this.strike = Objects.requireNonNull(strike, "strike");
        this.clouds = Objects.requireNonNull(clouds, "clouds");
    }

    /** Damages through the server's damage path, with the shuriken as the direct cause. */
    public static void damage(LivingEntity victim, double damage, Projectile shuriken, Player thrower) {
        victim.damage(damage, DamageSource.builder(DamageType.THROWN).withDirectEntity(shuriken)
                .withCausingEntity(thrower).build());
    }

    /** New uses take the new values; existing durations and cooldown deadlines keep their values. */
    public void setSettings(ToolSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Registers every tool's kit item, such as {@code ninjaarena:shuriken}. */
    public static void registerItems(KitItems items) {
        for (NinjaTool tool : NinjaTool.values()) {
            items.register(tool.kitId(), tool == NinjaTool.DASH ? 1 : 16, player -> tool.create());
        }
    }

    /**
     * Whether a shuriken hit the player in the last few seconds; for enemy targeting. False
     * once the mark runs out, at the end of the round, or when the player leaves or is out.
     */
    public boolean isMarked(UUID player) {
        Long until = marks.get(player);
        if (until == null) {
            return false;
        }
        if (ticks.getAsLong() >= until) {
            marks.remove(player);
            return false;
        }
        return true;
    }

    /**
     * Whether a place is inside a live smoke bomb cloud: within its radius across and
     * {@value #SMOKE_REACH} blocks above or below its centre; for enemy targeting.
     */
    public boolean inSmoke(Location at) {
        if (smoke.isEmpty() || at.getWorld() == null) {
            return false;
        }
        for (Smoke cloud : smoke.values()) {
            AreaEffectCloud entity = cloud.cloud();
            double radius = entity.getRadius();
            if (!entity.isValid()) {
                continue;
            }
            Location centre = entity.getLocation();
            double dx = at.getX() - centre.getX();
            double dz = at.getZ() - centre.getZ();
            if (at.getWorld().equals(centre.getWorld()) && Math.abs(at.getY() - centre.getY()) <= SMOKE_REACH
                    && dx * dx + dz * dz <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    /**
     * Blocks every tool for a player who may not use one now, and dashes. Right-clicking
     * air arrives already marked as not using a block, so cancelled events are seen too.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        Optional<NinjaTool> tool = NinjaTool.of(event.getItem());
        if (tool.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (!usable(player)) {
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }
        if (tool.get() == NinjaTool.DASH) {
            event.setUseItemInHand(Event.Result.DENY);
            dash(player);
        }
    }

    /** A sheltered, out or outside player's throw is cancelled, which keeps the item. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunchAttempt(PlayerLaunchProjectileEvent event) {
        Optional<NinjaTool> tool = NinjaTool.of(event.getItemStack());
        if (tool.isPresent() && tool.get().thrown() && !usable(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(PlayerLaunchProjectileEvent event) {
        Optional<NinjaTool> tool = NinjaTool.of(event.getItemStack());
        Player player = event.getPlayer();
        if (tool.isEmpty() || !tool.get().thrown() || !usable(player)) {
            return;
        }
        Projectile projectile = event.getProjectile();
        String arena = matches.matchOf(player.getUniqueId()).orElseThrow().arena();
        thrown.put(projectile.getUniqueId(), new Thrown(tool.get(), player.getUniqueId(), arena, projectile));
    }

    /**
     * A tool lands: the smoke bomb leaves its cloud and the shuriken strikes what it hit.
     * Either way the projectile is removed instead of landing as a vanilla snowball. Nothing
     * happens if the thrower has since left, been eliminated, or the fight has stopped.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        Thrown shot = thrown.get(projectile.getUniqueId());
        if (shot == null) {
            return;
        }
        event.setCancelled(true);
        try {
            Player thrower = players.apply(shot.thrower());
            if (thrower == null || !usable(thrower) || !inArena(shot.thrower(), shot.arena())) {
                return;
            }
            switch (shot.tool()) {
                case SMOKE_BOMB -> smoke(shot, projectile.getLocation());
                case SHURIKEN -> {
                    if (event.getHitEntity() instanceof LivingEntity victim
                            && !victim.getUniqueId().equals(shot.thrower())) {
                        strike.hit(victim, settings.shurikenDamage(), projectile, thrower);
                    }
                }
                case DASH -> {
                    // Never thrown.
                }
            }
        } finally {
            thrown.remove(projectile.getUniqueId());
            projectile.remove();
        }
    }

    /** Marks a rival a shuriken actually damaged, after every damage rule has had its say. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShurikenDamage(EntityDamageByEntityEvent event) {
        Thrown shot = thrown.get(event.getDamager().getUniqueId());
        if (shot == null || shot.tool() != NinjaTool.SHURIKEN || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Optional<MatchView> match = matches.match(shot.arena());
        int team = match.map(view -> view.teamOf(shot.thrower())).orElse(0);
        if (team == 0 || !rival(match, team, victim)) {
            return;
        }
        int duration = settings.glowSeconds() * TICKS_PER_SECOND;
        marks.put(victim.getUniqueId(), ticks.getAsLong() + duration);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0, false, false, true));
    }

    /** Smoke reaches only rival players alive in the round, whatever else the cloud covers. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        Smoke cloud = smoke.get(event.getEntity().getUniqueId());
        if (cloud == null) {
            return;
        }
        Optional<MatchView> match = matches.match(cloud.arena());
        event.getAffectedEntities().removeIf(entity -> !(entity instanceof Player player)
                || !rival(match, cloud.team(), player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(RoundEndEvent event) {
        clear(event.match());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        clear(event.match());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEliminated(PlayerEliminatedEvent event) {
        discard(event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(PlayerLeaveMatchEvent event) {
        discard(event.player());
    }

    /** Removes every flying tool and cloud and drops all cooldowns and marks. Called on plugin disable. */
    public void close() {
        removeEntities(thrown, shot -> true);
        removeEntities(smoke, cloud -> true);
        Set<UUID> tracked = new HashSet<>(dashReady.keySet());
        tracked.addAll(marks.keySet());
        tracked.forEach(this::forget);
    }

    private boolean usable(Player player) {
        UUID id = player.getUniqueId();
        return !player.isDead() && matches.isInMatch(id) && !matches.isDisarmed(id) && !matches.isSheltered(id);
    }

    private boolean inArena(UUID player, String arena) {
        return matches.matchOf(player).filter(match -> match.arena().equals(arena)).isPresent();
    }

    private static boolean rival(Optional<MatchView> match, int team, Player player) {
        UUID id = player.getUniqueId();
        return match.filter(view -> view.isAlive(id) && view.teamOf(id) != team).isPresent();
    }

    private void smoke(Thrown shot, Location at) {
        int team = matches.matchOf(shot.thrower()).map(match -> match.teamOf(shot.thrower())).orElse(0);
        smoke.values().removeIf(cloud -> !cloud.cloud().isValid());
        AreaEffectCloud cloud = clouds.apply(at);
        cloud.setRadius((float) settings.smokeRadius());
        cloud.setRadiusOnUse(0);
        cloud.setRadiusPerTick(0);
        cloud.setDuration(settings.smokeSeconds() * TICKS_PER_SECOND);
        cloud.setDurationOnUse(0);
        cloud.setWaitTime(0);
        cloud.setReapplicationDelay(SMOKE_REAPPLY_TICKS);
        cloud.setParticle(Particle.LARGE_SMOKE);
        cloud.addCustomEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                settings.blindnessSeconds() * TICKS_PER_SECOND, 0, false, false, true), true);
        Player thrower = players.apply(shot.thrower());
        if (thrower != null) {
            cloud.setSource(thrower);
        }
        smoke.put(cloud.getUniqueId(), new Smoke(shot.thrower(), shot.arena(), team, cloud));
    }

    private void dash(Player player) {
        UUID id = player.getUniqueId();
        long now = ticks.getAsLong();
        Long ready = dashReady.get(id);
        if (ready != null && now < ready) {
            return;
        }
        if (player.isGliding() || player.isFlying() || player.isRiptiding() || player.isInsideVehicle()) {
            return;
        }
        Location at = player.getLocation();
        Optional<ArenaBounds> box = matches.matchOf(id).flatMap(match -> bounds.apply(match.arena()));
        if (box.isEmpty() || !box.get().contains(at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
            return;
        }
        ArenaBounds arena = box.get();
        double yaw = Math.toRadians(at.getYaw());
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        double speed = Math.min(settings.dashStrength(), Math.min(
                reach(at.getX(), dx, arena.minX(), arena.maxX() + 1),
                reach(at.getZ(), dz, arena.minZ(), arena.maxZ() + 1)));
        player.setVelocity(new Vector(dx * speed, settings.dashLift(), dz * speed));
        player.setFallDistance(0);
        int cooldown = settings.dashCooldownSeconds() * TICKS_PER_SECOND;
        dashReady.put(id, now + cooldown);
        player.setCooldown(NinjaTool.DASH.material(), cooldown);
    }

    /** The fastest burst along one axis that still stops inside {@code [min, max]}. */
    static double reach(double position, double direction, double min, double max) {
        if (Math.abs(direction) < 1e-9) {
            return Double.MAX_VALUE;
        }
        double room = (direction > 0 ? max - position : position - min) - EDGE_MARGIN;
        return Math.max(0, room) * (1 - AIR_DRAG) / Math.abs(direction);
    }

    private void clear(MatchView match) {
        String arena = match.arena();
        removeEntities(thrown, shot -> shot.arena().equals(arena));
        removeEntities(smoke, cloud -> cloud.arena().equals(arena));
        match.players().forEach(this::forget);
    }

    /** Removes one player's flying tools and clouds and drops their cooldown and mark. */
    private void discard(UUID player) {
        removeEntities(thrown, shot -> shot.thrower().equals(player));
        removeEntities(smoke, cloud -> cloud.thrower().equals(player));
        forget(player);
    }

    private void forget(UUID id) {
        boolean cooling = dashReady.remove(id) != null;
        boolean marked = marks.remove(id) != null;
        Player player = players.apply(id);
        if (player == null) {
            return;
        }
        if (cooling) {
            player.setCooldown(NinjaTool.DASH.material(), 0);
        }
        if (marked) {
            player.removePotionEffect(PotionEffectType.GLOWING);
        }
    }

    private static <T> void removeEntities(Map<UUID, T> tracked, Predicate<T> which) {
        List<T> removed = new ArrayList<>();
        tracked.values().removeIf(value -> which.test(value) && removed.add(value));
        for (T value : removed) {
            Entity entity = switch (value) {
                case Thrown shot -> shot.projectile();
                case Smoke cloud -> cloud.cloud();
                default -> null;
            };
            if (entity != null) {
                entity.remove();
            }
        }
    }
}
