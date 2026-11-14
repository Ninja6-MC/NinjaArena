package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.match.MatchService.Exit;
import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.world.CrossWorldTransfers;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Feeds what happens to players in a match to {@link MatchService}: deaths, teleports that
 * take them out of it ({@link CrossWorldTransfers}), disconnects and rejoins
 * ({@link MatchService#disconnect}, {@link MatchService#rejoin}). Outside COMBAT and OVERRUN, players in a match take
 * no damage except from the void or {@code /kill}, so nobody falls forever.
 *
 * <p>Deaths eliminate players through the match engine; {@link MatchDeathListener} owns
 * inventory retention, team spectating and respawn placement.
 */
public final class MatchListener implements Listener {

    private final MatchService matches;
    private final MatchDeathListener deaths;
    private final CrossWorldTransfers transfers;

    public MatchListener(MatchService matches, MatchDeathListener deaths) {
        this(matches, deaths, new CrossWorldTransfers());
    }

    public MatchListener(MatchService matches, MatchDeathListener deaths, CrossWorldTransfers transfers) {
        this.matches = Objects.requireNonNull(matches, "matches");
        this.deaths = Objects.requireNonNull(deaths, "deaths");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && matches.isSheltered(player.getUniqueId())
                && event.getCause() != DamageCause.VOID && event.getCause() != DamageCause.KILL) {
            event.setCancelled(true);
        }
    }

    /** A player who is out of the round deals no damage, directly or with a projectile. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (damager instanceof Player attacker && matches.isDisarmed(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Tells the match which enemy player hurt whom, directly, with a projectile or with a
     * lingering cloud, once nothing has cancelled the hit and it did damage (a hit a shield
     * fully blocks does not count): the kill credit for a player who disconnects during the
     * fight.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !matches.isInMatch(victim.getUniqueId())
                || event.getFinalDamage() <= 0) {
            return;
        }
        Player attacker = source(event.getDamager());
        if (attacker == null) {
            attacker = source(event.getDamageSource().getCausingEntity());
        }
        if (attacker != null) {
            matches.damaged(victim.getUniqueId(), attacker.getUniqueId());
        }
    }

    private static Player source(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) {
            return player;
        }
        return null;
    }

    /**
     * A death in the lobby world never counts: no damage is taken there, and a fall into its
     * void returns the player to the lobby spawn, so only another plugin can kill a match
     * player there, during a shop phase say.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!event.isCancelled() && matches.isInMatch(player.getUniqueId())
                && !PluginWorlds.isLobby(player.getLocation())) {
            matches.died(player, player.getKiller());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        deaths.placeRespawn(event);
    }

    /** Also sees vanilla portal travel, whose event is a teleport event. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (matches.isInMatch(player.getUniqueId()) && !transfers.keepsMatch(player.getUniqueId(),
                event.getFrom().getWorld(), to.getWorld(), OwnTeleports.isActive(player.getUniqueId()))) {
            matches.leave(player, Exit.TELEPORTED);
        }
    }

    /**
     * Before the room and the lobby see the quit, so that a player held in their match for the
     * rejoin grace keeps their room.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        matches.disconnect(event.getPlayer());
    }

    /** Before the join restore runs, which leaves a player who is back in their match alone. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        matches.rejoin(event.getPlayer());
    }
}
