package com.ninja6.ninjaarena.director;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.match.MatchView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;

/**
 * Spawns the director's enemies as vanilla mobs at the arena's enemy spawns, through
 * {@link SpawnPlacement}. Nothing is spawned when no spawn point is hidden and clear. Only
 * the match's players alive in the round, online and in the arena world, can refuse a point;
 * spectators, eliminated players and anyone outside the match never do. Call on the server
 * thread.
 */
public final class MobSpawner implements MatchDirector.Spawner {

    private final World world;
    private final SpawnPlacement placement;
    private final ArenaBounds bounds;
    private final MatchView match;
    private final Function<UUID, Player> players;
    private final SpawnDiagnostics diagnostics;
    private final ShadowStalker stalkers;

    private final Supplier<ItemStack> bow;

    /**
     * @param bounds   the arena's bounds, which a Shadow Stalker's blink never leaves; null
     *                 leaves Stalkers without the blink
     * @param stalkers readies each Shadow Stalker
     */
    public MobSpawner(World world, SpawnPlacement placement, ArenaBounds bounds, MatchView match,
            Function<UUID, Player> players, SpawnDiagnostics diagnostics, ShadowStalker stalkers) {
        this(world, placement, bounds, match, players, diagnostics, stalkers, () -> new ItemStack(Material.BOW));
    }

    MobSpawner(World world, SpawnPlacement placement, ArenaBounds bounds, MatchView match,
            Function<UUID, Player> players, SpawnDiagnostics diagnostics, ShadowStalker stalkers,
            Supplier<ItemStack> bow) {
        this.world = Objects.requireNonNull(world, "world");
        this.placement = Objects.requireNonNull(placement, "placement");
        this.bounds = bounds;
        this.match = Objects.requireNonNull(match, "match");
        this.players = Objects.requireNonNull(players, "players");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.stalkers = Objects.requireNonNull(stalkers, "stalkers");
        this.bow = Objects.requireNonNull(bow, "bow");
    }

    /**
     * A spawner for each match, placing enemies at its arena's spawns and explaining refused
     * or cancelled spawns through {@link SpawnDiagnostics}. A match whose arena is gone gets a
     * spawner that spawns nothing, and a warning is logged.
     */
    public static Function<MatchView, MatchDirector.Spawner> factory(ArenaRegistry arenas, Supplier<World> world,
            Function<UUID, Player> players, SpawnSettings settings, ShadowStalker stalkers, Logger logger) {
        return match -> arenas.get(match.arena())
                .<MatchDirector.Spawner>map(arena -> new MobSpawner(world.get(), new SpawnPlacement(arena, settings),
                        arena.bounds(), match, players, new SpawnDiagnostics(match, settings, logger), stalkers))
                .orElseGet(() -> {
                    logger.warning("Arena " + match.arena() + " is gone, so its match gets no enemies.");
                    return (kind, target) -> Optional.empty();
                });
    }

    @Override
    public Optional<MatchDirector.Enemy> spawn(EnemyKind kind, UUID target) {
        double scale = stalkers.scale(kind);
        Optional<Location> at = placement.next(world, alivePlayers(), kind.width() * scale, kind.height() * scale);
        if (at.isEmpty()) {
            diagnostics.refused(kind, placement.refusals());
            return Optional.empty();
        }
        boolean stalker = kind == EnemyKind.SHADOW_STALKER;
        Mob mob = world.spawn(at.get(), kind.type(), false, spawned -> {
            prepare(spawned, bow);
            if (stalker) {
                stalkers.prepare(spawned);
            }
        });
        if (!mob.isValid()) {
            // Another plugin cancelled the spawn.
            diagnostics.cancelled(kind, at.get());
            return Optional.empty();
        }
        if (stalker) {
            stalkers.arm(mob, match, bounds);
        }
        MobEnemy enemy = new MobEnemy(kind, mob, players);
        enemy.target(target);
        return Optional.of(enemy);
    }

    @Override
    public Optional<String> lastRefusal() {
        return diagnostics.last();
    }

    @Override
    public void blocked(MatchDirector.Blockage reason, double targetThreat, double inPlay) {
        diagnostics.blocked(reason, targetThreat, inPlay);
    }

    /** The match's players alive in the round who are online and in the arena world. */
    private List<Player> alivePlayers() {
        List<Player> alive = new ArrayList<>();
        for (UUID id : match.alivePlayers()) {
            Player player = players.apply(id);
            if (player != null && player.isOnline() && player.getWorld() == world) {
                alive.add(player);
            }
        }
        return alive;
    }

    /**
     * Readies a mob before it is added to the world. The arena world's clock is fixed at
     * noon, so undead that burn in daylight (zombies and their husk and drowned variants,
     * and every skeleton) are kept from burning; otherwise they would die in any open arena
     * and the director would keep replacing them. Spawning without randomized data keeps
     * vanilla from adding riders or mounts that the director would not track.
     */
    static void prepare(Mob mob, Supplier<ItemStack> bow) {
        mob.setPersistent(false);
        mob.setCanPickupItems(false);
        if (mob instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
        }
        if (mob instanceof AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(false);
            if (!(skeleton instanceof WitherSkeleton)) {
                // Spawning without randomized data leaves the skeleton unequipped.
                skeleton.getEquipment().setItemInMainHand(bow.get());
            }
        }
    }

    /** A spawned mob as the director sees it. */
    static final class MobEnemy implements MatchDirector.Enemy {

        private final EnemyKind kind;
        private final EnemyMotion motion = new EnemyMotion();
        private boolean deathHandled;
        private final Mob mob;
        private final Function<UUID, Player> players;

        MobEnemy(EnemyKind kind, Mob mob, Function<UUID, Player> players) {
            this.kind = kind;
            this.mob = mob;
            this.players = players;
        }

        Mob mob() {
            return mob;
        }

        EnemyMotion motion() {
            return motion;
        }

        boolean claimDeath() {
            if (deathHandled) {
                return false;
            }
            deathHandled = true;
            return true;
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
