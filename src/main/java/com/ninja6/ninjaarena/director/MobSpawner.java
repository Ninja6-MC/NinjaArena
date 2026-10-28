package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.match.MatchView;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/**
 * Spawns the director's enemies as vanilla mobs at the arena's enemy spawns, through
 * {@link SpawnPlacement}. Nothing is spawned when no spawn point is hidden and clear. Call on
 * the server thread.
 */
public final class MobSpawner implements MatchDirector.Spawner {

    private final World world;
    private final SpawnPlacement placement;
    private final Function<UUID, Player> players;

    public MobSpawner(World world, SpawnPlacement placement, Function<UUID, Player> players) {
        this.world = Objects.requireNonNull(world, "world");
        this.placement = Objects.requireNonNull(placement, "placement");
        this.players = Objects.requireNonNull(players, "players");
    }

    /**
     * A spawner for each match, placing enemies at its arena's spawns. A match whose arena is
     * gone gets a spawner that spawns nothing, and a warning is logged.
     */
    public static Function<MatchView, MatchDirector.Spawner> factory(ArenaRegistry arenas, Supplier<World> world,
            Function<UUID, Player> players, Logger logger) {
        return match -> arenas.get(match.arena())
                .<MatchDirector.Spawner>map(arena -> new MobSpawner(world.get(), new SpawnPlacement(arena), players))
                .orElseGet(() -> {
                    logger.warning("Arena " + match.arena() + " is gone, so its match gets no enemies.");
                    return (kind, target) -> Optional.empty();
                });
    }

    @Override
    public Optional<MatchDirector.Enemy> spawn(EnemyKind kind, UUID target) {
        Optional<Location> at = placement.next(world, kind.width(), kind.height());
        if (at.isEmpty()) {
            return Optional.empty();
        }
        Mob mob = world.spawn(at.get(), kind.type());
        if (!mob.isValid()) {
            // Another plugin cancelled the spawn.
            return Optional.empty();
        }
        MobEnemy enemy = new MobEnemy(kind, mob, players);
        enemy.target(target);
        return Optional.of(enemy);
    }

    /** A spawned mob as the director sees it. */
    static final class MobEnemy implements MatchDirector.Enemy {

        private final EnemyKind kind;
        private final Mob mob;
        private final Function<UUID, Player> players;

        MobEnemy(EnemyKind kind, Mob mob, Function<UUID, Player> players) {
            this.kind = kind;
            this.mob = mob;
            this.players = players;
        }

        @Override
        public EnemyKind kind() {
            return kind;
        }

        @Override
        public boolean isAlive() {
            return mob.isValid() && !mob.isDead();
        }

        @Override
        public UUID target() {
            return mob.getTarget() instanceof Player player ? player.getUniqueId() : null;
        }

        @Override
        public void target(UUID playerId) {
            Player player = playerId == null ? null : players.apply(playerId);
            mob.setTarget(player != null && player.isOnline() ? player : null);
        }
    }
}
