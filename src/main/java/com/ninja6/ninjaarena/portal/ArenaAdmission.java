package com.ninja6.ninjaarena.portal;

import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import java.io.IOException;
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

/**
 * Snapshot-before-teleport entry for spectator portals, which seat the player at a live
 * match. Play portals lead to the lobby, which saves the snapshot itself. Server thread only.
 */
public final class ArenaAdmission {

    private final Plugin plugin;
    private final PortalEntry entry;
    private final Supplier<World> world;
    private final SnapshotService snapshots;

    public ArenaAdmission(Plugin plugin, PortalEntry entry, Supplier<World> world, SnapshotService snapshots) {
        this.plugin = plugin;
        this.entry = entry;
        this.world = world;
        this.snapshots = snapshots;
    }

    /**
     * Seats a player who walked into a spectator portal. The destination chunk must already be
     * loaded. A failure keeps the player's own inventory.
     *
     * @param onSpectate told the player and the arena once they stand at the match
     */
    public boolean enter(Player player, Portal portal, BiConsumer<Player, String> onSpectate,
            Consumer<String> refusal) {
        return enter(player, portal.arena(), () -> entry.admit(player, portal),
                () -> entry.destination(portal).orElseThrow(), onSpectate, refusal);
    }

    private boolean enter(Player player, String arena, Supplier<Optional<PortalEntry.Refusal>> admit,
            Supplier<ArenaPoint> destination, BiConsumer<Player, String> arrived, Consumer<String> refusal) {
        if (holdsLooseItems(player)) {
            refusal.accept(text("entry.loose-items"));
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
        refusal.accept(text("entry.teleport-failed"));
        return false;
    }

    /** Whether the player holds items on the cursor or in a crafting grid. */
    public static boolean holdsLooseItems(Player player) {
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
