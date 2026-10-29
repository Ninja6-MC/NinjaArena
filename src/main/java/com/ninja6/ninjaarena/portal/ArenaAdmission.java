package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import java.io.IOException;
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

    public ArenaAdmission(Plugin plugin, PortalEntry entry, Supplier<World> world, SnapshotService snapshots,
            BiConsumer<Player, String> onEnter) {
        this.plugin = plugin;
        this.entry = entry;
        this.world = world;
        this.snapshots = snapshots;
        this.onEnter = onEnter;
    }

    /** The destination chunk must already be loaded. A failure keeps the player's own inventory. */
    public boolean enter(Player player, String arena, Consumer<String> refusal) {
        if (holdsLooseItems(player)) {
            refusal.accept("Put away the items on your cursor or in your crafting grid, then try again.");
            return false;
        }
        player.closeInventory();
        var denied = entry.admit(player, arena);
        if (denied.isPresent()) {
            refusal.accept(denied.get().message());
            return false;
        }
        ArenaPoint room = entry.waitingRoom(arena).orElseThrow();
        Location target = new Location(world.get(), room.x(), room.y(), room.z(), room.yaw(), room.pitch());
        boolean moved;
        try {
            moved = player.teleport(target, TeleportCause.PLUGIN);
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
            onEnter.accept(player, arena);
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
