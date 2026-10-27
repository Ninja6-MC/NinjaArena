package com.ninja6.ninjaarena.match;

import com.ninja6.ninjaarena.match.MatchService.Exit;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.Objects;
import org.bukkit.Location;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Feeds what happens to players in a match to {@link MatchService}: deaths, teleports out
 * of the arena world, and disconnects. Outside COMBAT and OVERRUN, players in a match take
 * no damage except from the void or {@code /kill}, so nobody falls forever.
 *
 * <p>Death handling here is deliberately minimal: a death takes the player out of the round
 * and they respawn at their team spawn, where they deal no damage until the next round.
 * Drops and spectating belong to the death-handling task.
 */
public final class MatchListener implements Listener {

    private final MatchService matches;

    public MatchListener(MatchService matches) {
        this.matches = Objects.requireNonNull(matches, "matches");
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (matches.isInMatch(player.getUniqueId())) {
            matches.died(player, player.getKiller());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        matches.respawnPoint(event.getPlayer().getUniqueId()).ifPresent(event::setRespawnLocation);
    }

    /** Also sees vanilla portal travel, whose event is a teleport event. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (matches.isInMatch(player.getUniqueId())
                && (to.getWorld() == null || !ArenaWorld.NAME.equals(to.getWorld().getName()))) {
            matches.leave(player, Exit.TELEPORTED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        matches.leave(event.getPlayer(), Exit.QUIT);
    }
}
