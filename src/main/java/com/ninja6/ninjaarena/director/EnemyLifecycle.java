package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.TeamScores;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Enemy maintenance, kill scoring and arena teardown. Call only on the server thread. */
public final class EnemyLifecycle implements Listener {

    private final Supplier<List<MatchView>> matches;
    private final Function<MatchView, Optional<MatchDirector>> directors;
    private final Function<String, Optional<ArenaBounds>> bounds;
    private final Supplier<World> world;
    private final TeamScores scores;
    private final EnemyCosts costs;
    private final LongSupplier clock;
    private BukkitTask loop;

    public EnemyLifecycle(Supplier<List<MatchView>> matches,
            Function<MatchView, Optional<MatchDirector>> directors,
            Function<String, Optional<ArenaBounds>> bounds, Supplier<World> world,
            TeamScores scores, EnemyCosts costs, LongSupplier clock) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.directors = Objects.requireNonNull(directors, "directors");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.world = Objects.requireNonNull(world, "world");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.costs = Objects.requireNonNull(costs, "costs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Runs between rounds too, and independently of a failed spawn loop. */
    public void start(Plugin plugin) {
        if (loop == null) {
            loop = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
        }
    }

    void tick() {
        long now = clock.getAsLong();
        for (MatchView match : matches.get()) {
            bounds.apply(match.arena()).ifPresent(box -> {
                for (MobSpawner.MobEnemy enemy : enemies(match)) {
                    if (enemy.isAlive() && enemy.motion().expired(enemy.mob().getLocation(), world.get(), box, now)) {
                        enemy.mob().remove();
                    }
                }
            });
        }
    }

    /** Paper can ignite undead in daylight despite their should-burn-in-day flag. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        // Block and entity sources (lava, fire and attacks) must still ignite enemies.
        if (event.getClass() != EntityCombustEvent.class) {
            return;
        }
        for (MatchView match : matches.get()) {
            for (MobSpawner.MobEnemy enemy : enemies(match)) {
                if (enemy.mob().equals(event.getEntity())) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        for (MatchView match : matches.get()) {
            for (MobSpawner.MobEnemy enemy : enemies(match)) {
                if (!enemy.mob().equals(event.getEntity())) {
                    continue;
                }
                event.getDrops().clear();
                event.setDroppedExp(0);
                if (enemy.claimDeath()) {
                    Player killer = event.getEntity().getKiller();
                    if (killer != null && match.phase().isFighting() && match.isAlive(killer.getUniqueId())) {
                        scores.addPoints(match, match.teamOf(killer.getUniqueId()), enemy.kind().cost(costs));
                    }
                }
                return;
            }
        }
    }

    /** Runs before DirectorService discards the match's only enemy registry. */
    @EventHandler(priority = EventPriority.LOW)
    public void onMatchEnd(MatchEndEvent event) {
        teardown(event.match());
    }

    void teardown(MatchView match) {
        // Escaped enemies belong to this match too, even outside the cleanup box.
        for (MobSpawner.MobEnemy enemy : enemies(match)) {
            enemy.mob().remove();
        }
        bounds.apply(match.arena()).ifPresent(box -> {
            World arenaWorld = world.get();
            for (Entity entity : arenaWorld.getEntities()) {
                Location at = entity.getLocation();
                if ((entity instanceof Mob || entity instanceof Projectile || entity instanceof Item
                        || entity instanceof ExperienceOrb)
                        && box.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
                    entity.remove();
                }
            }
        });
        // MatchService resets the arena and releases its existing tickets on close.
    }

    /** Called before the directors close, so disable also removes escaped enemies. */
    public void close() {
        if (loop != null) {
            loop.cancel();
            loop = null;
        }
        matches.get().forEach(this::teardown);
    }

    private List<MobSpawner.MobEnemy> enemies(MatchView match) {
        return directors.apply(match).map(director -> director.enemies().stream()
                .filter(MobSpawner.MobEnemy.class::isInstance).map(MobSpawner.MobEnemy.class::cast).toList())
                .orElseGet(List::of);
    }
}
