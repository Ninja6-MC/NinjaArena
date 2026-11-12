package com.ninja6.ninjaarena.room;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.command.HostingCommands;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.command.RoomCommands;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.sound.Sounds;
import com.ninja6.ninjaarena.sound.Sounds.Cue;
import com.ninja6.ninjaarena.waiting.WaitingRoom;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.CrossWorldTransfers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Host-started rooms. A player with {@link #CREATE} opens a room in the lobby, chooses its
 * settings, invites players and presses start once enough have joined; nothing starts a
 * room's match by itself. The rules for teams and hand-offs are in {@link Room}.
 *
 * <p><b>Arena slots.</b> A room plays on a hand-built arena, the first free one when it is
 * created, and leases a slot for it at once ({@link SlotLeases}). While every slot is in use,
 * or its arena is, the room waits in a queue and its players see their position. The lease
 * lasts through the start, every round and the end of the match, and is given up when the
 * room is back in the lobby, where it queues again for its next match, or when it closes;
 * a room that closes while its match runs keeps the lease until the match ends. At most
 * {@link #maxActive()} slots are used at once. Every match is a room's, so every running
 * match holds a lease.
 *
 * <p><b>Ways in.</b> Besides {@code /narena room create} and an invitation, a player who
 * walks through a play portal comes into the lobby and joins an open room there
 * ({@link #fromPortal}), and {@code /narena host <arena>} opens an invite-only room on the
 * named arena. None of these starts a match.
 *
 * <p><b>Events.</b> While a scheduled event runs, its arena is the featured map
 * ({@link #setFeatured}): new rooms play on it when it is free, the room list shows it, and
 * portals that may take a player to any room prefer rooms on it. A match there earns the
 * event bonus, which the match engine captures when it starts.
 *
 * <p><b>Leaving.</b> A member who leaves the room, by {@code /narena room leave}, by
 * {@code /narena leave}, by being removed, or by leaving the lobby and match world some
 * other way, is out of the room. When the host goes, the longest-waiting member who may
 * create rooms becomes the host; when there is none, the room closes and its players stay in
 * the lobby. A match that is running goes on either way.
 *
 * <p><b>Worlds.</b> While any room is open the arena world is held open, and room players
 * may be moved between the lobby and arena worlds by the plugin's own teleports without
 * leaving their match.
 *
 * <p>Not thread-safe: call it from the server thread.
 */
public final class RoomService implements RoomCommands, HostingCommands, Listener {

    /** Opening a room and being handed one; a child of {@code ninjaarena.play}. */
    public static final String CREATE = "ninjaarena.room.create";

    private static final long TICKS_PER_SECOND = 20;
    private static final String ROOT = "/ninjaarena room ";

    private final Plugin plugin;
    private final ArenaRegistry arenas;
    private final MatchService matches;
    private final RoomLobby lobby;
    private final Supplier<WaitingRoomSettings> defaults;
    private final Supplier<Runnable> worldHold;
    private final Set<Room> rooms = new LinkedHashSet<>();
    private final Map<UUID, Room> roomOf = new HashMap<>();
    /** Members' names, for the messages sent after they went offline. */
    private final Map<UUID, String> names = new HashMap<>();
    /** Rooms whose match runs, by arena. */
    private final Map<String, Room> playing = new HashMap<>();
    private final SlotLeases<Room> slots;
    private final Runnable crossing;
    private Supplier<Optional<String>> featured = Optional::empty;
    private Runnable heldWorld;
    private Sounds sounds;

    private BukkitTask ticker;
    private int configuredMax;

    /**
     * @param defaults  the waiting-room settings a new room starts from: its required player
     *                  count and team size
     * @param worldHold {@code ArenaWorld#hold}
     * @param maxActive {@code arenas.max-active}
     */
    public RoomService(Plugin plugin, ArenaRegistry arenas, MatchService matches, RoomLobby lobby,
            Supplier<WaitingRoomSettings> defaults, CrossWorldTransfers transfers, Supplier<Runnable> worldHold,
            int maxActive) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.lobby = Objects.requireNonNull(lobby, "lobby");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.worldHold = Objects.requireNonNull(worldHold, "worldHold");
        this.configuredMax = maxActive;
        this.slots = new SlotLeases<>(effectiveMax(maxActive));
        slots.setArenaTaken(arena -> !arenas.isAvailable(arena));
        this.crossing = transfers.allow(roomOf::containsKey);
        matches.onClose(this::matchClosed);
        arenas.onEditOrDelete(this::arenaChanged);
    }

    /** The featured map of the scheduled event running now, {@code ArenaEvents#arena}; by default none. */
    public void setFeatured(Supplier<Optional<String>> featured) {
        this.featured = Objects.requireNonNull(featured, "featured");
    }

    public void setSounds(Sounds sounds) {
        this.sounds = sounds;
    }

    /** Starts the once-a-second queue check and room status line. */
    public void start() {
        if (ticker == null) {
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TICKS_PER_SECOND,
                    TICKS_PER_SECOND);
        }
    }

    // ---- Slots ----

    /** The slot limit in force: {@code arenas.max-active}, at most the cells of the arena grid. */
    public int maxActive() {
        return slots.max();
    }

    /**
     * Applies a new {@code arenas.max-active}. A lower value never ends a lease already
     * granted; a higher one lets queued rooms in now.
     */
    public void setMaxActive(int maxActive) {
        configuredMax = maxActive;
        granted(slots.setMax(effectiveMax(maxActive)));
    }

    /**
     * The arena grid or the world border changed on reload: the cap is worked out again from
     * the configured {@code arenas.max-active}. Like any change of the cap, it never ends a
     * lease already granted.
     */
    public void gridChanged() {
        setMaxActive(configuredMax);
    }

    private int effectiveMax(int configured) {
        int cells = Math.max(1, arenas.grid().cellsNearestFirst().size());
        if (configured > cells) {
            plugin.getLogger().warning("arenas.max-active is " + configured + " but the arena grid has only " + cells
                    + " cell(s); using " + cells + ".");
            return cells;
        }
        return Math.max(1, configured);
    }

    private void granted(List<Room> granted) {
        for (Room room : granted) {
            if (rooms.contains(room)) {
                broadcast(room, msg("room.lease-granted", "arena", room.arena()));
            }
        }
    }

    // ---- Queries ----

    public boolean isMember(UUID player) {
        return roomOf.containsKey(player);
    }

    public Optional<Room> roomOf(UUID player) {
        return Optional.ofNullable(roomOf.get(player));
    }

    /** Open rooms, oldest first. */
    public List<Room> rooms() {
        return List.copyOf(rooms);
    }

    /** The room a player hosts, if any. */
    public Optional<Room> hostedBy(UUID host) {
        Room room = roomOf.get(host);
        return room != null && room.host().equals(host) ? Optional.of(room) : Optional.empty();
    }

    /** A queued room's place in the queue, from 1. */
    public OptionalInt position(Room room) {
        return slots.position(room);
    }

    /** Whether the room holds its arena's slot. */
    public boolean hasLease(Room room) {
        return slots.lease(room).isPresent();
    }

    // ---- Commands ----

    @Override
    public void show(Player player) {
        Room room = roomOf.get(player.getUniqueId());
        if (room == null) {
            list(player);
            return;
        }
        player.sendMessage(msg("room.info-header", "host", name(room.host()), "arena", room.arena(),
                "privacy", privacy(room.settings())));
        if (isFeatured(room.arena())) {
            tell(player, "room.info-featured", "arena", room.arena());
        }
        player.sendMessage(msg("room.info-settings", "size", room.size(), "required", room.settings().players(),
                "teams", room.settings().teams(), "team-size", teamSize(room.settings())));
        player.sendMessage(status(room));
        boolean host = room.host().equals(player.getUniqueId());
        for (UUID member : room.members()) {
            Component kick = host && !member.equals(room.host())
                    ? button("room.button-kick", "kick " + name(member)) : Component.empty();
            player.sendMessage(msg(member.equals(room.host()) ? "room.info-host" : "room.info-member",
                    "player", name(member), "team", room.teamOf(member), "kick", kick));
        }
        if (host && !room.isPlaying() && room.canStart() && hasLease(room)) {
            player.sendMessage(msg("room.start-ready", "start", button("room.button-start", "start")));
        }
    }

    @Override
    public void create(Player player) {
        if (!player.hasPermission(CREATE)) {
            tell(player, "room.no-permission");
            return;
        }
        open(player, null);
    }

    /**
     * {@code /narena host <arena>}, for {@code ninjaarena.host}: opens an invite-only room on
     * the named arena, which need not be pickable. {@code /narena invite} and
     * {@code /narena accept} are the room's {@link #invite} and {@link #accept}.
     */
    @Override
    public void host(Player player, String arenaName) {
        Arena target = arenas.get(arenaName).orElse(null);
        if (target == null) {
            tell(player, "room.map-unknown", "arena", arenaName);
            return;
        }
        if (target.teamSpawns().size() < Arena.MIN_TEAMS) {
            tell(player, "room.map-too-few-teams", "arena", target.name());
            return;
        }
        Room room = open(player, target);
        if (room != null) {
            room.setSettings(room.settings().withInviteOnly(true));
            tell(player, "room.hosted", "arena", room.arena());
        }
    }

    /**
     * Opens a room for the player on the arena, or on the {@linkplain #defaultArena default}
     * one when it is {@code null}, and asks for its slot.
     *
     * @return the room, or {@code null} after telling the player why there is none
     */
    private Room open(Player player, Arena chosen) {
        UUID id = player.getUniqueId();
        if (roomOf.containsKey(id)) {
            tell(player, "room.already-in-room");
            return null;
        }
        Optional<Arena> arena = chosen != null ? Optional.of(chosen) : defaultArena(null);
        if (arena.isEmpty()) {
            tell(player, "room.no-arena");
            return null;
        }
        if (!holdWorld()) {
            tell(player, "room.world-failed");
            return null;
        }
        Optional<String> refused = lobby.join(player);
        if (refused.isPresent()) {
            releaseWorldIfUnused();
            player.sendMessage(msg("room.refused", "message", refused.get()));
            return null;
        }
        WaitingRoomSettings base = defaults.get();
        int teams = teamCount(arena.get());
        int teamSize = base.teamSize();
        int capacity = teamSize == 0 ? Integer.MAX_VALUE : teams * teamSize;
        RoomSettings settings = new RoomSettings(false,
                Math.min(Math.min(base.minPlayers(), RoomSettings.MAX_PLAYERS), capacity), teams, teamSize);
        Room room = new Room(id, arena.get().name(), settings);
        rooms.add(room);
        roomOf.put(id, room);
        names.put(id, player.getName());
        tell(player, "room.created", "arena", room.arena());
        if (sounds != null) {
            sounds.room(Cue.ROOM_JOIN, room);
        }
        if (!slots.request(room, room.arena())) {
            tellQueued(player, room);
        }
        return room;
    }

    @Override
    public void settings(Player player, String setting, String value) {
        Room room = hostRoom(player);
        if (room == null) {
            return;
        }
        if (setting == null) {
            show(player);
            return;
        }
        if (room.isPlaying()) {
            tell(player, "room.settings-playing");
            return;
        }
        RoomSettings current = room.settings();
        RoomSettings next;
        String key = setting.toLowerCase(Locale.ROOT);
        switch (key) {
            case "privacy" -> {
                String choice = value.toLowerCase(Locale.ROOT);
                if (!choice.equals("public") && !choice.equals("invite")) {
                    tell(player, "room.settings-privacy");
                    return;
                }
                next = current.withInviteOnly(choice.equals("invite"));
            }
            case "players", "teams", "team-size" -> {
                int max = switch (key) {
                    case "players" -> Math.min(RoomSettings.MAX_PLAYERS, current.capacity());
                    case "teams" -> arenas.get(room.arena()).map(RoomService::teamCount).orElse(1);
                    default -> RoomSettings.MAX_TEAM_SIZE;
                };
                int min = key.equals("team-size") ? 0 : 1;
                Integer number = parse(value);
                if (number == null || number < min || number > max) {
                    tell(player, "room.settings-range", "setting", key, "min", min, "max", max);
                    return;
                }
                next = switch (key) {
                    case "players" -> current.withPlayers(number);
                    case "teams" -> current.withTeams(number);
                    default -> current.withTeamSize(number);
                };
                if (next.capacity() < Math.max(room.size(), next.players())) {
                    tell(player, "room.settings-capacity", "size", Math.max(room.size(), next.players()));
                    return;
                }
            }
            default -> {
                tell(player, "room.settings-unknown", "setting", setting);
                return;
            }
        }
        List<List<UUID>> before = room.teams();
        room.setSettings(next);
        broadcast(room, msg("room.settings-changed", "player", player.getName(), "setting", key,
                "value", key.equals("privacy") ? privacy(next) : key.equals("team-size") ? teamSize(next)
                        : Component.text(value)));
        if (!before.equals(room.teams())) {
            for (UUID member : room.members()) {
                tell(member, "room.team", "team", room.teamOf(member));
            }
        }
    }

    @Override
    public void invite(Player player, String invitee) {
        Room room = hostRoom(player);
        if (room == null) {
            return;
        }
        if (invitee == null) {
            List<UUID> free = lobby.members().stream().filter(id -> !roomOf.containsKey(id)).toList();
            if (free.isEmpty()) {
                tell(player, "room.invite-list-empty");
                return;
            }
            tell(player, "room.invite-list-header");
            for (UUID id : free) {
                player.sendMessage(msg("room.invite-list-line", "player", name(id),
                        "invite", button("room.button-invite", "invite " + name(id))));
            }
            return;
        }
        Player target = plugin.getServer().getPlayerExact(invitee);
        if (target == null || !target.isOnline()) {
            tell(player, "room.no-player", "player", invitee);
            return;
        }
        UUID targetId = target.getUniqueId();
        if (targetId.equals(player.getUniqueId())) {
            tell(player, "room.self");
            return;
        }
        if (room.contains(targetId)) {
            tell(player, "room.already-member", "player", target.getName());
            return;
        }
        if (!target.hasPermission(NinjaArenaCommand.PLAY)) {
            tell(player, "room.cannot-invite", "player", target.getName());
            return;
        }
        room.invite(targetId);
        target.sendMessage(msg("room.invited-you", "host", player.getName(), "arena", room.arena(),
                "accept", button("room.button-accept", "accept " + player.getName())));
        tell(player, "room.invited", "player", target.getName());
    }

    @Override
    public void accept(Player player, String hostName) {
        UUID id = player.getUniqueId();
        Room room = roomNamed(hostName);
        if (room == null) {
            tell(player, "room.no-room", "host", hostName);
            return;
        }
        if (room.contains(id)) {
            tell(player, "room.already-joined");
            return;
        }
        if (roomOf.containsKey(id)) {
            tell(player, "room.already-in-room");
            return;
        }
        String host = name(room.host());
        if (room.isPlaying()) {
            tell(player, "room.playing", "host", host);
            return;
        }
        if (!room.admits(id)) {
            tell(player, "room.no-invitation", "host", host);
            return;
        }
        if (room.isFull()) {
            tell(player, "room.full", "host", host);
            return;
        }
        Optional<String> refused = lobby.join(player);
        if (refused.isPresent()) {
            player.sendMessage(msg("room.refused", "message", refused.get()));
            return;
        }
        if (!rooms.contains(room) || room.isPlaying() || room.isFull()) {
            // Entering the lobby can teleport; the room may have changed meanwhile.
            tell(player, "room.full", "host", host);
            return;
        }
        join(player, room);
    }

    /**
     * A player who walked through a play portal: into the lobby, saving their snapshot, and
     * into the open room with the most players that admits them and has space, on the arena
     * when one is given ({@code PortalEntry#roomArena}). Without an arena, rooms on the
     * featured map come first. With no such room they stay in the lobby and see the room
     * list. Nothing here starts a match.
     *
     * @return empty once they are in the lobby, otherwise why not, as text to show them
     */
    public Optional<String> fromPortal(Player player, Optional<String> arena) {
        Optional<String> refused = lobby.join(player);
        if (refused.isPresent()) {
            return refused;
        }
        UUID id = player.getUniqueId();
        if (roomOf.containsKey(id)) {
            return Optional.empty();
        }
        Optional<String> wanted = arena.map(Arena::normalizeName);
        Optional<Room> open = rooms.stream()
                .filter(room -> !room.isPlaying() && !room.isFull() && room.admits(id))
                .filter(room -> wanted.isEmpty() || wanted.get().equals(room.arena()))
                .sorted(Comparator.comparing((Room room) -> wanted.isEmpty() && isFeatured(room.arena()) ? 0 : 1)
                        .thenComparing(Room::size, Comparator.reverseOrder()))
                .findFirst();
        if (open.isPresent()) {
            join(player, open.get());
        } else {
            tell(player, wanted.isPresent() ? "room.portal-no-room-arena" : "room.portal-no-room",
                    "arena", wanted.orElse(""));
            list(player);
        }
        return Optional.empty();
    }

    /** Puts a lobby member in a room that admits them and has space. */
    private void join(Player player, Room room) {
        UUID id = player.getUniqueId();
        String host = name(room.host());
        WaitingRoom.Joined joined = room.join(id);
        roomOf.put(id, room);
        names.put(id, player.getName());
        if (sounds != null) {
            sounds.room(Cue.ROOM_JOIN, room);
        }
        notifyMoves(joined.moves());
        tell(player, "room.joined", "host", host, "team", joined.team());
        for (UUID other : room.others(id)) {
            tell(other, "room.player-joined", "player", player.getName(), "team", joined.team(), "count", count(room));
        }
    }

    @Override
    public void kick(Player player, String member) {
        Room room = hostRoom(player);
        if (room == null) {
            return;
        }
        Player target = plugin.getServer().getPlayerExact(member);
        UUID targetId = target != null ? target.getUniqueId() : room.members().stream()
                .filter(id -> name(id).equalsIgnoreCase(member)).findFirst().orElse(null);
        if (targetId == null || !room.contains(targetId)) {
            tell(player, "room.kick-not-member", "player", member);
            return;
        }
        if (targetId.equals(player.getUniqueId())) {
            tell(player, "room.kick-self");
            return;
        }
        if (room.isPlaying()) {
            tell(player, "room.kick-playing");
            return;
        }
        roomOf.remove(targetId);
        notifyMoves(room.kick(targetId).orElse(List.of()));
        tell(targetId, "room.kicked", "host", player.getName());
        broadcast(room, msg("room.player-kicked", "player", name(targetId)));
    }

    @Override
    public void start(CommandSender sender, String hostName) {
        boolean admin = sender.hasPermission(NinjaArenaCommand.ADMIN);
        Room room;
        if (hostName == null) {
            room = sender instanceof Player player ? roomOf.get(player.getUniqueId()) : null;
            if (room == null) {
                sender.sendMessage(msg(sender instanceof Player ? "room.not-in-room" : "command.players-only"));
                return;
            }
        } else {
            room = roomNamed(hostName);
            if (room == null) {
                sender.sendMessage(msg("room.no-room", "host", hostName));
                return;
            }
        }
        boolean host = sender instanceof Player player && room.host().equals(player.getUniqueId());
        if (!host && !admin) {
            sender.sendMessage(msg("room.not-host-or-admin"));
            return;
        }
        if (room.isPlaying()) {
            sender.sendMessage(msg("room.already-playing"));
            return;
        }
        if (!room.canStart()) {
            sender.sendMessage(msg("room.below-count", "required", room.settings().players(), "size", room.size()));
            return;
        }
        if (!hasLease(room)) {
            sender.sendMessage(queued(room));
            return;
        }
        String arena = room.arena();
        if (!arenas.isAvailable(arena) || matches.match(arena).isPresent()) {
            sender.sendMessage(msg("room.not-ready", "arena", arena));
            return;
        }
        room.setPlaying(true);
        playing.put(arena, room);
        broadcast(room, msg("room.starting", "arena", arena));
        try {
            matches.start(arena, room.teams());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not start the match of the room of " + name(room.host())
                    + " in arena " + arena + ".", e);
        }
        if (matches.match(arena).isEmpty()) {
            playing.remove(arena, room);
            room.setPlaying(false);
            for (UUID member : room.members()) {
                Player online = plugin.getServer().getPlayer(member);
                if (online != null && online.isOnline()) {
                    enterLobby(online);
                }
            }
            broadcast(room, msg("room.start-failed"));
            if (!host) {
                sender.sendMessage(msg("room.start-failed"));
            }
        }
    }

    @Override
    public void leave(Player player) {
        if (!leaveRoom(player)) {
            tell(player, "room.not-in-room");
        }
    }

    /**
     * Takes a player out of their room, and out of the room's match if they are in it, to
     * the lobby. For {@code /narena leave}.
     *
     * @return false if they were in no room
     */
    public boolean leaveRoom(Player player) {
        UUID id = player.getUniqueId();
        Room room = roomOf.get(id);
        if (room == null) {
            return false;
        }
        remove(room, id);
        tell(player, "room.left");
        if (matches.isInMatch(id)) {
            matches.leave(player, MatchService.Exit.COMMAND);
        }
        return true;
    }

    /**
     * Where a player goes who leaves a match, or spectating, without leaving the plugin's
     * worlds: the lobby, keeping their snapshot. False if they could not be put there, and
     * should be sent home instead.
     */
    public boolean enterLobby(Player player) {
        try {
            return lobby.enter(player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not return " + player.getName() + " to the lobby.", e);
            return false;
        }
    }

    // ---- Events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Room room = roomOf.get(id);
        if (room != null) {
            remove(room, id);
        }
    }

    /**
     * A match closed. A room's players are back in the lobby, and its slot goes to the
     * longest-queued room; so does that of a room that closed while its match ran.
     */
    void matchClosed(String arena) {
        Room room = playing.remove(Arena.normalizeName(arena));
        if (room == null || !rooms.contains(room)) {
            if (room != null) {
                room.setPlaying(false);
                granted(slots.release(room));
            }
            granted(slots.pump());
            return;
        }
        room.setPlaying(false);
        // Back in the lobby: the lease ends, and the room queues for its next match.
        granted(slots.release(room));
        broadcast(room, msg("room.returned"));
        if (!slots.request(room, room.arena())) {
            for (UUID member : room.members()) {
                Player online = plugin.getServer().getPlayer(member);
                if (online != null) {
                    tellQueued(online, room);
                }
            }
        }
    }

    /** An arena entered edit mode or was deleted. */
    void arenaChanged(String arena) {
        String name = Arena.normalizeName(arena);
        for (Room room : List.copyOf(rooms)) {
            if (!room.arena().equals(name) || room.isPlaying()) {
                continue;
            }
            Optional<Arena> found = arenas.get(name);
            if (found.isPresent()) {
                // Edit mode: queue again until the arena is ready.
                granted(slots.release(room));
                slots.request(room, name);
                continue;
            }
            Optional<Arena> replacement = defaultArena(room);
            if (replacement.isEmpty()) {
                close(room, "room.closed-no-arena");
                continue;
            }
            setArena(room, replacement.get().name());
            broadcast(room, msg("room.arena-changed", "old", name, "arena", room.arena()));
        }
    }

    @Override
    public List<String> pickableMaps() {
        return arenas.all().stream().filter(Arena::pickable).map(Arena::name).toList();
    }

    @Override
    public void mapList(Player player) {
        Room room = roomOf.get(player.getUniqueId());
        if (room == null) {
            tell(player, "room.not-in-room");
            return;
        }
        List<String> maps = pickableMaps();
        tell(player, maps.isEmpty() ? "room.map-list-empty" : "room.map-list-header");
        for (String arena : maps) {
            tell(player, arena.equals(room.arena()) ? "room.map-list-current" : "room.map-list-entry",
                    "arena", arena);
        }
    }

    @Override
    public void mapSet(Player player, String arena) {
        if (!player.hasPermission(NinjaArenaCommand.MAP_CHANGE)) {
            tell(player, "room.map-no-permission");
            return;
        }
        Room room = hostRoom(player);
        if (room == null) {
            return;
        }
        if (room.isPlaying()) {
            tell(player, "room.map-playing");
            return;
        }
        Arena target = arenas.get(arena).orElse(null);
        if (target == null) {
            tell(player, "room.map-unknown", "arena", arena);
            return;
        }
        if (target.name().equals(room.arena())) {
            tell(player, "room.map-already", "arena", room.arena());
            return;
        }
        if (!target.pickable()) {
            tell(player, "room.map-not-pickable", "arena", target.name());
            return;
        }
        if (target.teamSpawns().size() < Arena.MIN_TEAMS) {
            tell(player, "room.map-too-few-teams", "arena", target.name());
            return;
        }
        String old = room.arena();
        setArena(room, target.name());
        broadcast(room, msg("room.map-changed", "old", old, "arena", room.arena(), "player", player.getName()));
        if (position(room).isPresent()) {
            broadcast(room, queued(room));
        }
    }

    /**
     * Moves a room in the lobby to another hand-built arena. Its lease moves with it: the old
     * slot is given up and the room asks for one on the new arena, queueing if it must. The
     * team count is lowered to what the new arena has spawns for.
     *
     * @throws IllegalStateException if the room is playing
     */
    public void setArena(Room room, String arena) {
        if (room.isPlaying()) {
            throw new IllegalStateException("the room is playing");
        }
        if (room.arena().equals(Arena.normalizeName(arena))) {
            return;
        }
        Arena target = arenas.get(arena).orElseThrow(() -> new IllegalArgumentException("unknown arena " + arena));
        room.setArena(target.name());
        int teams = Math.min(room.settings().teams(), teamCount(target));
        if (teams != room.settings().teams()) {
            RoomSettings fewer = room.settings().withTeams(teams);
            room.setSettings(fewer.capacity() < room.size() ? fewer.withTeamSize(0) : fewer);
        }
        granted(slots.release(room));
        slots.request(room, room.arena());
    }

    /** Checks the queue, drops members who are gone and shows each room its status. */
    public void tick() {
        for (Room room : List.copyOf(rooms)) {
            for (UUID member : room.members()) {
                Player online = plugin.getServer().getPlayer(member);
                if (online == null || !online.isOnline() || !lobby.isMember(member) && !matches.isInMatch(member)) {
                    remove(room, member);
                }
            }
        }
        granted(slots.pump());
        for (Room room : rooms) {
            if (room.isPlaying()) {
                continue;
            }
            Component line = status(room);
            for (UUID member : room.members()) {
                Player online = plugin.getServer().getPlayer(member);
                if (online != null) {
                    online.sendActionBar(line);
                }
            }
        }
    }

    /** Closes every room. Called on plugin disable, before the lobby and the matches close. */
    public void close() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Room room : List.copyOf(rooms)) {
            rooms.remove(room);
            slots.release(room);
            room.members().forEach(roomOf::remove);
        }
        playing.clear();
        crossing.run();
        releaseWorldIfUnused();
    }

    // ---- Internals ----

    /** Takes a member out, handing the room on or closing it when its host goes. */
    private void remove(Room room, UUID id) {
        String leaving = name(id);
        UUID host = room.host();
        roomOf.remove(id);
        List<WaitingRoom.Move> moves = room.leave(id, this::canHost).orElse(List.of());
        if (room.size() == 0 || room.isOrphaned()) {
            close(room, "room.closed");
            return;
        }
        notifyMoves(moves);
        broadcast(room, msg("room.player-left", "player", leaving, "count", count(room)));
        if (!room.host().equals(host)) {
            broadcast(room, msg("room.new-host", "player", name(room.host())));
        }
    }

    private boolean canHost(UUID id) {
        Player player = plugin.getServer().getPlayer(id);
        return player != null && player.isOnline() && player.hasPermission(CREATE);
    }

    /** Closes a room; its players stay where they are. */
    private void close(Room room, String reason) {
        if (!rooms.remove(room)) {
            return;
        }
        for (UUID member : room.members()) {
            roomOf.remove(member);
            tell(member, reason);
        }
        room.members().forEach(names::remove);
        if (!room.isPlaying()) {
            // A running match keeps its lease until it ends (matchClosed).
            granted(slots.release(room));
        }
        releaseWorldIfUnused();
    }

    private boolean holdWorld() {
        if (heldWorld != null) {
            return true;
        }
        try {
            heldWorld = worldHold.get();
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not open the arena world for a room.", e);
            return false;
        }
    }

    private void releaseWorldIfUnused() {
        if (rooms.isEmpty() && heldWorld != null) {
            Runnable release = heldWorld;
            heldWorld = null;
            release.run();
        }
    }

    /**
     * The arena a new room plays on until a map can be chosen: the featured map of a running
     * event when it is ready and no other room holds or waits for it, else the first such
     * arena by name, else the first no room claims, else the first with a team spawn. Empty
     * when there is no arena at all.
     */
    private Optional<Arena> defaultArena(Room except) {
        List<Arena> usable = new ArrayList<>();
        for (Arena arena : arenas.all()) {
            if (!arena.teamSpawns().isEmpty()) {
                usable.add(arena);
            }
        }
        usable.sort(Comparator.comparing(arena -> isFeatured(arena.name()) ? 0 : 1));
        for (Arena arena : usable) {
            if (arenas.isAvailable(arena.name()) && !claimedByOthers(arena.name(), except)) {
                return Optional.of(arena);
            }
        }
        for (Arena arena : usable) {
            if (!claimedByOthers(arena.name(), except)) {
                return Optional.of(arena);
            }
        }
        return usable.stream().findFirst();
    }

    private boolean claimedByOthers(String arena, Room except) {
        for (Room room : rooms) {
            if (room != except && room.arena().equals(arena)) {
                return true;
            }
        }
        return false;
    }

    private static int teamCount(Arena arena) {
        return Math.max(1, arena.teamSpawns().size());
    }

    /** The room the player hosts; tells them why not when it is none. */
    private Room hostRoom(Player player) {
        Room room = roomOf.get(player.getUniqueId());
        if (room == null) {
            tell(player, "room.not-in-room");
            return null;
        }
        if (!room.host().equals(player.getUniqueId())) {
            tell(player, "room.not-host");
            return null;
        }
        return room;
    }

    private Room roomNamed(String hostName) {
        Player host = plugin.getServer().getPlayerExact(hostName);
        if (host != null) {
            return hostedBy(host.getUniqueId()).orElse(null);
        }
        for (Room room : rooms) {
            if (name(room.host()).equalsIgnoreCase(hostName)) {
                return room;
            }
        }
        return null;
    }

    /**
     * The room list: the featured map of a running event, then every open room with its
     * arena, head count and state, and a join button where the player may join.
     */
    private void list(Player player) {
        featured.get().map(Arena::normalizeName)
                .ifPresent(arena -> tell(player, "room.list-featured", "arena", arena));
        if (rooms.isEmpty()) {
            tell(player, "room.list-empty");
            return;
        }
        tell(player, "room.list-header");
        UUID id = player.getUniqueId();
        for (Room room : rooms) {
            String host = name(room.host());
            Component state = room.isPlaying() ? msg("room.list-playing")
                    : room.isFull() ? msg("room.list-full")
                    : room.admits(id) ? button("room.button-accept", "accept " + host)
                    : msg("room.privacy-invite");
            player.sendMessage(msg(isFeatured(room.arena()) ? "room.list-line-featured" : "room.list-line",
                    "host", host, "arena", room.arena(), "count", count(room), "state", state));
        }
    }

    private boolean isFeatured(String arena) {
        return featured.get().map(Arena::normalizeName).filter(arena::equals).isPresent();
    }

    private Component status(Room room) {
        if (room.isPlaying()) {
            return msg("room.status-playing", "arena", room.arena());
        }
        OptionalInt position = slots.position(room);
        if (position.isPresent()) {
            return msg("room.status-queued", "position", position.getAsInt());
        }
        return room.canStart() ? msg("room.status-ready", "count", count(room))
                : msg("room.status-waiting", "count", count(room));
    }

    private Component queued(Room room) {
        int position = slots.position(room).orElse(1);
        return slots.hasFree() ? msg("room.queued-arena", "arena", room.arena(), "position", position)
                : msg("room.queued", "position", position);
    }

    private void tellQueued(Player player, Room room) {
        player.sendMessage(queued(room));
    }

    private static Component count(Room room) {
        return msg("room.count", "size", room.size(), "required", room.settings().players());
    }

    private static Component privacy(RoomSettings settings) {
        return msg(settings.inviteOnly() ? "room.privacy-invite" : "room.privacy-public");
    }

    private static Component teamSize(RoomSettings settings) {
        return settings.teamSize() == 0 ? msg("room.team-size-any") : Component.text(settings.teamSize());
    }

    /** A button from {@code messages.yml} that runs a room subcommand when clicked. */
    private static Component button(String key, String subcommand) {
        return msg(key).clickEvent(ClickEvent.runCommand(ROOT + subcommand));
    }

    private static Integer parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void notifyMoves(List<WaitingRoom.Move> moves) {
        for (WaitingRoom.Move move : moves) {
            tell(move.player(), "room.team", "team", move.to());
        }
    }

    private String name(UUID id) {
        Player player = plugin.getServer().getPlayer(id);
        if (player != null) {
            names.put(id, player.getName());
            return player.getName();
        }
        return names.getOrDefault(id, id.toString());
    }

    private void broadcast(Room room, Component message) {
        for (UUID member : room.members()) {
            Player player = plugin.getServer().getPlayer(member);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    private void tell(UUID id, String key, Object... placeholders) {
        Player player = plugin.getServer().getPlayer(id);
        if (player != null) {
            player.sendMessage(msg(key, placeholders));
        }
    }

    private static void tell(Player player, String key, Object... placeholders) {
        player.sendMessage(msg(key, placeholders));
    }
}
