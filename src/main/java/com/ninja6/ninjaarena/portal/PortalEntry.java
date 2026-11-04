package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.entity.Player;

/**
 * Decides whether a player who walked into a portal may enter its arena. {@link #check}
 * runs the cheap tests on the move itself, so a refused player can be stopped at the edge;
 * {@link #admit} repeats them just before the teleport and then saves the player's snapshot,
 * which must succeed before they leave. Server thread only.
 */
public final class PortalEntry {

    /** The node a player needs to enter an arena. */
    public static final String PLAY = "ninjaarena.play";

    /** Why a player was not let in, with the message they are shown. */
    public record Refusal(Reason reason, String message) {
    }

    public enum Reason {
        NO_PERMISSION,
        /** The arena was deleted, or has no waiting room to send the player to. */
        NO_ARENA,
        /** In edit mode, in use by a match, or not complete. */
        UNAVAILABLE,
        /** The player has a snapshot from an earlier visit that has not been restored. */
        SNAPSHOT_PENDING,
        /** The snapshot could not be written. */
        SNAPSHOT_FAILED
    }

    public static final String SPECTATE = "ninjaarena.spectate";

    private final ArenaRegistry arenas;
    private final Function<String, Optional<ArenaPoint>> spectatorPoint;
    private final Predicate<UUID> pendingSnapshot;
    private final Function<Player, CaptureResult> capture;

    /**
     * @param pendingSnapshot {@code SnapshotService#hasPending}
     * @param capture         {@code SnapshotService#captureAndPersist}
     */
    public PortalEntry(ArenaRegistry arenas, Predicate<UUID> pendingSnapshot, Function<Player, CaptureResult> capture) {
        this(arenas, pendingSnapshot, capture, arena -> Optional.empty());
    }

    public PortalEntry(ArenaRegistry arenas, Predicate<UUID> pendingSnapshot, Function<Player, CaptureResult> capture,
            Function<String, Optional<ArenaPoint>> spectatorPoint) {
        this.arenas = Objects.requireNonNull(arenas, "arenas");
        this.spectatorPoint = Objects.requireNonNull(spectatorPoint, "spectatorPoint");
        this.pendingSnapshot = Objects.requireNonNull(pendingSnapshot, "pendingSnapshot");
        this.capture = Objects.requireNonNull(capture, "capture");
    }

    /** Everything except the snapshot write. Empty if the player may go on. */
    public Optional<Refusal> check(Player player, Portal portal) {
        if (!portal.spectator()) {
            return check(player, portal.arena());
        }
        if (!player.hasPermission(SPECTATE)) {
            return refuse(Reason.NO_PERMISSION, "You do not have permission to spectate matches.");
        }
        if (destination(portal).isEmpty()) {
            return refuse(Reason.UNAVAILABLE, "This portal does not lead to a match open to spectators.");
        }
        return pendingSnapshot.test(player.getUniqueId()) ? pending() : Optional.empty();
    }

    /** The same admission checks for an explicitly accepted invitation. */
    public Optional<Refusal> check(Player player, String arenaName) {
        if (!player.hasPermission(PLAY)) {
            return refuse(Reason.NO_PERMISSION, "You do not have permission to enter arenas.");
        }
        Optional<Arena> arena = arenas.get(arenaName);
        if (arena.isEmpty() || arena.get().waitingRoom() == null) {
            return refuse(Reason.NO_ARENA, "This portal does not lead to an arena.");
        }
        String name = arena.get().name();
        if (!arenas.isAvailable(name)) {
            if (arenas.isHeld(name)) {
                return refuse(Reason.UNAVAILABLE, "A match is already running in arena " + name + ".");
            }
            if (!arenas.editing().isEmpty()) {
                return refuse(Reason.UNAVAILABLE, "Arenas are being edited right now. Try again later.");
            }
            return refuse(Reason.UNAVAILABLE, "Arena " + name + " is not ready yet.");
        }
        if (pendingSnapshot.test(player.getUniqueId())) {
            return pending();
        }
        return Optional.empty();
    }

    /**
     * Repeats {@link #check} and then saves the player's snapshot. Empty means the snapshot
     * is on disk and the player must now be teleported to {@link #waitingRoom}; if that
     * teleport fails, the caller deletes the snapshot again.
     */
    public Optional<Refusal> admit(Player player, Portal portal) {
        return capture(player, check(player, portal));
    }

    public Optional<Refusal> admit(Player player, String arenaName) {
        return capture(player, check(player, arenaName));
    }

    private Optional<Refusal> capture(Player player, Optional<Refusal> refusal) {
        if (refusal.isPresent()) {
            return refusal;
        }
        return switch (capture.apply(player)) {
            case SAVED -> Optional.empty();
            case ALREADY_PENDING -> pending();
            case WRITE_FAILED -> refuse(Reason.SNAPSHOT_FAILED,
                    "Your belongings could not be saved, so you were not sent to the arena.");
        };
    }

    /** The waiting-room point of the arena bound to this portal. */
    public Optional<ArenaPoint> waitingRoom(Portal portal) {
        return waitingRoom(portal.arena());
    }

    /** Where the portal sends players, if its destination is currently available. */
    public Optional<ArenaPoint> destination(Portal portal) {
        return portal.spectator() ? spectatorPoint.apply(portal.arena()) : waitingRoom(portal);
    }

    public Optional<ArenaPoint> waitingRoom(String arenaName) {
        return arenas.get(arenaName).map(Arena::waitingRoom);
    }

    private static Optional<Refusal> pending() {
        return refuse(Reason.SNAPSHOT_PENDING, "Your belongings from an earlier arena visit have not been"
                + " returned yet, so you cannot enter. Rejoin the server, or ask an admin if this keeps happening.");
    }

    private static Optional<Refusal> refuse(Reason reason, String message) {
        return Optional.of(new Refusal(reason, message));
    }
}
