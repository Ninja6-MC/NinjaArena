package com.ninja6.ninjaarena.soak;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.ninja6.ninjaarena.NinjaArenaPlugin;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.ArenaRegistry;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.director.DirectorService;
import com.ninja6.ninjaarena.director.StabilityGate;
import com.ninja6.ninjaarena.match.MatchService;
import com.ninja6.ninjaarena.match.MatchSettings;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Optional;
import org.bukkit.event.world.WorldUnloadEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Local-server instrumentation. Never packaged with the production plugin. */
public final class SoakProbe extends JavaPlugin implements Listener {
    private static final int MATCHES = 4;
    private static final int WARMUP_SECONDS = 60;
    private final Path output = Path.of("soak-results");
    private MatchService matches;
    private ArenaRegistry arenas;
    private DirectorService directors;
    private SnapshotService snapshots;
    private Object worldManager;
    private String worldName;
    private WeakReference<World> retired = new WeakReference<>(null);
    private long closedNanos;
    private long unloadEventNanos;
    private long unloadCompletionObservedMs = -1;
    private long heapAtClose;
    private long closeDurationMs;
    private int gcAttempts;
    private Runnable initialLease;
    private PrintWriter ticks;
    private PrintWriter samples;
    private int seconds;
    private int startedAt = -1;
    private int endedAt = -1;
    private boolean finished;
    private final int duration = Integer.getInteger("soak.seconds", 600);
    private final String stage = System.getProperty("soak.stage", "run");

    @Override
    public void onEnable() {
        try {
            if (duration < 60 || duration > 1800) {
                throw new IllegalArgumentException("soak.seconds must be 60 to 1800");
            }
            Files.createDirectories(output);
            var plugin = (NinjaArenaPlugin) getServer().getPluginManager().getPlugin("NinjaArena");
            matches = plugin.matches();
            arenas = plugin.arenas();
            directors = plugin.directors();
            snapshots = plugin.snapshots();
            worldManager = plugin.arenaWorld();
            worldName = plugin.arenaWorld().world().getName();
            try {
                initialLease = (Runnable) worldManager.getClass().getMethod("acquire").invoke(worldManager);
            } catch (NoSuchMethodException oldVersion) {
                initialLease = null;
            }
            if (stage.startsWith("lifecycle-")) {
                releaseInitialLease();
                new LifecycleProbe(this, plugin, stage, output).start();
                return;
            }
            if (stage.equals("setup")) {
                setup();
                mark("setup-complete");
                getServer().getScheduler().runTaskLater(this, Bukkit::shutdown, 100);
                return;
            }
            if (stage.equals("verify")) {
                for (var arena : arenas.all()) {
                    var b = arena.bounds();
                    if (world().getBlockAt(b.minX() + 4, 64, b.minZ() + 4).getType() != Material.AIR) {
                        throw new IllegalStateException("Marker survived restart");
                    }
                }
                mark("restart-markers-clean");
                getServer().getScheduler().runTaskLater(this, Bukkit::shutdown, 20);
                return;
            }
            if (!stage.equals("run") || arenas.all().size() != MATCHES) {
                throw new IllegalStateException("Run setup first in a dedicated empty server");
            }
            ticks = new PrintWriter(Files.newBufferedWriter(output.resolve("ticks.csv")));
            samples = new PrintWriter(Files.newBufferedWriter(output.resolve("matches.csv")));
            ticks.println("tick,milliseconds");
            samples.println("second,arena,phase,intensity,enemies,threat,budget,looping,arena_chunks,minimum_health,average_mspt,tps,arena_holders");
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getScheduler().runTaskTimer(this, this::second, 20, 20);
            getServer().getScheduler().runTaskLater(this, () -> {
                try {
                    mark("ready");
                } catch (Exception error) {
                    fail(error);
                }
            }, 100);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void setup() throws Exception {
        if (!arenas.all().isEmpty()) {
            throw new IllegalStateException("Setup requires an empty arena registry");
        }
        // Separate cells keep the four matches' players and mobs independent.
        for (int i = 0; i < MATCHES; i++) {
            String name = "soak" + i;
            int x = (i % 2 == 0 ? -1 : 1) * 256 + 32;
            int z = (i / 2 == 0 ? -1 : 1) * 256 + 32;
            arenas.create(name, new GridCell(i % 2 == 0 ? -1 : 1, i / 2 == 0 ? -1 : 1));
            var bounds = new ArenaBounds(x, 63, z, x + 63, 75, z + 63);
            for (int bx = x; bx <= x + 63; bx++) {
                for (int bz = z; bz <= z + 63; bz++) {
                    world().getBlockAt(bx, 63, bz).setType(Material.STONE, false);
                    if (bx == x || bx == x + 63 || bz == z || bz == z + 63) {
                        for (int y = 64; y <= 68; y++) {
                            world().getBlockAt(bx, y, bz).setType(Material.STONE, false);
                        }
                    }
                }
            }
            // The central wall hides enemy spawn lanes without disabling placement checks.
            for (int bz = z + 8; bz <= z + 54; bz++) {
                for (int y = 64; y <= 67; y++) {
                    world().getBlockAt(x + 32, y, bz).setType(Material.STONE, false);
                }
            }
            arenas.update(name, a -> a.withBounds(bounds)
                    .withWaitingRoom(point(x + 16, z + 24))
                    .withTeamSpawn(1, point(x + 16, z + 24))
                    .withTeamSpawn(2, point(x + 16, z + 40))
                    .withEnemySpawn(point(x + 48, z + 16))
                    .withEnemySpawn(point(x + 48, z + 32))
                    .withEnemySpawn(point(x + 48, z + 48)));
            var problems = arenas.finishEdit(name);
            if (!problems.isEmpty()) {
                throw new IllegalStateException(problems.toString());
            }
        }
    }

    private static ArenaPoint point(int x, int z) {
        return new ArenaPoint(x + 0.5, 64, z + 0.5, 90, 0);
    }

    private List<Player> clients() {
        var players = new ArrayList<Player>();
        for (int i = 0; i < MATCHES * 2; i++) {
            var player = Bukkit.getPlayerExact("Soak" + i);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private void second() {
        if (finished) {
            return;
        }
        try {
            seconds++;
            if (startedAt < 0) {
                if (seconds > 180) {
                    throw new IllegalStateException("Eight clients did not arrive within 180 seconds");
                }
                if (Files.exists(output.resolve("start")) && clients().size() == MATCHES * 2) {
                    start();
                }
                return;
            }
            if (endedAt < 0) {
                if (clients().size() != MATCHES * 2 || matches.matches().size() != MATCHES) {
                    throw new IllegalStateException("Clients or concurrent matches disappeared");
                }
                for (var match : matches.matches()) {
                    var found = directors.director(match);
                    if (found.isEmpty()) {
                        if (match.phase().isFighting()) {
                            throw new IllegalStateException("Fighting match has no director");
                        }
                        continue;
                    }
                    var director = found.get();
                    samples.printf(Locale.ROOT, "%d,%s,%s,%.6f,%d,%.3f,%.3f,%s,%d,%.3f,%.6f,%.3f,%d%n", seconds - startedAt,
                            match.arena(), match.phase(), director.intensity(), StabilityGate.alive(director),
                            director.threatInPlay(), director.roundBudget(), directors.isLooping(match),
                            world().getLoadedChunks().length,
                            clients().stream().mapToDouble(Player::getHealth).min().orElse(0),
                            Bukkit.getAverageTickTime(), Bukkit.getTPS()[0], holders());
                    if (match.phase().isFighting() && !directors.isLooping(match)) {
                        throw new IllegalStateException("Director stopped in " + match.arena());
                    }
                }
                samples.flush();
                if (seconds - startedAt == 30) {
                    for (var arena : arenas.all()) {
                        var b = arena.bounds();
                        if (world().getBlockAt(b.minX() + 4, 64, b.minZ() + 4).getType() != Material.GOLD_BLOCK) {
                            throw new IllegalStateException("Live marker missing: " + arena.name());
                        }
                    }
                    mark("active");
                }
                if (seconds - startedAt >= duration + WARMUP_SECONDS) {
                    retired = new WeakReference<>(world());
                    closedNanos = System.nanoTime();
                    matches.close();
                    closeDurationMs = (System.nanoTime() - closedNanos) / 1_000_000;
                    heapAtClose = heapUsed();
                    endedAt = seconds;
                    mark("matches-closed");
                }
            } else if (seconds - endedAt >= 45) {
                if (Bukkit.getWorld(worldName) != null || currentWorld().isPresent()) {
                    throw new IllegalStateException("Arena world remains registered/current after idle release");
                }
                // Never resolve the weak referent in the stack that requests GC. Check
                // only on a later tick after four unconditional collection requests.
                if (gcAttempts < 4) {
                    gcAttempts++;
                    System.gc();
                    return;
                }
                if (retired.get() != null) {
                    throw new IllegalStateException("Retired arena world remains strongly reachable after bounded GC attempts");
                }
                Files.writeString(output.resolve("teardown-chunks.json"),
                        "{\"world_registered\":false,\"current_world\":false,\"retired_world_collected\":true,"
                                + "\"full_chunks\":null,\"holders\":null,\"gc_attempts\":" + gcAttempts
                                + ",\"heap_used_bytes\":" + heapUsed()
                                + ",\"heap_at_close_bytes\":" + heapAtClose
                                + ",\"match_close_ms\":" + closeDurationMs
                                + ",\"release_observed_ms\":" + ((System.nanoTime() - closedNanos) / 1_000_000)
                                + ",\"unload_completion_observed_ms\":" + unloadCompletionObservedMs + "}");
                for (var arena : arenas.all()) {
                    if (arenas.isHeld(arena.name())) {
                        throw new IllegalStateException("Arena still held: " + arena.name());
                    }
                }
                if (!snapshots.listPending().isEmpty()) {
                    throw new IllegalStateException("Snapshots remain pending");
                }
                mark("chunks-released");
                finished = true;
                mark("complete");
                Files.writeString(output.resolve("shutdown-requested"), Long.toString(System.currentTimeMillis()));
                Bukkit.shutdown();
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private World world() {
        World current = Bukkit.getWorld(worldName);
        if (current == null) {
            throw new IllegalStateException("Active arena world is not registered");
        }
        return current;
    }

    private Optional<?> currentWorld() throws ReflectiveOperationException {
        return (Optional<?>) worldManager.getClass().getMethod("currentWorld").invoke(worldManager);
    }

    private int holders() throws ReflectiveOperationException {
        return holders(world());
    }

    private int holders(World current) throws ReflectiveOperationException {
        Object level = current.getClass().getMethod("getHandle").invoke(current);
        Object scheduler = level.getClass().getMethod("moonrise$getChunkTaskScheduler").invoke(level);
        Object manager = scheduler.getClass().getField("chunkHolderManager").get(scheduler);
        return (Integer) manager.getClass().getMethod("size").invoke(manager);
    }

    private static long heapUsed() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(WorldUnloadEvent event) {
        if (event.getWorld().getName().equals(worldName) && !event.isCancelled()) {
            unloadEventNanos = System.nanoTime();
            Bukkit.getScheduler().runTask(this, () -> {
                if (Bukkit.getWorld(worldName) == null) {
                    unloadCompletionObservedMs = (System.nanoTime() - unloadEventNanos) / 1_000_000;
                }
            });
        }
    }

    private void start() throws Exception {
        matches.setSettings(new MatchSettings(2, 2, duration + WARMUP_SECONDS, 1, 1, 1, 0));
        List<Player> players = clients();
        var home = Bukkit.getWorlds().getFirst().getSpawnLocation();
        for (Player player : players) {
            player.teleport(home);
            if (snapshots.captureAndPersist(player) != SnapshotService.CaptureResult.SAVED) {
                throw new IllegalStateException("Could not snapshot " + player.getName());
            }
        }
        for (int i = 0; i < MATCHES; i++) {
            matches.start("soak" + i, List.of(List.of(players.get(i * 2).getUniqueId()),
                    List.of(players.get(i * 2 + 1).getUniqueId())));
        }
        for (Player player : players) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40000, 4, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 40000, 0, false, false));
        }
        for (var arena : arenas.all()) {
            var b = arena.bounds();
            world().getBlockAt(b.minX() + 4, 64, b.minZ() + 4).setType(Material.GOLD_BLOCK, false);
        }
        startedAt = seconds;
        releaseInitialLease();
        mark("started");
    }

    @EventHandler
    public void onTick(ServerTickEndEvent event) {
        if (startedAt >= 0 && endedAt < 0 && !finished) {
            ticks.printf(Locale.ROOT, "%d,%.6f%n", event.getTickNumber(), event.getTickDuration());
        }
    }

    private void mark(String name) throws Exception {
        Files.writeString(output.resolve(name), Integer.toString(seconds));
        getLogger().info("Soak checkpoint: " + name);
    }

    private void fail(Exception error) {
        finished = true;
        getLogger().log(java.util.logging.Level.SEVERE, "Soak failed", error);
        try {
            Files.createDirectories(output);
            Files.writeString(output.resolve("failed"), error.toString());
            World previous = retired.get();
            if (endedAt >= 0 && previous != null) {
                Files.writeString(output.resolve("teardown-chunks.json"),
                        "{\"world_registered\":" + (Bukkit.getWorld(worldName) != null)
                                + ",\"retired_world_collected\":false,\"retired_full_chunks\":" + previous.getLoadedChunks().length
                                + ",\"retired_holders\":" + holders(previous)
                                + ",\"retired_plugin_ticket_chunks\":" + previous.getPluginChunkTickets().values().stream()
                                        .mapToInt(java.util.Collection::size).sum() + "}");
            }
        } catch (Exception writeError) {
            getLogger().severe(writeError.toString());
        }
        FailureArtifacts.shutdown(this, output);
    }

    @Override
    public void onDisable() {
        releaseInitialLease();
        if (ticks != null) {
            ticks.close();
        }
        if (samples != null) {
            samples.close();
        }
    }

    private void releaseInitialLease() {
        if (initialLease != null) {
            initialLease.run();
            initialLease = null;
        }
    }
}
