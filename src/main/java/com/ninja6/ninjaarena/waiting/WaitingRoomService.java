package com.ninja6.ninjaarena.waiting;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs every arena's waiting room: puts players who come through a portal on a team, ticks
 * the start countdowns once a second, and sends players home through the restore flow when
 * they leave. The rules for teams and countdowns are in {@link WaitingRoom}.
 *
 * <p><b>Leaving.</b> A player leaves the waiting room by walking or falling more than
 * {@link WaitingRoomSettings#radius()} blocks from it, by being teleported out of it for any
 * reason, by dying, by the leave command, or by disconnecting. Each of these restores them
 * from their snapshot, except a disconnect: that snapshot is restored when they next join.
 * A player is taken out of the room before the restore runs, so the restore's own teleport
 * is not seen as another exit.
 *
 * <p><b>Start.</b> When a countdown finishes, the room is closed and its players are handed
 * to the {@link MatchStart}, which owns them from then on.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class WaitingRoomService {

    /** How a player left the waiting room. */
    public enum Exit {
        /** The leave command; restored at once. */
        COMMAND,
        /** Walked or fell out of the room; restored on the next tick. */
        WALKED_OUT,
        /** Teleported out of the room; restored on the next tick, once the teleport is done. */
        TELEPORTED,
        /** Died; restored on the next tick, or on respawn if they are still dead. */
        DIED,
        /** Disconnected; restored on their next join. */
        QUIT
    }

    private static final long TICKS_PER_SECOND = 20;

    private record Room(String arena, ArenaPoint point, WaitingRoom state) {
    }

    private final Plugin plugin;
    private final ArenaRegistry arenas;
    private final WaitingRoomSettings settings;
    private final Function<Player, RestoreResult> restore;
    private final MatchStart matchStart;

    private final Map<String, Room> rooms = new TreeMap<>();
    private final Map<UUID, String> arenaOf = new HashMap<>();
    /** Players out of their room whose restore waits for the next tick. */
    private final Set<UUID> homeBound = new HashSet<>();
    private BukkitTask ticker;

    /**
     * @param restore {@code RestoreService#restore}
     */
    public WaitingRoomService(Plugin plugin, ArenaRegistry arenas, WaitingRoomSettings settings,
            Function<Player, RestoreResult> restore, MatchStart matchStart) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.matchStart = Objects.requireNonNull(matchStart, "matchStart");
    }

    /** Starts ticking the countdowns once a second. */
    public void start() {
        if (ticker == null) {
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TICKS_PER_SECOND,
                    TICKS_PER_SECOND);
        }
    }

    /**
     * Adds a player who has just been teleported to an arena's waiting room. For the portal,
     * once the player's snapshot is saved and the teleport has succeeded.
     */
    public void join(Player player, String arenaName) {
        UUID playerId = player.getUniqueId();
        if (arenaOf.containsKey(playerId)) {
            // Entry needs a fresh snapshot, so a waiting player cannot come through a portal.
            plugin.getLogger().warning(player.getName() + " entered arena " + arenaName
                    + " while already waiting in " + arenaOf.get(playerId) + "; ignored.");
            return;
        }
        Optional<Arena> arena = arenas.get(arenaName);
        Room room = arena.map(found -> rooms.get(found.name())).orElse(null);
        if (room == null) {
            if (arena.isEmpty() || arena.get().waitingRoom() == null) {
                player.sendMessage(Component.text("This arena is gone, so you were sent back.", NamedTextColor.RED));
                sendHome(player);
                return;
            }
            Arena found = arena.get();
            room = new Room(found.name(), found.waitingRoom(), new WaitingRoom(
                    Math.max(1, found.teamSpawns().size()), settings.minPlayers(), settings.countdownSeconds()));
            rooms.put(found.name(), room);
        }
        WaitingRoom.Joined joined = room.state().join(playerId);
        arenaOf.put(playerId, room.arena());
        player.sendMessage(Component.text("You joined arena " + room.arena() + " on team " + joined.team() + ".",
                NamedTextColor.GREEN));
        broadcast(room, playerId, Component.text(player.getName() + " joined team " + joined.team() + " "
                + count(room) + ".", NamedTextColor.GRAY));
        if (joined.countdownStarted()) {
            broadcast(room, null, Component.text("The match starts in " + room.state().secondsLeft() + " seconds.",
                    NamedTextColor.GOLD));
        }
        showStatus(room);
    }

    public boolean isWaiting(UUID playerId) {
        return arenaOf.containsKey(playerId);
    }

    /** The arena a player is waiting for, if any. */
    public Optional<String> arenaOf(UUID playerId) {
        return Optional.ofNullable(arenaOf.get(playerId));
    }

    /** The player's team, numbered from 1, or 0 if they are not waiting. */
    public int teamOf(UUID playerId) {
        String arena = arenaOf.get(playerId);
        return arena == null ? 0 : rooms.get(arena).state().teamOf(playerId);
    }

    /** Whether a waiting player at this location is still in their room. */
    public boolean inRoom(UUID playerId, Location location) {
        String arena = arenaOf.get(playerId);
        if (arena == null) {
            return false;
        }
        if (location.getWorld() == null || !ArenaWorld.NAME.equals(location.getWorld().getName())) {
            return false;
        }
        return settings.inRoom(rooms.get(arena).point(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
    }

    /**
     * Takes a player out of their waiting room and, unless they quit, sends them home.
     *
     * @return false if they were not waiting
     */
    public boolean leave(Player player, Exit exit) {
        UUID playerId = player.getUniqueId();
        String arena = arenaOf.remove(playerId);
        if (arena == null) {
            return false;
        }
        Room room = rooms.get(arena);
        WaitingRoom.Left left = room.state().leave(playerId).orElseThrow();
        if (room.state().isEmpty()) {
            rooms.remove(arena);
        } else {
            broadcast(room, null, Component.text(player.getName() + " left " + count(room) + ".",
                    NamedTextColor.GRAY));
            left.move().ifPresent(move -> {
                Player moved = plugin.getServer().getPlayer(move.player());
                if (moved != null) {
                    moved.sendMessage(Component.text("You were moved to team " + move.to()
                            + " to keep the teams even.", NamedTextColor.YELLOW));
                }
            });
            if (left.countdownCancelled()) {
                broadcast(room, null, Component.text("Not enough players. The countdown stopped.",
                        NamedTextColor.RED));
            }
            showStatus(room);
        }
        switch (exit) {
            case QUIT -> {
                // The restore listener marks the snapshot; it is restored on the next join.
            }
            case COMMAND -> {
                player.sendMessage(Component.text("You left the waiting room.", NamedTextColor.YELLOW));
                sendHome(player);
            }
            case WALKED_OUT, TELEPORTED, DIED -> {
                if (exit != Exit.DIED) {
                    player.sendMessage(Component.text("You left the waiting room.", NamedTextColor.YELLOW));
                }
                // Not inside the move, teleport or death event that saw the player leave.
                homeBound.add(playerId);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!homeBound.remove(playerId)) {
                        return;
                    }
                    Player online = plugin.getServer().getPlayer(playerId);
                    if (online != null && online.isOnline()) {
                        sendHome(online);
                    }
                });
            }
        }
        return true;
    }

    /** Advances every countdown by a second. Run once a second by {@link #start()}. */
    public void tick() {
        for (Room room : new ArrayList<>(rooms.values())) {
            WaitingRoom.Tick tick = room.state().tick();
            if (tick.phase() == WaitingRoom.Phase.START) {
                launch(room, tick.teams());
            } else {
                showStatus(room);
            }
        }
    }

    /** Stops the countdowns and sends every waiting player home. Called on plugin disable. */
    public void close() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        // Restores put off to a tick that will now never run happen here too.
        List<UUID> waiting = new ArrayList<>(arenaOf.keySet());
        waiting.addAll(homeBound);
        rooms.clear();
        arenaOf.clear();
        homeBound.clear();
        for (UUID playerId : waiting) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                sendHome(player);
            }
        }
    }

    private void launch(Room room, List<List<UUID>> teams) {
        rooms.remove(room.arena());
        for (List<UUID> team : teams) {
            for (UUID playerId : team) {
                arenaOf.remove(playerId);
            }
        }
        for (List<UUID> team : teams) {
            for (UUID playerId : team) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    player.sendActionBar(Component.empty());
                    player.sendMessage(Component.text("The match is starting.", NamedTextColor.GOLD));
                }
            }
        }
        try {
            matchStart.start(room.arena(), teams);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not start the match in arena " + room.arena()
                    + "; sending its players home.", e);
            for (List<UUID> team : teams) {
                for (UUID playerId : team) {
                    Player player = plugin.getServer().getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        player.sendMessage(Component.text("The match could not start.", NamedTextColor.RED));
                        sendHome(player);
                    }
                }
            }
        }
    }

    private void sendHome(Player player) {
        RestoreResult result = restore.apply(player);
        if (result == RestoreResult.FAILED) {
            player.sendMessage(Component.text("Your belongings could not be returned yet. Rejoin the server,"
                    + " or ask an admin if this keeps happening.", NamedTextColor.RED));
        }
    }

    private void showStatus(Room room) {
        WaitingRoom state = room.state();
        Component line = state.isCounting()
                ? Component.text("Match starts in " + state.secondsLeft() + "s", NamedTextColor.GOLD)
                : Component.text("Waiting for players " + count(room), NamedTextColor.GRAY);
        for (UUID playerId : state.players()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.sendActionBar(line);
            }
        }
    }

    /** The head count, against the minimum until it is reached. */
    private static String count(Room room) {
        int size = room.state().size();
        int min = room.state().minPlayers();
        return size < min ? "(" + size + "/" + min + ")" : "(" + size + (size == 1 ? " player)" : " players)");
    }

    /** Sends a message to everyone in the room except {@code skip}, which may be {@code null}. */
    private void broadcast(Room room, UUID skip, Component message) {
        for (UUID playerId : room.state().players()) {
            if (playerId.equals(skip)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }
}
