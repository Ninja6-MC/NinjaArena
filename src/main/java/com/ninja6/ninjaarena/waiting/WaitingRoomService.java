package com.ninja6.ninjaarena.waiting;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.restore.RestoreService.RestoreResult;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs every arena's waiting room and the shared hub: puts players who come through a portal
 * on a team or in the hub queue, ticks the start countdowns once a second, and sends players
 * home through the restore flow when they leave. The rules for teams and countdowns are in
 * {@link WaitingRoom} and {@link SharedHub}.
 *
 * <p><b>Dedicated rooms and the shared hub.</b> A dedicated room belongs to one arena and
 * starts a match there. The hub belongs to no arena: once its countdown ends, the players
 * who arrived first are sent, as one group, to a free arena's teams, and the rest wait for
 * the next free arena. A free arena is available for a match, has no waiting room open and
 * is not reserved by a host. Of the free arenas the hub picks the smallest that takes every
 * waiting player, or the largest if none does. Which of the two a portal leads to is the
 * portal's choice ({@code PortalEntry}); hosted matches always use the dedicated room.
 *
 * <p><b>Player caps.</b> A match takes at most {@link WaitingRoomSettings#capacity(int)}
 * players. A dedicated room that is full turns arrivals away ({@link #full(String)}); the
 * hub never does, since overflow waits there for the next arena. An arena that holds fewer
 * than {@code min-players} starts once full, from its room or from the hub: the hub's
 * countdown needs only as many players as the smallest free arena holds, if that is fewer.
 *
 * <p><b>Leaving.</b> A player leaves a waiting room or the hub by walking or falling more
 * than {@link WaitingRoomSettings#radius()} blocks from it, by being teleported out of it for
 * any reason, by dying, by the leave command, or by disconnecting. Each of these restores
 * them from their snapshot, except a disconnect: that snapshot is restored when they next
 * join. A player is taken out before the restore runs, so the restore's own teleport is not
 * seen as another exit.
 *
 * <p><b>Arena changes.</b> A room keeps the waiting-room point and team count its arena had
 * when the room opened. An arena changes only in edit mode, so when its arena enters edit
 * mode or is deleted, {@link #closeRoom(String)} sends the room's players home and drops the
 * room. The hub is not tied to an arena: while arenas are in edit mode none is free, so its
 * countdown holds and its players keep waiting. The hub keeps the point it had when it
 * opened; if an admin moves or removes the hub point, the next tick sends the players home.
 * The hub is in the arena world and holds no world reference, so a world that was retired
 * while the hub was empty is reopened by the next admission as for any waiting room.
 *
 * <p><b>Start.</b> When a countdown finishes, the players are handed to the
 * {@link MatchStart}, which owns them from then on. While no match may start (every match
 * slot is in use, see {@link #setStartAllowed}), or the hub has no free arena, a finished
 * countdown holds at its last second; when a slot frees up, the room or hub held longest
 * starts first.
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
        /** Moved to a live match as a spectator, retaining the existing snapshot. */
        SPECTATE,
        /** Disconnected; restored on their next join. */
        QUIT
    }

    private static final long TICKS_PER_SECOND = 20;
    /** The hub's key among the held countdowns; never a valid arena name. */
    private static final String HUB = "*hub";

    private record Room(String arena, ArenaPoint point, int teams, WaitingRoom state) {
    }

    private record Hub(ArenaPoint point, SharedHub state) {
    }

    private final Plugin plugin;
    private final ArenaRegistry arenas;
    private WaitingRoomSettings settings;
    private final Function<Player, RestoreResult> restore;
    private UUID transferring;
    private final Consumer<Player> empty;
    private final MatchStart matchStart;

    private final Map<String, Room> rooms = new TreeMap<>();
    private final Map<UUID, String> arenaOf = new HashMap<>();
    /** The shared hub while anyone is in it, otherwise {@code null}. */
    private Hub hub;
    private Supplier<Optional<ArenaPoint>> hubPoint = Optional::empty;
    private Predicate<String> claimed = arena -> false;
    /** Players out of their room whose restore waits for the next tick. */
    private final Set<UUID> homeBound = new HashSet<>();
    private BukkitTask ticker;
    private BooleanSupplier startAllowed = () -> true;
    /** Rooms and the hub held at their last second for a free match slot, with the order they were held in. */
    private final Map<String, Long> heldSince = new HashMap<>();
    private long holds;
    private Function<String, List<UUID>> party = arena -> List.of();
    private BiConsumer<UUID, String> departed = (player, arena) -> { };
    private Consumer<String> closedRoom = arena -> { };

    public void setParty(Function<String, List<UUID>> party) {
        this.party = Objects.requireNonNull(party, "party");
    }

    public void onDeparture(BiConsumer<UUID, String> departed) {
        this.departed = Objects.requireNonNull(departed, "departed");
    }

    /** Called when a room closes, including handing its players to a match. */
    public void onClose(Consumer<String> closedRoom) {
        this.closedRoom = Objects.requireNonNull(closedRoom, "closedRoom");
    }

    /** Where the shared hub is, {@code PortalRegistry#hub} on a server; by default there is none. */
    public void setHub(Supplier<Optional<ArenaPoint>> hubPoint) {
        this.hubPoint = Objects.requireNonNull(hubPoint, "hubPoint");
    }

    /** Arenas the hub must leave alone, such as those a host has reserved. */
    public void setClaimed(Predicate<String> claimed) {
        this.claimed = Objects.requireNonNull(claimed, "claimed");
    }

    public boolean hasRoom(String arena) {
        return rooms.containsKey(Arena.normalizeName(arena));
    }

    public void refreshParty(String arena) {
        Room room = rooms.get(arena);
        if (room != null) {
            room.state().setParty(party.apply(arena));
            notifyMoves(room.state().refreshParty());
        }
    }

    /**
     * @param restore {@code RestoreService#restore}
     * @param empty   empties the inventory of a player who arrives, {@code KitInventory#emptyForWaiting}
     *                on a server
     */
    public WaitingRoomService(Plugin plugin, ArenaRegistry arenas, WaitingRoomSettings settings,
            Function<Player, RestoreResult> restore, Consumer<Player> empty, MatchStart matchStart) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.empty = Objects.requireNonNull(empty, "empty");
        this.matchStart = Objects.requireNonNull(matchStart, "matchStart");
    }

    public WaitingRoomSettings settings() {
        return settings;
    }

    /**
     * Applies room thresholds, caps and radius now, retaining viable countdowns' remaining
     * time. Players already waiting stay when a cap shrinks; only arrivals are turned away.
     * The default lobby applies to the next portal entry.
     */
    public void setSettings(WaitingRoomSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        for (Room room : new ArrayList<>(rooms.values())) {
            room.state().setSettings(settings.minPlayers(), settings.countdownSeconds(),
                    settings.capacity(room.teams()));
            for (UUID id : room.state().players()) {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null && !inRoom(id, player.getLocation())) {
                    leave(player, Exit.WALKED_OUT);
                }
            }
            if (rooms.containsKey(room.arena())) {
                showStatus(room);
            }
        }
        if (hub != null) {
            hub.state().setSettings(hubMinimum(), settings.countdownSeconds());
            for (UUID id : hub.state().players()) {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null && !inRoom(id, player.getLocation())) {
                    leave(player, Exit.WALKED_OUT);
                }
            }
            if (hub != null) {
                showHubStatus(hub);
            }
        }
    }

    /** Starts ticking the countdowns once a second. */
    public void start() {
        if (ticker == null) {
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TICKS_PER_SECOND,
                    TICKS_PER_SECOND);
        }
    }

    /**
     * Why the arena's waiting room cannot take another player now, if it is full. For the
     * portal, before the player's snapshot is saved.
     */
    public Optional<String> full(String arenaName) {
        Room room = rooms.get(Arena.normalizeName(arenaName));
        if (room == null || !room.state().isFull()) {
            return Optional.empty();
        }
        int size = room.state().size();
        return Optional.of("The waiting room of arena " + room.arena() + " is full (" + size
                + (size == 1 ? " player" : " players") + "). Try another arena, or come back after its match.");
    }

    /**
     * The arena a player-built portal sends its next player to under the dedicated lobby: the
     * waiting room with the most players that still takes more, so it starts sooner, else a
     * free arena with a waiting-room point. Arenas a host has reserved, and arenas that are not
     * available, are skipped. Ties go to the first by name. Empty when every arena is busy.
     */
    public Optional<String> openArena() {
        Room filling = null;
        for (Room room : rooms.values()) {
            if (room.state().isFull() || claimed.test(room.arena()) || !arenas.isAvailable(room.arena())) {
                continue;
            }
            if (filling == null || room.state().size() > filling.state().size()) {
                filling = room;
            }
        }
        if (filling != null) {
            return Optional.of(filling.arena());
        }
        for (Arena arena : arenas.all()) {
            if (arena.waitingRoom() != null && isFree(arena)) {
                return Optional.of(arena.name());
            }
        }
        return Optional.empty();
    }

    /**
     * Adds a player who has just been teleported to an arena's waiting room. For the portal,
     * once the player's snapshot is saved and the teleport has succeeded.
     *
     * <p>The player's inventory is emptied first, with their effects and fire, and they are put
     * in adventure mode; their snapshot gives all of it back when they leave. A player who
     * cannot be emptied, or who finds the room full, is sent home instead, so no one waits
     * holding their own items.
     */
    public void join(Player player, String arenaName) {
        UUID playerId = player.getUniqueId();
        if (isWaiting(playerId)) {
            // Entry needs a fresh snapshot, so a waiting player cannot come through a portal.
            plugin.getLogger().warning(player.getName() + " entered arena " + arenaName
                    + " while already waiting in " + arenaOf.getOrDefault(playerId, "the hub") + "; ignored.");
            return;
        }
        Optional<Arena> arena = arenas.get(arenaName);
        Room room = arena.map(found -> rooms.get(found.name())).orElse(null);
        if (room == null && (arena.isEmpty() || arena.get().waitingRoom() == null)) {
            player.sendMessage(Component.text("This arena is gone, so you were sent back.", NamedTextColor.RED));
            sendHome(player);
            return;
        }
        if (room != null && room.state().isFull()) {
            player.sendMessage(Component.text("The waiting room of arena " + room.arena() + " filled up before you"
                    + " arrived, so you were sent back.", NamedTextColor.RED));
            sendHome(player);
            return;
        }
        if (!prepare(player, "the waiting room of arena " + arenaName)) {
            return;
        }
        if (room == null) {
            Arena found = arena.get();
            int teams = teamCount(found);
            room = new Room(found.name(), found.waitingRoom(), teams, new WaitingRoom(teams, settings.minPlayers(),
                    settings.countdownSeconds(), settings.capacity(teams)));
            rooms.put(found.name(), room);
        }
        room.state().setParty(party.apply(room.arena()));
        WaitingRoom.Joined joined = room.state().join(playerId);
        arenaOf.put(playerId, room.arena());
        notifyMoves(joined.moves());
        player.sendMessage(Component.text("You joined arena " + room.arena() + " on team " + joined.team() + ".",
                NamedTextColor.GREEN));
        broadcast(room.state().players(), playerId, Component.text(player.getName() + " joined team "
                + joined.team() + " " + count(room) + ".", NamedTextColor.GRAY));
        if (joined.countdownStarted()) {
            broadcast(room.state().players(), null, Component.text("The match starts in "
                    + room.state().secondsLeft() + " seconds.", NamedTextColor.GOLD));
        }
        showStatus(room);
    }

    /**
     * Adds a player who has just been teleported to the shared hub, as {@link #join} does for
     * a waiting room. A player who arrives after the hub point was removed is sent home.
     */
    public void joinHub(Player player) {
        UUID playerId = player.getUniqueId();
        if (isWaiting(playerId)) {
            plugin.getLogger().warning(player.getName() + " entered the hub while already waiting in "
                    + arenaOf.getOrDefault(playerId, "the hub") + "; ignored.");
            return;
        }
        checkHubPoint();
        Optional<ArenaPoint> point = hub != null ? Optional.of(hub.point()) : hubPoint.get();
        if (point.isEmpty()) {
            player.sendMessage(Component.text("The hub is gone, so you were sent back.", NamedTextColor.RED));
            sendHome(player);
            return;
        }
        if (!prepare(player, "the hub")) {
            return;
        }
        if (hub == null) {
            hub = new Hub(point.get(), new SharedHub(hubMinimum(), settings.countdownSeconds()));
        }
        boolean started = hub.state().join(playerId);
        player.sendMessage(Component.text("You joined the hub. You go to the next free arena once enough players"
                + " are here.", NamedTextColor.GREEN));
        broadcast(hub.state().players(), playerId, Component.text(player.getName() + " joined the hub "
                + count(hub.state().size(), hub.state().minPlayers()) + ".", NamedTextColor.GRAY));
        if (started) {
            broadcast(hub.state().players(), null, Component.text("The match starts in "
                    + hub.state().secondsLeft() + " seconds.", NamedTextColor.GOLD));
        }
        showHubStatus(hub);
    }

    /** Empties an arriving player; one who cannot be emptied is sent home. */
    private boolean prepare(Player player, String place) {
        try {
            empty.accept(player);
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not empty the inventory of " + player.getName() + " ("
                    + player.getUniqueId() + ") in " + place + "; sending them home.", e);
            player.sendMessage(Component.text("You could not join " + (place.equals("the hub") ? "the hub"
                    : "the waiting room") + ", so you were sent back.", NamedTextColor.RED));
            sendHome(player);
            return false;
        }
    }

    /** Whether the player is in a waiting room or the shared hub. */
    public boolean isWaiting(UUID playerId) {
        return arenaOf.containsKey(playerId) || inHub(playerId);
    }

    /** Whether the player is waiting in the shared hub. */
    public boolean inHub(UUID playerId) {
        return hub != null && hub.state().contains(playerId);
    }

    /** The arena a player is waiting for, if any; empty for a player in the hub. */
    public Optional<String> arenaOf(UUID playerId) {
        return Optional.ofNullable(arenaOf.get(playerId));
    }

    /** The player's team, numbered from 1, or 0 if they are not on one yet. */
    public int teamOf(UUID playerId) {
        String arena = arenaOf.get(playerId);
        return arena == null ? 0 : rooms.get(arena).state().teamOf(playerId);
    }

    /** Suppresses the room's teleport exit while a spectator transfer is attempted. */
    public boolean transferToSpectate(Player player, Location destination) {
        UUID id = player.getUniqueId();
        if (!isWaiting(id) || transferring != null) {
            return false;
        }
        transferring = id;
        try {
            if (!player.teleport(destination, TeleportCause.PLUGIN)) {
                return false;
            }
            return leave(player, Exit.SPECTATE);
        } finally {
            transferring = null;
        }
    }

    public boolean isTransferring(UUID playerId) {
        return playerId.equals(transferring);
    }

    /** Whether a waiting player at this location is still in their room or the hub. */
    public boolean inRoom(UUID playerId, Location location) {
        ArenaPoint point;
        if (inHub(playerId)) {
            point = hub.point();
        } else {
            String arena = arenaOf.get(playerId);
            if (arena == null) {
                return false;
            }
            point = rooms.get(arena).point();
        }
        if (location.getWorld() == null || !ArenaWorld.NAME.equals(location.getWorld().getName())) {
            return false;
        }
        return settings.inRoom(point, location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * Takes a player out of their waiting room or the hub and, unless they quit, sends them
     * home.
     *
     * @return false if they were not waiting
     */
    public boolean leave(Player player, Exit exit) {
        UUID playerId = player.getUniqueId();
        String place;
        if (inHub(playerId)) {
            leaveHub(player);
            place = "the hub";
        } else if (leaveRoom(player)) {
            place = "the waiting room";
        } else {
            return false;
        }
        switch (exit) {
            case SPECTATE -> {
                player.sendActionBar(Component.empty());
            }
            case QUIT -> {
                // The restore listener marks the snapshot; it is restored on the next join.
            }
            case COMMAND -> {
                player.sendMessage(Component.text("You left " + place + ".", NamedTextColor.YELLOW));
                sendHome(player);
            }
            case WALKED_OUT, TELEPORTED, DIED -> {
                if (exit != Exit.DIED) {
                    player.sendMessage(Component.text("You left " + place + ".", NamedTextColor.YELLOW));
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

    private boolean leaveRoom(Player player) {
        UUID playerId = player.getUniqueId();
        String arena = arenaOf.remove(playerId);
        if (arena == null) {
            return false;
        }
        Room room = rooms.get(arena);
        departed.accept(playerId, arena);
        room.state().setParty(party.apply(arena));
        WaitingRoom.Left left = room.state().leave(playerId).orElseThrow();
        if (room.state().isEmpty()) {
            rooms.remove(arena);
        } else {
            broadcast(room.state().players(), null, Component.text(player.getName() + " left " + count(room) + ".",
                    NamedTextColor.GRAY));
            notifyMoves(left.moves());
            if (left.countdownCancelled()) {
                broadcast(room.state().players(), null, Component.text("Not enough players. The countdown stopped.",
                        NamedTextColor.RED));
            }
            showStatus(room);
        }
        return true;
    }

    private void leaveHub(Player player) {
        Hub current = hub;
        boolean cancelled = current.state().leave(player.getUniqueId()).orElseThrow();
        if (current.state().isEmpty()) {
            hub = null;
            heldSince.remove(HUB);
            return;
        }
        broadcast(current.state().players(), null, Component.text(player.getName() + " left the hub "
                + count(current.state().size(), current.state().minPlayers()) + ".", NamedTextColor.GRAY));
        if (cancelled) {
            broadcast(current.state().players(), null, Component.text("Not enough players. The countdown stopped.",
                    NamedTextColor.RED));
        }
        showHubStatus(current);
    }

    /**
     * Sends everyone waiting for the arena home and drops its room, because the arena is
     * entering edit mode or being deleted. Does nothing if no one is waiting for it. Players
     * in the hub stay: the hub waits for the next free arena instead.
     */
    public void closeRoom(String arenaName) {
        String name = Arena.normalizeName(arenaName);
        closedRoom.accept(name);
        Room room = rooms.remove(name);
        if (room == null) {
            return;
        }
        List<UUID> waiting = room.state().players();
        for (UUID playerId : waiting) {
            arenaOf.remove(playerId);
        }
        sendAllHome(waiting, "Arena " + room.arena() + " is no longer available, so you were sent back.");
    }

    /** Sends everyone in the hub home and closes it, because its point was moved or removed. */
    public void closeHub() {
        Hub closing = hub;
        if (closing == null) {
            return;
        }
        hub = null;
        heldSince.remove(HUB);
        sendAllHome(closing.state().players(), "The hub was moved or removed, so you were sent back.");
    }

    private void sendAllHome(List<UUID> waiting, String message) {
        for (UUID playerId : waiting) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.empty());
                player.sendMessage(Component.text(message, NamedTextColor.YELLOW));
                sendHome(player);
            }
        }
    }

    /**
     * Sets whether a match may start now, asked each second for every room about to start. While
     * it says no, the countdown holds at its last second; by default a match may always start.
     */
    public void setStartAllowed(BooleanSupplier startAllowed) {
        this.startAllowed = Objects.requireNonNull(startAllowed, "startAllowed");
    }

    /** Advances every countdown by a second. Run once a second by {@link #start()}. */
    public void tick() {
        checkHubPoint();
        heldSince.keySet().removeIf(key -> key.equals(HUB) ? hub == null : !rooms.containsKey(key));
        List<String> order = new ArrayList<>(rooms.keySet());
        if (hub != null) {
            order.add(HUB);
        }
        // Held countdowns first, longest held first, so a free slot goes to the one that waited longest.
        order.sort(Comparator.comparingLong(key -> heldSince.getOrDefault(key, Long.MAX_VALUE)));
        for (String key : order) {
            if (key.equals(HUB)) {
                if (hub != null) {
                    tickHub(hub);
                }
                continue;
            }
            Room room = rooms.get(key);
            if (room == null) {
                continue;
            }
            if (room.state().secondsLeft() == 1 && !startAllowed.getAsBoolean()) {
                heldSince.putIfAbsent(room.arena(), holds++);
                showHeld(room.state().players(), "Every match slot is in use. Starting when one frees up.");
                continue;
            }
            heldSince.remove(room.arena());
            WaitingRoom.Tick tick = room.state().tick();
            if (tick.phase() == WaitingRoom.Phase.START) {
                launch(room, tick.teams());
            } else {
                showStatus(room);
            }
        }
    }

    private void tickHub(Hub current) {
        SharedHub state = current.state();
        boolean wasCounting = state.isCounting();
        state.setSettings(hubMinimum(), settings.countdownSeconds());
        if (wasCounting && !state.isCounting()) {
            broadcast(state.players(), null, Component.text("Not enough players. The countdown stopped.",
                    NamedTextColor.RED));
            showHubStatus(current);
            return;
        }
        if (!wasCounting && state.isCounting()) {
            broadcast(state.players(), null, Component.text("The match starts in " + state.secondsLeft()
                    + " seconds.", NamedTextColor.GOLD));
        }
        if (state.secondsLeft() == 1) {
            boolean slot = startAllowed.getAsBoolean();
            Optional<Arena> arena = slot ? freeArena(state.size()) : Optional.empty();
            if (arena.isEmpty()) {
                heldSince.putIfAbsent(HUB, holds++);
                showHeld(state.players(), slot ? "Every arena is in use. Starting when one frees up."
                        : "Every match slot is in use. Starting when one frees up.");
                return;
            }
            heldSince.remove(HUB);
            state.tick();
            launchHub(current, arena.get());
            return;
        }
        heldSince.remove(HUB);
        state.tick();
        showHubStatus(current);
    }

    /**
     * Players the hub needs before its countdown starts: {@code min-players}, or the capacity
     * of the smallest free arena if that is lower, so an arena that holds fewer than
     * {@code min-players} starts once the hub can fill it, as its dedicated room would.
     */
    private int hubMinimum() {
        int minimum = settings.minPlayers();
        for (Arena arena : arenas.all()) {
            if (isFree(arena)) {
                minimum = Math.min(minimum, settings.capacity(teamCount(arena)));
            }
        }
        return minimum;
    }

    /** Whether the arena is available for a match, has no waiting room open and no reservation. */
    private boolean isFree(Arena arena) {
        String name = arena.name();
        return !rooms.containsKey(name) && !claimed.test(name) && arenas.isAvailable(name);
    }

    /**
     * The arena the hub sends its next group to. Of the free arenas the group can start, that
     * is those needing no more than {@code waiting} players ({@code min-players}, or the
     * capacity if lower), the smallest that takes every waiting player, or the largest if none
     * does. Ties go to the first by name.
     */
    private Optional<Arena> freeArena(int waiting) {
        Arena best = null;
        int bestCapacity = 0;
        for (Arena arena : arenas.all()) {
            if (!isFree(arena)) {
                continue;
            }
            int capacity = settings.capacity(teamCount(arena));
            if (Math.min(settings.minPlayers(), capacity) > waiting) {
                continue;
            }
            boolean fits = capacity >= waiting;
            boolean bestFits = best != null && bestCapacity >= waiting;
            if (best == null || (fits && (!bestFits || capacity < bestCapacity))
                    || (!fits && !bestFits && capacity > bestCapacity)) {
                best = arena;
                bestCapacity = capacity;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int teamCount(Arena arena) {
        return Math.max(1, arena.teamSpawns().size());
    }

    /** Stops the countdowns and sends every waiting player home. Called on plugin disable. */
    public void close() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        // Restores put off to a tick that will now never run happen here too.
        List<UUID> waiting = new ArrayList<>(arenaOf.keySet());
        if (hub != null) {
            waiting.addAll(hub.state().players());
        }
        waiting.addAll(homeBound);
        for (String arena : new ArrayList<>(rooms.keySet())) {
            closedRoom.accept(arena);
        }
        rooms.clear();
        hub = null;
        heldSince.clear();
        arenaOf.clear();
        homeBound.clear();
        for (UUID playerId : waiting) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                sendHome(player);
            }
        }
    }

    /** Closes the hub if its point is no longer the one an admin has set. */
    private void checkHubPoint() {
        if (hub != null && !hubPoint.get().filter(hub.point()::equals).isPresent()) {
            closeHub();
        }
    }

    private void launch(Room room, List<List<UUID>> teams) {
        closedRoom.accept(room.arena());
        rooms.remove(room.arena());
        for (List<UUID> team : teams) {
            for (UUID playerId : team) {
                arenaOf.remove(playerId);
            }
        }
        startMatch(room.arena(), teams);
    }

    private void launchHub(Hub current, Arena arena) {
        SharedHub state = current.state();
        List<UUID> group = state.take(settings.capacity(teamCount(arena)));
        if (state.isEmpty()) {
            hub = null;
        } else {
            broadcast(state.players(), null, Component.text("Arena " + arena.name() + " is full, so you stay in the"
                    + " hub for the next free arena.", NamedTextColor.YELLOW));
            if (state.isCounting()) {
                broadcast(state.players(), null, Component.text("The next match starts in " + state.secondsLeft()
                        + " seconds.", NamedTextColor.GOLD));
            }
            showHubStatus(current);
        }
        startMatch(arena.name(), WaitingRoom.balance(teamCount(arena), group));
    }

    /** Hands players who are no longer waiting to the match engine. */
    private void startMatch(String arena, List<List<UUID>> teams) {
        for (List<UUID> team : teams) {
            for (UUID playerId : team) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    player.sendActionBar(Component.empty());
                    player.sendMessage(Component.text("The match is starting in arena " + arena + ".",
                            NamedTextColor.GOLD));
                }
            }
        }
        try {
            matchStart.start(arena, teams);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not start the match in arena " + arena
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

    private void notifyMoves(List<WaitingRoom.Move> moves) {
        for (WaitingRoom.Move move : moves) {
            Player moved = plugin.getServer().getPlayer(move.player());
            if (moved != null) {
                moved.sendMessage(Component.text("You were moved to team " + move.to()
                        + " to keep the teams even.", NamedTextColor.YELLOW));
            }
        }
    }

    private void showStatus(Room room) {
        WaitingRoom state = room.state();
        Component line = state.isCounting()
                ? Component.text("Match starts in " + state.secondsLeft() + "s", NamedTextColor.GOLD)
                : Component.text("Waiting for players " + count(room), NamedTextColor.GRAY);
        showLine(state.players(), line);
    }

    private void showHubStatus(Hub current) {
        SharedHub state = current.state();
        Component line = state.isCounting()
                ? Component.text("Match starts in " + state.secondsLeft() + "s", NamedTextColor.GOLD)
                : Component.text("Waiting for players " + count(state.size(), state.minPlayers()),
                        NamedTextColor.GRAY);
        showLine(state.players(), line);
    }

    private void showHeld(List<UUID> players, String message) {
        showLine(players, Component.text(message, NamedTextColor.GOLD));
    }

    private void showLine(List<UUID> players, Component line) {
        for (UUID playerId : players) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.sendActionBar(line);
            }
        }
    }

    /** The head count, against the minimum until it is reached. */
    private static String count(Room room) {
        return count(room.state().size(), room.state().minPlayers());
    }

    private static String count(int size, int min) {
        return size < min ? "(" + size + "/" + min + ")" : "(" + size + (size == 1 ? " player)" : " players)");
    }

    /** Sends a message to the players except {@code skip}, which may be {@code null}. */
    private void broadcast(List<UUID> players, UUID skip, Component message) {
        for (UUID playerId : players) {
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
