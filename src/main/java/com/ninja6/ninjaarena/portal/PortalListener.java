package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.portal.PortalEntry.Refusal;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
 * into the edge again does not flood chat. The message for a failed teleport shares that
 * cooldown.
 *
 * <p><b>Entry.</b> The waiting room's chunk is loaded first. Entry then runs in a scheduled
 * task on a later tick, never inside the move event that crossed into the portal, even when
 * the chunk was already loaded: the event is not done with the player yet, and the waiting
 * room's own move listener would see the player in the room with the event's destination
 * still next to the portal. In that task the checks run again, the player's inventory screen
 * is closed so the cursor and crafting grid go back into the saved inventory, the snapshot is
 * written and the player is teleported, all in the same tick, so nothing the player does can
 * fall between the snapshot and the teleport. A player who is by then offline, dead, or no
 * longer in or next to the portal, or whose portal has changed, is dropped silently. A
 * teleport that fails is logged and deletes the snapshot again, since the player never left;
 * one that succeeds hands the player to the waiting room, which empties their inventory.
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

    /** Players whose entry is waiting for the waiting room's chunk. */
    private final Map<UUID, Object> entering = new HashMap<>();
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    /**
     * @param onQuit  also told when a player leaves, to drop per-player command state
     * @param onEnter told the player and the arena's name once the player is in its waiting room
     */
    public PortalListener(Plugin plugin, PortalRegistry portals, PortalEntry entry, Supplier<World> arenaWorld,
            SnapshotService snapshots, Consumer<UUID> onQuit, BiConsumer<Player, String> onEnter) {
        this(plugin, portals, entry, arenaWorld, snapshots, onQuit, onEnter, () -> () -> { });
    }

    public PortalListener(Plugin plugin, PortalRegistry portals, PortalEntry entry, Supplier<World> arenaWorld,
            SnapshotService snapshots, Consumer<UUID> onQuit, BiConsumer<Player, String> onEnter,
            Supplier<Runnable> acquireWorld) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.portals = Objects.requireNonNull(portals, "portals");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.arenaWorld = Objects.requireNonNull(arenaWorld, "arenaWorld");
        this.acquireWorld = Objects.requireNonNull(acquireWorld, "acquireWorld");
        this.admission = new ArenaAdmission(plugin, entry, arenaWorld, snapshots, onEnter);
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
        if (player.getGameMode() == GameMode.SPECTATOR || entering.containsKey(playerId)) {
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
        Object request = new Object();
        Runnable releaseWorld;
        try {
            releaseWorld = acquireWorld.get();
        } catch (RuntimeException failure) {
            tell(player, "The arena could not load. Please try again.");
            return;
        }
        entering.put(playerId, request);
        try {
            arenaWorld.get().getChunkAtAsync(Math.floorDiv(room.blockX(), 16), Math.floorDiv(room.blockZ(), 16))
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
            tell(player, "The arena could not load. Please try again.");
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

    private void enter(UUID playerId, Object request, Portal portal, Location outside, Throwable error) {
        if (!entering.remove(playerId, request)) {
            // The player left while the chunk loaded.
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (error != null) {
            if (player != null) {
                tell(player, "The arena could not load. Please try again.");
            }
            return;
        }
        // The player must still be where they walked in. One teleported away while the chunk
        // loaded would otherwise be snapshotted, and later restored, wherever they ended up.
        if (player == null || !player.isOnline() || player.isDead()
                || !portal.equals(portals.get(portal.name()).orElse(null))
                || !isAt(portal, player.getLocation())) {
            return;
        }
        admission.enter(player, portal.arena(), message -> {
            if (portal.equals(portals.at(player.getLocation()))) {
                player.teleport(outside, TeleportCause.PLUGIN);
            }
            tell(player, message);
        });
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
        player.sendMessage(Component.text(message, NamedTextColor.RED));
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
