package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.portal.PortalEntry.Refusal;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
 * Sends players who walk into a portal to its arena's waiting room.
 *
 * <p><b>Detection.</b> Only a move that crosses into another block is looked at, and the
 * portal is found through the registry's chunk index. Entry fires when the new block is in
 * a portal and the old one was not in the same portal, so walking around inside a portal
 * does nothing. Spectators are ignored.
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
 * into the edge again does not flood chat.
 *
 * <p><b>Entry.</b> The waiting room's chunk is loaded first. Once it is, on the server
 * thread, the checks run again, the snapshot is written and the player is teleported, all in
 * the same tick, so nothing the player does can fall between the snapshot and the teleport.
 * A teleport that fails deletes the snapshot again, since the player never left.
 */
public final class PortalListener implements Listener {

    static final long MESSAGE_COOLDOWN_MILLIS = 2000;

    private final Plugin plugin;
    private final PortalRegistry portals;
    private final PortalEntry entry;
    private final Supplier<World> arenaWorld;
    private final SnapshotService snapshots;
    private final Consumer<UUID> onQuit;

    /** Players whose entry is waiting for the waiting room's chunk. */
    private final Set<UUID> entering = new HashSet<>();
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    /**
     * @param onQuit also told when a player leaves, to drop per-player command state
     */
    public PortalListener(Plugin plugin, PortalRegistry portals, PortalEntry entry, Supplier<World> arenaWorld,
            SnapshotService snapshots, Consumer<UUID> onQuit) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.portals = Objects.requireNonNull(portals, "portals");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.arenaWorld = Objects.requireNonNull(arenaWorld, "arenaWorld");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.onQuit = Objects.requireNonNull(onQuit, "onQuit");
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
        if (player.getGameMode() == GameMode.SPECTATOR || entering.contains(playerId)) {
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
        ArenaPoint room = entry.waitingRoom(portal).orElseThrow();
        entering.add(playerId);
        arenaWorld.get().getChunkAtAsync(Math.floorDiv(room.blockX(), 16), Math.floorDiv(room.blockZ(), 16))
                .whenComplete((chunk, error) -> onServerThread(() -> enter(playerId, portal, outside)));
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

    private void enter(UUID playerId, Portal portal, Location outside) {
        if (!entering.remove(playerId)) {
            // The player left while the chunk loaded.
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline() || player.isDead()
                || !portal.equals(portals.get(portal.name()).orElse(null))) {
            return;
        }
        Optional<Refusal> refusal = entry.admit(player, portal);
        if (refusal.isPresent()) {
            if (portal.equals(portals.at(player.getLocation()))) {
                player.teleport(outside, TeleportCause.PLUGIN);
            }
            tell(player, refusal.get());
            return;
        }
        ArenaPoint room = entry.waitingRoom(portal).orElseThrow();
        Location target = new Location(arenaWorld.get(), room.x(), room.y(), room.z(), room.yaw(), room.pitch());
        boolean moved;
        try {
            moved = player.teleport(target, TeleportCause.PLUGIN);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not teleport " + player.getName() + " to arena "
                    + portal.arena(), e);
            moved = false;
        }
        if (moved) {
            return;
        }
        // The player never left, so what they have now is theirs and the snapshot is stale.
        try {
            snapshots.delete(playerId);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not delete the snapshot of " + player.getName() + " ("
                    + playerId + ") after a failed arena teleport; it will be restored on their next join.", e);
        }
        player.sendMessage(Component.text("You could not be sent to the arena.", NamedTextColor.RED));
    }

    private void tell(Player player, Refusal refusal) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MILLIS) {
            return;
        }
        lastMessage.put(player.getUniqueId(), now);
        player.sendMessage(Component.text(refusal.message(), NamedTextColor.RED));
    }

    private void onServerThread(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }
}
