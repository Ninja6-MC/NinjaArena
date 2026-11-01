package com.ninja6.ninjaarena.soak;

import com.ninja6.ninjaarena.NinjaArenaPlugin;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.snapshot.SnapshotService;
import com.ninja6.ninjaarena.restore.RestoreService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Piston;
import org.bukkit.block.data.type.PistonHead;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TechnicalPiston;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

/** Native mechanics plus continuous traversal while a real client keeps one generation busy. */
final class ContainmentProbe implements Listener {
    private final JavaPlugin owner;
    private final NinjaArenaPlugin plugin;
    private final Consumer<Map<String, Object>> complete;
    private final Consumer<Exception> failure;
    private final Supplier<World> prepareBusy;
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final List<Integer> holderSamples = new ArrayList<>();
    private World world;
    private Player player;
    private BukkitTask task;
    private int step;
    private int clicks;
    private int wantedClicks;
    private int clickWait;
    private boolean physicsChallenge;
    private int base;
    private boolean extended;
    private Map<String, String> pistonBefore;
    private int travel;
    private int refused;
    private int commandRefusals;

    ContainmentProbe(JavaPlugin owner, NinjaArenaPlugin plugin, Player player,
            Supplier<World> prepareBusy, Consumer<Map<String, Object>> complete, Consumer<Exception> failure) {
        this.owner = owner;
        this.plugin = plugin;
        this.player = player;
        this.complete = complete;
        this.failure = failure;
        this.prepareBusy = prepareBusy;
    }

    void start() {
        world = plugin.arenaWorld().world();
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        Bukkit.getPluginManager().registerEvents(this, owner);
        task = Bukkit.getScheduler().runTaskTimer(owner, this::tick, 1, 20);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent event) {
        if (event.getPlayer() == player && event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.LEVER
                && event.getHand() == EquipmentSlot.HAND && event.getClickedBlock().getWorld() == world
                && event.getClickedBlock().getX() == base - 1 && event.getClickedBlock().getY() == 65
                && event.getClickedBlock().getZ() == 52
                && (event.getItem() == null || event.getItem().isEmpty()) && player.getGameMode() == GameMode.ADVENTURE) {
            clicks++;
        }
    }

    private void tick() {
        try {
            if (step < 8) {
                pistonStep();
                return;
            }
            if (step == 8) {
                world.getBlockAt(500, 63, 500).setType(Material.STONE, false);
                require(player.teleport(new Location(world, 500.5, 64, 500.5)), "Old snapshot origin refused");
                player.setLevel(7);
                Location originalRespawn = player.getRespawnLocation();
                player.setRespawnLocation(new Location(world, 500.5, 64, 500.5), true);
                require(plugin.snapshots().captureAndPersist(player) == SnapshotService.CaptureResult.SAVED,
                        "Configured-shrink snapshot was not captured");
                player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
                player.setLevel(0);
                plugin.arenaWorld().setBorderSize(128);
                require(plugin.restores().restore(player) == RestoreService.RestoreResult.RESTORED
                        && player.getWorld() != world && player.getLevel() == 7
                        && !plugin.snapshots().hasPending(player.getUniqueId()),
                        "Configured border shrink left a snapshot stuck instead of restoring with fallback");
                player.setRespawnLocation(originalRespawn, true);
                evidence.put("configured_shrink_restore_fallback_and_cleanup", true);
                player.setAllowFlight(true);
                player.setFlying(true);
                plugin.arenaWorld().setDistances(2, 2);
                world = prepareBusy.get();
                step++;
            }
            // Visit every source chunk in the finite envelope, then repeat it three times.
            // No idle interval, generation replacement, match interruption or saving occurs.
            for (int n = 0; n < 8; n++) {
                int cell = travel++ % 64;
                int x = (cell % 8 - 4) * 16 + 8;
                int z = (cell / 8 - 4) * 16 + 8;
                require(player.teleport(new Location(world, x, 80, z)), "In-bounds traversal refused");
                require(!player.teleport(new Location(world, 10000 + travel * 100, 80, 10000)),
                        "Out-of-bounds teleport admitted");
                refused++;
                if (travel % 8 == 0) {
                    require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "execute in " + world.getKey()
                            + " run teleport " + player.getName() + " " + (20000 + travel * 100) + " 80 20000"),
                            "Native teleport command was not dispatched");
                    require(player.getWorld() == world && player.getLocation().getX() == x
                            && player.getLocation().getZ() == z, "Native command bypassed the extent");
                    commandRefusals++;
                }
            }
            require(plugin.arenaWorld().currentWorld().orElseThrow() == world, "Busy generation changed");
            require(!world.isAutoSave(), "Busy traversal enabled saving");
            if (travel % 64 == 0) {
                holderSamples.add(holders());
            }
            if (travel >= 256) {
                int first = holderSamples.get(2);
                require(holderSamples.subList(2, 4).stream().allMatch(count -> count <= first),
                        "Repeated traversal increased holders: " + holderSamples);
                int ceiling = holderCeiling();
                require(holderSamples.stream().allMatch(count -> count <= ceiling),
                        "Holder count exceeded conservative spatial ceiling");
                evidence.put("continuous_busy_source_chunks", 64);
                evidence.put("continuous_busy_traversals", travel);
                evidence.put("continuous_busy_warmup_traversals", 128);
                evidence.put("continuous_busy_holder_samples", holderSamples);
                evidence.put("continuous_busy_holder_coordinate_ceiling", ceiling);
                evidence.put("refused_outside_teleports", refused);
                evidence.put("suppressed_native_command_targets", commandRefusals);
                evidence.put("same_generation_and_saving_off", true);
                plugin.arenaWorld().setBorderSize(2048);
                plugin.arenaWorld().setDistances(6, 4);
                player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
                stop();
                complete.accept(evidence);
            }
        } catch (Exception error) {
            stop();
            failure.accept(error);
        }
    }

    private void pistonStep() throws Exception {
        if (((step > 0 && step <= 5) || step == 7) && clicks < wantedClicks) {
            require(++clickWait < 15, "No empty-hand adventure client lever interaction");
            return;
        }
        if (step > 0 && step <= 4) {
            require(clicks >= wantedClicks, "No empty-hand adventure client lever interaction");
            require(pistonBefore.equals(pistonState(base)), "Protected piston changed base/head/payload");
            if (!physicsChallenge) {
                // Remove the latched power source itself, bypassing its redstone event.
                // Recheck on a later tick, after a native queued retract could have run.
                world.getBlockAt(base - 1, 65, 52).setType(Material.AIR, true);
                physicsChallenge = true;
                return;
            }
            physicsChallenge = false;
        }
        if (step <= 3) {
            var bounds = plugin.arenas().get("lifecycle").orElseThrow().bounds();
            base = switch (step) {
                case 2 -> bounds.maxX();
                case 3 -> bounds.minX() - 1;
                default -> bounds.maxX() - 1;
            };
            extended = step != 0;
            fixture(base, extended, Material.DIAMOND_BLOCK);
            pistonBefore = pistonState(base);
            wantedClicks = clicks + 1;
            useLever(base);
            step++;
            return;
        }
        if (step == 4) {
            evidence.put("empty_hand_adventure_lever_uses", clicks);
            evidence.put("outside_payload_head_base_preserved", true);
            evidence.put("preextended_sticky_piston_preserved", true);
            evidence.put("direct_power_removal_physics_preserved", true);
            plugin.arenas().create("boundary", new GridCell(1, 0));
            base = plugin.arenas().get("lifecycle").orElseThrow().bounds().maxX() + 17;
            fixture(base, true, Material.DIAMOND_BLOCK);
            useLever(base);
            wantedClicks = clicks + 1;
            step++;
            return;
        }
        if (step == 5) {
            require(clicks >= wantedClicks, "Edit control received no client use");
            if (world.getBlockAt(base + 2, 65, 52).getType() != Material.AIR
                    || world.getBlockAt(base + 1, 65, 52).getType() != Material.DIAMOND_BLOCK) {
                require(++clickWait < 10, "Editing native piston did not retract: " + pistonState(base)
                        + ", lever=" + world.getBlockAt(base - 1, 65, 52).getBlockData().getAsString());
                return;
            }
            evidence.put("editing_native_retraction_control", true);
            fixture(base, true, Material.BEDROCK);
            // Queues a sticky retract that has no cancellable payload event on Paper.
            world.getBlockAt(base - 1, 65, 52).setType(Material.AIR, true);
            pistonBefore = pistonState(base);
            plugin.arenas().finishEdit("boundary");
            step++;
            return;
        }
        if (step == 6) {
            require(pistonBefore.equals(pistonState(base)), "Queued edit action crossed into gameplay");
            evidence.put("queued_immovable_retract_cleared_at_edit_close", true);
            plugin.arenas().delete("boundary");
            world = Bukkit.getWorlds().getFirst();
            world.addPluginChunkTicket(base >> 4, 52 >> 4, owner);
            fixture(base, true, Material.DIAMOND_BLOCK);
            wantedClicks = clicks + 1;
            useLever(base);
            step++;
            return;
        }
        if (world.getBlockAt(base + 2, 65, 52).getType() != Material.AIR
                || world.getBlockAt(base + 1, 65, 52).getType() != Material.DIAMOND_BLOCK) {
            require(++clickWait < 10, "Other-world native piston did not retract: " + pistonState(base)
                    + ", lever=" + world.getBlockAt(base - 1, 65, 52).getBlockData().getAsString());
            return;
        }
        world.removePluginChunkTicket(base >> 4, 52 >> 4, owner);
        evidence.put("other_world_native_retraction_control", true);
        world = plugin.arenaWorld().world();
        evidence.put("piston_checks_passed", true);
        step++;
    }

    private void fixture(int x, boolean extended, Material payload) {
        for (int dx = -3; dx <= 3; dx++) {
            world.getBlockAt(x + dx, 64, 52).setType(Material.STONE, false);
            world.getBlockAt(x + dx, 65, 52).setType(Material.AIR, false);
        }
        Switch lever = (Switch) Material.LEVER.createBlockData();
        lever.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
        lever.setFacing(BlockFace.NORTH);
        lever.setPowered(extended);
        world.getBlockAt(x - 1, 65, 52).setBlockData(lever, false);
        Piston piston = (Piston) Material.STICKY_PISTON.createBlockData();
        piston.setFacing(BlockFace.EAST);
        piston.setExtended(extended);
        world.getBlockAt(x, 65, 52).setBlockData(piston, false);
        if (extended) {
            PistonHead head = (PistonHead) Material.PISTON_HEAD.createBlockData();
            head.setFacing(BlockFace.EAST);
            head.setType(TechnicalPiston.Type.STICKY);
            world.getBlockAt(x + 1, 65, 52).setBlockData(head, false);
        }
        world.getBlockAt(x + 2, 65, 52).setType(payload, false);
    }

    private void useLever(int x) {
        clickWait = 0;
        player.teleport(new Location(world, x - 2.5, 65, 52.5));
        Bukkit.getScheduler().runTaskLater(owner,
                () -> player.sendMessage("BOUNDARY_USE " + (x - 1) + " 65 52"), 10);
    }

    private Map<String, String> pistonState(int x) {
        var state = new LinkedHashMap<String, String>();
        for (int dx = 0; dx <= 2; dx++) {
            state.put(Integer.toString(dx), world.getBlockAt(x + dx, 65, 52).getBlockData().getAsString());
        }
        return state;
    }

    private int holders() throws Exception {
        Object level = world.getClass().getMethod("getHandle").invoke(world);
        Object scheduler = level.getClass().getMethod("moonrise$getChunkTaskScheduler").invoke(level);
        Object manager = scheduler.getClass().getField("chunkHolderManager").get(scheduler);
        return (Integer) manager.getClass().getMethod("size").invoke(manager);
    }

    private int holderCeiling() {
        int minX = -4, minZ = -4, maxX = 3, maxZ = 3;
        // Reopening also restores copies; a large fixture can extend beyond the
        // deliberately smaller player-traversal envelope.
        for (var arena : plugin.arenas().all()) {
            var bounds = arena.bounds();
            if (bounds != null) {
                minX = Math.min(minX, bounds.minChunkX());
                minZ = Math.min(minZ, bounds.minChunkZ());
                maxX = Math.max(maxX, bounds.maxChunkX());
                maxZ = Math.max(maxZ, bounds.maxChunkZ());
            }
        }
        int halo = 2 + 2 + 16; // configured distance, ticking, validated dependency radius
        return (maxX - minX + 1 + 2 * halo) * (maxZ - minZ + 1 + 2 * halo);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void stop() {
        task.cancel();
        HandlerList.unregisterAll(this);
        world = null;
        player = null;
    }
}
