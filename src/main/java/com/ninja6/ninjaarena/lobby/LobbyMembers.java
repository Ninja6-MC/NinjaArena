package com.ninja6.ninjaarena.lobby;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.restore.OwnTeleports;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Who is in the lobby: a player state like a waiting room. A member's inventory is locked,
 * the join restore and trophy delivery treat them as busy, and they stay inside the lobby
 * bounds ({@link LobbyLayout#bounds()}), which take the place of the waiting-room radius here.
 *
 * <p>{@link #enter} is the way in and {@link #leave} the way out. The caller of {@code enter}
 * has already saved the player's snapshot, as a portal does before a waiting room, because
 * leaving sends the player home from it. A player in a match, a waiting room or spectating
 * cannot enter.
 */
public final class LobbyMembers {

    /** Why a member left. */
    public enum Exit {
        /** The leave command; sent home at once. */
        COMMAND,
        /** Teleported out of the lobby world by something else; sent home on the next tick. */
        TELEPORTED,
        /**
         * Taken out by another NinjaArena feature that places the player itself, such as a room
         * starting its match; nothing is sent home.
         */
        MOVED,
        /** Disconnected; restored on their next join from the saved snapshot. */
        QUIT
    }

    private final Plugin plugin;
    private final LobbyLayout layout;
    private final Supplier<World> world;
    private final Predicate<UUID> elsewhere;
    private final Consumer<Player> empty;
    private Consumer<Player> arrival = player -> { };
    private final Function<Player, RestoreResult> home;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<UUID> homeBound = new HashSet<>();
    private final Set<UUID> returning = new HashSet<>();

    /**
     * @param world     the lobby world
     * @param elsewhere whether a player is in a match, a waiting room or the hub, or spectating
     * @param empty     readies an arriving player, as for a waiting room
     * @param home      sends a player home from their snapshot
     */
    public LobbyMembers(Plugin plugin, LobbyLayout layout, Supplier<World> world, Predicate<UUID> elsewhere,
            Consumer<Player> empty, Function<Player, RestoreResult> home) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.world = Objects.requireNonNull(world, "world");
        this.elsewhere = Objects.requireNonNull(elsewhere, "elsewhere");
        this.empty = Objects.requireNonNull(empty, "empty");
        this.home = Objects.requireNonNull(home, "home");
    }

    /** Runs after a successful entry; by default nothing is shown. */
    public void setArrival(Consumer<Player> arrival) {
        this.arrival = Objects.requireNonNull(arrival, "arrival");
    }

    public LobbyLayout layout() {
        return layout;
    }

    /** The lobby spawn in the lobby world. */
    public Location spawn() {
        return layout.spawn(world.get());
    }

    /**
     * Takes a player whose snapshot is saved to the lobby spawn and makes them a member. Their
     * inventory is emptied, as in a waiting room. Nothing changes for a player who is already
     * a member or busy elsewhere, or whom the teleport does not move.
     *
     * @return whether the player is now a member
     */
    public boolean enter(Player player) {
        UUID id = player.getUniqueId();
        if (members.contains(id) || elsewhere.test(id)) {
            return false;
        }
        if (!OwnTeleports.teleport(player, spawn(), TeleportCause.PLUGIN)) {
            return false;
        }
        try {
            empty.accept(player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not empty the inventory of " + player.getName() + " ("
                    + id + ") in the lobby; sending them home.", e);
            player.sendMessage(msg("lobby.join-failed"));
            sendHome(player);
            return false;
        }
        homeBound.remove(id);
        members.add(id);
        player.sendMessage(msg("lobby.joined"));
        arrival.accept(player);
        return true;
    }

    public boolean isMember(UUID id) {
        return members.contains(id);
    }

    /** Members in the order they entered. */
    public List<UUID> members() {
        return List.copyOf(members);
    }

    /**
     * Takes a member out of the lobby and, depending on why, sends them home.
     *
     * @return false if they were not a member
     */
    public boolean leave(Player player, Exit exit) {
        UUID id = player.getUniqueId();
        if (!members.remove(id)) {
            return false;
        }
        returning.remove(id);
        switch (exit) {
            case COMMAND -> {
                player.sendMessage(msg("lobby.left"));
                sendHome(player);
            }
            case TELEPORTED -> {
                player.sendMessage(msg("lobby.left"));
                // Not inside the teleport event that took the player out.
                homeBound.add(id);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!homeBound.remove(id)) {
                        return;
                    }
                    Player online = plugin.getServer().getPlayer(id);
                    if (online != null && online.isOnline()) {
                        sendHome(online);
                    }
                });
            }
            case MOVED, QUIT -> {
                // The feature that moved them places them; a quit is restored on the next join.
            }
        }
        return true;
    }

    /**
     * Puts a player back at the lobby spawn on the next tick, as when a member walks or falls
     * out of the bounds. Repeated calls before then return them once.
     */
    public void returnToSpawnLater(UUID id) {
        if (!returning.add(id)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!returning.remove(id)) {
                return;
            }
            Player online = plugin.getServer().getPlayer(id);
            if (online != null && online.isOnline() && !online.isDead()) {
                returnToSpawn(online);
            }
        });
    }

    /** Puts a player at the lobby spawn now, with no fall speed left to land with. */
    public void returnToSpawn(Player player) {
        player.setFallDistance(0);
        player.setVelocity(new Vector());
        OwnTeleports.teleport(player, spawn(), TeleportCause.PLUGIN);
    }

    /** Sends every member home. Called on plugin disable. */
    public void close() {
        List<UUID> leaving = new ArrayList<>(members);
        leaving.addAll(homeBound);
        members.clear();
        homeBound.clear();
        returning.clear();
        for (UUID id : leaving) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline()) {
                sendHome(player);
            }
        }
    }

    private void sendHome(Player player) {
        if (home.apply(player) == RestoreResult.FAILED) {
            player.sendMessage(msg("common.restore-failed"));
        }
    }
}
