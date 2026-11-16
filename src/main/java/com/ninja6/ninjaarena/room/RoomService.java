package com.ninja6.ninjaarena.room;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.arena.Arena;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.command.HostingCommands;
import com.ninja6.ninjaarena.command.NinjaArenaCommand;
import com.ninja6.ninjaarena.command.RoomCommands;
import com.ninja6.ninjaarena.map.GeneratedMaps;
import com.ninja6.ninjaarena.map.SeedPolicy;
import com.ninja6.ninjaarena.map.SeedSettings;
import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.sound.Sounds;
import com.ninja6.ninjaarena.sound.Sounds.Cue;
import com.ninja6.ninjaarena.terrain.MapSearch;
import com.ninja6.ninjaarena.waiting.WaitingRoom;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.CrossWorldTransfers;
import com.ninja6.ninjaarena.world.PluginWorlds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
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
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Host-started rooms. A player with {@link #CREATE} opens a room in the lobby, chooses its
 * settings, invites players and presses start once enough have joined; nothing starts a
 * room's match by itself. The rules for teams and hand-offs are in {@link Room}.
 *
 * <p><b>Arena slots.</b> A room plays on a hand-built arena, the first ready one when it is
 * created, or on a generated map when no hand-built arena is ready (once the seed modes are
 * set, {@link #setSeedPolicy}), and leases a slot for it at once ({@link SlotLeases}). While every slot is in use,
 * or its arena is, the room waits in a queue and its players see their position. The lease
 * lasts through the start, every round and the end of the match, and is given up when the
 * room is back in the lobby, where it queues again for its next match, or when it closes;
 * a room that closes while its match runs keeps the lease until the match ends. At most
 * {@link #maxActive()} slots are used at once. Every match is a room's, so every running
 * match holds a lease.
 *
 * <p><b>Generated maps.</b> A room can play a generated map instead ({@link #playGenerated}):
 * its lease is for its generated map's name, and needs a free slot cell as well as a free
 * slot, and no arena in edit mode. Once leased, the map is generated and placed in the
 * cell; the room can start when it is ready. After each match the cell is cleared and the
 * map generated again for the next. When no map comes from the seed, the room falls back
 * to its last good seed, else to a hand-built arena, and tells its players which.
 *
 * <p><b>Seeds.</b> A room on generated maps plays a seed its host set by hand
 * ({@link #playGenerated}), or the seed the seed modes give the slot its map is generated in
 * ({@link SeedPolicy}), at each generation. Under {@code maps.seed-sharing: distinct} no two
 * maps placed, being generated or set by hand have the same seed: a seed set by hand that
 * another slot has is refused ({@link #mapSetGenerated}). With {@code regenerate: round} the
 * slot's map is generated again in each shop break, in another cell, and takes the old one's
 * place only once it is fully placed; until then the shop waits ({@link #mapReady}), and a
 * map that fails or is not placed in time is dropped and the round keeps the current map.
 *
 * <p><b>Ways in.</b> Besides {@code /narena room create} and an invitation, a player who
 * walks through a play portal comes into the lobby and joins an open room there
 * ({@link #fromPortal}), and {@code /narena host <arena>} opens an invite-only room on the
 * named arena. In the generated lobby a player can also stand on a room's pad
 * ({@link #fromPad}). None of these starts a match.
 *
 * <p><b>Events.</b> While a scheduled event runs, its arena is the featured map
 * ({@link #setFeatured}): new rooms play on it when it is free, the room list shows it, and
 * portals that may take a player to any room prefer rooms on it. A match there earns the
 * event bonus, which the match engine captures when it starts.
 *
 * <p><b>Hosts.</b> The host may make members co-hosts ({@link #cohost}), who may start the
 * match, invite and remove plain members; settings and the map stay the host's. The player
 * who opened the room keeps a crown in the room list, the room view and the status line
 * whenever they are in it, host or not, and the host can hand the room back to them
 * ({@link #handBack}).
 *
 * <p><b>Leaving.</b> A member who leaves the room, by {@code /narena room leave}, by
 * {@code /narena leave}, by being removed, or by leaving the lobby and match world some
 * other way, is out of the room. When the host goes, the longest-waiting co-host who may
 * create rooms becomes the host, else the longest-waiting member who may; when there is
 * none, the room closes and its players stay in the lobby. A match that is running goes on
 * either way. A member who disconnects while their match holds them for its rejoin grace
 * stays in the room, as host if they were, until they are no longer held; the usual hand-off
 * follows if they have not come back by then.
 *
 * <p><b>Host grace.</b> A host who disconnects while the room is in the lobby stays its host
 * for {@link #hostGraceSeconds()} ({@code rooms.host-grace-seconds}); other members leave at
 * once. The room keeps its slot lease, its members are told, and starting, settings and
 * removals wait. A host who rejoins in time is put back in the lobby and hosts the room
 * again ({@link #rejoin}); one the lobby cannot take, as while it is built, is out of the
 * room at once. When the grace runs out the room is handed on as above. Graces are kept in
 * memory only: disabling the plugin closes every room, grace or not, and after a restart the
 * host is restored home on joining.
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
    /** How often the build progress bars are brought up to date. */
    private static final long BAR_TICKS = 5;
    private static final String ROOT = "/ninjaarena room ";
    /** The teams of a new room on generated maps; its host can set 2 to 4. */
    static final int GENERATED_TEAMS = 2;
    /** What {@code /narena map set} takes to play generated maps. */
    public static final String GENERATED = "generated";
    /** The config key of the host grace. */
    public static final String HOST_GRACE_KEY = "rooms.host-grace-seconds";
    /** {@code rooms.host-grace-seconds} by default. */
    public static final int DEFAULT_HOST_GRACE_SECONDS = 60;
    /** The longest {@code rooms.host-grace-seconds}. */
    public static final int MAX_HOST_GRACE_SECONDS = 600;

    public static final String REQUEST_LIFETIME_KEY = "rooms.request-lifetime-seconds";
    public static final String REQUEST_COOLDOWN_KEY = "rooms.request-cooldown-seconds";
    public static final int DEFAULT_REQUEST_LIFETIME = 60;
    public static final int DEFAULT_REQUEST_COOLDOWN = 30;

    /** Identity is checked by screen callbacks, so a later request cannot reuse an old form. */
    public record JoinRequest(Room room, UUID player, long expiresAt) { }
    private record RequestKey(Room room, UUID player) { }
    private final Map<RequestKey, JoinRequest> joinRequests = new HashMap<>();
    private final Map<RequestKey, Long> requestCooldowns = new HashMap<>();
    private final LongSupplier clock;
    private int requestLifetime = DEFAULT_REQUEST_LIFETIME;
    private int requestCooldown = DEFAULT_REQUEST_COOLDOWN;
    private Consumer<Player> requestScreen = player -> { };

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
    private Predicate<UUID> bedrock = id -> false;
    private GeneratedMaps generated;
    private SeedPolicy seeds;
    /** The seed each generated map placed or being prepared was asked for, by map name. */
    private final Map<String, Long> mapSeeds = new HashMap<>();
    /** Maps generated again in a shop break, by the name of the map each will replace. */
    private final Map<String, SeedChoice> nextMaps = new HashMap<>();

    private BukkitTask ticker;
    private BukkitTask barTicker;
    private int configuredMax;
    private int hostGraceSeconds = DEFAULT_HOST_GRACE_SECONDS;
    private final BuildBars bars;
    private MapPreviews previews;

    /**
     * @param defaults  the waiting-room settings a new room starts from: its required player
     *                  count and team size
     * @param worldHold {@code ArenaWorld#hold}
     * @param maxActive {@code arenas.max-active}
     */
    public RoomService(Plugin plugin, ArenaRegistry arenas, MatchService matches, RoomLobby lobby,
            Supplier<WaitingRoomSettings> defaults, CrossWorldTransfers transfers, Supplier<Runnable> worldHold,
            int maxActive) {
        this(plugin, arenas, matches, lobby, defaults, transfers, worldHold, maxActive, System::currentTimeMillis);
    }

    /** Clock-injected variant for deterministic request deadlines. */
    public RoomService(Plugin plugin, ArenaRegistry arenas, MatchService matches, RoomLobby lobby,
            Supplier<WaitingRoomSettings> defaults, CrossWorldTransfers transfers, Supplier<Runnable> worldHold,
            int maxActive, LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.lobby = Objects.requireNonNull(lobby, "lobby");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.worldHold = Objects.requireNonNull(worldHold, "worldHold");
        this.configuredMax = maxActive;
        this.slots = new SlotLeases<>(effectiveMax(maxActive));
        slots.setArenaTaken(this::taken);
        this.crossing = transfers.allow(roomOf::containsKey);
        this.bars = new BuildBars(id -> plugin.getServer().getPlayer(id));
        matches.onClose(this::matchClosed);
        arenas.onEditOrDelete(this::arenaChanged);
    }

    /** The featured map of the scheduled event running now, {@code ArenaEvents#arena}; by default none. */
    public void setFeatured(Supplier<Optional<String>> featured) {
        this.featured = Objects.requireNonNull(featured, "featured");
    }

    /**
     * {@code rooms.host-grace-seconds}: how long a host who disconnects in the lobby stays
     * host, for disconnects from now on; clamped to 0 to {@link #MAX_HOST_GRACE_SECONDS}. 0
     * hands the room on at once.
     */
    public void setHostGraceSeconds(int seconds) {
        hostGraceSeconds = Math.clamp(seconds, 0, MAX_HOST_GRACE_SECONDS);
    }

    /** Live reload affects new requests and cooldowns; existing deadlines stay fixed. */
    public void setRequestTimes(int lifetime, int cooldown) {
        requestLifetime = Math.clamp(lifetime, 1, 600);
        requestCooldown = Math.clamp(cooldown, 0, 600);
    }

    public void setRequestScreen(Consumer<Player> screen) {
        requestScreen = Objects.requireNonNull(screen, "screen");
    }

    public int hostGraceSeconds() {
        return hostGraceSeconds;
    }

    /** Identifies recipients whose chat cannot run click events; by default none. */
    public void setBedrock(Predicate<UUID> bedrock) {
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
    }

    public void setSounds(Sounds sounds) {
        this.sounds = sounds;
    }

    /**
     * The generated maps and their slot cells, one per slot ({@link #maxActive()}); newly
     * reserved cells are cleared. Until set, rooms play hand-built arenas only and
     * {@link #playGenerated} is refused. A cell that becomes free lets a queued room in.
     */
    public void setGeneratedMaps(GeneratedMaps generated) {
        this.generated = Objects.requireNonNull(generated, "generated");
        generated.onFree(() -> granted(slots.pump()));
        generated.resize(cells(maxActive()));
    }

    /**
     * The seed modes ({@code maps} in config.yml), after {@link #setGeneratedMaps}. From then
     * on a room with no hand-built arena ready plays generated maps, rooms on generated maps
     * without a seed set by hand take their seeds from it, and slots that regenerate between
     * rounds get one spare cell to place the next map in. Logs what the settings warn about.
     */
    public void setSeedPolicy(SeedPolicy seeds) {
        this.seeds = Objects.requireNonNull(seeds, "seeds");
        warn();
        if (generated != null) {
            generated.resize(cells(maxActive()));
        }
    }

    /**
     * New seed modes on reload. Each applies at a slot's next generation: a map already
     * placed, a running match and a shop break already under way keep theirs.
     */
    public void setSeedSettings(SeedSettings settings) {
        if (seeds == null) {
            return;
        }
        seeds.setSettings(settings);
        warn();
        if (generated != null) {
            generated.resize(cells(maxActive()));
        }
    }

    public Optional<SeedPolicy> seedPolicy() {
        return Optional.ofNullable(seeds);
    }

    private void warn() {
        seeds.warnings(maxActive()).forEach(plugin.getLogger()::warning);
    }

    /**
     * The slot cells to reserve for this many slots: one more while a slot regenerates its
     * map between rounds and the grid has room, for the next map to be placed in.
     */
    private int cells(int max) {
        boolean spare = seeds != null && seeds.settings().regeneratesRounds(max)
                && max < arenas.grid().cellsNearestFirst().size();
        return spare ? max + 1 : max;
    }

    /**
     * Map previews for room members ({@link #preview}), after {@link #setGeneratedMaps}: a
     * preview of a map ends when its cell starts being cleared. Until set, previews are
     * refused.
     */
    public void setPreviews(MapPreviews previews) {
        this.previews = Objects.requireNonNull(previews, "previews");
        previews.attach(this);
        if (generated != null) {
            generated.onClearing(previews::clearing);
        }
    }

    /** Whether the player is previewing their room's map. */
    public boolean isPreviewing(UUID player) {
        return previews != null && previews.contains(player);
    }

    /** Starts the once-a-second queue check and room status line, and the build progress bars. */
    public void start() {
        if (ticker == null) {
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TICKS_PER_SECOND,
                    TICKS_PER_SECOND);
        }
        if (barTicker == null) {
            barTicker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::showProgress, BAR_TICKS,
                    BAR_TICKS);
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
        int max = effectiveMax(maxActive);
        if (generated != null) {
            // Reserved cells never move, and a cell a generated map uses stays reserved.
            generated.resize(cells(max));
        }
        granted(slots.setMax(max));
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
                leased(room);
            }
        }
    }

    /** Asks for the room's slot; a room that gets it at once starts on its map. */
    private boolean request(Room room) {
        boolean leased = slots.request(room, room.arena());
        if (leased) {
            leased(room);
        }
        return leased;
    }

    /** The room holds its slot now: one on generated maps has its map generated. */
    private void leased(Room room) {
        if (generated != null && room.playsGenerated() && generated.cellOf(room.arena()).isEmpty()) {
            SeedChoice choice = choose(room, generated.nextFreeSlot().orElse(1));
            broadcast(room, msg("room.map-generating", "seed", choice.seed(), "difficulty", choice.difficulty()));
            prepare(room, room.arena(), choice, false);
        }
    }

    /**
     * The seed of the room's next map, to be generated in the slot: the one set by hand, or
     * what the seed modes give the slot. Under distinct, a seed set by hand that another slot
     * has taken since is not played; the map gets a random seed instead, and that is logged.
     */
    private SeedChoice choose(Room room, int slot) {
        SeedPolicy.Request asked = room.takeRequest();
        Set<Long> active = activeSeeds(room.arena(), room);
        if (seeds == null || room.isPinned()) {
            SeedChoice pinned = room.generated().orElseThrow();
            if (seeds == null || !seeds.collides(pinned.seed(), active)) {
                return pinned;
            }
            long instead = seeds.next(slot, active, SeedPolicy.Request.REROLL).seed();
            plugin.getLogger().warning("Seed " + pinned.seed() + " was set by hand for the room of "
                    + name(room.host()) + ", but another slot plays it now, so its map gets random seed " + instead
                    + " this time.");
            return new SeedChoice(instead, pinned.difficulty());
        }
        SeedChoice choice = seeds.next(slot, active, asked).choice();
        room.setSeed(choice);
        return choice;
    }

    /**
     * The seeds active outside the room's map: those of every other map placed or being
     * prepared, of the maps being generated for a next round, and of the rooms whose seed
     * was set by hand.
     */
    public Set<Long> activeSeeds(String exceptMap, Room exceptRoom) {
        Set<Long> active = new HashSet<>();
        mapSeeds.forEach((map, seed) -> {
            if (!map.equals(exceptMap)) {
                active.add(seed);
            }
        });
        nextMaps.forEach((map, choice) -> {
            if (!map.equals(exceptMap)) {
                active.add(choice.seed());
            }
        });
        for (Room room : rooms) {
            if (room != exceptRoom && room.isPinned()) {
                room.generated().ifPresent(choice -> active.add(choice.seed()));
            }
        }
        return active;
    }

    /**
     * Whether a slot cannot be leased on the arena now. A hand-built arena must be available.
     * A generated map needs a free slot cell that no lease granted before it in the same
     * check has taken yet, and no arena in edit mode, since an edit turns protection off
     * across the arena world.
     */
    private boolean taken(String arena) {
        if (!Arena.isGeneratedName(arena)) {
            return !arenas.isAvailable(arena);
        }
        if (generated == null || !arenas.editing().isEmpty()) {
            return true;
        }
        return generated.freeCells() <= waitingForCell();
    }

    /** Leased slots on generated maps whose map has no cell yet. */
    private long waitingForCell() {
        return slots.leasedArenas().stream().filter(Arena::isGeneratedName)
                .filter(name -> generated.cellOf(name).isEmpty()).count();
    }

    /**
     * Whether a slot cell is free beyond those leased rooms wait for, so a map that is no
     * room's lease, such as an admin's or a next round's, can take it.
     */
    public boolean hasSpareCell() {
        return generated != null && arenas.editing().isEmpty() && generated.freeCells() > waitingForCell();
    }

    /** Whether a match may start on the room's map now. */
    private boolean available(String arena) {
        if (Arena.isGeneratedName(arena)) {
            return generated != null && generated.isAvailable(arena);
        }
        return arenas.isAvailable(arena);
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

    /** Display name shared with room screens, including members who are temporarily offline. */
    public String playerName(UUID id) {
        return name(id);
    }

    /** Online players the host or a co-host may invite; presses still use {@link #invite} to recheck. */
    public List<Player> inviteCandidates(Player host) {
        Room room = roomOf.get(host.getUniqueId());
        if (room == null || !room.isManager(host.getUniqueId())) {
            return List.of();
        }
        return freeInvitePlayers();
    }

    private List<Player> freeInvitePlayers() {
        return lobby.members().stream().filter(id -> !roomOf.containsKey(id))
                .map(id -> plugin.getServer().getPlayer(id))
                .filter(Objects::nonNull)
                .filter(player -> player.hasPermission(NinjaArenaCommand.PLAY)).toList();
    }

    @Override
    public void show(Player player) {
        Room room = roomOf.get(player.getUniqueId());
        if (room == null) {
            list(player);
            return;
        }
        player.sendMessage(msg("room.info-header", "host", name(room.host()), "arena", room.arena(),
                "privacy", privacy(room.settings())));
        if (room.isHostAway()) {
            tell(player, "room.info-host-away", "player", name(room.host()), "seconds", room.hostGraceLeft());
        }
        if (isFeatured(room.arena())) {
            tell(player, "room.info-featured", "arena", room.arena());
        }
        player.sendMessage(msg("room.info-settings", "size", room.size(), "required", room.settings().players(),
                "teams", room.settings().teams(), "team-size", teamSize(room.settings())));
        player.sendMessage(status(room));
        UUID viewer = player.getUniqueId();
        boolean host = room.host().equals(viewer);
        for (UUID member : room.members()) {
            String key = member.equals(room.host()) ? "room.info-host"
                    : room.isCohost(member) ? "room.info-cohost" : "room.info-member";
            Component buttons = Component.empty();
            if (!member.equals(room.host()) && (host || room.isCohost(viewer) && !room.isCohost(member))) {
                buttons = button(player, "room.button-kick", "kick " + name(member));
            }
            if (host && !member.equals(room.host())) {
                buttons = buttons.append(Component.space()).append(room.isCohost(member)
                        ? button(player, "room.button-uncohost", "uncohost " + name(member))
                        : button(player, "room.button-cohost", "cohost " + name(member)));
            }
            player.sendMessage(msg(key, "player", name(member), "crown", crown(room, member),
                    "team", room.teamOf(member), "kick", buttons));
        }
        if (host && room.hasCreator() && !room.creator().equals(viewer)) {
            player.sendMessage(msg("room.handback-offer", "player", name(room.creator()),
                    "handback", button(player, "room.button-handback", "handback")));
        }
        if (room.isManager(viewer) && !room.isHostAway() && !room.isPlaying() && room.canStart() && hasLease(room)) {
            player.sendMessage(msg("room.start-ready", "start", button(player, "room.button-start", "start")));
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
        Optional<Arena> arena = chosen != null ? Optional.of(chosen) : readyArena(null);
        // With no hand-built arena ready, a new room plays generated maps, so a server with
        // none at all works out of the box.
        boolean onGenerated = arena.isEmpty() && chosen == null && generated != null && seeds != null;
        if (arena.isEmpty() && !onGenerated) {
            arena = defaultArena(null);
        }
        if (arena.isEmpty() && !onGenerated) {
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
        int teams = onGenerated ? GENERATED_TEAMS : teamCount(arena.get());
        int teamSize = base.teamSize();
        int capacity = teamSize == 0 ? Integer.MAX_VALUE : teams * teamSize;
        RoomSettings settings = new RoomSettings(false,
                Math.min(Math.min(base.minPlayers(), RoomSettings.MAX_PLAYERS), capacity), teams, teamSize);
        Room room = new Room(id, onGenerated ? generated.newName() : arena.get().name(), settings);
        if (onGenerated) {
            room.setSeedModes();
        }
        rooms.add(room);
        cancelRequests(id, null);
        roomOf.put(id, room);
        names.put(id, player.getName());
        tell(player, "room.created", "arena", room.arena());
        if (sounds != null) {
            sounds.room(Cue.ROOM_JOIN, room);
        }
        if (!request(room)) {
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
                if (!choice.equals("public") && !choice.equals("ask") && !choice.equals("invite")) {
                    tell(player, "room.settings-privacy");
                    return;
                }
                next = current.withPrivacy(RoomSettings.Privacy.valueOf(choice.toUpperCase(Locale.ROOT)));
            }
            case "players", "teams", "team-size" -> {
                int max = switch (key) {
                    case "players" -> Math.min(RoomSettings.MAX_PLAYERS, current.capacity());
                    // A generated map is laid out for the team count, up to four.
                    case "teams" -> room.playsGenerated() ? Arena.MAX_TEAMS
                            : arenas.get(room.arena()).map(RoomService::teamCount).orElse(1);
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
        if (current.privacy() != next.privacy()) {
            cancelRequests(room, next.privacy() == RoomSettings.Privacy.PUBLIC
                    ? "room.request-public" : "room.request-invite");
        }
        broadcast(room, msg("room.settings-changed", "player", player.getName(), "setting", key,
                "value", key.equals("privacy") ? privacy(next) : key.equals("team-size") ? teamSize(next)
                        : Component.text(value)));
        if (!before.equals(room.teams())) {
            for (UUID member : room.members()) {
                tell(member, "room.team", "team", room.teamOf(member));
            }
        }
        if (room.playsGenerated() && layoutTeams(current) != layoutTeams(next)) {
            // A map laid out for 2 teams has no sides for 3 or 4: generate it again.
            regenerate(room);
        }
    }

    @Override
    public void invite(Player player, String invitee) {
        Room room = managerRoom(player);
        if (room == null) {
            return;
        }
        if (invitee == null) {
            List<Player> free = freeInvitePlayers();
            if (free.isEmpty()) {
                tell(player, "room.invite-list-empty");
                return;
            }
            tell(player, "room.invite-list-header");
            for (Player target : free) {
                player.sendMessage(msg("room.invite-list-line", "player", target.getName(),
                        "invite", button(player, "room.button-invite", "invite " + target.getName())));
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
        // The accept names the host, whoever invited: it finds the room by its host.
        String host = name(room.host());
        Component accept = button(target, "room.button-accept", "accept " + host);
        target.sendMessage(room.host().equals(player.getUniqueId())
                ? msg("room.invited-you", "host", host, "arena", room.arena(), "accept", accept)
                : msg("room.invited-you-by", "player", player.getName(), "host", host, "arena", room.arena(),
                        "accept", accept));
        tell(player, "room.invited", "player", target.getName());
    }

    @Override
    public void accept(Player player, String hostName) {
        Room room = roomNamed(hostName);
        if (room == null) {
            tell(player, "room.no-room", "host", hostName);
            return;
        }
        tryJoin(player, room, null, null);
    }

    /**
     * A player stood on the room's lobby pad for its full time ({@code RoomPads}): a public
     * room is joined, an ask-to-join room gets a join request, with its cooldown, and an
     * invite-only room tells them it is invite only unless they were invited, who join. It
     * takes the same path, and the same checks, as {@link #accept}: a full room, a room in a
     * match or closed, a lobby that is closed while it is built, a player already in a room.
     */
    public void fromPad(Player player, Room room) {
        tryJoin(player, room, null, null);
    }

    /** The shared admission path, including after approval; no invitation is left behind on failure. */
    private boolean tryJoin(Player player, Room room, JoinRequest approval, Player manager) {
        UUID id = player.getUniqueId();
        if (!player.hasPermission(NinjaArenaCommand.PLAY)) {
            tell(player, "command.no-permission");
            return false;
        }
        if (room.contains(id)) {
            tell(player, "room.already-joined");
            return false;
        }
        if (roomOf.containsKey(id)) {
            tell(player, "room.already-in-room");
            return false;
        }
        String host = name(room.host());
        if (!rooms.contains(room) || room.isPlaying()) {
            tell(player, "room.playing", "host", host);
            return false;
        }
        boolean asking = approval == null && !room.isInvited(id) && room.canRequest(id);
        if (!asking && !room.admits(id) && !validApproval(approval)) {
            tell(player, "room.no-invitation", "host", host);
            return false;
        }
        if (room.isFull()) {
            tell(player, "room.full", "host", host);
            return false;
        }
        Optional<String> refused = lobby.join(player);
        if (refused.isPresent()) {
            player.sendMessage(msg("room.refused", "message", refused.get()));
            return false;
        }
        // Entering the lobby may teleport and invoke other services: recheck all admission state.
        if (!rooms.contains(room) || room.isPlaying() || room.isFull() || roomOf.containsKey(id)
                || !lobby.isMember(id) || !player.isOnline()
                || !player.hasPermission(NinjaArenaCommand.PLAY)) {
            tell(player, "room.request-unavailable", "host", host);
            return false;
        }
        if (approval != null) {
            if (roomOf.get(manager.getUniqueId()) != room || !room.isManager(manager.getUniqueId())
                    || !manager.hasPermission(NinjaArenaCommand.PLAY)) {
                tell(manager, "room.request-approver-changed");
                return false;
            }
            if (!validApproval(approval)) {
                tell(manager, "room.request-missing");
                return false;
            }
        }
        if (asking && room.canRequest(id) && !room.isInvited(id)) {
            sendRequest(player, room);
            return false;
        }
        if (approval == null && !room.admits(id)) {
            tell(player, "room.no-invitation", "host", host);
            return false;
        }
        if (approval != null) {
            joinRequests.remove(new RequestKey(room, id), approval);
            tell(player, "room.request-accepted", "host", host);
        }
        join(player, room);
        return true;
    }

    private void sendRequest(Player player, Room room) {
        pruneRequests();
        RequestKey key = new RequestKey(room, player.getUniqueId());
        long now = clock.getAsLong();
        long remaining = requestCooldowns.getOrDefault(key, now) - now;
        if (remaining > 0) {
            tell(player, "room.request-cooldown", "seconds", (remaining + 999) / 1000);
            return;
        }
        if (joinRequests.containsKey(key)) {
            tell(player, "room.request-pending", "host", name(room.host()));
            return;
        }
        JoinRequest request = new JoinRequest(room, player.getUniqueId(), now + requestLifetime * 1000L);
        joinRequests.put(key, request);
        requestCooldowns.put(key, now + requestCooldown * 1000L);
        names.put(player.getUniqueId(), player.getName());
        tell(player, "room.request-sent", "host", name(room.host()), "seconds", requestLifetime);
        for (UUID member : room.members()) {
            Player manager = plugin.getServer().getPlayer(member);
            if (room.isManager(member) && manager != null && manager.isOnline()) {
                requestLine(manager, request);
            }
        }
    }

    private void requestLine(Player manager, JoinRequest request) {
        String player = name(request.player());
        manager.sendMessage(msg("room.request-received", "player", player,
                "accept", button(manager, "room.button-accept-request", "accept-request " + player),
                "decline", button(manager, "room.button-decline-request", "decline-request " + player),
                "view", button(manager, "room.button-requests", "requests")));
    }

    /** Pending requests for screens; only managers see them, and every read prunes invalid ones. */
    public List<JoinRequest> pendingRequests(Player player) {
        pruneRequests();
        Room room = roomOf.get(player.getUniqueId());
        if (room == null || !room.isManager(player.getUniqueId())) {
            return List.of();
        }
        return joinRequests.values().stream().filter(request -> request.room() == room)
                .sorted(Comparator.comparing(request -> name(request.player()), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public void requests(Player player) {
        if (managerRoom(player) == null) {
            return;
        }
        List<JoinRequest> pending = pendingRequests(player);
        tell(player, pending.isEmpty() ? "room.requests-empty" : "room.requests-header");
        pending.forEach(request -> requestLine(player, request));
        requestScreen.accept(player);
    }

    @Override
    public void acceptRequest(Player player, String requester) {
        decideRequest(player, requester, true);
    }

    @Override
    public void declineRequest(Player player, String requester) {
        decideRequest(player, requester, false);
    }

    private void decideRequest(Player player, String requester, boolean accept) {
        if (managerRoom(player) == null) {
            return;
        }
        JoinRequest request = pendingRequests(player).stream()
                .filter(pending -> name(pending.player()).equalsIgnoreCase(requester)).findFirst().orElse(null);
        decideRequest(player, request, accept);
    }

    /** Screens capture the request itself, never just a name that could refer to a later request. */
    public void decideRequest(Player manager, JoinRequest request, boolean accept) {
        if (!manager.hasPermission(NinjaArenaCommand.PLAY)) {
            tell(manager, "command.no-permission");
            return;
        }
        Room room = managerRoom(manager);
        if (room == null) {
            return;
        }
        pruneRequests();
        if (request == null || request.room() != room || !validApproval(request)) {
            tell(manager, "room.request-missing");
            return;
        }
        Player target = plugin.getServer().getPlayer(request.player());
        if (!accept) {
            joinRequests.remove(new RequestKey(room, request.player()), request);
            tell(target, "room.request-declined", "host", name(room.host()));
            tell(manager, "room.request-decision", "player", name(request.player()));
        } else if (tryJoin(target, room, request, manager)) {
            tell(manager, "room.request-decision", "player", name(request.player()));
        } else {
            // Failed approvals end too: the manager cannot retry a stale admission later.
            endRequest(request, "room.request-cancelled");
            tell(manager, "room.request-failed", "player", name(request.player()));
        }
    }

    private boolean validApproval(JoinRequest request) {
        if (request == null) {
            return false;
        }
        Room room = request.room();
        UUID id = request.player();
        Player player = plugin.getServer().getPlayer(id);
        return joinRequests.get(new RequestKey(room, id)) == request && clock.getAsLong() < request.expiresAt()
                && rooms.contains(room) && !room.isPlaying() && room.settings().asksToJoin()
                && room.canRequest(id) && !roomOf.containsKey(id) && lobby.isMember(id)
                && player != null && player.isOnline();
    }

    private void pruneRequests() {
        long now = clock.getAsLong();
        for (JoinRequest request : List.copyOf(joinRequests.values())) {
            if (!validApproval(request)) {
                String reason = now >= request.expiresAt() ? "room.request-expired"
                        : !rooms.contains(request.room()) ? "room.request-closed"
                        : request.room().isPlaying() ? "room.request-started"
                        : request.room().settings().privacy() == RoomSettings.Privacy.PUBLIC ? "room.request-public"
                        : request.room().settings().inviteOnly() ? "room.request-invite"
                        : roomOf.containsKey(request.player()) ? null
                        : !lobby.isMember(request.player()) ? "room.request-left-lobby" : "room.request-cancelled";
                endRequest(request, reason);
            }
        }
        requestCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now || !rooms.contains(entry.getKey().room()));
    }

    private void endRequest(JoinRequest request, String message) {
        if (joinRequests.remove(new RequestKey(request.room(), request.player()), request) && message != null) {
            tell(request.player(), message, "host", name(request.room().host()));
        }
    }

    /** A null reason ends silently when the requester changes rooms or disconnects. */
    private void cancelRequests(UUID player, String reason) {
        for (JoinRequest request : List.copyOf(joinRequests.values())) {
            if (request.player().equals(player)) {
                endRequest(request, reason);
            }
        }
    }

    private void cancelRequests(Room room, String reason) {
        for (JoinRequest request : List.copyOf(joinRequests.values())) {
            if (request.room() == room) {
                endRequest(request, reason);
            }
        }
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
                .filter(room -> !room.settings().asksToJoin())
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
        cancelRequests(id, null);
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
        if (id.equals(room.creator())) {
            // Back after a hand-over: the crown, not the host role, which the host can hand back.
            tell(player, "room.creator-returned");
            Player current = plugin.getServer().getPlayer(room.host());
            if (current != null) {
                current.sendMessage(msg("room.creator-back", "player", player.getName(),
                        "handback", button(current, "room.button-handback", "handback")));
            }
        }
    }

    @Override
    public void kick(Player player, String member) {
        Room room = managerRoom(player);
        if (room == null) {
            return;
        }
        UUID targetId = memberNamed(room, member);
        if (targetId == null) {
            tell(player, "room.kick-not-member", "player", member);
            return;
        }
        if (targetId.equals(player.getUniqueId())) {
            tell(player, "room.kick-self");
            return;
        }
        if (targetId.equals(room.host())) {
            tell(player, "room.kick-host");
            return;
        }
        if (!room.host().equals(player.getUniqueId()) && room.isCohost(targetId)) {
            tell(player, "room.kick-manager", "player", name(targetId));
            return;
        }
        if (room.isPlaying()) {
            tell(player, "room.kick-playing");
            return;
        }
        if (room.isHostAway()) {
            tellHostAway(player, room);
            return;
        }
        roomOf.remove(targetId);
        endPreview(targetId);
        notifyMoves(room.kick(targetId).orElse(List.of()));
        if (room.host().equals(player.getUniqueId())) {
            tell(targetId, "room.kicked", "host", player.getName());
        } else {
            tell(targetId, "room.kicked-by", "player", player.getName(), "host", name(room.host()));
        }
        broadcast(room, msg("room.player-kicked", "player", name(targetId)));
    }

    /** A member of the room by name, online or not; null if there is none. */
    private UUID memberNamed(Room room, String member) {
        Player target = plugin.getServer().getPlayerExact(member);
        UUID targetId = target != null ? target.getUniqueId() : room.members().stream()
                .filter(id -> name(id).equalsIgnoreCase(member)).findFirst().orElse(null);
        return targetId != null && room.contains(targetId) ? targetId : null;
    }

    @Override
    public void cohost(Player player, String member) {
        Room room = hostRoom(player);
        if (room == null) {
            return;
        }
        UUID targetId = memberNamed(room, member);
        if (targetId == null) {
            tell(player, "room.kick-not-member", "player", member);
            return;
        }
        if (targetId.equals(player.getUniqueId())) {
            tell(player, "room.cohost-self");
            return;
        }
        if (!room.addCohost(targetId)) {
            tell(player, "room.cohost-already", "player", name(targetId));
            return;
        }
        broadcast(room, msg("room.cohost-added", "player", name(targetId)));
    }

    @Override
    public void uncohost(Player player, String member) {
        Room room = hostRoom(player);
        if (room == null) {
            return;
        }
        UUID targetId = memberNamed(room, member);
        if (targetId == null) {
            tell(player, "room.kick-not-member", "player", member);
            return;
        }
        if (!room.removeCohost(targetId)) {
            tell(player, "room.cohost-not", "player", name(targetId));
            return;
        }
        broadcast(room, msg("room.cohost-removed", "player", name(targetId)));
    }

    /**
     * {@code /narena room handback}: the host hands the room back to the player who opened
     * it, who must be in it and allowed to host; the host before becomes a co-host.
     */
    @Override
    public void handBack(Player player) {
        Room room = hostRoom(player);
        if (room == null) {
            return;
        }
        UUID creator = room.creator();
        if (creator.equals(player.getUniqueId())) {
            tell(player, "room.handback-self");
            return;
        }
        if (!room.contains(creator)) {
            tell(player, "room.handback-no-creator", "player", name(creator));
            return;
        }
        if (!canHost(creator)) {
            tell(player, "room.handback-cannot-host", "player", name(creator));
            return;
        }
        room.handTo(creator);
        broadcast(room, msg("room.handed-back", "player", player.getName(), "creator", name(creator)));
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
        boolean manager = sender instanceof Player player && room.isManager(player.getUniqueId());
        boolean inRoom = sender instanceof Player player && room.contains(player.getUniqueId());
        if (!manager && !admin) {
            sender.sendMessage(msg("room.not-host-or-admin"));
            return;
        }
        if (room.isPlaying()) {
            sender.sendMessage(msg("room.already-playing"));
            return;
        }
        if (room.isHostAway()) {
            sender.sendMessage(hostAway(room));
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
        Optional<GeneratedMaps.Progress> building = building(room);
        if (building.isPresent()) {
            sender.sendMessage(msg("room.start-building", "percent", BuildBars.percent(building.get())));
            return;
        }
        String arena = room.arena();
        if (!available(arena) || matches.match(arena).isPresent()) {
            sender.sendMessage(msg("room.not-ready", "arena", arena));
            return;
        }
        if (!returnPreviewers(room)) {
            if (rooms.contains(room)) {
                sender.sendMessage(msg("room.start-failed"));
            }
            return;
        }
        cancelRequests(room, "room.request-started");
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
            if (!inRoom) {
                sender.sendMessage(msg("room.start-failed"));
            }
        }
    }

    /**
     * Brings the room's previewers back to the lobby before its match starts, so that only
     * players in the lobby are taken into it. One the lobby could not take was sent home and
     * is out of the room.
     *
     * @return whether the room is still open with enough players to start
     */
    private boolean returnPreviewers(Room room) {
        if (previews == null || !previews.anyIn(room)) {
            return true;
        }
        // One the lobby could not take is out of the room by now (sentHome).
        previews.endRoom(room);
        return rooms.contains(room) && room.canStart();
    }

    /**
     * A preview ended with the player restored home because the lobby could not take them:
     * they have left the plugin's worlds, so they leave the room now rather than at the
     * next tick, and no match can take them from home.
     */
    void sentHome(Player player) {
        UUID id = player.getUniqueId();
        Room room = roomOf.get(id);
        if (room != null && rooms.contains(room) && !lobby.isMember(id) && !matches.isInMatch(id)) {
            remove(room, id);
        }
    }

    // ---- Map previews ----

    /**
     * How far the room's generated map has got while it is being built for the room's next
     * match; empty when it is not being built, because it is placed, it failed, the room is
     * queued or playing, or plays a hand-built arena.
     */
    public Optional<GeneratedMaps.Progress> building(Room room) {
        if (generated == null || !room.playsGenerated() || room.isPlaying()) {
            return Optional.empty();
        }
        return generated.progress(room.arena()).filter(progress -> !progress.isComplete());
    }

    /** Whether the room's generated map can be previewed now: placed and not playing. */
    public boolean canPreview(Room room) {
        return previews != null && generated != null && rooms.contains(room) && !room.isPlaying()
                && room.playsGenerated() && generated.isAvailable(room.arena());
    }

    /** Whether the player's preview of the map may go on. */
    boolean previewable(UUID player, Room room, String map) {
        return roomOf.get(player) == room && room.arena().equals(map) && canPreview(room);
    }

    /** Brings the build progress bars up to date. Runs every few ticks once started. */
    public void showProgress() {
        bars.update(rooms, this::building);
    }

    /** Whether the room's members are shown its build progress bar. */
    public boolean showsProgress(Room room) {
        return bars.isShown(room);
    }

    @Override
    public void preview(Player player) {
        UUID id = player.getUniqueId();
        if (previews != null && previews.leave(player)) {
            return;
        }
        Room room = roomOf.get(id);
        if (room == null) {
            tell(player, "room.not-in-room");
            return;
        }
        if (previews == null || generated == null) {
            tell(player, "room.preview-unavailable");
            return;
        }
        if (room.isPlaying()) {
            tell(player, "room.preview-playing");
            return;
        }
        if (!room.playsGenerated()) {
            tell(player, "room.preview-hand-built");
            return;
        }
        Optional<GeneratedMaps.Progress> building = building(room);
        if (building.isPresent()) {
            tell(player, "room.preview-building", "percent", BuildBars.percent(building.get()));
            return;
        }
        Optional<Arena> map = canPreview(room) ? generated.get(room.arena()) : Optional.empty();
        if (map.isEmpty()) {
            tell(player, "room.preview-not-ready");
            return;
        }
        if (!lobby.isMember(id)) {
            tell(player, "room.preview-not-in-lobby");
            return;
        }
        previews.begin(player, room, map.get());
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
        boolean inMatch = matches.isInMatch(id);
        remove(room, id);
        if (inMatch) {
            // One message for both: the match and the room.
            matches.leaveWithRoom(player);
        } else {
            tell(player, "room.left");
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

    /** A trip out of the lobby ends requests even if the player returns before the next tick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChanged(PlayerChangedWorldEvent event) {
        if (PluginWorlds.isLobby(event.getFrom())) {
            cancelRequests(event.getPlayer().getUniqueId(), "room.request-left-lobby");
        }
    }

    /**
     * A member who disconnects leaves the room, unless their match holds them for the rejoin
     * grace; they stay a member, and the host stays the host, until they are no longer held.
     * A host who disconnects while the room is in the lobby is held for the host grace.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancelRequests(id, null);
        Room room = roomOf.get(id);
        if (room == null || matches.isHeld(id)) {
            return;
        }
        if (room.host().equals(id) && !room.isPlaying() && !matches.isInMatch(id) && hostGraceSeconds > 0) {
            names.put(id, event.getPlayer().getName());
            room.holdHost(hostGraceSeconds);
            for (UUID other : room.others(id)) {
                tell(other, "room.host-away", "player", name(id), "seconds", hostGraceSeconds);
            }
            return;
        }
        remove(room, id);
    }

    /**
     * A player who quit from the lobby flow joined again and goes back to the lobby, with
     * their snapshot kept ({@code LobbyReturns}). A host back within their grace hosts their
     * room again; one the lobby cannot take, as while it is built, is out of the room at once
     * and goes home.
     *
     * @return whether they are in the lobby now
     */
    public boolean rejoin(Player player) {
        UUID id = player.getUniqueId();
        Room room = roomOf.get(id);
        boolean away = room != null && room.isHostAway() && room.host().equals(id);
        boolean entered = enterLobby(player);
        if (away && rooms.contains(room) && room.contains(id)) {
            if (entered) {
                hostBack(room);
            } else {
                room.endGrace();
                tell(player, "room.host-grace-lost");
                remove(room, id);
            }
        }
        return entered;
    }

    private void hostBack(Room room) {
        room.endGrace();
        broadcast(room, msg("room.host-back", "player", name(room.host())));
    }

    /**
     * A match closed. A room's players are back in the lobby, and its slot goes to the
     * longest-queued room; so does that of a room that closed while its match ran.
     */
    void matchClosed(String arena) {
        String name = Arena.normalizeName(arena);
        Room room = playing.remove(name);
        if (generated != null && Arena.isGeneratedName(name)) {
            // Reset by clearing the cell; the room's next lease generates the map again.
            freeMap(name);
        }
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
        if (!request(room)) {
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
                request(room);
                continue;
            }
            Optional<Arena> replacement = defaultArena(room);
            if (replacement.isEmpty() && generated != null && seeds != null) {
                playSeedModes(room);
                if (rooms.contains(room)) {
                    broadcast(room, msg("room.arena-changed", "old", name, "arena", room.arena()));
                }
                continue;
            }
            if (replacement.isEmpty()) {
                close(room, "room.closed-no-arena");
                continue;
            }
            setArena(room, replacement.get().name());
            if (rooms.contains(room)) {
                broadcast(room, msg("room.arena-changed", "old", name, "arena", room.arena()));
            }
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
        boolean offersGenerated = generated != null && seeds != null;
        tell(player, maps.isEmpty() && !offersGenerated ? "room.map-list-empty" : "room.map-list-header");
        for (String arena : maps) {
            tell(player, arena.equals(room.arena()) ? "room.map-list-current" : "room.map-list-entry",
                    "arena", arena);
        }
        if (offersGenerated) {
            tell(player, room.playsGenerated() ? "room.map-list-generated-current" : "room.map-list-generated");
        }
    }

    /**
     * {@code /narena map set generated [seed] [difficulty]}, for {@code ninjaarena.map.change}:
     * the host's room plays generated maps, from the seed modes without a seed, or from the
     * seed set by hand. Under distinct, a seed another slot has is refused with a message,
     * never replaced. In the lobby the map is generated now; nothing changes while the room
     * plays.
     *
     * @param seed       null for the seed modes
     * @param difficulty null for {@code maps.default.difficulty}
     */
    @Override
    public void mapSetGenerated(Player player, Long seed, Integer difficulty) {
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
        if (generated == null || seeds == null) {
            tell(player, "room.map-generated-off");
            return;
        }
        if (seed == null) {
            if (room.playsGenerated() && !room.isPinned()) {
                tell(player, "room.map-already-generated");
                return;
            }
            String old = room.arena();
            playSeedModes(room);
            if (!rooms.contains(room)) {
                return;
            }
            broadcast(room, msg("room.map-changed-generated", "old", old, "player", player.getName()));
            return;
        }
        int level = difficulty != null ? difficulty : seeds.settings().defaults().difficulty();
        if (level < SeedSettings.MIN_DIFFICULTY || level > SeedSettings.MAX_DIFFICULTY) {
            tell(player, "room.map-difficulty-invalid", "min", SeedSettings.MIN_DIFFICULTY,
                    "max", SeedSettings.MAX_DIFFICULTY);
            return;
        }
        if (seeds.collides(seed, activeSeeds(room.arena(), room))) {
            tell(player, "room.map-seed-in-use", "seed", seed);
            return;
        }
        SeedChoice choice = new SeedChoice(seed, level);
        if (room.isPinned() && room.generated().equals(Optional.of(choice))) {
            tell(player, "room.map-seed-already", "seed", seed, "difficulty", level);
            return;
        }
        playGenerated(room, seed, level);
        if (!rooms.contains(room)) {
            return;
        }
        broadcast(room, msg("room.map-changed-seed", "seed", seed, "difficulty", level, "player", player.getName()));
    }

    /**
     * {@code /narena map next}, for {@code ninjaarena.map.change}: the next rotation seed at
     * the next generation of the host's map, a new random seed for a random slot.
     */
    @Override
    public void mapNext(Player player) {
        askSeed(player, SeedPolicy.Request.NEXT);
    }

    /** {@code /narena map reroll}, for {@code ninjaarena.map.change}: a new random seed at the next generation. */
    @Override
    public void mapReroll(Player player) {
        askSeed(player, SeedPolicy.Request.REROLL);
    }

    /**
     * Asks for a seed at the next generation of the host's generated map. A seed set by
     * hand gives way to the seed modes. In the lobby that generation starts now; while the
     * room plays, it is the next shop break with {@code regenerate: round}, else the next
     * match. A running round's map never changes.
     */
    private void askSeed(Player player, SeedPolicy.Request request) {
        if (!player.hasPermission(NinjaArenaCommand.MAP_CHANGE)) {
            tell(player, "room.map-no-permission");
            return;
        }
        Room room = hostRoom(player);
        if (room == null) {
            return;
        }
        if (seeds == null || !room.playsGenerated()) {
            tell(player, "room.map-not-generated");
            return;
        }
        if (request == SeedPolicy.Request.REROLL
                && !seeds.canReroll(generated.slotNumber(room.arena()).orElse(1))) {
            tell(player, "room.map-reroll-fixed");
            return;
        }
        boolean next = request == SeedPolicy.Request.NEXT;
        room.setSeedModes();
        room.request(request);
        if (room.isPlaying()) {
            broadcast(room, msg(next ? "room.map-next-queued" : "room.map-reroll-queued", "player", player.getName()));
            return;
        }
        broadcast(room, msg(next ? "room.map-next" : "room.map-reroll", "player", player.getName()));
        if (generated.cellOf(room.arena()).isPresent()) {
            regenerate(room);
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
        if (!rooms.contains(room)) {
            return;
        }
        broadcast(room, msg("room.map-changed", "old", old, "arena", room.arena(), "player", player.getName()));
        if (position(room).isPresent()) {
            broadcast(room, queued(room));
        }
    }

    /**
     * Moves a room in the lobby to another hand-built arena. Its lease moves with it: the old
     * slot is given up and the room asks for one on the new arena, queueing if it must. The
     * team count is lowered to what the new arena has spawns for. A room on generated maps
     * gives up its generated map and plays hand-built arenas from now on.
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
        if (!leaveGenerated(room)) {
            return;
        }
        room.setArena(target.name());
        int teams = Math.min(room.settings().teams(), teamCount(target));
        if (teams != room.settings().teams()) {
            RoomSettings fewer = room.settings().withTeams(teams);
            room.setSettings(fewer.capacity() < room.size() ? fewer.withTeamSize(0) : fewer);
        }
        granted(slots.release(room));
        request(room);
    }

    // ---- Generated maps ----

    /**
     * Makes a room in the lobby play generated maps from the seed at the difficulty, until
     * it is moved to a hand-built arena. The room gets a new generated map name and asks for
     * a slot for it, queueing if it must; once it holds the slot, a slot cell is cleared, the
     * map is generated off the server thread and placed, and the room can start. Each match
     * after that clears the cell and generates the map again from the same seed, which is
     * not checked against other slots here ({@link #mapSetGenerated} checks it).
     *
     * <p>When no map comes from the seed, the room plays its last good seed if it has one,
     * else the hand-built arena a new room would get, and its players are told which
     * ({@link MapSearch#fallback}).
     *
     * @throws IllegalStateException    if the room is playing, is not open, or generated maps
     *                                  are off
     * @throws IllegalArgumentException if the difficulty is out of range
     */
    public void playGenerated(Room room, long seed, int difficulty) {
        SeedChoice choice = new SeedChoice(seed, difficulty);
        if (generated == null) {
            throw new IllegalStateException("generated maps are off");
        }
        if (!rooms.contains(room)) {
            throw new IllegalStateException("the room is not open");
        }
        if (room.isPlaying()) {
            throw new IllegalStateException("the room is playing");
        }
        if (!leaveGenerated(room)) {
            return;
        }
        room.setGenerated(choice);
        room.setArena(generated.newName());
        granted(slots.release(room));
        if (!request(room)) {
            broadcast(room, queued(room));
        }
    }

    /**
     * Makes a room in the lobby play generated maps whose seeds the seed modes pick, from its
     * next map on, under a new generated map name; as {@link #playGenerated} otherwise.
     *
     * @throws IllegalStateException if the room is playing or not open, or there are no seed modes
     */
    public void playSeedModes(Room room) {
        if (generated == null || seeds == null) {
            throw new IllegalStateException("generated maps are off");
        }
        if (!rooms.contains(room)) {
            throw new IllegalStateException("the room is not open");
        }
        if (room.isPlaying()) {
            throw new IllegalStateException("the room is playing");
        }
        SeedPolicy.Request asked = room.requested();
        if (!leaveGenerated(room)) {
            return;
        }
        room.setSeedModes();
        room.request(asked);
        room.setArena(generated.newName());
        int teams = Math.min(Math.max(room.settings().teams(), 1), Arena.MAX_TEAMS);
        if (teams != room.settings().teams()) {
            room.setSettings(room.settings().withTeams(teams));
        }
        granted(slots.release(room));
        if (!request(room)) {
            broadcast(room, queued(room));
        }
    }

    /** Frees the room's generated map and asks for a slot again, to generate it anew. */
    private void regenerate(Room room) {
        freeMap(room.arena());
        if (!rooms.contains(room)) {
            // Closed while its map was freed (see leaveGenerated): close() gave up its slot and map.
            return;
        }
        granted(slots.release(room));
        if (!request(room)) {
            broadcast(room, queued(room));
        }
    }

    /**
     * A room on generated maps frees its map and plays hand-built arenas again.
     *
     * <p>Freeing the map ends its previews, and a previewer the lobby cannot take back is
     * sent home and out of the room at once ({@link #sentHome}), which can close it. The
     * room's slot and map are then given up already, so callers stop on false and change
     * nothing more; {@link #regenerate} checks the same.
     *
     * @return whether the room is still open
     */
    private boolean leaveGenerated(Room room) {
        if (room.playsGenerated()) {
            if (generated != null) {
                freeMap(room.arena());
            }
            room.setGenerated(null);
        }
        return rooms.contains(room);
    }

    /** Frees a generated map's cell, and its next round's map, and forgets their seeds. */
    private void freeMap(String name) {
        mapSeeds.remove(name);
        nextMaps.remove(name);
        generated.free(name);
    }

    /** The sides a generated map has for the room's team count. */
    private static int layoutTeams(RoomSettings settings) {
        return settings.teams() <= 2 ? 2 : 4;
    }

    private void prepare(Room room, String name, SeedChoice choice, boolean fallback) {
        mapSeeds.put(name, choice.seed());
        try {
            generated.prepare(name, new GeneratedMaps.Request(choice.seed(), choice.difficulty(),
                    layoutTeams(room.settings())), outcome -> prepared(room, name, choice, fallback, outcome));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not generate map " + name + " for the room of "
                    + name(room.host()) + ".", e);
        }
    }

    /**
     * A map was placed, or no map came from the seed. A good one is remembered as the room's
     * last good seed. A failed search falls back to the last good seed, generated at the
     * difficulty it was good at, else to a hand-built arena; the players are told which.
     */
    private void prepared(Room room, String name, SeedChoice choice, boolean fallback,
            GeneratedMaps.Prepared outcome) {
        if (!rooms.contains(room) || !room.arena().equals(name)) {
            // Closed or moved meanwhile; its map was freed then.
            return;
        }
        switch (outcome) {
            case GeneratedMaps.Ready ready -> {
                room.setLastGoodSeed(new SeedChoice(ready.seed(), choice.difficulty()));
                broadcast(room, msg("room.map-ready", "seed", ready.seed()));
            }
            case GeneratedMaps.Failed failed -> {
                Set<Long> active = activeSeeds(name, room);
                // Under distinct, a last good seed another slot has taken since is no fallback.
                Optional<SeedChoice> last = fallback ? Optional.empty() : room.lastGoodSeed()
                        .filter(seed -> seeds == null || !seeds.collides(seed.seed(), active));
                MapSearch.FallbackDecision decision = MapSearch.fallback(
                        last.map(seed -> OptionalLong.of(seed.seed())).orElse(OptionalLong.empty()));
                if (decision.fallback() == MapSearch.Fallback.LAST_GOOD_SEED) {
                    broadcast(room, msg(decision.messageKey(), "seed", failed.requestedSeed(),
                            "attempts", failed.attempts(), "last", last.get().seed()));
                    prepare(room, name, last.get(), true);
                    return;
                }
                Optional<Arena> handBuilt = defaultArena(room);
                if (handBuilt.isEmpty() && seeds != null && !room.isPinned() && !fallback) {
                    // No hand-built arena to fall back to: one more try, from a new random seed.
                    int slot = generated.slotNumber(name).orElse(1);
                    SeedChoice retry = seeds.next(slot, active, SeedPolicy.Request.REROLL).choice();
                    room.setSeed(retry);
                    broadcast(room, msg("room.map-fallback-random", "seed", failed.requestedSeed(),
                            "attempts", failed.attempts(), "next", retry.seed()));
                    prepare(room, name, retry, true);
                    return;
                }
                if (handBuilt.isEmpty()) {
                    leaveGenerated(room);
                    close(room, "room.closed-no-arena");
                    return;
                }
                broadcast(room, msg(decision.messageKey(), "seed", failed.requestedSeed(),
                        "attempts", failed.attempts(), "arena", handBuilt.get().name()));
                setArena(room, handBuilt.get().name());
            }
        }
    }

    // ---- Generating the map again between rounds ----

    /**
     * A match on a generated map went to its shop break: with {@code regenerate: round} for
     * its slot, the next map is generated in a spare cell. Leaving the break drops a next map
     * that is not placed yet; the round keeps the current map. Register this class as a
     * listener, or pass each {@link MatchPhaseChangeEvent} here.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPhase(MatchPhaseChangeEvent event) {
        String arena = Arena.normalizeName(event.match().arena());
        if (event.to() == MatchPhase.SHOP) {
            nextRoundMap(arena);
        } else if (event.from() == MatchPhase.SHOP && nextMaps.remove(arena) != null) {
            generated.dropReplacement(arena);
            plugin.getLogger().info("The next map for the match in " + arena + " was not placed in time; the round"
                    + " keeps the current map.");
        }
    }

    /**
     * Whether the next round's map is ready: true unless it is still being generated in the
     * shop break. {@code MatchService#setMapReady}.
     */
    public boolean mapReady(MatchView match) {
        return !nextMaps.containsKey(Arena.normalizeName(match.arena()));
    }

    private void nextRoundMap(String arena) {
        Room room = playing.get(arena);
        if (generated == null || seeds == null || room == null || !rooms.contains(room) || room.isPinned()
                || !Arena.isGeneratedName(arena) || nextMaps.containsKey(arena)) {
            return;
        }
        OptionalInt slot = generated.slotNumber(arena);
        if (slot.isEmpty() || seeds.forSlot(slot.getAsInt()).regenerate() != SeedSettings.Regenerate.ROUND
                || generated.get(arena).isEmpty()) {
            return;
        }
        if (!hasSpareCell()) {
            plugin.getLogger().info("No slot cell is free to generate the next map for the match in " + arena
                    + ", so the next round keeps the current map.");
            return;
        }
        SeedChoice choice = seeds.next(slot.getAsInt(), activeSeeds(arena, room), room.takeRequest()).choice();
        nextMaps.put(arena, choice);
        broadcast(room, msg("room.map-next-round", "seed", choice.seed(), "difficulty", choice.difficulty()));
        try {
            generated.prepareReplacement(arena, new GeneratedMaps.Request(choice.seed(), choice.difficulty(),
                    layoutTeams(room.settings())), outcome -> nextRoundPrepared(room, arena, choice, outcome));
        } catch (RuntimeException e) {
            nextMaps.remove(arena);
            plugin.getLogger().log(Level.SEVERE, "Could not generate the next map for the match in " + arena + ".", e);
        }
    }

    /**
     * The next round's map is placed, or no map came from its seed. A placed one takes the
     * current one's place while the match is still in its shop break. Otherwise it is
     * dropped, and by {@link MapSearch#fallback} the round plays the last good seed: the map
     * already placed.
     */
    private void nextRoundPrepared(Room room, String arena, SeedChoice choice, GeneratedMaps.Prepared outcome) {
        if (nextMaps.get(arena) != choice) {
            return;
        }
        nextMaps.remove(arena);
        boolean inBreak = matches.match(arena).map(match -> match.phase() == MatchPhase.SHOP).orElse(false);
        switch (outcome) {
            case GeneratedMaps.Ready ready when inBreak -> {
                generated.commitReplacement(arena);
                mapSeeds.put(arena, choice.seed());
                room.setSeed(choice);
                room.setLastGoodSeed(new SeedChoice(ready.seed(), choice.difficulty()));
                broadcast(room, msg("room.map-ready", "seed", ready.seed()));
            }
            case GeneratedMaps.Ready ready -> generated.dropReplacement(arena);
            case GeneratedMaps.Failed failed -> {
                generated.dropReplacement(arena);
                long current = room.lastGoodSeed().map(SeedChoice::seed).orElse(mapSeeds.getOrDefault(arena, 0L));
                MapSearch.FallbackDecision decision = MapSearch.fallback(OptionalLong.of(current));
                broadcast(room, msg(decision.messageKey(), "seed", failed.requestedSeed(),
                        "attempts", failed.attempts(), "last", current));
            }
        }
    }

    /** Checks the queue, drops members who are gone and shows each room its status. */
    public void tick() {
        pruneRequests();
        if (previews != null) {
            previews.check();
        }
        for (Room room : List.copyOf(rooms)) {
            if (room.isHostAway()) {
                hostGraceTick(room);
            }
            for (UUID member : room.members()) {
                if (matches.isHeld(member) || room.isHostAway() && member.equals(room.host())) {
                    continue;
                }
                if (!rooms.contains(room)) {
                    break;
                }
                Player online = plugin.getServer().getPlayer(member);
                if (online == null || !online.isOnline()
                        || !lobby.isMember(member) && !matches.isInMatch(member) && !isPreviewing(member)) {
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

    /**
     * One second of an away host's grace: one back in the lobby by any way hosts the room
     * again; when the grace runs out they are out of the room and it is handed on.
     */
    private void hostGraceTick(Room room) {
        UUID host = room.host();
        Player online = plugin.getServer().getPlayer(host);
        if (online != null && online.isOnline() && lobby.isMember(host)) {
            hostBack(room);
            return;
        }
        if (room.countDownGrace()) {
            for (UUID other : room.others(host)) {
                tell(other, "room.host-gone", "player", name(host));
            }
            remove(room, host);
        }
    }

    /**
     * Closes every room, ending any host grace: an away host's next join after a restart
     * restores them home. Called on plugin disable, before the lobby and the matches close.
     */
    public void close() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        if (barTicker != null) {
            barTicker.cancel();
            barTicker = null;
        }
        List.copyOf(joinRequests.values()).forEach(request -> endRequest(request, "room.request-closed"));
        requestCooldowns.clear();
        bars.close();
        if (previews != null) {
            // Home before the lobby closes, and never left in spectator mode.
            previews.close();
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
        endPreview(id);
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

    /** Back to the lobby from a preview, for a player who is no longer in the room. */
    private void endPreview(UUID id) {
        Player player = plugin.getServer().getPlayer(id);
        if (previews != null && player != null && player.isOnline()) {
            previews.end(player);
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
        cancelRequests(room, "room.request-closed");
        requestCooldowns.keySet().removeIf(key -> key.room() == room);
        bars.remove(room);
        if (previews != null) {
            previews.endRoom(room);
        }
        for (UUID member : room.members()) {
            roomOf.remove(member);
            tell(member, reason);
        }
        room.members().forEach(names::remove);
        if (!room.isPlaying()) {
            // A running match keeps its lease, and its generated map, until it ends (matchClosed).
            if (generated != null && room.playsGenerated()) {
                freeMap(room.arena());
            }
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
        Optional<Arena> ready = readyArena(except);
        if (ready.isPresent()) {
            return ready;
        }
        List<Arena> usable = usableArenas();
        for (Arena arena : usable) {
            if (!claimedByOthers(arena.name(), except)) {
                return Optional.of(arena);
            }
        }
        return usable.stream().findFirst();
    }

    /**
     * The hand-built arena a new room plays when one is ready: the featured map of a running
     * event when it is available and no other room holds or waits for it, else the first
     * such arena by name.
     */
    private Optional<Arena> readyArena(Room except) {
        for (Arena arena : usableArenas()) {
            if (arenas.isAvailable(arena.name()) && !claimedByOthers(arena.name(), except)) {
                return Optional.of(arena);
            }
        }
        return Optional.empty();
    }

    private List<Arena> usableArenas() {
        List<Arena> usable = new ArrayList<>();
        for (Arena arena : arenas.all()) {
            if (!arena.teamSpawns().isEmpty()) {
                usable.add(arena);
            }
        }
        usable.sort(Comparator.comparing(arena -> isFeatured(arena.name()) ? 0 : 1));
        return usable;
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

    /** The room the player hosts or co-hosts; tells them why not when it is none. */
    private Room managerRoom(Player player) {
        Room room = roomOf.get(player.getUniqueId());
        if (room == null) {
            tell(player, "room.not-in-room");
            return null;
        }
        if (!room.isManager(player.getUniqueId())) {
            tell(player, "room.not-host-or-cohost");
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
                    : room.canRequest(id) && !room.isInvited(id) ? msg("room.privacy-ask").append(Component.space())
                            .append(button(player, "room.button-request", "accept " + host))
                    : room.admits(id) ? button(player, "room.button-accept", "accept " + host)
                    : msg("room.privacy-invite");
            if (room.isHostAway()) {
                state = msg("room.list-host-away").append(Component.space()).append(state);
            }
            Component crown = crown(room, room.host());
            if (room.hasCreator() && !room.creator().equals(room.host())) {
                crown = msg("room.list-creator", "player", name(room.creator()));
            }
            player.sendMessage(msg(isFeatured(room.arena()) ? "room.list-line-featured" : "room.list-line",
                    "host", host, "crown", crown, "arena", room.arena(), "count", count(room), "state", state));
        }
    }

    private boolean isFeatured(String arena) {
        return featured.get().map(Arena::normalizeName).filter(arena::equals).isPresent();
    }

    private Component status(Room room) {
        if (room.isPlaying()) {
            return msg("room.status-playing", "arena", room.arena());
        }
        Component creator = room.hasCreator() ? msg("room.status-creator", "player", name(room.creator()))
                : Component.empty();
        if (room.isHostAway()) {
            return creator.append(msg("room.status-host-away", "count", count(room), "player", name(room.host()),
                    "seconds", room.hostGraceLeft()));
        }
        OptionalInt position = slots.position(room);
        if (position.isPresent()) {
            return creator.append(msg("room.status-queued", "position", position.getAsInt()));
        }
        return creator.append(room.canStart() ? msg("room.status-ready", "count", count(room))
                : msg("room.status-waiting", "count", count(room)));
    }

    /** The crown after the name of the member who opened the room; nothing after anyone else's. */
    private static Component crown(Room room, UUID member) {
        return member.equals(room.creator()) ? msg("room.crown") : Component.empty();
    }

    /** Why starting and removals wait: the host is away for their grace. */
    private Component hostAway(Room room) {
        return msg("room.host-away-wait", "player", name(room.host()), "seconds", room.hostGraceLeft());
    }

    private void tellHostAway(Player player, Room room) {
        player.sendMessage(hostAway(room));
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

    static String privacyKey(RoomSettings settings) {
        return switch (settings.privacy()) {
            case PUBLIC -> "room.privacy-public";
            case ASK -> "room.privacy-ask";
            case INVITE -> "room.privacy-invite";
        };
    }

    private static Component privacy(RoomSettings settings) {
        return msg(privacyKey(settings));
    }

    private static Component teamSize(RoomSettings settings) {
        return settings.teamSize() == 0 ? msg("room.team-size-any") : Component.text(settings.teamSize());
    }

    /** A chat button, or the command to type for a Bedrock recipient. */
    private Component button(Player recipient, String key, String subcommand) {
        String command = ROOT + subcommand;
        return bedrock.test(recipient.getUniqueId()) ? msg(key + "-bedrock", "command", command)
                : msg(key).clickEvent(ClickEvent.runCommand(command));
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
