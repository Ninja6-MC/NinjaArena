package com.ninja6.ninjaarena.snapshot;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

/**
 * Captures and keeps the pre-entry state of players. Arena entry calls
 * {@link #captureAndPersist(Player)} before teleporting and must abort unless it returns
 * {@link CaptureResult#SAVED}; the restore path reads the snapshot back with
 * {@link #load(UUID)} and calls {@link #delete(UUID)} once the player is home.
 *
 * <p>All methods are meant for the server thread: the write is synchronous because the
 * teleport must not happen before the snapshot is on disk.
 */
public final class SnapshotService {

    /** Outcome of a capture. Anything other than {@link #SAVED} means: do not enter. */
    public enum CaptureResult {
        /** The snapshot is on disk. */
        SAVED,
        /**
         * The player already has a snapshot that has not been restored. Overwriting it
         * would replace their real belongings with whatever they hold now, so it is kept.
         */
        ALREADY_PENDING,
        /** The snapshot could not be written; nothing on disk changed. */
        WRITE_FAILED
    }

    private final SnapshotStore store;
    private final Logger logger;
    private final Clock clock;

    public SnapshotService(SnapshotStore store, Logger logger) {
        this(store, logger, Clock.systemUTC());
    }

    SnapshotService(SnapshotStore store, Logger logger, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Snapshots the player as they are now and writes it to disk. */
    public CaptureResult captureAndPersist(Player player) {
        PlayerSnapshot snapshot;
        try {
            snapshot = capture(player);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not capture the state of " + player.getName(), e);
            return CaptureResult.WRITE_FAILED;
        }
        return persist(snapshot);
    }

    /** Writes an already captured snapshot, with the same rules as {@link #captureAndPersist}. */
    public CaptureResult persist(PlayerSnapshot snapshot) {
        if (store.exists(snapshot.playerId())) {
            return refusePending(snapshot.playerId());
        }
        try {
            store.save(snapshot);
            return CaptureResult.SAVED;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "Could not write the snapshot of " + snapshot.playerName()
                    + " (" + snapshot.playerId() + ")", e);
            return CaptureResult.WRITE_FAILED;
        }
    }

    /**
     * @return empty if the player has no snapshot
     * @throws IOException if the snapshot exists but cannot be read; it is left on disk
     */
    public Optional<PlayerSnapshot> load(UUID playerId) throws IOException {
        return store.load(playerId);
    }

    public boolean hasPending(UUID playerId) {
        return store.exists(playerId);
    }

    /** Players whose snapshot is still on disk, for example after a crash mid-match. */
    public List<UUID> listPending() throws IOException {
        return store.listPending();
    }

    /** Drops a snapshot once it has been restored. */
    public boolean delete(UUID playerId) throws IOException {
        return store.delete(playerId);
    }

    PlayerSnapshot capture(Player player) {
        Location location = player.getLocation();
        PlayerInventory inventory = player.getInventory();
        List<SnapshotEffect> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(new SnapshotEffect(
                    effect.getType().key().asString(),
                    effect.getDuration(),
                    effect.getAmplifier(),
                    effect.isAmbient(),
                    effect.hasParticles(),
                    effect.hasIcon()));
        }
        return new PlayerSnapshot(
                player.getUniqueId(),
                player.getName(),
                clock.instant(),
                new SnapshotLocation(
                        location.getWorld().getUID(),
                        location.getWorld().getName(),
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch()),
                ItemStack.serializeItemsAsBytes(inventory.getStorageContents()),
                ItemStack.serializeItemsAsBytes(inventory.getArmorContents()),
                ItemStack.serializeItemsAsBytes(new ItemStack[] {inventory.getItemInOffHand()}),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                effects,
                player.getGameMode(),
                player.getFireTicks(),
                player.getFallDistance());
    }

    private CaptureResult refusePending(UUID playerId) {
        logger.warning("Not overwriting the unrestored snapshot of " + playerId);
        return CaptureResult.ALREADY_PENDING;
    }
}
