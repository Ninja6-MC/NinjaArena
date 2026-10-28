package com.ninja6.ninjaarena.director;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;

/**
 * {@link AggroSteering.Senses} on a server, for enemies spawned by {@link MobSpawner}. An
 * enemy it cannot find counts as seeing everyone and as outside any smoke, so it is never
 * hidden from. As a listener it also keeps a mob inside a smoke cloud from picking a new
 * target by itself, so the cloud's effect holds between the director's seconds.
 *
 * <p>Call on the server thread.
 */
public final class WorldSenses implements AggroSteering.Senses, Listener {

    private final Function<UUID, Player> players;
    private final Predicate<Location> smoke;

    /**
     * @param players {@code Server#getPlayer}
     * @param smoke   whether a place is inside a smoke bomb cloud; {@code NinjaTools#inSmoke}
     */
    public WorldSenses(Function<UUID, Player> players, Predicate<Location> smoke) {
        this.players = Objects.requireNonNull(players, "players");
        this.smoke = Objects.requireNonNull(smoke, "smoke");
    }

    @Override
    public boolean crouching(UUID player) {
        Player online = players.apply(player);
        return online != null && online.isSneaking();
    }

    @Override
    public int light(UUID player) {
        Player online = players.apply(player);
        return online == null ? StealthSettings.MAX_LIGHT : online.getLocation().getBlock().getLightLevel();
    }

    @Override
    public boolean sees(MatchDirector.Enemy enemy, UUID player) {
        Mob mob = mob(enemy);
        Player online = players.apply(player);
        if (mob == null || online == null) {
            return true;
        }
        return mob.getWorld().equals(online.getWorld()) && mob.hasLineOfSight(online);
    }

    @Override
    public boolean inSmoke(MatchDirector.Enemy enemy) {
        Mob mob = mob(enemy);
        return mob != null && smoke.test(mob.getLocation());
    }

    /** A mob inside a smoke cloud may drop its target but not take a new one. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() != null && event.getEntity() instanceof Mob mob && smoke.test(mob.getLocation())) {
            event.setCancelled(true);
        }
    }

    private static Mob mob(MatchDirector.Enemy enemy) {
        return enemy instanceof MobSpawner.MobEnemy spawned && spawned.isAlive() ? spawned.mob() : null;
    }
}
