package com.ninja6.ninjaarena.portal;

import static com.ninja6.ninjaarena.message.Messages.msg;
import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.portal.PortalEntry.Refusal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

/**
 * Sends players who walk into a portal on: through a play portal to the lobby, through a
 * spectator portal to a live match. {@link PortalEntry} decides whether they may; everything
 * below applies to both.
 *
 * <p><b>Detection.</b> Only a move that crosses into another block is looked at, and the
 * portal is found through the registry's chunk index. Entry fires when the new block is in
 * a portal and the old one was not in the same portal, so walking around inside a portal
 * does nothing. Players in spectator mode are ignored by play portals.
 *
 * <p><b>Teleports.</b> A teleport that lands inside a portal does not enter it: commands,
 * other plugins and the restore path all teleport players, and a player returned home next
 * to a portal must not be thrown straight back in. Teleport events are a separate event
 * from moves, so they never reach {@link #onMove}. A player who arrives inside a portal
 * this way enters it by stepping out and back in. Vanilla nether portal and end gateway
 * travel is cancelled for a player standing inside a portal, so a lit frame is decoration.
 *
 * <p><b>Refusal.</b> A player who may not enter is put back on the block they came from and
 * told why, at most once every {@link #MESSAGE_COOLDOWN_MILLIS} milliseconds, so walking
 * into the edge again does not flood chat. The message for a failed teleport shares that
 * cooldown.
 *
 * <p><b>Entry.</b> Entry runs in a scheduled task on a later tick, never inside the move
 * event that crossed into the portal: the event is not done with the player yet. A player
 * who is by then offline, dead, or no longer in or next to the portal, or whose portal has
 * changed, is dropped silently. A play portal then hands the player to the lobby
 * ({@link #setLobby}), which saves their snapshot, takes them in and puts them in an open
 * room if there is one ({@link PortalEntry#roomArena}). A spectator portal loads the match's
 * chunk first; in the task the checks run again, the player's inventory screen is closed so
 * the cursor and crafting grid go back into the saved inventory, the snapshot is written and
 * the player is teleported, all in the same tick. A teleport that fails is logged and
 * deletes the snapshot again, since the player never left.
 */
public final class PortalListener implements Listener {

    static final long MESSAGE_COOLDOWN_MILLIS = 2000;

    private final Plugin plugin;
    private final PortalRegistry portals;
    private final PortalEntry entry;
    private final Supplier<World> arenaWorld;
    private final Supplier<Runnable> acquireWorld;
    private final ArenaAdmission admission;
    private final Consumer<UUID> onQuit;
    private final BiConsumer<Player, String> onSpectate;
    private PortalLobby lobby = (player, arena) -> Optional.of(text("entry.lobby-unavailable"));

    /** Players whose entry waits for the next tick or, at a spectator portal, the match's chunk. */
    private final Map<UUID, Object> entering = new HashMap<>();
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    /**
     * @param onQuit     also told when a player leaves, to drop per-player command state
     * @param onSpectate told the player and the arena once a spectator portal has seated them
     */
    public PortalListener(Plugin plugin, PortalRegistry portals, PortalEntry entry, Supplier<World> arenaWorld,
            ArenaAdmission admission, Consumer<UUID> onQuit, Supplier<Runnable> acquireWorld,
            BiConsumer<Player, String> onSpectate) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.portals = Objects.requireNonNull(portals, "portals");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.arenaWorld = Objects.requireNonNull(arenaWorld, "arenaWorld");
        this.acquireWorld = Objects.requireNonNull(acquireWorld, "acquireWorld");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.onSpectate = Objects.requireNonNull(onSpectate, "onSpectate");
        this.onQuit = Objects.requireNonNull(onQuit, "onQuit");
    }

    /** Where play portals lead; by default they turn everyone away. */
    public void setLobby(PortalLobby lobby) {
        this.lobby = Objects.requireNonNull(lobby, "lobby");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Location to = event.getTo();
        Portal portal = portals.at(to);
        if (portal == null || portal.equals(portals.at(event.getFrom()))) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if ((!portal.spectator() && player.getGameMode() == GameMode.SPECTATOR) || entering.containsKey(playerId)) {
            return;
        }
        Location outside = event.getFrom().clone();
        outside.setYaw(to.getYaw());
        outside.setPitch(to.getPitch());
        Optional<Refusal> refusal = entry.check(player, portal);
        if (refusal.isPresent()) {
            event.setTo(outside);
            tell(player, refusal.get());
            return;
        }
        Object request = new Object();
        if (!portal.spectator()) {
            entering.put(playerId, request);
            if (!later(() -> enterLobby(playerId, request, portal, outside))) {
                entering.remove(playerId, request);
            }
            return;
        }
        ArenaPoint seat = entry.destination(portal).orElseThrow();
        Runnable releaseWorld;
        try {
            releaseWorld = acquireWorld.get();
        } catch (RuntimeException failure) {
            tell(player, text("entry.load-failed"));
            return;
        }
        entering.put(playerId, request);
        try {
            arenaWorld.get().getChunkAtAsync(Math.floorDiv(seat.blockX(), 16), Math.floorDiv(seat.blockZ(), 16))
                    .whenComplete((chunk, error) -> {
                        try {
                            if (!later(() -> {
                                try {
                                    enter(playerId, request, portal, outside, error);
                                } finally {
                                    releaseWorld.run();
                                }
                            })) {
                                releaseWorld.run();
                            }
                        } catch (RuntimeException failure) {
                            releaseWorld.run();
                            throw failure;
                        }
                    });
        } catch (RuntimeException failure) {
            entering.remove(playerId, request);
            releaseWorld.run();
            tell(player, text("entry.load-failed"));
        }
    }

    /** Lets the region, not the frame, decide where a player standing in a portal goes. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVanillaPortal(PlayerPortalEvent event) {
        if (portals.at(event.getFrom()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        entering.remove(playerId);
        lastMessage.remove(playerId);
        onQuit.accept(playerId);
    }

    /** Takes a player who walked into a play portal to the lobby, on the tick after. */
    private void enterLobby(UUID playerId, Object request, Portal portal, Location outside) {
        if (!entering.remove(playerId, request)) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (!stillAt(player, portal)) {
            return;
        }
        Optional<Refusal> refusal = entry.check(player, portal);
        Optional<String> refused = refusal.isPresent() ? refusal.map(Refusal::message)
                : lobby.enter(player, entry.roomArena(portal));
        refused.ifPresent(message -> putBack(player, portal, outside, message));
    }

    /** Seats a player who walked into a spectator portal, once the match's chunk has loaded. */
    private void enter(UUID playerId, Object request, Portal portal, Location outside, Throwable error) {
        if (!entering.remove(playerId, request)) {
            // The player left while the chunk loaded.
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (error != null) {
            if (player != null) {
                tell(player, text("entry.load-failed"));
            }
            return;
        }
        if (!stillAt(player, portal)) {
            return;
        }
        admission.enter(player, portal, onSpectate, message -> putBack(player, portal, outside, message));
    }

    /**
     * Whether the player is still where they walked in. One teleported away meanwhile would
     * otherwise be snapshotted, and later restored, wherever they ended up.
     */
    private boolean stillAt(Player player, Portal portal) {
        return player != null && player.isOnline() && !player.isDead()
                && portal.equals(portals.get(portal.name()).orElse(null)) && isAt(portal, player.getLocation());
    }

    /** Puts a refused player back outside the portal, if they still stand in it, and tells them why. */
    private void putBack(Player player, Portal portal, Location outside, String message) {
        if (portal.equals(portals.at(player.getLocation()))) {
            OwnTeleports.teleport(player, outside, TeleportCause.PLUGIN);
        }
        tell(player, message);
    }

    /** Whether the location is in the portal or in a block touching it, in the portal's world. */
    static boolean isAt(Portal portal, Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(portal.world())) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= portal.region().minX() - 1 && x <= portal.region().maxX() + 1
                && y >= portal.region().minY() - 1 && y <= portal.region().maxY() + 1
                && z >= portal.region().minZ() - 1 && z <= portal.region().maxZ() + 1;
    }

    private void tell(Player player, Refusal refusal) {
        tell(player, refusal.message());
    }

    /** Sends a red message, at most once every {@link #MESSAGE_COOLDOWN_MILLIS} per player. */
    private void tell(Player player, String message) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MILLIS) {
            return;
        }
        lastMessage.put(player.getUniqueId(), now);
        player.sendMessage(msg("entry.refused", "message", message));
    }

    /**
     * Runs the task on the server thread on a later tick. Never runs it inline, because a chunk
     * that is already loaded completes its future at once, inside the move event.
     */
    private boolean later(Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
            return true;
        }
        return false;
    }
}
