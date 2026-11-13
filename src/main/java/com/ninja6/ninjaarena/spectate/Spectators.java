package com.ninja6.ninjaarena.spectate;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.MapSource;
import com.ninja6.ninjaarena.kit.KitInventory;
import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.portal.PortalEntry;
import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.restore.RestoreService;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
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
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

/**
 * Tracks viewers independently of the match roster. A viewer who stops watching, by the leave
 * command, by the match ending or by its arena closing, goes to the lobby with their snapshot
 * kept ({@link #setLobby}); only a failed arrival and the plugin shutting down send them
 * home. All operations run on the server thread.
 *
 * <p>During a shop phase between rounds, viewers go with the players to the lobby world's
 * shop area ({@link #setShopArea}) and may fly within the lobby bounds; the next PREP
 * brings them back to the arena. Nobody new is let in to watch while the shop is open.
 */
public final class Spectators implements Listener {

    private record Seat(MatchView match, ArenaBounds bounds, ArenaPoint point) {
    }

    private final Plugin plugin;
    private final MapSource arenas;
    private final MatchService matches;
    private final RestoreService restores;
    private final Supplier<World> world;
    private final Map<UUID, Seat> viewers = new HashMap<>();
    private SpectatorSettings settings;
    private Predicate<Player> lobby = player -> false;
    private Supplier<Location> shopArea = () -> null;
    private Predicate<Location> lobbyArea = location -> false;

    private Predicate<UUID> inLobby = id -> false;

    public Spectators(Plugin plugin, MapSource arenas, MatchService matches, RestoreService restores,
            Supplier<World> world, SpectatorSettings settings) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.matches = matches;
        this.restores = restores;
        this.world = world;
        this.settings = settings;
    }

    /**
     * Who may start watching with {@code /narena spectate}: lobby members who are in no room,
     * whose snapshot is already saved. By default nobody may.
     */
    public void setLobbyMembers(Predicate<UUID> inLobby) {
        this.inLobby = java.util.Objects.requireNonNull(inLobby, "inLobby");
    }

    /**
     * Where viewers go when they stop watching: the lobby, {@code RoomService#enterLobby} on
     * a server. When it answers false they are sent home; by default everyone is.
     */
    public void setLobby(Predicate<Player> lobby) {
        this.lobby = java.util.Objects.requireNonNull(lobby, "lobby");
    }

    /**
     * Where viewers wait while their match is in its shop phase, and the lobby bounds they
     * may fly in meanwhile. While {@code shop} gives {@code null}, the default, they stay in
     * the arena.
     */
    public void setShopArea(Supplier<Location> shop, Predicate<Location> lobbyArea) {
        this.shopArea = java.util.Objects.requireNonNull(shop, "shop");
        this.lobbyArea = java.util.Objects.requireNonNull(lobbyArea, "lobbyArea");
    }

    public void setSettings(SpectatorSettings settings) {
        this.settings = settings;
    }

    public boolean contains(UUID id) {
        return viewers.containsKey(id);
    }

    /** Spectators of this match, including any in a transition between worlds. */
    public java.util.Collection<UUID> players(String arena) {
        return viewers.entrySet().stream().filter(entry -> entry.getValue().match().arena().equals(arena))
                .map(Map.Entry::getKey).toList();
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
        return phase != MatchPhase.WAITING && phase != MatchPhase.SHOP && phase != MatchPhase.MATCH_END;
    }

    /** Takes a match's viewers to the shop area as it opens, and back to the arena at the next PREP. */
    @EventHandler
    public void onPhase(MatchPhaseChangeEvent event) {
        boolean opening = event.to() == MatchPhase.SHOP;
        if (!opening && !(event.from() == MatchPhase.SHOP && event.to() == MatchPhase.PREP)) {
            return;
        }
        Location shop = shopArea.get();
        if (shop == null) {
            return;
        }
        for (var viewer : Map.copyOf(viewers).entrySet()) {
            Seat seat = viewer.getValue();
            Player player = seat.match() == event.match() ? plugin.getServer().getPlayer(viewer.getKey()) : null;
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (!opening) {
                // A generated map can be placed again in the break, elsewhere: back to it as it is now.
                Seat before = seat;
                seat = arenas.get(before.match().arena()).filter(found -> !found.teamSpawns().isEmpty())
                        .map(found -> new Seat(before.match(), found.bounds(), found.teamSpawns().getFirst()))
                        .orElse(before);
                viewers.put(viewer.getKey(), seat);
            }
            ArenaPoint point = seat.point();
            Location to = opening ? shop.clone()
                    : new Location(world.get(), point.x(), point.y(), point.z(), point.yaw(), point.pitch());
            if (!OwnTeleports.teleport(player, to, TeleportCause.PLUGIN)) {
                plugin.getLogger().warning("Could not move the viewer " + player.getName() + (opening
                        ? " to the shop area" : " back to arena " + seat.match().arena())
                        + "; sending them to the lobby.");
                leave(player);
            }
        }
    }

    /**
     * {@code /narena spectate <arena>} from the lobby. A lobby member already has a durable
     * snapshot; they leave the lobby by this plugin's own teleport, so it is not taken as a
     * way out, and stop watching back in the lobby.
     */
    public void fromLobby(Player player, String arena) {
        if (!player.hasPermission(PortalEntry.SPECTATE) || !inLobby.test(player.getUniqueId()) || player.isDead()) {
            tell(player, "spectate.not-in-lobby");
            return;
        }
        Seat seat = seat(arena).orElse(null);
        if (seat == null) {
            tell(player, "spectate.not-open");
            return;
        }
        ArenaPoint point = seat.point();
        Location target = new Location(world.get(), point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        if (!OwnTeleports.teleport(player, target, TeleportCause.PLUGIN)) {
            tell(player, "spectate.send-failed");
            return;
        }
        arrived(player, arena);
    }

    /** Called after shared admission has saved a snapshot and teleported the player. */
    public void arrived(Player player, String arena) {
        Seat seat = seat(arena).orElse(null);
        if (seat == null || !inside(seat, player.getLocation())) {
            tell(player, "spectate.send-failed");
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
                player.sendMessage(msg("spectate.watching", "arena", arena));
                return;
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not prepare " + player.getName() + " to spectate.", e);
        }
        tell(player, "spectate.mode-failed");
        leave(player);
    }

    public boolean leave(Player player) {
        if (viewers.remove(player.getUniqueId()) == null) {
            return false;
        }
        toLobby(player);
        return true;
    }

    /** To the lobby, or home when the lobby cannot take the player. */
    private void toLobby(Player player) {
        boolean placed;
        try {
            placed = lobby.test(player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not send " + player.getName() + " to the lobby.", e);
            placed = false;
        }
        if (!placed) {
            restore(player);
        }
    }

    private void restore(Player player) {
        if (restores.restore(player) == RestoreService.RestoreResult.FAILED) {
            tell(player, "spectate.restore-failed");
        }
    }

    public void closeArena(String arena) {
        closeMatching(seat -> seat.match().arena().equals(arena), true);
    }

    /** Sends every viewer home. Called on plugin disable. */
    public void close() {
        closeMatching(seat -> true, false);
    }

    private void closeMatching(Predicate<Seat> matchesSeat, boolean toLobby) {
        for (var viewer : Map.copyOf(viewers).entrySet()) {
            if (matchesSeat.test(viewer.getValue()) && viewers.remove(viewer.getKey(), viewer.getValue())) {
                Player player = plugin.getServer().getPlayer(viewer.getKey());
                if (player != null && player.isOnline()) {
                    if (toLobby) {
                        toLobby(player);
                    } else {
                        restore(player);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onMatchEnd(MatchEndEvent event) {
        closeMatching(seat -> seat.match() == event.match(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // RestoreListener retains the snapshot for the next join.
        viewers.remove(event.getPlayer().getUniqueId());
    }

    private boolean inside(Seat seat, Location to) {
        if (seat.match().phase() == MatchPhase.SHOP && shopArea.get() != null) {
            return lobbyArea.test(to);
        }
        return PluginWorlds.isArena(to) && contains(seat.bounds(), to.getX(), to.getY(), to.getZ());
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

    private static void tell(Player player, String key) {
        player.sendMessage(msg(key));
    }
}
