package com.ninja6.ninjaarena.restore;

import com.ninja6.ninjaarena.snapshot.PlayerSnapshot;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/**
 * Puts players back the way they were before arena entry. Leaving an arena and the end of
 * a match call {@link #restore(Player)}; a player who was offline when that should have
 * happened, because they disconnected mid-arena or the server stopped, is restored when
 * they next join.
 *
 * <p>The snapshot is deleted only after the restore has fully succeeded. Anything that
 * goes wrong before that leaves it on disk, so the next attempt starts from the same data.
 * Server thread only.
 */
public final class RestoreService {

    /** Outcome of {@link #restore(Player)}. */
    public enum RestoreResult {
        /** The player is home and the snapshot is gone. */
        RESTORED,
        /** There was nothing to restore. */
        NO_SNAPSHOT,
        /**
         * The player is dead, for example back on the death screen after disconnecting on it.
         * The snapshot is kept and the restore runs again when they respawn.
         */
        AWAITING_RESPAWN,
        /** The player was not restored; the snapshot is kept for the next attempt. */
        FAILED,
        /**
         * The player is home but the snapshot could not be deleted. Their next join restores
         * it again, which puts back the same state rather than adding to it.
         */
        CLEANUP_FAILED
    }

    /** Applies a snapshot to an online player. */
    @FunctionalInterface
    public interface Applier {

        /**
         * Moves the player to a safe spot and restores their state.
         *
         * @throws RestoreException if the player could not be restored; whatever was already
         *                          applied is overwritten by the next attempt
         */
        void apply(Player player, PlayerSnapshot snapshot) throws RestoreException;
    }

    /** A restore that did not complete. */
    public static final class RestoreException extends Exception {

        private static final long serialVersionUID = 1L;

        public RestoreException(String message) {
            super(message);
        }

        public RestoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SnapshotService snapshots;
    private final Applier applier;
    private final Logger logger;
    private final Set<UUID> awaitingJoin = new HashSet<>();
    private final Set<UUID> awaitingRespawn = new HashSet<>();

    public RestoreService(SnapshotService snapshots, Applier applier, Logger logger) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.applier = Objects.requireNonNull(applier, "applier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Marks every snapshot left on disk, from a crash or a stop mid-match, so its player is
     * restored when they join.
     */
    public void markPendingOnStartup() {
        List<UUID> pending;
        try {
            pending = snapshots.listPending();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not list pending snapshots; players are still"
                    + " checked one by one as they join.", e);
            return;
        }
        awaitingJoin.addAll(pending);
        if (!pending.isEmpty()) {
            logger.info(pending.size() + " player(s) have an unrestored snapshot and will be"
                    + " restored when they join.");
        }
    }

    /** Called when a player leaves the server; a pending snapshot is restored on rejoin. */
    public void markIfPending(UUID playerId) {
        // A rejoin decides again, so a death screen left on quit is not carried over.
        awaitingRespawn.remove(playerId);
        if (snapshots.hasPending(playerId)) {
            awaitingJoin.add(playerId);
        }
    }

    /** Whether a player joining now should be restored. */
    public boolean shouldRestoreOnJoin(UUID playerId) {
        // The disk check also covers a snapshot that appeared while the server was running.
        return awaitingJoin.contains(playerId) || snapshots.hasPending(playerId);
    }

    /**
     * Whether a player who has just respawned should be restored now: only one whose
     * restore was put off because they were dead. A respawn inside a running match must not
     * send the player home.
     */
    public boolean shouldRestoreOnRespawn(UUID playerId) {
        return awaitingRespawn.contains(playerId);
    }

    /** Players marked to be restored when they join. */
    public Set<UUID> awaitingJoin() {
        return Set.copyOf(awaitingJoin);
    }

    /** Restores the player from their pending snapshot, if they have one. */
    public RestoreResult restore(Player player) {
        UUID playerId = player.getUniqueId();
        Optional<PlayerSnapshot> snapshot;
        try {
            snapshot = snapshots.load(playerId);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not read the snapshot of " + player.getName() + " ("
                    + playerId + "); it is kept on disk and they were not restored.", e);
            return RestoreResult.FAILED;
        }
        if (snapshot.isEmpty()) {
            awaitingJoin.remove(playerId);
            awaitingRespawn.remove(playerId);
            return RestoreResult.NO_SNAPSHOT;
        }
        if (player.isDead()) {
            // A dead player cannot be teleported; the respawn listener tries again.
            awaitingRespawn.add(playerId);
            logger.info(player.getName() + " is dead; they will be restored when they respawn.");
            return RestoreResult.AWAITING_RESPAWN;
        }
        try {
            applier.apply(player, snapshot.get());
        } catch (RestoreException | RuntimeException e) {
            logger.log(Level.SEVERE, "Could not restore " + player.getName() + " (" + playerId
                    + "); the snapshot is kept for the next attempt.", e);
            return RestoreResult.FAILED;
        }
        awaitingJoin.remove(playerId);
        awaitingRespawn.remove(playerId);
        try {
            snapshots.delete(playerId);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Restored " + player.getName() + " (" + playerId
                    + ") but could not delete the snapshot.", e);
            return RestoreResult.CLEANUP_FAILED;
        }
        return RestoreResult.RESTORED;
    }
}
