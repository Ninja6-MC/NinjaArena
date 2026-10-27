package com.ninja6.ninjaarena.restore;

import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotEffect;
import com.ninja6.ninjaarena.snapshot.SnapshotLocation;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * The live-server side of a restore: picks a safe landing, teleports the player there and
 * writes the snapshot back onto them.
 *
 * <p>The landing is the saved spot if it is safe, else the nearest safe spot around it (see
 * {@link SafeLanding}), else the player's respawn point if that is safe, else the spawn of
 * the main world.
 */
public final class PlayerRestorer implements RestoreService.Applier {

    private final Logger logger;

    public PlayerRestorer(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void apply(Player player, PlayerSnapshot snapshot) throws RestoreService.RestoreException {
        Landing landing = landing(player, snapshot.location());
        if (landing.source() != Landing.Source.SAVED) {
            logger.info("The saved spot of " + player.getName() + " is unsafe; returning them to "
                    + landing.source().description + " instead.");
        }

        // A player who rides something or carries a passenger cannot be teleported.
        player.leaveVehicle();
        player.eject();
        player.setGliding(false);
        if (!player.teleport(landing.location(), TeleportCause.PLUGIN)) {
            throw new RestoreService.RestoreException("the teleport of " + player.getName()
                    + " was cancelled");
        }

        restoreState(player, snapshot);

        player.setVelocity(new Vector());
        player.setGliding(false);
        // A fall in progress at entry only carries over to the spot it started from.
        player.setFallDistance(landing.source() == Landing.Source.SAVED ? snapshot.fallDistance() : 0);
    }

    /** Where to put the player, by the order in the class description. */
    Landing landing(Player player, SnapshotLocation saved) {
        World world = savedWorld(saved);
        if (world != null) {
            Optional<SafeLanding.Spot> spot = SafeLanding.find(
                    new WorldTerrain(world), saved.x(), saved.y(), saved.z());
            if (spot.isPresent()) {
                SafeLanding.Spot found = spot.get();
                return new Landing(
                        new Location(world, found.x(), found.y(), found.z(), saved.yaw(), saved.pitch()),
                        found.moved() ? Landing.Source.NEARBY : Landing.Source.SAVED);
            }
        }
        Location respawn = player.getRespawnLocation();
        if (respawn != null && respawn.getWorld() != null && SafeLanding.isSafe(
                new WorldTerrain(respawn.getWorld()), respawn.getX(), respawn.getY(), respawn.getZ())) {
            return new Landing(respawn, Landing.Source.RESPAWN);
        }
        return new Landing(Bukkit.getWorlds().getFirst().getSpawnLocation(), Landing.Source.WORLD_SPAWN);
    }

    /** By UID first; by name if the world was recreated under a new UID. */
    private static World savedWorld(SnapshotLocation saved) {
        World world = Bukkit.getWorld(saved.worldId());
        return world != null ? world : Bukkit.getWorld(saved.worldName());
    }

    private void restoreState(Player player, PlayerSnapshot snapshot) {
        player.setGameMode(snapshot.gameMode());

        // Whatever is on the cursor or in the crafting grid came from the arena.
        player.setItemOnCursor(null);
        player.closeInventory();
        PlayerInventory inventory = player.getInventory();
        inventory.setStorageContents(ItemStack.deserializeItemsFromBytes(snapshot.storage()));
        inventory.setArmorContents(ItemStack.deserializeItemsFromBytes(snapshot.armour()));
        ItemStack[] offhand = ItemStack.deserializeItemsFromBytes(snapshot.offhand());
        inventory.setItemInOffHand(offhand.length > 0 ? offhand[0] : null);

        player.setTotalExperience(snapshot.totalExperience());
        player.setLevel(snapshot.level());
        player.setExp(snapshot.exp());

        player.setHealth(health(player, snapshot.health()));
        player.setFoodLevel(snapshot.foodLevel());
        player.setSaturation(snapshot.saturation());
        player.setExhaustion(snapshot.exhaustion());

        for (PotionEffect active : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(active.getType());
        }
        for (SnapshotEffect effect : snapshot.effects()) {
            PotionEffectType type = effectType(effect.type());
            if (type == null) {
                logger.warning("Dropping the unknown effect " + effect.type() + " from the snapshot of "
                        + player.getName());
                continue;
            }
            player.addPotionEffect(new PotionEffect(type, effect.duration(), effect.amplifier(),
                    effect.ambient(), effect.particles(), effect.icon()));
        }

        player.setFireTicks(snapshot.fireTicks());
    }

    /** The saved health, kept within what the player can have now and never zero. */
    private static double health(Player player, double saved) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        double max = attribute != null ? attribute.getValue() : 20;
        if (!(saved > 0)) {
            return max;
        }
        return Math.min(saved, max);
    }

    private static PotionEffectType effectType(String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        return namespacedKey == null ? null : Registry.MOB_EFFECT.get(namespacedKey);
    }

    /** A landing location and which step of the fallback order chose it. */
    record Landing(Location location, Source source) {

        enum Source {
            SAVED("their saved spot"),
            NEARBY("a safe spot near where they entered"),
            RESPAWN("their respawn point"),
            WORLD_SPAWN("the world spawn");

            final String description;

            Source(String description) {
                this.description = description;
            }
        }
    }
}
