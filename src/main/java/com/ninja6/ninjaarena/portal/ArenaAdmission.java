package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Shared snapshot-before-teleport entry for portals and accepted invitations. Server thread only. */
public final class ArenaAdmission {

    private final Plugin plugin;
    private final PortalEntry entry;
    private final Supplier<World> world;
    private final SnapshotService snapshots;
    private final BiConsumer<Player, String> onEnter;
    private final Consumer<Player> onHub;

    public ArenaAdmission(Plugin plugin, PortalEntry entry, Supplier<World> world, SnapshotService snapshots,
            BiConsumer<Player, String> onEnter) {
        this(plugin, entry, world, snapshots, onEnter, player -> {
            throw new IllegalStateException("No shared hub is connected");
        });
    }

    /**
     * @param onEnter told the player and the arena once they are in its waiting room
     * @param onHub   told the player once they are in the shared hub
     */
    public ArenaAdmission(Plugin plugin, PortalEntry entry, Supplier<World> world, SnapshotService snapshots,
            BiConsumer<Player, String> onEnter, Consumer<Player> onHub) {
        this.plugin = plugin;
        this.entry = entry;
        this.world = world;
        this.snapshots = snapshots;
        this.onEnter = onEnter;
        this.onHub = onHub;
    }

    /** The destination chunk must already be loaded. A failure keeps the player's own inventory. */
    public boolean enter(Player player, String arena, Consumer<String> refusal) {
        return enter(player, arena, () -> entry.admit(player, arena),
                () -> entry.waitingRoom(arena).orElseThrow(), onEnter, refusal);
    }

    public boolean enter(Player player, Portal portal, BiConsumer<Player, String> onSpectate,
            Consumer<String> refusal) {
        BiConsumer<Player, String> arrived;
        Portal target = portal;
        if (entry.leadsToHub(portal)) {
            arrived = (entered, arena) -> onHub.accept(entered);
        } else {
            arrived = portal.spectator() ? onSpectate : onEnter;
            // A player-built portal routed to the hub when the player walked in is still unbound
            // if the default lobby changed since. Bind it once here, so the checks, the
            // destination and the waiting room joined all name the same arena.
            Optional<Portal> routed = entry.route(portal);
            if (routed.isEmpty()) {
                refusal.accept("All arenas are busy. Try again when a match ends.");
                return false;
            }
            target = routed.get();
        }
        Portal admitted = target;
        // A player-built portal that leads to the hub is bound to no arena; the name is only logged.
        return enter(player, Objects.requireNonNullElse(admitted.arena(), "hub"), () -> entry.admit(player, admitted),
                () -> entry.destination(admitted).orElseThrow(), arrived, refusal);
    }

    private boolean enter(Player player, String arena, Supplier<Optional<PortalEntry.Refusal>> admit,
            Supplier<ArenaPoint> destination, BiConsumer<Player, String> arrived, Consumer<String> refusal) {
        if (holdsLooseItems(player)) {
            refusal.accept("Put away the items on your cursor or in your crafting grid, then try again.");
            return false;
        }
        player.closeInventory();
        var denied = admit.get();
        if (denied.isPresent()) {
            refusal.accept(denied.get().message());
            return false;
        }
        ArenaPoint room = destination.get();
        Location target = new Location(world.get(), room.x(), room.y(), room.z(), room.yaw(), room.pitch());
        boolean moved;
        try {
            moved = OwnTeleports.teleport(player, target, TeleportCause.PLUGIN);
            if (!moved) {
                plugin.getLogger().warning("The teleport of " + player.getName() + " (" + player.getUniqueId()
                        + ") to arena " + arena + " was refused.");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not teleport " + player.getName() + " ("
                    + player.getUniqueId() + ") to arena " + arena, e);
            moved = false;
        }
        if (moved) {
            arrived.accept(player, arena);
            return true;
        }
        try {
            snapshots.delete(player.getUniqueId());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not delete the snapshot of " + player.getName() + " ("
                    + player.getUniqueId() + ") after a failed arena teleport; it will be restored on their next join.", e);
        }
        refusal.accept("You could not be sent to the arena.");
        return false;
    }

    /** Whether the player holds items on the cursor or in a crafting grid. */
    static boolean holdsLooseItems(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.isEmpty()) {
            return true;
        }
        if (player.getOpenInventory().getTopInventory() instanceof CraftingInventory grid) {
            for (ItemStack item : grid.getMatrix()) {
                if (item != null && !item.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

}
