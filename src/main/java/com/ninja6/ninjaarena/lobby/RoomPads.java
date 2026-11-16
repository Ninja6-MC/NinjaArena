package com.ninja6.ninjaarena.lobby;

import static com.ninja6.ninjaarena.message.Messages.msg;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.room.Room;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Room pads of the generated lobby ({@link LobbyLayout#pads()}): each open room is given a
 * pad, shown by a label and coloured fog, and a lobby member who stands on its plate for
 * {@link PadSettings#joinSeconds()} joins it, asks to join it or is told it is invite only.
 *
 * <p><b>Assignment.</b> A room gets the lowest-numbered free pad when it opens, rooms taken
 * oldest first, and keeps it until it closes; then the pad is free for the oldest room
 * without one. Rooms beyond the pad count have none and are only in the room list. A
 * lobby generated again with fewer pads takes the pads that are gone from their rooms; the
 * obsidian lobby has no pads, so nothing here does anything there. Assignments are kept in
 * memory only, like the rooms themselves.
 *
 * <p><b>Countdown.</b> Anywhere on the plate counts, from its floor block to a jump above
 * it, so moving, turning, jumping and chatting there keep it going; stepping off cancels
 * it, and stepping back on starts it again from the full time. After it acts the pad does
 * nothing more for that player until they step off. It shows as a title, since the room
 * status line uses the action bar. A pad whose room is in a match does not count down.
 *
 * <p><b>Fog.</b> Each draw, a viewer in the lobby world who moved, looked, chatted or used a
 * command in the last {@link PadSettings#activeSeconds()} gets dust on the ring of each
 * assigned pad within {@link PadSettings#fogDistance()}, nearest first, up to their cap.
 * Particles are sent to that viewer only. Holds nothing between draws.
 *
 * <p>Labels are disposable text displays, tagged and not saved with the chunk, like the
 * room boards ({@link RoomBoards}). Main thread only, except the chat listener.
 */
public final class RoomPads implements Listener, AutoCloseable {

    /** The open rooms and what the pads do with them. */
    public interface PadRooms {
        /** Open rooms, oldest first. */
        List<Room> rooms();

        /** A player's name. */
        String name(UUID player);

        /** Whether the player may use a pad: a lobby member in no room, match or spectating. */
        boolean eligible(UUID player);

        /** The countdown ran out: join, ask or tell them it is invite only. */
        void trigger(Player player, Room room);
    }

    static final NamespacedKey KEY = new NamespacedKey("ninjaarena", "room_pad");
    /** How many blocks above the plate's floor block a player's feet count as on it: a jump. */
    static final int REACH = 3;
    static final float SIZE = 1.0f;
    private static final Title.Times TIMES = Title.Times.times(Duration.ZERO, Duration.ofMillis(1500),
            Duration.ofMillis(250));

    /** {@link Standing#started} while the pad cannot act for the player. */
    private static final long WAITING = Long.MIN_VALUE;

    private record Label(TextDisplay text, int chunkX, int chunkZ) { }

    /** A player on a plate. {@code shown} is the second on their title, 0 for none. */
    private static final class Standing {
        final int pad;
        Room room;
        long started;
        long shown;
        boolean done;

        Standing(int pad, Room room, long started) {
            this.pad = pad;
            this.room = room;
            this.started = started;
        }
    }

    private final World world;
    private final Supplier<LobbyLayout> layout;
    private final BooleanSupplier building;
    private final PadRooms rooms;
    private final BiConsumer<Location, Runnable> load;
    private final BoardTickets tickets;
    private final Predicate<UUID> bedrock;
    private final PadSettings settings;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final Consumer<String> log;

    private final Map<Room, Integer> padOf = new IdentityHashMap<>();
    private final Map<Integer, Room> roomAt = new HashMap<>();
    private final Map<Integer, Label> labels = new HashMap<>();
    private final Set<Integer> loading = new HashSet<>();
    private final Map<UUID, Standing> standing = new HashMap<>();
    private final Map<UUID, Long> active = new ConcurrentHashMap<>();
    private LobbyLayout current;
    private boolean wasBuilding;
    private int generation;
    private boolean closed;

    /**
     * @param building whether the lobby is being built; pads do nothing meanwhile
     * @param load     runs the task once the chunk at the location is loaded
     * @param bedrock  whether a player plays on Bedrock, for their particle cap
     * @param clock    milliseconds
     */
    public RoomPads(World world, Supplier<LobbyLayout> layout, BooleanSupplier building, PadRooms rooms,
            BiConsumer<Location, Runnable> load, BoardTickets tickets, Predicate<UUID> bedrock,
            PadSettings settings, LongSupplier clock, RandomGenerator random, Consumer<String> log) {
        this.world = Objects.requireNonNull(world, "world");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.building = Objects.requireNonNull(building, "building");
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.load = Objects.requireNonNull(load, "load");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Who may use a pad: a lobby member in no room. Players in a match, its shop phase
     * included, and spectators are not lobby members, but are refused here as well.
     */
    public static boolean eligible(UUID player, Predicate<UUID> lobbyMember, Predicate<UUID> inRoom,
            Predicate<UUID> inMatch, Predicate<UUID> spectating) {
        return lobbyMember.test(player) && !inRoom.test(player) && !inMatch.test(player) && !spectating.test(player);
    }

    // ---- Queries ----

    /** The pad a room has, if any. */
    public OptionalInt padOf(Room room) {
        Integer pad = padOf.get(room);
        return pad == null ? OptionalInt.empty() : OptionalInt.of(pad);
    }

    /** The room on a pad, or null. */
    public Room roomAt(int pad) {
        return roomAt.get(pad);
    }

    /** The label text of a room's pad. */
    public Component label(Room room) {
        return label(rooms.name(room.host()), room.size(), room.settings().players(), PadState.of(room));
    }

    /** A pad's label: the host, the player count and the state, in words as well as colour. */
    public static Component label(String host, int players, int needed, PadState state) {
        return msg("lobby.pads.label", "host", shortName(host), "players", players, "needed", needed,
                "state", msg("lobby.pads.state." + state.key()));
    }

    private static String shortName(String value) {
        return value.length() <= 20 ? value : value.substring(0, 19) + "…";
    }

    // ---- Ticks ----

    /**
     * Follows the lobby and the rooms: assigns and frees pads, places and removes labels, and
     * runs the countdowns. Called every few ticks.
     */
    public void tick() {
        if (closed) {
            return;
        }
        refreshLayout();
        sync();
        countdowns(clock.getAsLong());
    }

    private List<LobbyPad> pads() {
        return settings.enabled() && current != null && !wasBuilding ? current.pads() : List.of();
    }

    /** A lobby built again, or being built: labels go, and pads that are gone are freed. */
    private void refreshLayout() {
        LobbyLayout next = layout.get();
        boolean busy = building.getAsBoolean();
        if (next == current && busy == wasBuilding) {
            return;
        }
        generation++;
        removeLabels();
        stepEveryoneOff();
        current = next;
        wasBuilding = busy;
        int count = settings.enabled() ? next.pads().size() : 0;
        for (Integer pad : List.copyOf(roomAt.keySet())) {
            if (pad >= count) {
                padOf.remove(roomAt.remove(pad));
            }
        }
    }

    private void sync() {
        List<Room> open = rooms.rooms();
        Set<Room> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(open);
        for (Room room : List.copyOf(padOf.keySet())) {
            if (!present.contains(room)) {
                free(padOf.get(room));
            }
        }
        int count = settings.enabled() && current != null ? current.pads().size() : 0;
        for (Room room : open) {
            if (padOf.containsKey(room)) {
                continue;
            }
            for (int pad = 0; pad < count; pad++) {
                if (!roomAt.containsKey(pad)) {
                    padOf.put(room, pad);
                    roomAt.put(pad, room);
                    break;
                }
            }
        }
        for (LobbyPad pad : pads()) {
            if (roomAt.containsKey(pad.index()) && !labels.containsKey(pad.index())) {
                placeLabel(pad);
            }
        }
    }

    private void free(int pad) {
        Room room = roomAt.remove(pad);
        padOf.remove(room);
        removeLabel(pad);
    }

    private void countdowns(long now) {
        Set<UUID> seen = new HashSet<>();
        List<LobbyPad> pads = pads();
        for (Player player : world.getPlayers()) {
            UUID id = player.getUniqueId();
            seen.add(id);
            LobbyPad pad = padUnder(pads, player.getLocation());
            if (pad == null) {
                stepOff(player);
                continue;
            }
            Room room = roomAt.get(pad.index());
            Standing on = standing.get(id);
            if (on == null || on.pad != pad.index()) {
                stepOff(player);
                on = new Standing(pad.index(), room, now);
                standing.put(id, on);
            }
            if (on.room != room) {
                // The pad was given to another room, or freed, while they stood there.
                hideTitle(player, on);
                on.room = room;
                on.started = now;
                on.done = false;
            }
            boolean counting = room != null && !on.done && !room.isPlaying() && rooms.eligible(id);
            if (!counting) {
                // The full time counts from when the pad can act for them.
                hideTitle(player, on);
                on.started = WAITING;
                continue;
            }
            if (on.started == WAITING) {
                on.started = now;
            }
            long left = settings.joinSeconds() * 1000L - (now - on.started);
            if (left <= 0) {
                on.done = true;
                hideTitle(player, on);
                try {
                    rooms.trigger(player, room);
                } catch (RuntimeException e) {
                    log.accept("A room pad could not act for " + player.getName() + ": " + e);
                }
                continue;
            }
            long seconds = (left + 999) / 1000;
            if (seconds != on.shown) {
                on.shown = seconds;
                player.showTitle(Title.title(msg("lobby.pads.countdown", "seconds", seconds),
                        msg(action(room, id), "host", rooms.name(room.host())), TIMES));
            }
        }
        standing.keySet().retainAll(seen);
    }

    /** The subtitle key of what the pad will do for the player: join, ask or say it is invite only. */
    private static String action(Room room, UUID player) {
        if (room.isInvited(player)) {
            return "lobby.pads.countdown-join";
        }
        return switch (room.settings().privacy()) {
            case PUBLIC -> "lobby.pads.countdown-join";
            case ASK -> "lobby.pads.countdown-ask";
            case INVITE -> "lobby.pads.countdown-invite";
        };
    }

    private void stepOff(Player player) {
        Standing on = standing.remove(player.getUniqueId());
        if (on != null) {
            hideTitle(player, on);
        }
    }

    /** Cancels every countdown, clearing the titles of those still in the lobby world. */
    private void stepEveryoneOff() {
        for (Player player : world.getPlayers()) {
            stepOff(player);
        }
        standing.clear();
    }

    private static void hideTitle(Player player, Standing on) {
        if (on.shown != 0) {
            on.shown = 0;
            player.clearTitle();
        }
    }

    /** The pad whose plate the location is on, from its floor block to {@link #REACH} above. */
    static LobbyPad padUnder(List<LobbyPad> pads, Location at) {
        if (at == null) {
            return null;
        }
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        for (LobbyPad pad : pads) {
            ArenaBounds plate = pad.plate();
            if (x >= plate.minX() && x <= plate.maxX() && z >= plate.minZ() && z <= plate.maxZ()
                    && y >= plate.minY() && y <= plate.maxY() + REACH) {
                return pad;
            }
        }
        return null;
    }

    // ---- Fog ----

    /** One draw for every recently active viewer in the lobby world. Called every {@link PadSettings#fogIntervalTicks()}. */
    public void drawFog() {
        if (closed || !settings.fog() || roomAt.isEmpty() || pads().isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        for (Player viewer : world.getPlayers()) {
            if (isActive(viewer.getUniqueId(), now)) {
                drawFor(viewer, cap(viewer.getUniqueId()));
            }
        }
    }

    /** Whether the player moved, looked, chatted or used a command in the last {@link PadSettings#activeSeconds()}. */
    boolean isActive(UUID player, long now) {
        Long last = active.get(player);
        return last != null && now - last <= settings.activeSeconds() * 1000L;
    }

    /** The most particles the viewer gets in one draw. */
    int cap(UUID viewer) {
        return bedrock.test(viewer) ? settings.bedrockMaxParticles() : settings.maxParticles();
    }

    /**
     * Sends one draw of fog to the viewer: the rings of assigned pads within reach, nearest
     * first, at most {@code cap} particles.
     *
     * @return how many particles were sent
     */
    int drawFor(Player viewer, int cap) {
        Location at = viewer.getLocation();
        if (cap <= 0 || at == null) {
            return 0;
        }
        double reach = (double) settings.fogDistance() * settings.fogDistance();
        Map<LobbyPad, Double> near = new LinkedHashMap<>();
        for (LobbyPad pad : pads()) {
            Room room = roomAt.get(pad.index());
            if (room == null) {
                continue;
            }
            ArenaBounds plate = pad.plate();
            double dx = (plate.minX() + plate.maxX() + 1) / 2.0 - at.getX();
            double dz = (plate.minZ() + plate.maxZ() + 1) / 2.0 - at.getZ();
            double distance = dx * dx + dz * dz;
            if (distance <= reach) {
                near.put(pad, distance);
            }
        }
        List<LobbyPad> order = new ArrayList<>(near.keySet());
        order.sort(Comparator.comparingDouble(near::get));
        int sent = 0;
        for (LobbyPad pad : order) {
            Particle.DustOptions dust = new Particle.DustOptions(
                    Color.fromRGB(settings.colour(PadState.of(roomAt.get(pad.index())))), SIZE);
            for (int[] block : pad.ring()) {
                for (int i = 0; i < settings.perBlock(); i++) {
                    if (sent >= cap) {
                        return sent;
                    }
                    viewer.spawnParticle(Particle.DUST, block[0] + random.nextDouble(),
                            block[1] + 1.05 + random.nextDouble() * 0.25, block[2] + random.nextDouble(),
                            1, 0, 0, 0, 0, dust);
                    sent++;
                }
            }
        }
        return sent;
    }

    void markActive(UUID player) {
        active.put(player, clock.getAsLong());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition() || event.hasChangedOrientation()) {
            markActive(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        markActive(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        markActive(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        active.remove(id);
        standing.remove(id);
    }

    // ---- Labels ----

    private Location location(ArenaPoint point) {
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    private void placeLabel(LobbyPad pad) {
        if (!loading.add(pad.index())) {
            return;
        }
        int epoch = generation;
        Room room = roomAt.get(pad.index());
        load.accept(location(pad.label()), () -> {
            loading.remove(pad.index());
            if (!closed && generation == epoch && roomAt.get(pad.index()) == room && !labels.containsKey(pad.index())) {
                spawnLabel(pad, room);
            }
        });
    }

    private void spawnLabel(LobbyPad pad, Room room) {
        Location at = location(pad.label());
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;
        Component content = label(room);
        tickets.acquire(chunkX, chunkZ);
        TextDisplay text = world.spawn(at, TextDisplay.class, display -> {
            display.getPersistentDataContainer().set(KEY, PersistentDataType.INTEGER, pad.index());
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.text(content);
            display.setBillboard(Display.Billboard.FIXED);
            display.setLineWidth(200);
        });
        labels.put(pad.index(), new Label(text, chunkX, chunkZ));
    }

    /**
     * Brings label text up to date, replaces a lost label and sweeps tagged duplicates.
     * Called every second.
     */
    public void update() {
        if (closed) {
            return;
        }
        for (var entry : Map.copyOf(labels).entrySet()) {
            int pad = entry.getKey();
            Room room = roomAt.get(pad);
            if (room == null) {
                removeLabel(pad);
            } else if (!entry.getValue().text().isValid()) {
                removeLabel(pad);
            } else {
                entry.getValue().text().text(label(room));
            }
        }
        for (Entity entity : world.getEntities()) {
            removeDuplicate(entity);
        }
        sync();
    }

    private Integer padTag(Entity entity) {
        return entity.getWorld() == world
                ? entity.getPersistentDataContainer().get(KEY, PersistentDataType.INTEGER) : null;
    }

    private void removeDuplicate(Entity entity) {
        Integer pad = padTag(entity);
        if (pad == null) {
            return;
        }
        Label label = labels.get(pad);
        if (label == null || label.text() != entity) {
            entity.remove();
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (event.getWorld() == world) {
            for (Entity entity : event.getChunk().getEntities()) {
                removeDuplicate(entity);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (padTag(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    private void removeLabel(int pad) {
        Label label = labels.remove(pad);
        loading.remove(pad);
        if (label != null) {
            label.text().remove();
            tickets.release(label.chunkX(), label.chunkZ());
        }
    }

    private void removeLabels() {
        for (Integer pad : List.copyOf(labels.keySet())) {
            removeLabel(pad);
        }
        loading.clear();
        for (Entity entity : world.getEntities()) {
            if (padTag(entity) != null) {
                entity.remove();
            }
        }
    }

    /** Plugin disable: labels and titles go; the rooms close on their own. */
    @Override
    public void close() {
        stepEveryoneOff();
        closed = true;
        generation++;
        removeLabels();
        padOf.clear();
        roomAt.clear();
        active.clear();
    }
}
