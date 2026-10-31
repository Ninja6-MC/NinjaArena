package com.ninja6.ninjaarena.soak;

import com.ninja6.ninjaarena.NinjaArenaPlugin;
import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.match.MatchSettings;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Dirty disk and world-generation lifecycle fixture, restricted to a fresh local server. */
final class LifecycleProbe {
    private final JavaPlugin owner;
    private final NinjaArenaPlugin plugin;
    private final String stage;
    private final Path output;
    private final Object manager;
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final int size = Boolean.getBoolean("soak.large") ? 128 : 32;
    private Runnable lease;
    private WeakReference<World> retired = new WeakReference<>(null);
    private Path folder;
    private UUID uid;
    private int seconds;
    private int gcAttempts;
    private long retiredAt;
    private Map<String, String> before;
    private int rejoinStep;
    private long rejoinStarted;
    private boolean failed;
    private boolean dirtyReopenWaiting;
    private long dirtyReopenStarted;

    LifecycleProbe(JavaPlugin owner, NinjaArenaPlugin plugin, String stage, Path output) {
        this.owner = owner;
        this.plugin = plugin;
        this.stage = stage;
        this.output = output;
        manager = plugin.arenaWorld();
    }

    void start() throws Exception {
        lease = (Runnable) manager.getClass().getMethod("acquire").invoke(manager);
        World world = plugin.arenaWorld().world();
        folder = world.getWorldFolder().toPath();
        uid = world.getUID();
        evidence.put("size", size);
        evidence.put("height", size == 128 ? 65 : 13);
        evidence.put("uid", uid.toString());
        evidence.put("initial_heap_bytes", heap());
        if (stage.equals("lifecycle-setup")) {
            setup(world);
            return;
        }
        if (!stage.equals("lifecycle-verify")) {
            throw new IllegalArgumentException("Unknown lifecycle stage " + stage);
        }
        // This happens on enable, before any test client can request admission.
        assertCanonical(world);
        evidence.put("canonical_at_first_public_access", true);
        // Let Paper finish bootstrap and its forced startup save before the test baseline.
        Bukkit.getScheduler().runTaskLater(owner, () -> {
            try {
                assertCanonical(plugin.arenaWorld().world());
                before = hashes();
                evidence.put("settled_baseline_hashes", before);
                checkpoint("lifecycle-ready");
                Bukkit.getScheduler().runTaskTimer(owner, this::tick, 20, 20);
            } catch (Exception error) {
                fail(error);
            }
        }, 100);
    }

    private void setup(World world) throws Exception {
        if (!plugin.arenas().all().isEmpty()) {
            throw new IllegalStateException("Lifecycle setup requires an empty registry");
        }
        long started = System.nanoTime();
        var arenas = plugin.arenas();
        arenas.create("lifecycle", new GridCell(0, 0));
        for (int x = 28; x < 32 + size; x++) {
            for (int z = 32; z < 32 + size; z++) {
                world.getBlockAt(x, 63, z).setType(Material.STONE, false);
            }
        }
        int top = size == 128 ? 127 : 75;
        arenas.update("lifecycle", a -> a.withBounds(new ArenaBounds(32, 63, 32, 31 + size, top, 31 + size))
                .withWaitingRoom(point(36, 40)).withTeamSpawn(1, point(36, 40))
                .withTeamSpawn(2, point(36, 48)).withEnemySpawn(point(28 + size, 28 + size)));
        List<String> problems = arenas.finishEdit("lifecycle");
        if (!problems.isEmpty()) {
            throw new IllegalStateException(problems.toString());
        }
        evidence.put("setup_copy_ms", (System.nanoTime() - started) / 1_000_000);
        world.getBlockAt(28, 64, 40).setType(Material.DIAMOND_BLOCK, false);
        world.spawn(new Location(world, 29.5, 64, 44.5), ArmorStand.class, e -> {
            e.setGravity(false);
            e.addScoreboardTag("lifecycle-decoration");
        });
        dirty(world);
        // Deliberately violate the clean-soak baseline: force dirty blocks and temporary
        // entities to disk after the authoritative copy, and prove their saved NBT in Python.
        world.setAutoSave(true);
        world.save();
        world.setAutoSave(false);
        evidence.put("dirty_saved", true);
        evidence.put("setup_heap_bytes", heap());
        writeEvidence("lifecycle-setup.json");
        checkpoint("lifecycle-setup-complete");
        release();
        Bukkit.shutdown();
    }

    private void dirty(World world) {
        world.getBlockAt(36, 64, 36).setType(Material.GOLD_BLOCK, false);
        world.spawn(new Location(world, 38.5, 64, 36.5), Zombie.class, e -> {
            e.setAI(false);
            e.setPersistent(true);
            e.setSilent(true);
        });
        Item item = world.dropItem(new Location(world, 40.5, 65, 36.5), new ItemStack(Material.DIRT));
        item.setGravity(false);
        item.setPersistent(true);
        world.spawn(new Location(world, 42.5, 65, 36.5), Snowball.class, e -> {
            e.setGravity(false);
            e.setPersistent(true);
        });
        world.spawn(new Location(world, 43.5, 65, 36.5), Arrow.class, e -> {
            e.setGravity(false);
            e.setPersistent(true);
        });
        world.spawn(new Location(world, 44.5, 65, 36.5), ExperienceOrb.class, e -> {
            e.setExperience(3);
            e.setGravity(false);
            e.setPersistent(true);
        });
    }

    private void tick() {
        if (failed) {
            return;
        }
        try {
            if (++seconds > 240) {
                throw new IllegalStateException("Lifecycle fixture exceeded 240 seconds");
            }
            if (retiredAt != 0) {
                if (rejoinStep != 0) {
                    rejoin();
                    return;
                }
                afterRetire();
                return;
            }
            if (!Files.exists(output.resolve("lifecycle-baseline-captured"))) {
                return;
            }
            if (Bukkit.getPlayerExact("Soak0") == null || Bukkit.getPlayerExact("Soak1") == null
                    || Bukkit.getPlayerExact("Soak2") == null) {
                return;
            }
            // getEntities() must load persisted entity chunks before claiming reset success.
            World world = plugin.arenaWorld().world();
            assertCanonical(world);
            Player outside = Bukkit.getPlayerExact("Soak2");
            outside.teleport(new Location(world, 28.5, 64, 46.5));
            plugin.matches().setSettings(new MatchSettings(30, 30, 120, 1, 1, 1, 0));
            List<Player> players = List.of(Bukkit.getPlayerExact("Soak0"), Bukkit.getPlayerExact("Soak1"));
            for (Player player : players) {
                if (plugin.snapshots().captureAndPersist(player) != SnapshotService.CaptureResult.SAVED) {
                    throw new IllegalStateException("Snapshot failed");
                }
            }
            dirty(world);
            plugin.matches().start("lifecycle", List.of(List.of(players.get(0).getUniqueId()),
                    List.of(players.get(1).getUniqueId())));
            assertCanonical(world);
            evidence.put("normal_start_canonical", true);
            var ignite = new BlockIgniteEvent(world.getBlockAt(28, 64, 40),
                    BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, players.get(0));
            Bukkit.getPluginManager().callEvent(ignite);
            if (!ignite.isCancelled()) {
                throw new IllegalStateException("Outside-bounds fire was not cancelled");
            }
            evidence.put("outside_fire_event_cancelled", true);
            dirty(world);
            plugin.matches().close();
            assertCanonical(world);
            if (outside.getWorld() != world || outside.getLocation().distanceSquared(new Location(world, 28.5, 64, 46.5)) > .01) {
                throw new IllegalStateException("Outside player changed during reset");
            }
            evidence.put("outside_player_preserved", true);
            evidence.put("normal_end_canonical", true);
            if (!plugin.snapshots().listPending().isEmpty() || plugin.arenas().isHeld("lifecycle")) {
                throw new IllegalStateException("Held arena or pending snapshots after match close");
            }
            for (Player player : List.copyOf(world.getPlayers())) {
                player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
            }
            if (!world.getPluginChunkTickets().isEmpty()) {
                throw new IllegalStateException("Plugin tickets remain before recycle");
            }
            if (!before.equals(hashes())) {
                throw new IllegalStateException("Match changed settled save baseline");
            }
            retired = new WeakReference<>(world);
            release();
            long started = System.nanoTime();
            boolean unloaded = (Boolean) manager.getClass().getMethod("releaseIfIdle", BooleanSupplier.class)
                    .invoke(manager, (BooleanSupplier) this::registryIdle);
            evidence.put("unload_ms", (System.nanoTime() - started) / 1_000_000.0);
            if (!unloaded || Bukkit.getWorld(uid) != null || current().isPresent()) {
                throw new IllegalStateException("World did not retire after normal teardown");
            }
            evidence.put("hashes_before_unload", before);
            evidence.put("hashes_after_unload", hashes());
            if (!before.equals(hashes())) {
                throw new IllegalStateException("Unload changed persisted region/entity/POI files");
            }
            retiredAt = System.nanoTime();
            evidence.put("retired_heap_bytes", heap());
            checkpoint("lifecycle-world-retired");
        } catch (Exception error) {
            fail(error);
        }
    }

    private void afterRetire() throws Exception {
        if (dirtyReopenWaiting) {
            if (Bukkit.getWorld(uid) != null || current().isPresent()) {
                throw new IllegalStateException("Dirty reopen world became present before Python proof");
            }
            if (System.nanoTime() - dirtyReopenStarted > 60_000_000_000L) {
                throw new IllegalStateException("Dirty reopen Python proof exceeded 60 seconds");
            }
            if (!Files.exists(output.resolve("dirty-reopen-baseline-captured"))) {
                return;
            }
            long started = System.nanoTime();
            World reopened = plugin.arenaWorld().world();
            if (!uid.equals(reopened.getUID())) {
                throw new IllegalStateException("Dirty reopen changed world UUID");
            }
            assertCanonical(reopened);
            if (!plugin.arenas().isAvailable("lifecycle")) {
                throw new IllegalStateException("Dirty reopened canonical arena unavailable");
            }
            evidence.put("dirty_reopen_canonical_before_admission", true);
            evidence.put("dirty_reopen_before_available_ms", (System.nanoTime() - started) / 1_000_000.0);
            evidence.put("hashes_after_dirty_reopen", hashes());
            if (!before.equals(hashes())) {
                throw new IllegalStateException("Dirty reopen changed deliberately dirty save baseline");
            }
            lease = (Runnable) manager.getClass().getMethod("acquire").invoke(manager);
            dirtyReopenWaiting = false;
            startPendingRejoin(reopened);
            return;
        }
        if (System.nanoTime() - retiredAt < 45_000_000_000L) {
            return;
        }
        if (Bukkit.getWorld(uid) != null || current().isPresent()) {
            throw new IllegalStateException("World became registered while idle");
        }
        if (gcAttempts < 4) {
            gcAttempts++;
            System.gc();
            return;
        }
        if (retired.get() != null) {
            throw new IllegalStateException("Retired world retained after bounded collection attempts");
        }
        evidence.put("retired_world_collected", true);
        evidence.put("gc_attempts", gcAttempts);
        // Public access must reset the dirty disk generation before returning a World.
        long started = System.nanoTime();
        World reopened = plugin.arenaWorld().world();
        evidence.put("reopen_before_available_ms", (System.nanoTime() - started) / 1_000_000.0);
        lease = (Runnable) manager.getClass().getMethod("acquire").invoke(manager);
        if (!uid.equals(reopened.getUID())) {
            throw new IllegalStateException("Reopen changed world UUID");
        }
        assertCanonical(reopened);
        if (!plugin.arenas().isAvailable("lifecycle")) {
            throw new IllegalStateException("Reopened canonical arena unavailable");
        }
        evidence.put("canonical_before_reopen_admission", true);
        evidence.put("hashes_after_reopen", hashes());
        if (!before.equals(hashes())) {
            throw new IllegalStateException("Reopen changed persisted region/entity/POI files");
        }
        evidence.put("reopened_heap_bytes", heap());
        if (Boolean.getBoolean("soak.dirtyReopen")) {
            dirty(reopened);
            reopened.setAutoSave(true);
            reopened.save();
            reopened.setAutoSave(false);
            evidence.put("intentional_dirty_reopen_save", true);
            release();
            boolean unloaded = (Boolean) manager.getClass().getMethod("releaseIfIdle", BooleanSupplier.class)
                    .invoke(manager, (BooleanSupplier) this::registryIdle);
            if (!unloaded || Bukkit.getWorld(uid) != null || current().isPresent()) {
                throw new IllegalStateException("Deliberately dirty world did not retire before Python proof");
            }
            before = hashes();
            evidence.put("dirty_reopen_baseline_hashes", before);
            evidence.put("dirty_reopen_world_absent_for_python_proof", true);
            writeEvidence("dirty-reopen-ready.json");
            dirtyReopenWaiting = true;
            dirtyReopenStarted = System.nanoTime();
            checkpoint("dirty-reopen-ready");
            return;
        }
        startPendingRejoin(reopened);
    }

    private void startPendingRejoin(World reopened) throws Exception {
        Player player = Bukkit.getPlayerExact("Soak2");
        if (player == null) {
            throw new IllegalStateException("Outside client missing before arena-origin snapshot test");
        }
        player.teleport(new Location(reopened, 28.5, 64, 46.5));
        if (plugin.snapshots().captureAndPersist(player) != SnapshotService.CaptureResult.SAVED) {
            throw new IllegalStateException("Could not persist arena-origin snapshot");
        }
        evidence.put("rejoin_origin", Map.of("uid", uid.toString(), "x", 28.5, "y", 64, "z", 46.5));
        rejoinStarted = System.nanoTime();
        rejoinStep = 1;
        checkpoint("lifecycle-rejoin-requested");
        player.kick(net.kyori.adventure.text.Component.text("Lifecycle fixture reconnect"));
    }

    private void rejoin() throws Exception {
        if (System.nanoTime() - rejoinStarted > 60_000_000_000L) {
            throw new IllegalStateException("Arena-origin reconnect fixture exceeded 60 seconds");
        }
        if (rejoinStep == 1) {
            if (Bukkit.getPlayerExact("Soak2") != null) {
                return;
            }
            release();
            boolean unloaded = (Boolean) manager.getClass().getMethod("releaseIfIdle", BooleanSupplier.class)
                    .invoke(manager, (BooleanSupplier) this::registryIdle);
            if (!unloaded || Bukkit.getWorld(uid) != null || current().isPresent()) {
                throw new IllegalStateException("Arena-origin snapshot world did not retire before reconnect");
            }
            evidence.put("pending_origin_world_absent_before_rejoin", true);
            evidence.put("hashes_after_pending_unload", hashes());
            if (!before.equals(hashes())) {
                throw new IllegalStateException("Pending-origin unload changed save");
            }
            rejoinStep = 2;
            return;
        }
        if (rejoinStep == 3) {
            release();
            if (!registryIdle() || !plugin.snapshots().listPending().isEmpty()) {
                throw new IllegalStateException("Final registry or pending snapshots are not idle");
            }
            boolean alreadyAbsent = Bukkit.getWorld(uid) == null && current().isEmpty();
            evidence.put("final_world_retired_autonomously", alreadyAbsent);
            if (!alreadyAbsent) {
                manager.getClass().getMethod("releaseIfIdle", BooleanSupplier.class)
                        .invoke(manager, (BooleanSupplier) this::registryIdle);
            }
            if (Bukkit.getWorld(uid) != null || current().isPresent()) {
                throw new IllegalStateException("Final world did not retire after player evacuation");
            }
            evidence.put("final_world_absent_before_shutdown", true);
            evidence.put("hashes_before_shutdown", hashes());
            if (!before.equals(hashes())) {
                throw new IllegalStateException("Final retirement changed settled save baseline");
            }
            writeEvidence("lifecycle-result.json");
            failed = true;
            checkpoint("lifecycle-complete");
            checkpoint("shutdown-requested");
            Bukkit.shutdown();
            return;
        }
        Player player = Bukkit.getPlayerExact("Soak2");
        if (player == null || !player.isOnline() || !plugin.snapshots().listPending().isEmpty()) {
            return;
        }
        var at = player.getLocation();
        if (!uid.equals(player.getWorld().getUID()) || Math.abs(at.getX() - 28.5) > .01
                || Math.abs(at.getY() - 64) > .01 || Math.abs(at.getZ() - 46.5) > .01) {
            throw new IllegalStateException("Arena-origin snapshot fell back or lost exact coordinates: " + at);
        }
        assertCanonical(player.getWorld());
        evidence.put("pending_origin_restored_exactly", true);
        evidence.put("pending_snapshot_cleared", true);
        evidence.put("pending_rejoin_ms", (System.nanoTime() - rejoinStarted) / 1_000_000);
        evidence.put("hashes_after_pending_rejoin", hashes());
        if (!before.equals(hashes())) {
            throw new IllegalStateException("Pending-origin reopen changed save");
        }
        for (Player online : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (!online.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation())) {
                throw new IllegalStateException("Final player evacuation failed");
            }
        }
        // The next scheduled server-thread turn observes completed teleports before release.
        rejoinStep = 3;
    }

    private boolean registryIdle() {
        try {
            return (Boolean) plugin.arenas().getClass().getMethod("canReleaseWorld").invoke(plugin.arenas());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Registry idle guard unavailable", error);
        }
    }

    private void assertCanonical(World world) {
        if (world.getBlockAt(36, 64, 36).getType() != Material.AIR
                || world.getBlockAt(28, 64, 40).getType() != Material.DIAMOND_BLOCK) {
            throw new IllegalStateException("Canonical marker or outside decoration incorrect");
        }
        var bounds = plugin.arenas().get("lifecycle").orElseThrow().bounds();
        int decoration = 0;
        for (int x = bounds.minChunkX() - 1; x <= bounds.maxChunkX(); x++) {
            for (int z = bounds.minChunkZ(); z <= bounds.maxChunkZ(); z++) {
                for (Entity entity : world.getChunkAt(x, z).getEntities()) {
                    var at = entity.getLocation();
                    if (entity.getScoreboardTags().contains("lifecycle-decoration")) {
                        decoration++;
                    }
                    if (bounds.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ())
                            && (entity instanceof Zombie || entity instanceof Item || entity instanceof Snowball
                                    || entity instanceof Arrow
                                    || entity instanceof ExperienceOrb)) {
                        throw new IllegalStateException("Temporary entity survived reset: " + entity.getType());
                    }
                }
            }
        }
        if (decoration != 1) {
            throw new IllegalStateException("Outside decoration entity was not preserved");
        }
    }

    private Optional<?> current() throws Exception {
        return (Optional<?>) manager.getClass().getMethod("currentWorld").invoke(manager);
    }

    private Map<String, String> hashes() throws Exception {
        var result = new LinkedHashMap<String, String>();
        for (String name : List.of("region", "entities", "poi")) {
            if (!Files.isDirectory(folder.resolve(name))) {
                continue;
            }
            try (var files = Files.list(folder.resolve(name))) {
                for (Path path : files.filter(p -> p.toString().endsWith(".mca")).sorted().limit(65).toList()) {
                    if (result.size() >= 64 || Files.size(path) > 64 * 1024 * 1024L) {
                        throw new IllegalStateException("Fixture save exceeds bounded hashing budget");
                    }
                    var hash = MessageDigest.getInstance("SHA-256");
                    try (var stream = Files.newInputStream(path)) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = stream.read(buffer)) != -1) {
                            hash.update(buffer, 0, read);
                        }
                    }
                    result.put(folder.relativize(path).toString().replace('\\', '/'), HexFormat.of().formatHex(hash.digest()));
                }
            }
        }
        return result;
    }

    private static ArenaPoint point(int x, int z) {
        return new ArenaPoint(x + .5, 64, z + .5, 90, 0);
    }

    private static long heap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private void release() {
        if (lease != null) {
            lease.run();
            lease = null;
        }
    }

    private void checkpoint(String name) throws Exception {
        Files.writeString(output.resolve(name), Long.toString(System.currentTimeMillis()));
    }

    private void writeEvidence(String name) throws Exception {
        Files.writeString(output.resolve(name), json(evidence));
    }

    private static String json(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return "{" + map.entrySet().stream().map(e -> json(e.getKey()) + ":" + json(e.getValue()))
                    .collect(java.util.stream.Collectors.joining(",")) + "}";
        }
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private void fail(Exception error) {
        failed = true;
        owner.getLogger().log(java.util.logging.Level.SEVERE, "Lifecycle fixture failed", error);
        try {
            Files.writeString(output.resolve("failed"), error.toString());
        } catch (Exception writeError) {
            owner.getLogger().severe(writeError.toString());
        }
        release();
        FailureArtifacts.shutdown(owner, output);
    }
}
