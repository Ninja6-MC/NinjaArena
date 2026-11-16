package com.ninja6.ninjaarena.room;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

/**
 * Room members looking over their room's generated map in spectator mode before its match.
 *
 * <p>A preview starts from the lobby, where the player's snapshot is already saved, and
 * never takes a new one: the snapshot that brought them into the lobby is what puts them
 * back. A preview ends, and the player goes back to the lobby still in their room, when
 * they ask ({@code /narena room preview} again, the room screen or {@code /narena leave}),
 * when the room's match starts, when they leave the room or it closes, and when the map is
 * cleared or replaced. Whoever the lobby cannot take is restored home from the snapshot.
 *
 * <p>Nothing about a preview is kept anywhere but here, so nothing of it survives a
 * disconnect or a restart: a previewer who quits is still a room member at that moment, so
 * their next join puts them in the lobby with their snapshot kept (as for any room member),
 * and after a restart or a crash their snapshot restores them home, game mode included. On
 * disable every previewer is restored home at once. A player is never left in spectator
 * mode: when neither the lobby nor the restore takes them, they are put in adventure mode
 * and their snapshot waits for their next join.
 *
 * <p>While previewing, a player stays inside the map's bounds with some room around it,
 * cannot attach to an entity's view, use a portal or leave spectator mode by other means,
 * and is neither a lobby member nor a match player. Server thread only.
 */
public final class MapPreviews implements Listener {

    /** Blocks of space around the map a previewer may fly in, on each side and above. */
    static final int MARGIN = 16;
    /** How far above the map's top a preview starts. */
    static final int START_ABOVE = 8;
    /** Looking down onto the map from the start. */
    private static final float START_PITCH = 50;

    /** One preview: the room, its map and cell when it started, and where the previewer may fly. */
    record Preview(Room room, String map, GridCell cell, ArenaBounds bounds) {
    }

    private final Plugin plugin;
    private final Supplier<World> world;
    private final Predicate<UUID> saved;
    private final Function<Player, RestoreResult> home;
    private final Map<UUID, Preview> previews = new LinkedHashMap<>();
    private RoomService rooms;

    /**
     * @param world the arena world
     * @param saved whether a player has a snapshot pending, {@code SnapshotService#hasPending}
     * @param home  restores a player home from their snapshot, {@code RestoreService#restore}
     */
    public MapPreviews(Plugin plugin, Supplier<World> world, Predicate<UUID> saved,
            Function<Player, RestoreResult> home) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.world = Objects.requireNonNull(world, "world");
        this.saved = Objects.requireNonNull(saved, "saved");
        this.home = Objects.requireNonNull(home, "home");
    }

    /** Called by {@link RoomService#setPreviews}. */
    void attach(RoomService rooms) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    public boolean contains(UUID player) {
        return previews.containsKey(player);
    }

    /** Whether anyone in the room is previewing its map. */
    boolean anyIn(Room room) {
        return previews.values().stream().anyMatch(preview -> preview.room() == room);
    }

    /**
     * Takes a room member from the lobby over the room's placed map, in spectator mode. The
     * caller has checked the room and the map; this checks the snapshot that will bring
     * the player back.
     */
    void begin(Player player, Room room, Arena map) {
        UUID id = player.getUniqueId();
        GridCell cell = map.cell();
        if (!saved.test(id) || previews.containsKey(id) || player.isDead()) {
            player.sendMessage(msg("room.preview-failed"));
            return;
        }
        ArenaBounds bounds = around(map.bounds());
        Preview preview = new Preview(room, map.name(), cell, bounds);
        ArenaBounds inner = map.bounds();
        Location target = new Location(world.get(), (inner.minX() + inner.maxX() + 1) / 2.0,
                Math.min(inner.maxY() + START_ABOVE, bounds.maxY()), (inner.minZ() + inner.maxZ() + 1) / 2.0,
                0, START_PITCH);
        // Recorded first, so the way out of the lobby is known to be a preview's.
        previews.put(id, preview);
        if (!OwnTeleports.teleport(player, target, TeleportCause.PLUGIN)) {
            previews.remove(id, preview);
            player.sendMessage(msg("room.preview-failed"));
            return;
        }
        try {
            player.setGameMode(GameMode.SPECTATOR);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not put " + player.getName() + " in spectator mode for"
                    + " a map preview.", e);
        }
        if (previews.get(id) != preview) {
            // Ended meanwhile, by whatever reacted to the change.
            return;
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.sendMessage(msg("room.preview-failed"));
            end(player);
            return;
        }
        player.sendMessage(msg("room.preview-started"));
    }

    /**
     * Ends the player's preview and takes them back to the lobby, in their room; home, and
     * out of the room, when the lobby cannot take them.
     *
     * @return false if they were not previewing
     */
    public boolean end(Player player) {
        return end(player, "room.preview-ended", null);
    }

    /**
     * Ends the player's preview at their own request, {@code /narena leave} or previewing
     * again, as {@link #end(Player)} does, telling them they are still in the room.
     *
     * @return false if they were not previewing
     */
    public boolean leave(Player player) {
        return end(player, "room.preview-left", "room.preview-left-home");
    }

    /** {@code homeKey}, when not null, is said before a player the lobby cannot take is sent home. */
    private boolean end(Player player, String endedKey, String homeKey) {
        if (previews.remove(player.getUniqueId()) == null) {
            return false;
        }
        boolean placed;
        try {
            placed = rooms != null && rooms.enterLobby(player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not return " + player.getName() + " to the lobby from"
                    + " a map preview.", e);
            placed = false;
        }
        if (placed) {
            player.sendMessage(msg(endedKey));
            return true;
        }
        if (homeKey != null) {
            player.sendMessage(msg(homeKey));
        }
        sendHome(player);
        if (rooms != null) {
            // Home, so out of the plugin's worlds: no longer in the room, before anything can start its match.
            rooms.sentHome(player);
        }
        return true;
    }

    /** Ends every preview of the room's map, as its match starts or the room closes. */
    void endRoom(Room room) {
        endMatching(preview -> preview.room() == room);
    }

    /** A slot cell is being cleared: previews of the map that was in it end. */
    public void clearing(GridCell cell) {
        endMatching(preview -> preview.cell().equals(cell));
    }

    /**
     * Ends the previews that are no longer allowed: of a player who is gone, left their
     * room, or whose room is playing or has another map now. Run once a second.
     */
    void check() {
        for (Map.Entry<UUID, Preview> entry : List.copyOf(previews.entrySet())) {
            Preview preview = entry.getValue();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                previews.remove(entry.getKey(), preview);
            } else if (rooms == null || !rooms.previewable(entry.getKey(), preview.room(), preview.map())) {
                end(player);
            }
        }
    }

    private void endMatching(Predicate<Preview> ends) {
        for (Map.Entry<UUID, Preview> entry : List.copyOf(previews.entrySet())) {
            if (!ends.test(entry.getValue())) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                end(player);
            } else {
                previews.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Restores every previewer home. Called on plugin disable, before rooms and the lobby close. */
    public void close() {
        for (Map.Entry<UUID, Preview> entry : List.copyOf(previews.entrySet())) {
            previews.remove(entry.getKey());
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                sendHome(player);
            }
        }
    }

    /** Home from the snapshot; out of spectator mode at least, whatever happens. */
    private void sendHome(Player player) {
        RestoreResult result;
        try {
            result = home.apply(player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not restore " + player.getName() + " after a map preview.", e);
            result = RestoreResult.FAILED;
        }
        if (result == RestoreResult.FAILED || result == RestoreResult.NO_SNAPSHOT) {
            leaveSpectator(player);
            player.sendMessage(msg("common.restore-failed"));
        }
    }

    private void leaveSpectator(Player player) {
        if (player.getGameMode() != GameMode.SPECTATOR) {
            return;
        }
        try {
            player.setGameMode(GameMode.ADVENTURE);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not take " + player.getName() + " out of spectator mode"
                    + " after a map preview.", e);
        }
    }

    /** The map's bounds with {@link #MARGIN} around it, and above it, but not below. */
    static ArenaBounds around(ArenaBounds map) {
        return new ArenaBounds(map.minX() - MARGIN, map.minY(), map.minZ() - MARGIN,
                map.maxX() + MARGIN, map.maxY() + MARGIN, map.maxZ() + MARGIN);
    }

    static boolean inside(ArenaBounds bounds, Location to) {
        if (to == null || !PluginWorlds.isArena(to)) {
            return false;
        }
        return to.getX() >= bounds.minX() && to.getX() < bounds.maxX() + 1.0
                && to.getY() >= bounds.minY() && to.getY() < bounds.maxY() + 1.0
                && to.getZ() >= bounds.minZ() && to.getZ() < bounds.maxZ() + 1.0;
    }

    // ---- Events ----

    /**
     * Before the room lets a quitting member go, and after the lobby return has seen them
     * as a room member: their next join puts them in the lobby.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        previews.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Preview preview = previews.get(event.getPlayer().getUniqueId());
        if (preview != null && !inside(preview.bounds(), event.getTo())) {
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (contains(event.getPlayer().getUniqueId())) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    /** A previewer who died anyway comes back to the lobby once respawned. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerPostRespawnEvent event) {
        end(event.getPlayer());
    }
}
