package com.ninja6.ninjaarena.hazard;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaProtectionListener;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.map.CellWriter;
import com.ninja6.ninjaarena.map.GeneratedMaps;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import com.ninja6.ninjaarena.terrain.HazardPlan;
import com.ninja6.ninjaarena.terrain.MovementModel;
import com.ninja6.ninjaarena.terrain.ThemePalette;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Real lava flow and collapsing blocks in generated maps during a round, kept to what the
 * map's {@link HazardPlan} allows.
 *
 * <p><b>Lava.</b> When a fight starts (COMBAT), every lava fall of the map starts flowing,
 * up to {@link HazardSettings#maxSourcesPerMap()} sources in the map and
 * {@link HazardSettings#maxSources()} across every map, the same number on every team's
 * side; the match hears the hazard warning. A fall's still column is taken away and its
 * head set flowing, so the column fills again with real falling lava that spreads where it
 * lands. Held ceiling lava stays held until {@link #openLava} releases it. A source flows
 * for at most {@link HazardSettings#flowSeconds()}, then it is taken away. Only the cells of
 * a flowing source's footprint may take its flow ({@link #allows}, through
 * {@link ArenaProtectionListener}): every other flow in the arena world stays cancelled, so
 * lava never leaves the footprint, the map, or reaches a spawn, the centre or the route.
 *
 * <p><b>Collapses.</b> A crumbling block a fighting player stands on cracks: the player hears
 * the hazard warning, and {@link #COLLAPSE_DELAY_TICKS} later the block collapses. It turns to
 * air and a falling block of it drops from there, which never lands as a block.
 *
 * <p><b>Changes.</b> Every block change goes through the {@link CellWriter} as a change in the
 * map's slot cell, at most {@link CellWriter#MAX_CHANGES_PER_TICK} a tick across every map,
 * within the placement time budget.
 *
 * <p><b>Clean-up.</b> When the round ends (any phase but PREP, COMBAT and OVERRUN) or the match
 * ends, nothing may flow any more, cracking blocks no longer collapse, and every source is
 * taken away; the lava it fed drains by itself. At the next PREP the held lava and the fall
 * heads are put back still and the collapsed blocks put back, while the teams are at their
 * spawns. When the slot cell starts being cleared (the map freed or replaced, a sweep, or
 * plugin disable) everything for the map is dropped at once, without block changes: the
 * clear removes the lava. {@link #close} stops all flow and removes the lava of every
 * footprint at once, since with the plugin gone nothing would hold it.
 *
 * <p>Hand-built arenas have no plan and are never touched. Server thread only, apart from
 * working out plans, which runs on the executor given.
 */
public final class HazardService implements Listener, ArenaProtectionListener.FlowFilter {

    /** Ticks between a crumbling block cracking under a player and collapsing. */
    public static final int COLLAPSE_DELAY_TICKS = 20;

    /** Ticks between checks of feet on crumbling blocks and of the flow time limit. */
    public static final int CHECK_TICKS = 2;

    static final int TICKS_PER_SECOND = 20;

    /** Where players stand, for collapses. */
    @FunctionalInterface
    public interface Feet {

        /** The block the player stands on in the arena world, {@link #pack packed}; empty if none. */
        OptionalLong under(UUID player);
    }

    /** The hazard warning sound. */
    public interface Warnings {

        /** Lava starts in the match's map. */
        void lava(MatchView match);

        /** A block cracks under the player. */
        void crack(UUID player);

        Warnings NONE = new Warnings() {
            @Override
            public void lava(MatchView match) {
            }

            @Override
            public void crack(UUID player) {
            }
        };
    }

    /** A lava source in world coordinates, with its footprint packed. */
    record Source(HazardPlan.Kind kind, int x, int y, int z, long[] footprint, long[] column) {

        long at() {
            return pack(x, y, z);
        }
    }

    /** A map's plan in world coordinates. */
    record WorldPlan(List<Source> sources, Map<Long, Material> crumble, int teams) {

        static final WorldPlan EMPTY = new WorldPlan(List.of(), Map.of(), 1);
    }

    /** One generated map's hazards. */
    private static final class MapHazards {

        final GeneratedMaps.Placed placed;
        final GridCell cell;
        final ArenaBounds bounds;
        MatchView match;
        WorldPlan plan;
        CompletableFuture<?> planning = CompletableFuture.completedFuture(null);
        boolean fighting;
        /** Start the falls once the plan is worked out, if the fight is still on. */
        boolean openOnPlan;
        /** Bumped by every start and stop, so a change queued before it does nothing. */
        int generation;
        /** Sources started or queued to start, and the tick each started; -1 while queued. */
        final Map<Long, Integer> open = new LinkedHashMap<>();
        /** How many open sources' footprints hold each cell. */
        final Map<Long, Integer> allowed = new HashMap<>();
        /** Source cells taken away since the round began, put back still at the next PREP. */
        final Map<Long, Source> stopped = new LinkedHashMap<>();
        /** Crumbling blocks cracking, and the tick each collapses. */
        final Map<Long, Integer> cracking = new LinkedHashMap<>();
        /** Collapsed blocks and what they were, put back at the next PREP. */
        final Map<Long, Material> collapsed = new LinkedHashMap<>();

        MapHazards(GeneratedMaps.Placed placed) {
            this.placed = placed;
            this.cell = placed.arena().cell();
            this.bounds = placed.arena().bounds();
        }
    }

    private final Function<String, Optional<GeneratedMaps.Placed>> placed;
    private final CellWriter writer;
    private final HazardBlocks blocks;
    private final HazardSettings settings;
    private final Executor planner;
    private final Executor server;
    private final IntSupplier currentTick;
    private final Logger logger;
    private final Map<GridCell, MapHazards> maps = new LinkedHashMap<>();
    private Feet feet = player -> OptionalLong.empty();
    private Warnings warnings = Warnings.NONE;
    private BukkitTask task;
    private boolean closed;

    /**
     * @param placed      the placed generated map of a name; {@link GeneratedMaps#placed}
     * @param planner     works out plans off the server thread
     * @param server      runs a task on the server thread
     * @param currentTick the server tick; {@code Bukkit#getCurrentTick}
     */
    public HazardService(Function<String, Optional<GeneratedMaps.Placed>> placed, CellWriter writer,
            HazardBlocks blocks, HazardSettings settings, Executor planner, Executor server,
            IntSupplier currentTick, Logger logger) {
        this.placed = Objects.requireNonNull(placed, "placed");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.server = Objects.requireNonNull(server, "server");
        this.currentTick = Objects.requireNonNull(currentTick, "currentTick");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void setFeet(Feet feet) {
        this.feet = Objects.requireNonNull(feet, "feet");
    }

    public void setWarnings(Warnings warnings) {
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /** Checks feet and time limits every {@link #CHECK_TICKS} ticks until {@link #close()}. */
    public void start(Plugin plugin) {
        if (task == null && !closed) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, CHECK_TICKS, CHECK_TICKS);
        }
    }

    // ---- Match events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPhase(MatchPhaseChangeEvent event) {
        switch (event.to()) {
            case PREP -> prepareRound(event.match());
            case COMBAT -> startFight(event.match());
            case OVERRUN -> {
                // The fight goes on.
            }
            default -> stopRound(event.match());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchEnd(MatchEndEvent event) {
        stopRound(event.match());
        for (MapHazards map : maps.values()) {
            if (map.match == event.match()) {
                map.match = null;
            }
        }
    }

    /**
     * A round is about to start on the match's map: what the last round took away or
     * collapsed is put back, and the plan is worked out if it is not yet.
     */
    public void prepareRound(MatchView match) {
        MapHazards map = bind(match);
        if (map == null) {
            return;
        }
        stop(map);
        List<Runnable> changes = new ArrayList<>();
        int generation = map.generation;
        for (Source source : map.stopped.values()) {
            changes.add(change(map, generation, () -> blocks.restore(source.x(), source.y(), source.z(),
                    Material.LAVA)));
        }
        map.stopped.clear();
        for (Map.Entry<Long, Material> block : map.collapsed.entrySet()) {
            long at = block.getKey();
            changes.add(change(map, generation, () -> blocks.restore(unpackX(at), unpackY(at), unpackZ(at),
                    block.getValue())));
        }
        map.collapsed.clear();
        if (!changes.isEmpty()) {
            writer.apply(map.cell, changes);
        }
    }

    /** The fight starts: every lava fall starts flowing, within the limits. */
    public void startFight(MatchView match) {
        MapHazards map = bind(match);
        if (map == null) {
            return;
        }
        map.fighting = true;
        if (map.plan == null) {
            map.openOnPlan = true;
            return;
        }
        if (open(map, HazardPlan.Kind.FALL, Integer.MAX_VALUE) > 0) {
            warnings.lava(match);
        }
    }

    /** The round is over: nothing flows or collapses any more and every source is taken away. */
    public void stopRound(MatchView match) {
        for (MapHazards map : maps.values()) {
            if (map.match == match) {
                stop(map);
            }
        }
    }

    /**
     * Starts up to {@code count} more lava sources of the kind in the match's map while it is
     * fighting, within the limits and rounded down to the same number on every side, in the
     * plan's order. For hazards that start later in a round.
     *
     * @return the sources started
     */
    public int openLava(MatchView match, HazardPlan.Kind kind, int count) {
        Objects.requireNonNull(kind, "kind");
        for (MapHazards map : maps.values()) {
            if (map.match == match && map.fighting && map.plan != null) {
                return open(map, kind, count);
            }
        }
        return 0;
    }

    /** Lava sources flowing or queued to, in one map's cell. */
    public int openSources(GridCell cell) {
        MapHazards map = maps.get(cell);
        return map == null ? 0 : map.open.size();
    }

    /** Lava sources flowing or queued to, across every map. */
    public int openSources() {
        int total = 0;
        for (MapHazards map : maps.values()) {
            total += map.open.size();
        }
        return total;
    }

    /** Whether the map in the cell has its plan worked out. */
    public boolean isPlanned(GridCell cell) {
        MapHazards map = maps.get(cell);
        return map != null && map.plan != null;
    }

    /**
     * The slot cell starts being cleared: everything for its map stops and is forgotten
     * without changing a block. {@link GeneratedMaps#onClearing}.
     */
    public void forget(GridCell cell) {
        MapHazards map = maps.remove(cell);
        if (map != null) {
            map.generation++;
            map.fighting = false;
            map.planning.cancel(false);
        }
    }

    // ---- Flow ----

    /**
     * Whether lava may flow from one block to the next: only while the map's fight is on, and
     * only when both blocks are in the footprint of a source flowing in that map.
     */
    @Override
    public boolean allows(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        for (MapHazards map : maps.values()) {
            if (map.fighting && map.bounds.contains(fromX, fromY, fromZ)) {
                return map.bounds.contains(toX, toY, toZ) && map.allowed.containsKey(pack(fromX, fromY, fromZ))
                        && map.allowed.containsKey(pack(toX, toY, toZ));
            }
        }
        return false;
    }

    // ---- Tick ----

    /** Cracks blocks under fighting players, collapses those due and stops sources past the time limit. */
    public void tick() {
        int now = currentTick.getAsInt();
        for (MapHazards map : List.copyOf(maps.values())) {
            if (!map.fighting || map.plan == null) {
                continue;
            }
            stopExpired(map, now);
            crack(map, now);
            collapseDue(map, now);
        }
    }

    private void stopExpired(MapHazards map, int now) {
        long limit = (long) settings.flowSeconds() * TICKS_PER_SECOND;
        List<Source> expired = new ArrayList<>();
        for (Source source : map.plan.sources()) {
            Integer started = map.open.get(source.at());
            if (started != null && started >= 0 && now - started >= limit) {
                expired.add(source);
            }
        }
        if (!expired.isEmpty()) {
            List<Runnable> changes = new ArrayList<>();
            for (Source source : expired) {
                close(map, source, changes);
            }
            writer.apply(map.cell, changes);
        }
    }

    private void crack(MapHazards map, int now) {
        if (map.match == null || map.plan.crumble().isEmpty()) {
            return;
        }
        for (UUID player : map.match.alivePlayers()) {
            OptionalLong under = feet.under(player);
            if (under.isEmpty()) {
                continue;
            }
            long at = under.getAsLong();
            if (map.plan.crumble().containsKey(at) && !map.collapsed.containsKey(at)
                    && !map.cracking.containsKey(at)) {
                map.cracking.put(at, now + COLLAPSE_DELAY_TICKS);
                warnings.crack(player);
            }
        }
    }

    private void collapseDue(MapHazards map, int now) {
        List<Runnable> changes = new ArrayList<>();
        int generation = map.generation;
        for (Iterator<Map.Entry<Long, Integer>> it = map.cracking.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Integer> due = it.next();
            if (due.getValue() > now) {
                continue;
            }
            it.remove();
            long at = due.getKey();
            Material material = map.plan.crumble().get(at);
            changes.add(change(map, generation, () -> {
                if (blocks.collapse(unpackX(at), unpackY(at), unpackZ(at), material)) {
                    map.collapsed.put(at, material);
                }
            }));
        }
        if (!changes.isEmpty()) {
            writer.apply(map.cell, changes);
        }
    }

    // ---- Starting and stopping ----

    /** The map's hazards for the match's map, made when the map is new; null for a hand-built arena. */
    private MapHazards bind(MatchView match) {
        if (closed) {
            return null;
        }
        Optional<GeneratedMaps.Placed> found = placed.apply(match.arena());
        if (found.isEmpty()) {
            return null;
        }
        GeneratedMaps.Placed current = found.get();
        MapHazards map = maps.get(current.arena().cell());
        if (map == null || map.placed.layout() != current.layout()) {
            forget(current.arena().cell());
            map = new MapHazards(current);
            maps.put(map.cell, map);
            plan(map);
        }
        // A map the match left behind for another is not this match's any more.
        for (MapHazards other : maps.values()) {
            if (other != map && other.match == match) {
                stop(other);
                other.match = null;
            }
        }
        map.match = match;
        return map;
    }

    private void plan(MapHazards map) {
        GeneratedMaps.Placed target = map.placed;
        CompletableFuture<WorldPlan> planned = CompletableFuture.supplyAsync(() -> worldPlan(target), planner);
        map.planning = planned;
        planned.whenCompleteAsync((plan, error) -> planned(map, plan, error), server);
    }

    private void planned(MapHazards map, WorldPlan plan, Throwable error) {
        if (maps.get(map.cell) != map) {
            return;
        }
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            logger.log(Level.SEVERE, "Could not work out the hazards of map " + map.placed.arena().name()
                    + "; its lava stays still and nothing collapses.", cause);
            plan = WorldPlan.EMPTY;
        }
        map.plan = plan;
        if (map.openOnPlan && map.fighting) {
            map.openOnPlan = false;
            if (open(map, HazardPlan.Kind.FALL, Integer.MAX_VALUE) > 0 && map.match != null) {
                warnings.lava(map.match);
            }
        }
    }

    private int open(MapHazards map, HazardPlan.Kind kind, int count) {
        int teams = map.plan.teams();
        int room = Math.min(settings.maxSourcesPerMap() - map.open.size(), settings.maxSources() - openSources());
        int wanted = Math.min(count, room);
        wanted -= Math.floorMod(wanted, teams);
        List<Source> chosen = new ArrayList<>();
        List<Source> sources = map.plan.sources();
        for (int i = 0; i + teams <= sources.size() && chosen.size() + teams <= wanted; i += teams) {
            List<Source> group = sources.subList(i, i + teams);
            if (group.getFirst().kind() == kind && group.stream().noneMatch(source -> map.open.containsKey(source.at()))) {
                chosen.addAll(group);
            }
        }
        if (chosen.isEmpty()) {
            return 0;
        }
        int generation = map.generation;
        List<Runnable> changes = new ArrayList<>();
        for (Source source : chosen) {
            map.open.put(source.at(), -1);
            map.stopped.remove(source.at());
            for (long cell : source.footprint()) {
                map.allowed.merge(cell, 1, Integer::sum);
            }
            if (source.kind() == HazardPlan.Kind.FALL) {
                // The still column goes first, so real falling lava fills it again.
                for (long cell : source.column()) {
                    changes.add(change(map, generation, () -> blocks.removeLava(unpackX(cell), unpackY(cell),
                            unpackZ(cell))));
                }
            }
            changes.add(change(map, generation, () -> {
                if (map.open.containsKey(source.at())) {
                    blocks.startLava(source.x(), source.y(), source.z());
                    map.open.put(source.at(), currentTick.getAsInt());
                }
            }));
        }
        writer.apply(map.cell, changes);
        return chosen.size();
    }

    /** Stops the source: its footprint closes and a change takes it away. */
    private void close(MapHazards map, Source source, List<Runnable> changes) {
        map.open.remove(source.at());
        for (long cell : source.footprint()) {
            map.allowed.computeIfPresent(cell, (key, holders) -> holders > 1 ? holders - 1 : null);
        }
        map.stopped.put(source.at(), source);
        // Runs whatever the generation: a source must never be left flowing.
        changes.add(() -> {
            try {
                blocks.stopLava(source.x(), source.y(), source.z());
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not stop a lava source in map " + map.placed.arena().name() + ".", e);
            }
        });
    }

    /** Ends the round for the map: nothing flows or collapses, and every source is taken away. */
    private void stop(MapHazards map) {
        map.fighting = false;
        map.openOnPlan = false;
        map.generation++;
        map.cracking.clear();
        if (map.plan == null || map.open.isEmpty()) {
            map.allowed.clear();
            return;
        }
        List<Runnable> changes = new ArrayList<>();
        for (Source source : map.plan.sources()) {
            if (map.open.containsKey(source.at())) {
                close(map, source, changes);
            }
        }
        map.allowed.clear();
        writer.apply(map.cell, changes);
    }

    /** A change that does nothing once the map's round has moved on, and never throws. */
    private Runnable change(MapHazards map, int generation, Runnable change) {
        return () -> {
            if (map.generation != generation || maps.get(map.cell) != map) {
                return;
            }
            try {
                change.run();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "A hazard change failed in map " + map.placed.arena().name() + ".", e);
            }
        };
    }

    /**
     * Stops every flow and removes the lava of every flowing source's footprint at once, and
     * every falling block still falling. Called on plugin disable, before the cells close.
     */
    public void close() {
        closed = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (MapHazards map : maps.values()) {
            map.generation++;
            map.fighting = false;
            map.planning.cancel(false);
            if (map.plan != null) {
                removeLava(map);
            }
            map.allowed.clear();
            map.open.clear();
        }
        maps.clear();
        try {
            blocks.removeDebris();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not remove falling blocks.", e);
        }
    }

    /** Removes the lava of every source flowing or taken away this round, footprints and all. */
    private void removeLava(MapHazards map) {
        for (Source source : map.plan.sources()) {
            if (!map.open.containsKey(source.at()) && !map.stopped.containsKey(source.at())) {
                continue;
            }
            for (long cell : source.footprint()) {
                try {
                    blocks.removeLava(unpackX(cell), unpackY(cell), unpackZ(cell));
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Could not remove lava in map " + map.placed.arena().name() + ".", e);
                    return;
                }
            }
        }
    }

    // ---- Plans ----

    /**
     * The map's plan in world coordinates. A source with any footprint cell outside the map's
     * bounds or hazard zone is dropped, and so is such a crumbling block, though the plan
     * never gives one.
     */
    static WorldPlan worldPlan(GeneratedMaps.Placed map) {
        ArenaLayout layout = map.layout();
        HazardPlan plan = HazardPlan.of(layout, MovementModel.DEFAULT);
        ArenaBounds bounds = map.arena().bounds();
        List<Source> sources = new ArrayList<>();
        List<HazardPlan.LavaSource> planned = plan.sources();
        for (int i = 0; i + plan.teams() <= planned.size(); i += plan.teams()) {
            List<Source> group = new ArrayList<>();
            for (HazardPlan.LavaSource source : planned.subList(i, i + plan.teams())) {
                Source placed = source(map, source, bounds);
                if (placed != null) {
                    group.add(placed);
                }
            }
            // Every side or none, so the sides stay the same.
            if (group.size() == plan.teams()) {
                sources.addAll(group);
            }
        }
        Map<Long, Material> crumble = new LinkedHashMap<>();
        for (HazardPlan.Crumble block : plan.crumble()) {
            ArenaLayout.Position at = block.block();
            int x = map.originX() + at.x();
            int y = map.originY() + at.y();
            int z = map.originZ() + at.z();
            if (HazardPlan.inHazardZone(layout, at.x(), at.y(), at.z()) && bounds.contains(x, y, z)) {
                crumble.put(pack(x, y, z), ThemePalette.material(layout.side().theme(), block.kind()));
            }
        }
        return new WorldPlan(List.copyOf(sources), crumble, plan.teams());
    }

    private static Source source(GeneratedMaps.Placed map, HazardPlan.LavaSource source, ArenaBounds bounds) {
        ArenaLayout layout = map.layout();
        List<ArenaLayout.Position> cells = source.footprint();
        ArenaLayout.Position head = source.source();
        long[] footprint = new long[cells.size()];
        for (int i = 0; i < footprint.length; i++) {
            ArenaLayout.Position cell = cells.get(i);
            int x = map.originX() + cell.x();
            int y = map.originY() + cell.y();
            int z = map.originZ() + cell.z();
            if (!HazardPlan.inHazardZone(layout, cell.x(), cell.y(), cell.z()) || !bounds.contains(x, y, z)) {
                return null;
            }
            footprint[i] = pack(x, y, z);
        }
        // The footprint lists the head, then the cells straight below it: its column.
        int column = 0;
        while (column + 1 < cells.size()) {
            ArenaLayout.Position cell = cells.get(column + 1);
            if (cell.x() != head.x() || cell.z() != head.z() || cell.y() != head.y() - column - 1) {
                break;
            }
            column++;
        }
        return new Source(source.kind(), map.originX() + head.x(), map.originY() + head.y(), map.originZ() + head.z(),
                footprint, Arrays.copyOfRange(footprint, 1, column + 1));
    }

    // ---- Positions ----

    /** A block position as one number: 26 bits of x, 26 of z and 12 of y, each signed. */
    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (long) y & 0xFFF;
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }
}
