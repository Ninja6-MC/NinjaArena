package com.ninja6.ninjaarena.portal;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Lights player-built portals and removes them when their frame breaks.
 *
 * <p><b>Lighting.</b> Using flint and steel on a block face whose empty neighbour lies in a
 * frame of the configured block opens a portal there, if {@link PlayerPortals} allows it. The
 * use places no fire and wears the flint and steel as lighting a fire would. A frame that is
 * not complete, or of any other block, is left to vanilla, and so is a nether portal: obsidian
 * cannot be the frame block. A use another plugin denied, such as in a protected region, is
 * ignored.
 *
 * <p><b>Breaking.</b> Any frame block broken, burnt, faded, blown up, pushed or pulled by a
 * piston, or changed by an entity removes its portal, once nothing cancelled the change.
 * Blocks next to the frame, the corners and the opening do not count, so neighbouring block
 * updates leave a portal alone.
 */
public final class PlayerPortalListener implements Listener {

    private final PlayerPortals portals;
    private final PortalRegistry registry;

    public PlayerPortalListener(PlayerPortals portals, PortalRegistry registry) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onIgnite(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || item == null || item.getType() != Material.FLINT_AND_STEEL
                || event.useItemInHand() == Event.Result.DENY || event.getClickedBlock() == null) {
            return;
        }
        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        World world = target.getWorld();
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        if (registry.at(world.getName(), x, y, z) != null) {
            return;
        }
        Optional<ArenaBounds> opening = PortalFrame.find(PlayerPortals.blocks(world, portals.settings().frame()), x, y,
                z);
        if (opening.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        PlayerPortals.Result result = portals.build(player, world.getName(), opening.get());
        player.sendMessage(msg(result.built() ? "player-portal.built" : "player-portal.refused",
                "message", result.message()));
        if (result.built()) {
            if (player.getGameMode() != GameMode.CREATIVE && event.getHand() != null) {
                player.damageItemStack(event.getHand(), 1);
            }
            world.playSound(new Location(world, x + 0.5, y + 0.5, z + 0.5), "minecraft:item.flintandsteel.use", 1, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        gone(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        gone(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        gone(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (event.getTo() != event.getBlock().getType()) {
            gone(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        gone(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        gone(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        gone(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        gone(event.getBlocks());
    }

    private void gone(List<Block> blocks) {
        for (Block block : blocks) {
            gone(block);
        }
    }

    private void gone(Block block) {
        portals.frameBlockGone(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }
}
