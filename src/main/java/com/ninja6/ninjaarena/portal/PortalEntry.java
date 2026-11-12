package com.ninja6.ninjaarena.portal;

import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * Decides whether a player who walked into a portal may enter its arena. {@link #check}
 * runs the cheap tests on the move itself, so a refused player can be stopped at the edge;
 * {@link #admit} repeats them just before the teleport and then saves the player's snapshot,
 * which must succeed before they leave. Server thread only.
 *
 * <p>A portal leads to its arena's waiting room or to the shared hub, by its own lobby choice
 * or the server default ({@link #setLobbies}). A full waiting room turns players away before
 * their snapshot is saved; the hub takes everyone.
 *
 * <p>A player-built portal has no arena of its own and always follows the server default.
 * Under the shared lobby it leads to the hub like any other portal; under the dedicated one
 * each entry is {@linkplain #route routed} to a waiting room that is filling, else to a free
 * arena, and refused with {@link Reason#BUSY} when there is neither. From there the same
 * checks and snapshot apply as for a portal bound to that arena.
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
        /** The arena's waiting room already holds as many players as one match takes. */
        FULL,
        /** The portal leads to the shared hub, but no hub point is set. */
        NO_HUB,
        /** A player-built portal found no waiting room that is filling and no free arena. */
        BUSY,
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
    private Supplier<LobbyMode> defaultLobby = () -> LobbyMode.DEDICATED;
    private Supplier<Optional<ArenaPoint>> hub = Optional::empty;
    private Function<String, Optional<String>> roomFull = arena -> Optional.empty();
    private Supplier<Optional<String>> openArena = Optional::empty;

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

    /**
     * Connects the lobby choice. By default every portal is dedicated and no room is ever full.
     *
     * @param defaultLobby where portals with no choice of their own lead, read at each entry
     * @param hub          the shared hub point, if one is set
     * @param roomFull     why an arena's waiting room cannot take another player, if it is full
     */
    public void setLobbies(Supplier<LobbyMode> defaultLobby, Supplier<Optional<ArenaPoint>> hub,
            Function<String, Optional<String>> roomFull) {
        this.defaultLobby = Objects.requireNonNull(defaultLobby, "defaultLobby");
        this.hub = Objects.requireNonNull(hub, "hub");
        this.roomFull = Objects.requireNonNull(roomFull, "roomFull");
    }

    /**
     * Connects the routing of player-built portals. By default no arena is ever open to them.
     *
     * @param openArena the arena a player-built portal sends the next player to, if any,
     *                  {@code WaitingRoomService#openArena} on a server
     */
    public void setRouting(Supplier<Optional<String>> openArena) {
        this.openArena = Objects.requireNonNull(openArena, "openArena");
    }

    /** Whether a player walking into the portal now goes to the shared hub. */
    public boolean leadsToHub(Portal portal) {
        return !portal.spectator() && portal.lobby(defaultLobby.get()) == LobbyMode.SHARED;
    }

    /**
     * The portal one entry uses. A player-built portal that does not lead to the hub is bound
     * to the arena it sends the player to now, or empty when every arena is busy; any other
     * portal is returned as it is.
     */
    public Optional<Portal> route(Portal portal) {
        if (!portal.playerBuilt() || portal.arena() != null || leadsToHub(portal)) {
            return Optional.of(portal);
        }
        return openArena.get().map(portal::boundTo);
    }

    /** Everything except the snapshot write. Empty if the player may go on. */
    public Optional<Refusal> check(Player player, Portal portal) {
        if (leadsToHub(portal)) {
            return checkHub(player);
        }
        if (!portal.spectator()) {
            if (portal.arena() == null) {
                if (!player.hasPermission(PLAY)) {
                    return refuse(Reason.NO_PERMISSION, text("entry.no-permission"));
                }
                Optional<Portal> routed = route(portal);
                if (routed.isEmpty()) {
                    return refuse(Reason.BUSY, text("entry.busy"));
                }
                return check(player, routed.get().arena());
            }
            return check(player, portal.arena());
        }
        if (!player.hasPermission(SPECTATE)) {
            return refuse(Reason.NO_PERMISSION, text("entry.no-spectate-permission"));
        }
        if (destination(portal).isEmpty()) {
            return refuse(Reason.UNAVAILABLE, text("entry.no-spectator-match"));
        }
        return pendingSnapshot.test(player.getUniqueId()) ? pending() : Optional.empty();
    }

    /** The same admission checks for an explicitly accepted invitation. */
    public Optional<Refusal> check(Player player, String arenaName) {
        if (!player.hasPermission(PLAY)) {
            return refuse(Reason.NO_PERMISSION, text("entry.no-permission"));
        }
        Optional<Arena> arena = arenas.get(arenaName);
        if (arena.isEmpty() || arena.get().waitingRoom() == null) {
            return refuse(Reason.NO_ARENA, text("entry.no-arena"));
        }
        String name = arena.get().name();
        if (!arenas.isAvailable(name)) {
            if (arenas.isHeld(name)) {
                return refuse(Reason.UNAVAILABLE, text("entry.match-running", "arena", name));
            }
            if (!arenas.editing().isEmpty()) {
                return refuse(Reason.UNAVAILABLE, text("entry.editing"));
            }
            return refuse(Reason.UNAVAILABLE, text("entry.not-ready", "arena", name));
        }
        Optional<String> full = roomFull.apply(name);
        if (full.isPresent()) {
            return refuse(Reason.FULL, full.get());
        }
        if (pendingSnapshot.test(player.getUniqueId())) {
            return pending();
        }
        return Optional.empty();
    }

    /**
     * The checks for the shared hub. No arena needs to be free: players wait in the hub, even
     * while arenas are in use or being edited, until one is.
     */
    private Optional<Refusal> checkHub(Player player) {
        if (!player.hasPermission(PLAY)) {
            return refuse(Reason.NO_PERMISSION, text("entry.no-permission"));
        }
        if (hub.get().isEmpty()) {
            return refuse(Reason.NO_HUB, text("entry.no-hub"));
        }
        return pendingSnapshot.test(player.getUniqueId()) ? pending() : Optional.empty();
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
            case WRITE_FAILED -> refuse(Reason.SNAPSHOT_FAILED, text("entry.snapshot-failed"));
        };
    }

    /** The waiting-room point of the arena bound to this portal. */
    public Optional<ArenaPoint> waitingRoom(Portal portal) {
        return portal.arena() == null ? Optional.empty() : waitingRoom(portal.arena());
    }

    /** Where the portal sends players, if its destination is currently available. */
    public Optional<ArenaPoint> destination(Portal portal) {
        if (leadsToHub(portal)) {
            return hub.get();
        }
        if (portal.spectator()) {
            return spectatorPoint.apply(portal.arena());
        }
        return route(portal).flatMap(this::waitingRoom);
    }

    public Optional<ArenaPoint> waitingRoom(String arenaName) {
        return arenas.get(arenaName).map(Arena::waitingRoom);
    }

    private static Optional<Refusal> pending() {
        return refuse(Reason.SNAPSHOT_PENDING, text("entry.snapshot-pending"));
    }

    private static Optional<Refusal> refuse(Reason reason, String message) {
        return Optional.of(new Refusal(reason, message));
    }
}
