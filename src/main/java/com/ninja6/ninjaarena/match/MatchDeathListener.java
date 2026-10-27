package com.ninja6.ninjaarena.match;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.match.event.PlayerEliminatedEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import com.ninja6.ninjaarena.match.event.RoundStartEvent;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Retains arena inventories on death and keeps eliminated players spectating their team.
 * All access is on the server thread. Respawn placement is delegated here by MatchListener;
 * mode and camera changes wait for Paper's post-respawn event, when the player is alive.
 */
public final class MatchDeathListener implements Listener {

    /** Free movement is limited to this distance from a teammate, or the base if none remain. */
    static final double SPECTATE_RADIUS = 16;

    private final Plugin plugin;
    private final MatchService matches;
    private final ArenaRegistry arenas;
    private final Map<UUID, MatchView> eliminated = new HashMap<>();
    private BukkitTask ticker;

    public MatchDeathListener(Plugin plugin, MatchService matches, ArenaRegistry arenas) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
    }

    public void start() {
        if (ticker == null) {
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }
    }

    public void close() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        eliminated.clear();
    }

    /** Used by the game-mode guard; round winners are never treated as eliminated. */
    public boolean isSpectating(UUID id) {
        MatchView match = eliminated.get(id);
        return match != null && matches.matchOf(id).filter(current -> current == match).isPresent()
                && !match.isAlive(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!event.isCancelled() && matches.isInMatch(event.getPlayer().getUniqueId())) {
            event.getDrops().clear();
            event.getItemsToKeep().clear();
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            event.setShouldDropExperience(false);
        }
    }

    @EventHandler
    public void onEliminated(PlayerEliminatedEvent event) {
        if (event.cause() == MatchUpdate.Cause.DIED
                && matches.matchOf(event.player()).filter(match -> match == event.match()).isPresent()) {
            eliminated.put(event.player(), event.match());
        }
    }

    /** Void or /kill deaths can also happen between rounds, where no elimination is emitted. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeathBetweenRounds(PlayerDeathEvent event) {
        if (event.isCancelled()) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        matches.matchOf(id).filter(match -> !match.phase().isInRound())
                .ifPresent(match -> eliminated.put(id, match));
    }

    /** The respawn location reflects this round, not the round in which the death occurred. */
    public void placeRespawn(PlayerRespawnEvent event) {
        matches.matchOf(event.getPlayer().getUniqueId()).ifPresent(match -> {
            Optional<Location> spawn = isSpectating(event.getPlayer().getUniqueId())
                    ? anchor(event.getPlayer(), match) : matches.respawnPoint(event.getPlayer().getUniqueId());
            spawn.ifPresent(event::setRespawnLocation);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        matches.matchOf(player.getUniqueId()).ifPresent(match -> {
            if (isSpectating(player.getUniqueId())) {
                follow(player, match);
            } else {
                clearCamera(player);
                player.setGameMode(GameMode.ADVENTURE);
                MatchService.readyForRound(player);
                matches.respawnPoint(player.getUniqueId()).ifPresent(player::teleport);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onRoundStart(RoundStartEvent event) {
        eliminated.entrySet().removeIf(entry -> entry.getValue() == event.match());
        for (UUID id : event.match().players()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && !player.isDead()) {
                clearCamera(player);
                player.setGameMode(GameMode.ADVENTURE);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onLeave(PlayerLeaveMatchEvent event) {
        eliminated.remove(event.player());
        Player player = plugin.getServer().getPlayer(event.player());
        if (player != null) {
            clearCamera(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (isSpectating(event.getPlayer().getUniqueId())) {
            matches.matchOf(event.getPlayer().getUniqueId()).ifPresent(match -> {
                if (!allowedPosition(event.getTo(), match, event.getPlayer())) {
                    anchor(event.getPlayer(), match).ifPresent(event::setTo);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (isSpectating(event.getPlayer().getUniqueId())) {
            matches.matchOf(event.getPlayer().getUniqueId()).ifPresent(match -> {
                if (!allowedPosition(event.getTo(), match, event.getPlayer())) {
                    event.setCancelled(true);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStartSpectating(PlayerStartSpectatingEntityEvent event) {
        matches.matchOf(event.getPlayer().getUniqueId()).ifPresent(match -> {
            if (!isSpectating(event.getPlayer().getUniqueId())
                    || !validTarget(event.getNewSpectatorTarget(), event.getPlayer(), match)) {
                event.setCancelled(true);
            }
        });
    }

    /** Retargets after teammate deaths/departures and catches camera movement without input. */
    public void tick() {
        for (var entry : Map.copyOf(eliminated).entrySet()) {
            if (!isSpectating(entry.getKey())) {
                eliminated.remove(entry.getKey());
                continue;
            }
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline() && !player.isDead()) {
                follow(player, entry.getValue());
            }
        }
    }

    private void follow(Player player, MatchView match) {
        if (player.isDead()) {
            return;
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            return;
        }
        Player target = target(player, match).orElse(null);
        if (player.getSpectatorTarget() != target) {
            clearCamera(player);
        }
        if (!allowedPosition(player.getLocation(), match, player)) {
            clearCamera(player);
            if (!anchor(player, match).map(player::teleport).orElse(false)) {
                return;
            }
        }
        if (target != null && player.getSpectatorTarget() != target) {
            player.setSpectatorTarget(target);
        }
    }

    private Optional<Player> target(Player player, MatchView match) {
        if (player.getGameMode() == GameMode.SPECTATOR
                && validTarget(player.getSpectatorTarget(), player, match)) {
            return Optional.of((Player) player.getSpectatorTarget());
        }
        return match.playersOf(match.teamOf(player.getUniqueId())).stream()
                .map(plugin.getServer()::getPlayer)
                .filter(candidate -> validTarget(candidate, player, match)).findFirst();
    }

    private boolean validTarget(Entity entity, Player viewer, MatchView match) {
        return entity instanceof Player teammate && !teammate.getUniqueId().equals(viewer.getUniqueId())
                && match.contains(teammate.getUniqueId())
                && match.teamOf(teammate.getUniqueId()) == match.teamOf(viewer.getUniqueId())
                && teammate.isOnline() && !teammate.isDead() && !isSpectating(teammate.getUniqueId())
                && inside(teammate.getLocation(), match);
    }

    private Optional<Location> anchor(Player player, MatchView match) {
        return target(player, match).map(Player::getLocation).or(() -> matches.respawnPoint(player.getUniqueId()));
    }

    private boolean allowedPosition(Location location, MatchView match, Player player) {
        if (!inside(location, match)) {
            return false;
        }
        var teammates = match.playersOf(match.teamOf(player.getUniqueId())).stream()
                .map(plugin.getServer()::getPlayer).filter(candidate -> validTarget(candidate, player, match)).toList();
        if (teammates.isEmpty()) {
            return matches.respawnPoint(player.getUniqueId()).filter(base -> near(location, base)).isPresent();
        }
        return teammates.stream().anyMatch(teammate -> near(location, teammate.getLocation()));
    }

    private boolean inside(Location location, MatchView match) {
        return location != null && location.getWorld() != null
                && ArenaWorld.NAME.equals(location.getWorld().getName())
                && arenas.get(match.arena()).filter(arena -> arena.bounds() != null
                        && arena.bounds().contains(location.getBlockX(), location.getBlockY(), location.getBlockZ()))
                        .isPresent();
    }

    private static boolean near(Location location, Location anchor) {
        return location.getWorld().equals(anchor.getWorld())
                && location.distanceSquared(anchor) <= SPECTATE_RADIUS * SPECTATE_RADIUS;
    }

    private static void clearCamera(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR && player.getSpectatorTarget() != null) {
            player.setSpectatorTarget(null);
        }
    }
}
