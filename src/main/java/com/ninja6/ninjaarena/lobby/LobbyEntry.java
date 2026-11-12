package com.ninja6.ninjaarena.lobby;

import static com.ninja6.ninjaarena.message.Messages.text;

import com.ninja6.ninjaarena.portal.ArenaAdmission;
import com.ninja6.ninjaarena.room.RoomLobby;
import com.ninja6.ninjaarena.snapshot.SnapshotService.CaptureResult;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.entity.Player;

/**
 * The way into the lobby for rooms. A player who comes from outside the plugin's worlds has
 * their snapshot saved first, as a portal does, and is then made a lobby member; one who is
 * already a member stays as they are. A player in a match, a waiting room or the hub, or
 * spectating, is turned away. Server thread only.
 */
public final class LobbyEntry implements RoomLobby {

    private final LobbyMembers members;
    private final Predicate<UUID> elsewhere;
    private final Function<Player, CaptureResult> capture;
    private final Consumer<UUID> discard;

    /**
     * @param elsewhere whether a player is in a match, a waiting room or the hub, or spectating
     * @param capture   {@code SnapshotService#captureAndPersist}
     * @param discard   deletes the snapshot of a player the lobby did not take in
     */
    public LobbyEntry(LobbyMembers members, Predicate<UUID> elsewhere, Function<Player, CaptureResult> capture,
            Consumer<UUID> discard) {
        this.members = Objects.requireNonNull(members, "members");
        this.elsewhere = Objects.requireNonNull(elsewhere, "elsewhere");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.discard = Objects.requireNonNull(discard, "discard");
    }

    @Override
    public boolean isMember(UUID player) {
        return members.isMember(player);
    }

    @Override
    public List<UUID> members() {
        return members.members();
    }

    @Override
    public Optional<String> join(Player player) {
        UUID id = player.getUniqueId();
        if (members.isMember(id)) {
            return Optional.empty();
        }
        if (elsewhere.test(id)) {
            return Optional.of(text("room.busy"));
        }
        if (ArenaAdmission.holdsLooseItems(player)) {
            return Optional.of(text("entry.loose-items"));
        }
        player.closeInventory();
        switch (capture.apply(player)) {
            case SAVED -> { }
            case ALREADY_PENDING -> {
                return Optional.of(text("entry.snapshot-pending"));
            }
            case WRITE_FAILED -> {
                return Optional.of(text("entry.snapshot-failed"));
            }
            case PLUGIN_WORLD -> {
                return Optional.of(text("entry.plugin-world"));
            }
        }
        if (members.enter(player)) {
            return Optional.empty();
        }
        // A refused teleport left the player where they were, so the new snapshot must go.
        // A player who reached the lobby but could not be emptied was sent home from it already.
        if (!PluginWorlds.isLobby(player.getLocation())) {
            discard.accept(id);
        }
        return Optional.of(text("entry.teleport-failed"));
    }

    @Override
    public boolean enter(Player player) {
        return members.isMember(player.getUniqueId()) || members.enter(player);
    }
}
