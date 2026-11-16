package com.ninja6.ninjaarena.soak;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.ninja6.ninjaarena.NinjaArenaPlugin;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.director.DirectorService;
import com.ninja6.ninjaarena.director.StabilityGate;
import com.ninja6.ninjaarena.director.StabilitySettings;
import com.ninja6.ninjaarena.director.TickTimeGuard;
import com.ninja6.ninjaarena.hazard.HazardService;
import com.ninja6.ninjaarena.map.GeneratedMaps;
import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.room.Room;
import com.ninja6.ninjaarena.room.RoomService;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.world.ArenaWorld;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Generated-map workload. Pairs of real clients open rooms with the normal room commands, so
 * every map is leased, generated and placed by the production room and map services. The
 * rooms play back-to-back matches with {@code regenerate: round}, then leave; the arena world
 * must retire while idle, reopen for a second set of rooms, and retire again.
 */
final class GeneratedSoak implements Listener {
    private enum Stage { WAITING, PLAY, DRAIN, RELEASE, REOPEN, FINISH, DONE }

    private static final int CLIENT_ARRIVAL_SECONDS = 180;
    private static final int FIRST_MATCH_GRACE_SECONDS = 300;
    private static final int DRAIN_LIMIT_SECONDS = 1200;
    private static final int RELEASE_LIMIT_SECONDS = 240;
    private static final int REOPEN_LIMIT_SECONDS = 300;
    private static final int GC_ATTEMPTS = 4;
    private static final int SETTLE_SECONDS = 45;

    private final SoakProbe probe;
    private final RoomService rooms;
    private final MatchService matches;
    private final DirectorService directors;
    private final SnapshotService snapshots;
    private final GeneratedMaps generated;
    private final HazardService hazards;
    private final TickTimeGuard guard;
    private final ArenaWorld arenaWorld;
    private final String worldName;
    private final Path output;
    private final int count;
    private final int duration;
    private final int stagger;
    private final Runnable releaseLease;
    private final List<Slot> slots = new ArrayList<>();
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final List<Map<String, Object>> releases = new ArrayList<>();
    private final List<Map<String, Object>> opens = new ArrayList<>();

    private PrintWriter ticks;
    private PrintWriter samples;
    private PrintWriter matchRows;
    private PrintWriter generation;
    private PrintWriter shops;
    private PrintWriter phases;
    private Stage stage = Stage.WAITING;
    private int second;
    private int stageStart;
    private int playStart = -1;
    private int building;
    private int maxConcurrent;
    private int fourAt = -1;
    private int lavaFlows;
    private int collapses;
    private int releaseCycle;
    private int gcAttempts;
    private long releaseRequested;
    private long releaseObserved = -1;
    private long reopenStart;
    private WeakReference<World> retired = new WeakReference<>(null);

    /** One room: its host, its guest and what the probe has seen of its map. */
    private static final class Slot {
        final int index;
        final String host;
        final String guest;
        long buildStart;
        int buildSecond;
        String buildKind;
        long blocks;
        int builds;
        int started;
        int ended;
        boolean inShop;
        boolean shopClosing;
        int shopRound;
        long shopStart;
        long shopEnd;
        Optional<GridCell> cellBefore = Optional.empty();
        long replacementStart;
        long replacementEnd;
        long replacementBlocks;

        Slot(int index) {
            this.index = index;
            this.host = "Soak" + (index * 2);
            this.guest = "Soak" + (index * 2 + 1);
        }
    }

    GeneratedSoak(SoakProbe probe, NinjaArenaPlugin plugin, Path output, int count, int duration, int stagger,
            Runnable releaseLease) throws ReflectiveOperationException {
        this.probe = probe;
        this.rooms = plugin.rooms();
        this.matches = plugin.matches();
        this.directors = plugin.directors();
        this.snapshots = plugin.snapshots();
        this.arenaWorld = plugin.arenaWorld();
        this.worldName = arenaWorld.world().getName();
        // Instrumentation only: these services have no public accessor on the plugin.
        this.generated = (GeneratedMaps) field(plugin, "generatedMaps");
        this.hazards = (HazardService) field(plugin, "hazards");
        this.guard = (TickTimeGuard) field(plugin, "tickTime");
        this.output = output;
        this.count = count;
        this.duration = duration;
        this.stagger = stagger;
        this.releaseLease = releaseLease;
        for (int i = 0; i < count; i++) {
            slots.add(new Slot(i));
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    void start() throws IOException {
        if (!probe.arenaRegistry().all().isEmpty()) {
            throw new IllegalStateException("The generated workload needs a server with no hand-built arenas");
        }
        ticks = writer("ticks.csv", "tick,milliseconds,stage,building");
        samples = writer("generated-seconds.csv", "second,stage,matches,building,guard,average_mspt,tps,"
                + "arena_chunks,arena_holders,lava_sources,lava_flows,collapses,enemies,minimum_health,heap_used_bytes");
        matchRows = writer("generated-matches.csv", "second,room,arena,phase,round,intensity,enemies,lava_sources,"
                + "pending_late");
        generation = writer("generation.csv", "second,stage,room,kind,milliseconds,blocks,seed,difficulty");
        shops = writer("shops.csv", "second,room,round,shop_milliseconds,replacement_started,"
                + "replacement_milliseconds,committed,blocks");
        phases = writer("phases.csv", "second,room,arena,from,to,round");
        StabilitySettings settings = guard.settings();
        result.put("rooms", count);
        result.put("duration_seconds", duration);
        result.put("stagger_seconds", stagger);
        result.put("guard", Map.of("pause_spawning_above_mspt", settings.pauseAboveMspt(),
                "drop_tier_above_mspt", settings.dropTierAboveMspt(),
                "resume_below_mspt", settings.resumeBelowMspt(),
                "match_mob_cap", settings.matchMobCap(), "global_mob_cap", settings.globalMobCap()));
        result.put("max_active", rooms.maxActive());
        result.put("map_size", generated.mapSize().toString());
        Bukkit.getPluginManager().registerEvents(this, probe);
        Bukkit.getScheduler().runTaskTimer(probe, this::second, 20, 20);
    }

    private PrintWriter writer(String name, String header) throws IOException {
        PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output.resolve(name)));
        writer.println(header);
        return writer;
    }

    // ---- Per tick ----

    @EventHandler
    public void onTick(ServerTickEndEvent event) {
        if (stage == Stage.WAITING || stage == Stage.DONE) {
            return;
        }
        try {
            track();
            ticks.printf(Locale.ROOT, "%d,%.6f,%s,%d%n", event.getTickNumber(), event.getTickDuration(),
                    stage.name().toLowerCase(Locale.ROOT), building);
        } catch (Exception error) {
            fail(error);
        }
    }

    /** Follows each room's map: its build, and a replacement built in a shop break. */
    private void track() {
        long now = System.nanoTime();
        int active = 0;
        for (Slot slot : slots) {
            Optional<Room> room = room(slot);
            if (room.isEmpty()) {
                slot.buildStart = 0;
                continue;
            }
            String name = room.get().arena();
            if (generated.isHeld(name)) {
                // A match holds the map: a build seen during a shop-break swap is not this one's.
                slot.buildStart = 0;
            }
            boolean placing = generated.cellOf(name).isPresent() && !generated.isAvailable(name)
                    && !generated.isHeld(name);
            if (placing) {
                active++;
                if (slot.buildStart == 0) {
                    slot.buildStart = now;
                    slot.buildSecond = second;
                    slot.buildKind = stage == Stage.REOPEN ? "reopen" : slot.started == 0 ? "initial" : "match";
                }
                generated.progress(name).ifPresent(progress -> slot.blocks = progress.total());
            } else if (slot.buildStart != 0 && generated.isAvailable(name)) {
                var seed = generated.seed(name);
                generation.printf(Locale.ROOT, "%d,%s,%d,%s,%.3f,%d,%s,%s%n", second,
                        stage.name().toLowerCase(Locale.ROOT), slot.index, slot.buildKind,
                        (now - slot.buildStart) / 1e6, slot.blocks,
                        seed.map(s -> Long.toString(s.seed())).orElse(""),
                        seed.map(s -> Integer.toString(s.difficulty())).orElse(""));
                generation.flush();
                slot.buildStart = 0;
                slot.builds++;
            }
            if (generated.hasReplacement(name)) {
                active++;
                if (slot.replacementStart == 0) {
                    slot.replacementStart = now;
                }
                generated.replacementProgress(name).ifPresent(progress -> slot.replacementBlocks = progress.total());
            } else if (slot.replacementStart != 0 && slot.replacementEnd == 0) {
                slot.replacementEnd = now;
            }
            if (slot.shopClosing) {
                boolean committed = !generated.cellOf(name).equals(slot.cellBefore);
                shops.printf(Locale.ROOT, "%d,%d,%d,%.3f,%s,%.3f,%s,%d%n", second, slot.index, slot.shopRound,
                        (slot.shopEnd - slot.shopStart) / 1e6, slot.replacementStart != 0,
                        slot.replacementEnd != 0 ? (slot.replacementEnd - slot.replacementStart) / 1e6 : -1.0,
                        committed, slot.replacementBlocks);
                shops.flush();
                slot.shopClosing = false;
                slot.replacementStart = 0;
                slot.replacementEnd = 0;
                slot.replacementBlocks = 0;
            }
        }
        building = active;
    }

    // ---- Per second ----

    private void second() {
        if (stage == Stage.DONE) {
            return;
        }
        try {
            second++;
            switch (stage) {
                case WAITING -> waiting();
                case PLAY, DRAIN -> play();
                case RELEASE -> release();
                case REOPEN -> reopen();
                case FINISH -> finish();
                default -> { }
            }
        } catch (Exception error) {
            fail(error);
        }
    }

    private void waiting() throws Exception {
        if (second > CLIENT_ARRIVAL_SECONDS) {
            throw new IllegalStateException(count * 2 + " clients did not arrive within " + CLIENT_ARRIVAL_SECONDS
                    + " seconds");
        }
        // Server startup trips the tick-time guard. Read it here, before any workload, so the
        // measured stages start from a settled guard rather than the startup's slow ticks.
        if (guard.read() != StabilityGate.Guard.NORMAL) {
            return;
        }
        if (Files.exists(output.resolve("start")) && clients().size() == count * 2) {
            enter(Stage.PLAY);
            playStart = second;
            openRooms("play");
            releaseLease.run();
            probe.mark("started");
        }
    }

    private void play() throws Exception {
        sample();
        if (clients().size() != count * 2) {
            throw new IllegalStateException("Clients disappeared during play");
        }
        for (Slot slot : slots) {
            Room room = room(slot).orElseThrow(() -> new IllegalStateException("Room closed: " + slot.host));
            if (stage == Stage.PLAY && !room.isPlaying() && generated.isAvailable(room.arena())
                    && rooms.hasLease(room) && room.canStart()
                    && (slot.started > 0 || second - playStart >= slot.index * stagger)) {
                player(slot.host).performCommand("narena room start");
                if (room.isPlaying()) {
                    slot.started++;
                    phases.printf(Locale.ROOT, "%d,%d,%s,,START,%d%n", second, slot.index, room.arena(), slot.started);
                    phases.flush();
                }
            }
            if (slot.started == 0 && second - playStart > slot.index * stagger + FIRST_MATCH_GRACE_SECONDS) {
                throw new IllegalStateException("Room " + slot.index + " never started a match");
            }
        }
        int running = matches.matches().size();
        maxConcurrent = Math.max(maxConcurrent, running);
        if (running == count && fourAt < 0) {
            fourAt = second - playStart;
            probe.mark("all-matches-running");
        }
        if (stage == Stage.PLAY && second - playStart >= duration) {
            enter(Stage.DRAIN);
            probe.mark("draining");
        }
        if (stage == Stage.DRAIN) {
            boolean playing = slots.stream().anyMatch(slot -> room(slot).map(Room::isPlaying).orElse(false));
            if (!playing && matches.matches().isEmpty()) {
                result.put("play_seconds", second - playStart);
                leaveRooms();
                beginRelease();
            } else if (second - stageStart > DRAIN_LIMIT_SECONDS) {
                throw new IllegalStateException("Matches did not end within " + DRAIN_LIMIT_SECONDS + " seconds");
            }
        }
    }

    private void release() throws Exception {
        sample();
        if (Bukkit.getWorld(worldName) != null || arenaWorld.currentWorld().isPresent()) {
            if (second - stageStart > RELEASE_LIMIT_SECONDS) {
                throw new IllegalStateException("Idle arena world was not released within " + RELEASE_LIMIT_SECONDS
                        + " seconds (cycle " + releaseCycle + ")");
            }
            return;
        }
        if (releaseObserved < 0) {
            releaseObserved = (System.nanoTime() - releaseRequested) / 1_000_000;
        }
        // As in the hand-built soak, collection is checked from 45 seconds after the close,
        // so Paper's own deferred work on the unloaded level has finished. Never resolve the
        // weak referent in the stack that requests collection.
        if (second - stageStart < SETTLE_SECONDS) {
            return;
        }
        if (gcAttempts < GC_ATTEMPTS) {
            gcAttempts++;
            System.gc();
            return;
        }
        // A retained world is recorded, not thrown, so the reopen and the second release are
        // still measured; acceptance fails on it. The count names one known retention path:
        // a player's last damage still referring to an enemy of the retired world.
        boolean collected = retired.get() == null;
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("cycle", releaseCycle);
        cycle.put("release_observed_ms", releaseObserved);
        cycle.put("world_registered", false);
        cycle.put("current_world", false);
        cycle.put("retired_world_collected", collected);
        cycle.put("players_last_damaged_by_retired_world_entity", collected ? 0 : lastDamagedFromArena());
        cycle.put("gc_attempts", gcAttempts);
        cycle.put("heap_used_bytes", SoakProbe.heapUsed());
        cycle.put("slot_cells_in_use", generated.inUse().size());
        releases.add(cycle);
        probe.mark("released-" + releaseCycle);
        if (releaseCycle == 1) {
            enter(Stage.REOPEN);
            reopenStart = System.nanoTime();
            openRooms("reopen");
        } else {
            for (Slot slot : slots) {
                player(slot.host).performCommand("narena leave");
                player(slot.guest).performCommand("narena leave");
            }
            enter(Stage.FINISH);
        }
    }

    private void reopen() throws Exception {
        sample();
        boolean ready = true;
        for (Slot slot : slots) {
            Room room = room(slot).orElseThrow(() -> new IllegalStateException("Reopened room closed: " + slot.host));
            ready &= generated.isAvailable(room.arena());
        }
        if (ready) {
            result.put("reopen_all_maps_ready_ms", (System.nanoTime() - reopenStart) / 1_000_000);
            probe.mark("reopened");
            leaveRooms();
            beginRelease();
        } else if (second - stageStart > REOPEN_LIMIT_SECONDS) {
            throw new IllegalStateException("Maps were not placed within " + REOPEN_LIMIT_SECONDS
                    + " seconds of reopening");
        }
    }

    private void finish() throws Exception {
        if (second - stageStart < 5) {
            return;
        }
        if (!matches.matches().isEmpty() || !generated.inUse().isEmpty()) {
            throw new IllegalStateException("A match or slot cell is still in use at the end");
        }
        if (!snapshots.listPending().isEmpty()) {
            throw new IllegalStateException("Snapshots remain pending");
        }
        if (Bukkit.getWorld(worldName) != null) {
            throw new IllegalStateException("Arena world was reopened after the final release");
        }
        result.put("max_concurrent_matches", maxConcurrent);
        result.put("all_matches_running_after_seconds", fourAt);
        result.put("matches_started", slots.stream().mapToInt(slot -> slot.started).sum());
        result.put("matches_ended", slots.stream().mapToInt(slot -> slot.ended).sum());
        result.put("maps_built", slots.stream().mapToInt(slot -> slot.builds).sum());
        result.put("lava_flow_events", lavaFlows);
        result.put("collapse_falling_blocks", collapses);
        result.put("releases", releases);
        result.put("room_opens", opens);
        result.put("pending_snapshots", 0);
        Files.writeString(output.resolve("generated-result.json"), Json.write(result));
        stage = Stage.DONE;
        closeWriters();
        probe.mark("complete");
        Files.writeString(output.resolve("shutdown-requested"), Long.toString(System.currentTimeMillis()));
        Bukkit.shutdown();
    }

    // ---- Steps ----

    private void enter(Stage next) {
        stage = next;
        stageStart = second;
    }

    /** Each host opens a room with the room command and its guest joins it the same way. */
    private void openRooms(String label) {
        List<Double> creates = new ArrayList<>();
        for (Slot slot : slots) {
            Player host = player(slot.host);
            long before = System.nanoTime();
            host.performCommand("narena room create");
            creates.add((System.nanoTime() - before) / 1e6);
            player(slot.guest).performCommand("narena room accept " + slot.host);
            Room room = rooms.roomOf(host.getUniqueId())
                    .orElseThrow(() -> new IllegalStateException("No room for " + slot.host));
            if (!room.contains(player(slot.guest).getUniqueId()) || !room.playsGenerated()) {
                throw new IllegalStateException("Room of " + slot.host + " is not a two-player generated room");
            }
        }
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("stage", label);
        open.put("second", second);
        open.put("create_command_ms", creates);
        opens.add(open);
    }

    private void leaveRooms() {
        for (Slot slot : slots) {
            player(slot.guest).performCommand("narena room leave");
            player(slot.host).performCommand("narena room leave");
        }
        if (!rooms.rooms().isEmpty()) {
            throw new IllegalStateException("Rooms remain open after every member left");
        }
    }

    private void beginRelease() {
        releaseCycle++;
        enter(Stage.RELEASE);
        releaseRequested = System.nanoTime();
        releaseObserved = -1;
        gcAttempts = 0;
        retired = new WeakReference<>(arenaWorld.currentWorld().orElse(null));
        try {
            probe.mark("rooms-left-" + releaseCycle);
        } catch (Exception error) {
            fail(error);
        }
    }

    private void sample() throws ReflectiveOperationException {
        Optional<World> world = arenaWorld.currentWorld();
        int enemies = 0;
        double health = Double.NaN;
        for (MatchView match : matches.matches()) {
            int alive = directors.director(match).map(StabilityGate::alive).orElse(0);
            double intensity = directors.director(match).map(director -> director.intensity()).orElse(0.0);
            enemies += alive;
            Optional<GridCell> cell = generated.cellOf(match.arena());
            int slot = slotOf(match.arena());
            matchRows.printf(Locale.ROOT, "%d,%d,%s,%s,%d,%.6f,%d,%d,%d%n", second, slot, match.arena(), match.phase(),
                    match.round(), intensity, alive, cell.map(hazards::openSources).orElse(0),
                    cell.map(hazards::pendingLate).orElse(0));
            for (UUID id : match.players()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.getWorld().getName().equals(worldName)) {
                    health = Double.isNaN(health) ? player.getHealth() : Math.min(health, player.getHealth());
                }
            }
        }
        matchRows.flush();
        samples.printf(Locale.ROOT, "%d,%s,%d,%d,%s,%.6f,%.3f,%d,%d,%d,%d,%d,%d,%s,%d%n", second,
                stage.name().toLowerCase(Locale.ROOT), matches.matches().size(), building, guard.guard(),
                Bukkit.getAverageTickTime(), Bukkit.getTPS()[0],
                world.map(w -> w.getLoadedChunks().length).orElse(-1),
                world.isPresent() ? SoakProbe.holders(world.get()) : -1,
                hazards.openSources(), lavaFlows, collapses, enemies,
                Double.isNaN(health) ? "" : String.format(Locale.ROOT, "%.3f", health), SoakProbe.heapUsed());
        samples.flush();
    }

    private int lastDamagedFromArena() {
        int found = 0;
        for (Player player : clients()) {
            if (player.getLastDamageCause() instanceof EntityDamageByEntityEvent damage
                    && damage.getDamager().getWorld().getName().equals(worldName)) {
                found++;
            }
        }
        return found;
    }

    private int slotOf(String arena) {
        for (Slot slot : slots) {
            if (room(slot).map(room -> room.arena().equals(arena)).orElse(false)) {
                return slot.index;
            }
        }
        return -1;
    }

    // ---- Events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPhase(MatchPhaseChangeEvent event) {
        MatchView match = event.match();
        int index = slotOf(match.arena());
        phases.printf(Locale.ROOT, "%d,%d,%s,%s,%s,%d%n", second, index, match.arena(), event.from(), event.to(),
                match.round());
        phases.flush();
        if (event.to() == MatchPhase.PREP) {
            // Stationary clients stay alive so the matches keep their load to the end.
            for (UUID id : match.players()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40000, 4, false, false));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 40000, 0, false, false));
                }
            }
        }
        if (index < 0) {
            return;
        }
        Slot slot = slots.get(index);
        if (event.to() == MatchPhase.SHOP) {
            slot.inShop = true;
            slot.shopRound = match.round();
            slot.shopStart = System.nanoTime();
            slot.cellBefore = generated.cellOf(match.arena());
            slot.replacementStart = 0;
            slot.replacementEnd = 0;
            slot.replacementBlocks = 0;
        } else if (event.from() == MatchPhase.SHOP && slot.inShop) {
            slot.inShop = false;
            slot.shopEnd = System.nanoTime();
            slot.shopClosing = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnd(MatchEndEvent event) {
        int index = slotOf(event.match().arena());
        if (index >= 0) {
            slots.get(index).ended++;
        }
        phases.printf(Locale.ROOT, "%d,%d,%s,,END,%d%n", second, index, event.match().arena(), event.match().round());
        phases.flush();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (event.getBlock().getType() == Material.LAVA && event.getBlock().getWorld().getName().equals(worldName)) {
            lavaFlows++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof FallingBlock && event.getLocation().getWorld().getName().equals(worldName)) {
            collapses++;
        }
    }

    // ---- Helpers ----

    private Optional<Room> room(Slot slot) {
        Player host = Bukkit.getPlayerExact(slot.host);
        return host == null ? Optional.empty() : rooms.hostedBy(host.getUniqueId());
    }

    private Player player(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null || !player.isOnline()) {
            throw new IllegalStateException(name + " is not online");
        }
        return player;
    }

    private List<Player> clients() {
        List<Player> players = new ArrayList<>();
        for (Slot slot : slots) {
            for (String name : List.of(slot.host, slot.guest)) {
                Player player = Bukkit.getPlayerExact(name);
                if (player != null && player.isOnline()) {
                    players.add(player);
                }
            }
        }
        return players;
    }

    private void fail(Exception error) {
        stage = Stage.DONE;
        closeWriters();
        probe.fail(error);
    }

    void close() {
        closeWriters();
    }

    private void closeWriters() {
        for (PrintWriter writer : new PrintWriter[] {ticks, samples, matchRows, generation, shops, phases}) {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /** Minimal JSON for the result file: maps, lists, strings, numbers, booleans and null. */
    static final class Json {
        private Json() {
        }

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            append(out, value);
            return out.toString();
        }

        private static void append(StringBuilder out, Object value) {
            switch (value) {
                case null -> out.append("null");
                case Map<?, ?> map -> {
                    out.append('{');
                    boolean first = true;
                    for (var entry : map.entrySet()) {
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        append(out, String.valueOf(entry.getKey()));
                        out.append(':');
                        append(out, entry.getValue());
                    }
                    out.append('}');
                }
                case List<?> list -> {
                    out.append('[');
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) {
                            out.append(',');
                        }
                        append(out, list.get(i));
                    }
                    out.append(']');
                }
                case Double number when !Double.isFinite(number) -> out.append("null");
                case Number number -> out.append(number);
                case Boolean bool -> out.append(bool);
                default -> {
                    out.append('"');
                    for (char c : value.toString().toCharArray()) {
                        switch (c) {
                            case '"' -> out.append("\\\"");
                            case '\\' -> out.append("\\\\");
                            default -> {
                                if (c < 0x20) {
                                    out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                                } else {
                                    out.append(c);
                                }
                            }
                        }
                    }
                    out.append('"');
                }
            }
        }
    }
}
