package com.ninja6.ninjaarena.spectate;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.kit.KitInventory;
import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.portal.PortalEntry;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.waiting.WaitingRoomService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/** Tracks viewers independently of the match roster. All operations run on the server thread. */
public final class Spectators implements Listener {

    private record Seat(MatchView match, ArenaBounds bounds, ArenaPoint point) {
    }

    private final Plugin plugin;
    private final ArenaRegistry arenas;
    private final MatchService matches;
    private final WaitingRoomService waiting;
    private final RestoreService restores;
    private final Supplier<World> world;
    private final Map<UUID, Seat> viewers = new HashMap<>();
    private SpectatorSettings settings;

    public Spectators(Plugin plugin, ArenaRegistry arenas, MatchService matches, WaitingRoomService waiting,
            RestoreService restores, Supplier<World> world, SpectatorSettings settings) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.matches = matches;
        this.waiting = waiting;
        this.restores = restores;
        this.world = world;
        this.settings = settings;
    }

    public void setSettings(SpectatorSettings settings) {
        this.settings = settings;
    }

    public boolean contains(UUID id) {
        return viewers.containsKey(id);
    }

    public Optional<ArenaPoint> destination(String arena) {
        return seat(arena).map(Seat::point);
    }

    private Optional<Seat> seat(String arena) {
        return matches.match(arena).filter(match -> watchable(match.phase()))
                .filter(match -> settings.allows((int) viewers.values().stream()
                        .filter(viewer -> viewer.match() == match).count()))
                .flatMap(match -> arenas.get(match.arena())
                        .filter(found -> arenas.isHeld(found.name()))
                        .map(found -> new Seat(match, found.bounds(), found.teamSpawns().getFirst())));
    }

    static boolean watchable(MatchPhase phase) {
        return phase != MatchPhase.WAITING && phase != MatchPhase.MATCH_END;
    }

    /** A waiting player already has a durable snapshot. Transfer only after teleport succeeds. */
    public void fromWaitingRoom(Player player, String arena) {
        if (!player.hasPermission(PortalEntry.SPECTATE) || !waiting.isWaiting(player.getUniqueId())
                || player.isDead()) {
            tell(player, "You must be in a waiting room to spectate with this command.");
            return;
        }
        Seat seat = seat(arena).orElse(null);
        if (seat == null) {
            tell(player, "That match is not open to spectators.");
            return;
        }
        ArenaPoint point = seat.point();
        Location target = new Location(world.get(), point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        if (!waiting.transferToSpectate(player, target)) {
            tell(player, "You could not be sent to the match.");
            return;
        }
        arrived(player, arena);
    }

    /** Called after shared admission has saved a snapshot and teleported the player. */
    public void arrived(Player player, String arena) {
        Seat seat = seat(arena).orElse(null);
        if (seat == null || !inside(seat, player.getLocation())) {
            tell(player, "You could not be sent to the match.");
            restore(player);
            return;
        }
        UUID id = player.getUniqueId();
        viewers.put(id, seat);
        try {
            player.setGameMode(GameMode.SPECTATOR);
            if (viewers.get(id) != seat) {
                return;
            }
            if (player.getGameMode() == GameMode.SPECTATOR) {
                KitInventory.clearForEntry(player);
                player.sendMessage(Component.text("Watching " + arena + ". Use /ninjaarena leave to return home.",
                        NamedTextColor.YELLOW));
                return;
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not prepare " + player.getName() + " to spectate.", e);
        }
        tell(player, "You could not enter spectator mode.");
        leave(player);
    }

    public boolean leave(Player player) {
        if (viewers.remove(player.getUniqueId()) == null) {
            return false;
        }
        restore(player);
        return true;
    }

    private void restore(Player player) {
        if (restores.restore(player) == RestoreService.RestoreResult.FAILED) {
            tell(player, "Your belongings could not be returned. Rejoin the server to try again.");
        }
    }

    public void closeArena(String arena) {
        closeMatching(seat -> seat.match().arena().equals(arena));
    }

    public void close() {
        closeMatching(seat -> true);
    }

    private void closeMatching(Predicate<Seat> matchesSeat) {
        for (var viewer : Map.copyOf(viewers).entrySet()) {
            if (matchesSeat.test(viewer.getValue()) && viewers.remove(viewer.getKey(), viewer.getValue())) {
                Player player = plugin.getServer().getPlayer(viewer.getKey());
                if (player != null && player.isOnline()) {
                    restore(player);
                }
            }
        }
    }

    @EventHandler
    public void onMatchEnd(MatchEndEvent event) {
        closeMatching(seat -> seat.match() == event.match());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // RestoreListener retains the snapshot for the next join.
        viewers.remove(event.getPlayer().getUniqueId());
    }

    private boolean inside(Seat seat, Location to) {
        return to != null && to.getWorld() == world.get() && contains(seat.bounds(), to.getX(), to.getY(), to.getZ());
    }

    static boolean contains(ArenaBounds bounds, double x, double y, double z) {
        return x >= bounds.minX() && x < bounds.maxX() + 1.0
                && y >= bounds.minY() && y < bounds.maxY() + 1.0
                && z >= bounds.minZ() && z < bounds.maxZ() + 1.0;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Seat seat = viewers.get(event.getPlayer().getUniqueId());
        if (seat != null && !inside(seat, event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        onMove(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCamera(PlayerStartSpectatingEntityEvent event) {
        // A camera attached to a moving entity can cross the bounds independently of flight.
        if (contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (contains(event.getPlayer().getUniqueId()) && event.getNewGameMode() != GameMode.SPECTATOR) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isViewer(event.getEntity()) || isViewer(event.getDamageSource().getCausingEntity())) {
            event.setCancelled(true);
        }
    }

    private boolean isViewer(Entity entity) {
        return entity instanceof Player player && contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Seat seat = viewers.get(player.getUniqueId());
        if (seat != null) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && viewers.get(player.getUniqueId()) == seat) {
                    leave(player);
                }
            });
        }
    }

    private static void tell(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
