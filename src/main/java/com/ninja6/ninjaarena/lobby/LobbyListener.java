package com.ninja6.ninjaarena.lobby;

import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * The rules of the lobby world and of lobby membership.
 *
 * <p>No damage is taken in the lobby world, by anyone or anything. A player who falls into
 * the void there is put back at the lobby spawn instead, so a fall never kills, and never
 * counts as a death in a match. A member who leaves the lobby bounds, by falling off or
 * climbing out, is put back at the spawn as well, and one teleported out of the lobby world
 * stops being a member.
 *
 * <p>Nobody can break or place blocks, place entities such as armour stands or boats, use
 * buckets, or change hanging items or armour stands in the lobby world, except an admin in lobby edit mode. Environmental changes are stopped
 * separately, by {@code ArenaProtectionListener}.
 */
public final class LobbyListener implements Listener {

    private final LobbyMembers members;
    private final LobbyEditing editing;

    public LobbyListener(LobbyMembers members, LobbyEditing editing) {
        this.members = Objects.requireNonNull(members, "members");
        this.editing = Objects.requireNonNull(editing, "editing");
    }

    /** First, so no other listener sees lobby damage as anything but cancelled. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!PluginWorlds.isLobby(entity.getWorld())) {
            return;
        }
        if (!(entity instanceof Player) && event instanceof EntityDamageByEntityEvent hit
                && editing(hit.getDamager())) {
            // An editor may break the armour stands and item frames they are arranging.
            return;
        }
        event.setCancelled(true);
        if (entity instanceof Player player && event.getCause() == DamageCause.VOID) {
            members.returnToSpawn(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && PluginWorlds.isLobby(player.getWorld())
                && members.isMember(player.getUniqueId()) && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.hasChangedBlock() && members.isMember(player.getUniqueId())
                && !members.layout().contains(event.getTo())) {
            members.returnToSpawnLater(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (!PluginWorlds.isLobby(to)) {
            editing.stop(player, true);
        }
        if (!members.isMember(player.getUniqueId())) {
            return;
        }
        if (!PluginWorlds.isLobby(to)) {
            members.leave(player, OwnTeleports.isActive(player.getUniqueId())
                    ? LobbyMembers.Exit.MOVED : LobbyMembers.Exit.TELEPORTED);
        } else if (!members.layout().contains(to)) {
            members.returnToSpawnLater(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        members.leave(event.getPlayer(), LobbyMembers.Exit.QUIT);
        editing.stop(event.getPlayer(), false);
    }

    /** A member who died anyway, through another plugin, comes back at the lobby spawn. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (members.isMember(event.getPlayer().getUniqueId())) {
            event.setRespawnLocation(members.spawn());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        protect(event.getPlayer(), event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        protect(event.getPlayer(), event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        protect(event.getPlayer(), event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        protect(event.getPlayer(), event.getBlock().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        protect(event.getPlayer(), event.getEntity().getWorld(), event);
    }

    /** Armour stands, boats, minecarts and end crystals; a placement with no player is left alone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null) {
            protect(event.getPlayer(), event.getEntity().getWorld(), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (PluginWorlds.isLobby(event.getEntity().getWorld()) && !editing(event.getRemover())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmourStand(PlayerArmorStandManipulateEvent event) {
        protect(event.getPlayer(), event.getRightClicked().getWorld(), event);
    }

    /** Turning an item frame or taking its item. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame frame) {
            protect(event.getPlayer(), frame.getWorld(), event);
        }
    }

    private void protect(Player player, org.bukkit.World world, Cancellable event) {
        if (PluginWorlds.isLobby(world) && (player == null || !editing.isEditing(player.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    /** Whether the entity, or whoever shot it, is an admin in lobby edit mode. */
    private boolean editing(Entity entity) {
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            entity = shooter;
        }
        return entity instanceof Player player && editing.isEditing(player.getUniqueId());
    }
}
