package com.ninja6.ninjaarena.portal;

import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * Decides whether a player who walked into a portal may go through it. {@link #check} runs
 * the cheap tests on the move itself, so a refused player can be stopped at the edge. Server
 * thread only.
 *
 * <p>A play portal, whatever its arena or lobby choice, leads to the lobby, never to a match:
 * the lobby saves the player's snapshot on the way in. Its lobby choice, or the server
 * default {@code waiting-room.lobby} ({@link #setDefaultLobby}), now only decides which open
 * rooms the player joins there ({@link #roomArena}). A spectator portal leads to the live
 * match on its arena; {@link #admit} repeats its checks just before the teleport and then
 * saves the snapshot, which must succeed before the player leaves.
 */
public final class PortalEntry {

    /** The node a player needs to go through a play portal. */
    public static final String PLAY = "ninjaarena.play";

    /** Why a player was not let in, with the message they are shown. */
    public record Refusal(Reason reason, String message) {
    }

    public enum Reason {
        NO_PERMISSION,
        /** A spectator portal whose arena has no match to watch now. */
        UNAVAILABLE,
        /** The player has a snapshot from an earlier visit that has not been restored. */
        SNAPSHOT_PENDING,
        /** The snapshot could not be written. */
        SNAPSHOT_FAILED,
        /** The player stands in the arena or lobby world, where no snapshot is taken. */
        PLUGIN_WORLD
    }

    public static final String SPECTATE = "ninjaarena.spectate";

    private final Function<String, Optional<ArenaPoint>> spectatorPoint;
    private final Predicate<UUID> pendingSnapshot;
    private final Function<Player, CaptureResult> capture;
    private Supplier<LobbyMode> defaultLobby = () -> LobbyMode.DEDICATED;

    /**
     * @param pendingSnapshot {@code SnapshotService#hasPending}
     * @param capture         {@code SnapshotService#captureAndPersist}
     */
    public PortalEntry(Predicate<UUID> pendingSnapshot, Function<Player, CaptureResult> capture) {
        this(pendingSnapshot, capture, arena -> Optional.empty());
    }

    /** @param spectatorPoint where a viewer of the arena's live match stands, if it can be watched now */
    public PortalEntry(Predicate<UUID> pendingSnapshot, Function<Player, CaptureResult> capture,
            Function<String, Optional<ArenaPoint>> spectatorPoint) {
        this.spectatorPoint = Objects.requireNonNull(spectatorPoint, "spectatorPoint");
        this.pendingSnapshot = Objects.requireNonNull(pendingSnapshot, "pendingSnapshot");
        this.capture = Objects.requireNonNull(capture, "capture");
    }

    /**
     * The deprecated {@code waiting-room.lobby}, read at each entry, for portals with no lobby
     * choice of their own. By default it is dedicated.
     */
    public void setDefaultLobby(Supplier<LobbyMode> defaultLobby) {
        this.defaultLobby = Objects.requireNonNull(defaultLobby, "defaultLobby");
    }

    /**
     * Whose open rooms a player who comes through a play portal joins: those on the portal's
     * arena while its lobby choice is dedicated, or any room (empty) while it is shared, as
     * the old shared hub took players to any arena. A player-built portal has no arena and
     * joins any room.
     */
    public Optional<String> roomArena(Portal portal) {
        if (portal.spectator() || portal.arena() == null || portal.lobby(defaultLobby.get()) == LobbyMode.SHARED) {
            return Optional.empty();
        }
        return Optional.of(portal.arena());
    }

    /** Everything except the snapshot write. Empty if the player may go on. */
    public Optional<Refusal> check(Player player, Portal portal) {
        if (!portal.spectator()) {
            if (!player.hasPermission(PLAY)) {
                return refuse(Reason.NO_PERMISSION, text("entry.no-permission"));
            }
            return pendingSnapshot.test(player.getUniqueId()) ? pending() : Optional.empty();
        }
        if (!player.hasPermission(SPECTATE)) {
            return refuse(Reason.NO_PERMISSION, text("entry.no-spectate-permission"));
        }
        if (destination(portal).isEmpty()) {
            return refuse(Reason.UNAVAILABLE, text("entry.no-spectator-match"));
        }
        return pendingSnapshot.test(player.getUniqueId()) ? pending() : Optional.empty();
    }

    /**
     * Repeats {@link #check} for a spectator portal and then saves the player's snapshot.
     * Empty means the snapshot is on disk and the player must now be teleported to
     * {@link #destination}; if that teleport fails, the caller deletes the snapshot again.
     */
    public Optional<Refusal> admit(Player player, Portal portal) {
        Optional<Refusal> refusal = check(player, portal);
        if (refusal.isPresent()) {
            return refusal;
        }
        return switch (capture.apply(player)) {
            case SAVED -> Optional.empty();
            case ALREADY_PENDING -> pending();
            case WRITE_FAILED -> refuse(Reason.SNAPSHOT_FAILED, text("entry.snapshot-failed"));
            case PLUGIN_WORLD -> refuse(Reason.PLUGIN_WORLD, text("entry.plugin-world"));
        };
    }

    /** Where a spectator portal seats its viewers now, if its arena's match can be watched. */
    public Optional<ArenaPoint> destination(Portal portal) {
        return portal.spectator() ? spectatorPoint.apply(portal.arena()) : Optional.empty();
    }

    private static Optional<Refusal> pending() {
        return refuse(Reason.SNAPSHOT_PENDING, text("entry.snapshot-pending"));
    }

    private static Optional<Refusal> refuse(Reason reason, String message) {
        return Optional.of(new Refusal(reason, message));
    }
}
