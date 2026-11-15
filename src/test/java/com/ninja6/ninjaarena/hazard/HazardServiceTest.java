package com.ninja6.ninjaarena.hazard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaGrid;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.map.BudgetedCellWriter;
import com.ninja6.ninjaarena.map.CellWriter;
import com.ninja6.ninjaarena.map.FakeBlocks;
import com.ninja6.ninjaarena.map.FakeWriters;
import com.ninja6.ninjaarena.map.GeneratedMaps;
import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.MatchView;
import com.ninja6.ninjaarena.match.event.MatchEndEvent;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.terrain.ArenaLayout;
import com.ninja6.ninjaarena.terrain.HazardPlan;
import com.ninja6.ninjaarena.terrain.MapSearch;
import com.ninja6.ninjaarena.terrain.MovementModel;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class HazardServiceTest {

    private static final ArenaGrid GRID = new ArenaGrid(256, 2048, -64, 320);
    private static final UUID ALICE = new UUID(0, 1);
    private static final int DIFFICULTY = 4;
    /** A seed whose map has lava falls that may flow and crumbling blocks. */
    private static final long SEED = seed();

    private final FakeBlocks blocks = new FakeBlocks();
    private final BudgetedCellWriter writer = FakeWriters.perTick(blocks, 1 << 20);
    private final GeneratedMaps maps = new GeneratedMaps(GRID, Set::of, () -> true, writer, blocks.ticketsView(),
            Runnable::run, Runnable::run, quiet());
    private final Hazards hazards = new Hazards(blocks);
    private final List<String> warned = new ArrayList<>();
    private int now;
    private Long feet;

    private static Logger quiet() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static long seed() {
        for (long seed = 1; seed < 200; seed++) {
            if (MapSearch.search(seed, DIFFICULTY, 2) instanceof MapSearch.Accepted accepted) {
                HazardPlan plan = HazardPlan.of(accepted.layout(), MovementModel.DEFAULT);
                long falls = plan.sources().stream().filter(source -> source.kind() == HazardPlan.Kind.FALL).count();
                if (falls >= 12 && !plan.crumble().isEmpty() && accepted.seed() == seed) {
                    return seed;
                }
            }
        }
        throw new IllegalStateException("no seed with lava falls");
    }

    /** Blocks in memory, with what the service did to them. */
    private static final class Hazards implements HazardBlocks {

        final FakeBlocks blocks;
        final Set<Long> started = new HashSet<>();
        int calls;
        int debris;
        boolean debrisRemoved;

        Hazards(FakeBlocks blocks) {
            this.blocks = blocks;
        }

        @Override
        public Material get(int x, int y, int z) {
            return blocks.get(x, y, z);
        }

        @Override
        public void startLava(int x, int y, int z) {
            calls++;
            started.add(HazardService.pack(x, y, z));
            blocks.set(x, y, z, Material.LAVA);
        }

        @Override
        public void stopLava(int x, int y, int z) {
            calls++;
            if (blocks.get(x, y, z) == Material.LAVA) {
                blocks.set(x, y, z, Material.AIR);
            }
        }

        @Override
        public void removeLava(int x, int y, int z) {
            calls++;
            if (blocks.get(x, y, z) == Material.LAVA) {
                blocks.set(x, y, z, Material.AIR);
            }
        }

        @Override
        public void restore(int x, int y, int z, Material material) {
            calls++;
            if (blocks.get(x, y, z) == Material.AIR) {
                blocks.set(x, y, z, material);
            }
        }

        @Override
        public boolean collapse(int x, int y, int z, Material material) {
            calls++;
            if (blocks.get(x, y, z) != material) {
                return false;
            }
            blocks.set(x, y, z, Material.AIR);
            debris++;
            return true;
        }

        @Override
        public void removeDebris() {
            debrisRemoved = true;
        }
    }

    private HazardService service(HazardSettings settings) {
        HazardService service = new HazardService(maps::placed, writer, hazards, settings, Runnable::run,
                Runnable::run, () -> now, quiet());
        maps.onClearing(service::forget);
        service.setFeet(player -> player.equals(ALICE) && feet != null ? OptionalLong.of(feet) : OptionalLong.empty());
        service.setWarnings(new HazardService.Warnings() {
            @Override
            public void lava(MatchView match) {
                warned.add("lava " + match.arena());
            }

            @Override
            public void crack(UUID player) {
                warned.add("crack " + player);
            }
        });
        return service;
    }

    private static MatchView match(String arena) {
        return (MatchView) Proxy.newProxyInstance(MatchView.class.getClassLoader(), new Class<?>[] {MatchView.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "arena" -> arena;
                    case "alivePlayers" -> List.of(ALICE);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "match in " + arena;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private GeneratedMaps.Placed place(String name, long seed) {
        if (maps.cells().isEmpty()) {
            maps.resize(2);
            drain();
        }
        List<GeneratedMaps.Prepared> outcomes = new ArrayList<>();
        maps.prepare(name, new GeneratedMaps.Request(seed, DIFFICULTY, 2), outcomes::add);
        drain();
        assertInstanceOf(GeneratedMaps.Ready.class, outcomes.getLast());
        return maps.placed(name).orElseThrow();
    }

    /** Works the writer until it is idle; returns the most hazard changes made in one tick. */
    private int drain() {
        int most = 0;
        for (int i = 0; i < 10_000 && writer.pending() > 0; i++) {
            int before = hazards.calls;
            writer.tick();
            most = Math.max(most, hazards.calls - before);
        }
        assertEquals(0, writer.pending());
        return most;
    }

    private static void phase(HazardService service, MatchView match, MatchPhase from, MatchPhase to) {
        service.onPhase(new MatchPhaseChangeEvent(match, from, to));
    }

    private static List<HazardService.Source> open(HazardService.WorldPlan plan, int count) {
        return plan.sources().stream().filter(source -> source.kind() == HazardPlan.Kind.FALL).limit(count).toList();
    }

    @Test
    void fallsFlowWhenTheFightStartsAndOnlyInsideTheirFootprintsAFewChangesATick() {
        GeneratedMaps.Placed placed = place("generated:1", SEED);
        HazardService.WorldPlan plan = HazardService.worldPlan(placed);
        HazardService service = service(HazardSettings.DEFAULTS);
        MatchView match = match("generated:1");
        phase(service, match, MatchPhase.WAITING, MatchPhase.PREP);
        assertTrue(service.isPlanned(placed.arena().cell()));
        drain();
        assertFalse(service.allows(0, 0, 0, 0, -1, 0));
        HazardService.Source first = open(plan, 1).getFirst();
        assertFalse(service.allows(first.x(), first.y(), first.z(), first.x(), first.y() - 1, first.z()),
                "nothing flows before the fight");

        phase(service, match, MatchPhase.PREP, MatchPhase.COMBAT);
        long falls = plan.sources().stream().filter(source -> source.kind() == HazardPlan.Kind.FALL).count();
        assertEquals(Math.min(falls, HazardSettings.MAX_SOURCES_PER_MAP), service.openSources(placed.arena().cell()));
        assertEquals(List.of("lava generated:1"), warned);
        int changes = open(plan, Integer.MAX_VALUE).stream().mapToInt(source -> source.column().length + 1).sum();
        assertTrue(changes > 2 * CellWriter.MAX_CHANGES_PER_TICK, "enough changes to need several ticks: " + changes);
        int most = drain();
        assertTrue(most <= CellWriter.MAX_CHANGES_PER_TICK, "at most 64 changes a tick: " + most);
        assertTrue(most > 0);
        assertEquals(Math.min(falls, HazardSettings.MAX_SOURCES_PER_MAP), hazards.started.size());

        ArenaBounds bounds = placed.arena().bounds();
        ArenaLayout layout = placed.layout();
        for (HazardService.Source source : open(plan, Integer.MAX_VALUE)) {
            assertEquals(Material.LAVA, blocks.get(source.x(), source.y(), source.z()), "the head flows");
            for (long cell : source.column()) {
                assertEquals(Material.AIR, blocks.get(HazardService.unpackX(cell), HazardService.unpackY(cell),
                        HazardService.unpackZ(cell)), "the still column made way for the flow");
            }
            long previous = source.at();
            for (long cell : source.footprint()) {
                int x = HazardService.unpackX(cell);
                int y = HazardService.unpackY(cell);
                int z = HazardService.unpackZ(cell);
                assertTrue(bounds.contains(x, y, z));
                assertTrue(HazardPlan.inHazardZone(layout, x - placed.originX(), y - placed.originY(),
                        z - placed.originZ()), "never a spawn or the centre");
                assertTrue(service.allows(HazardService.unpackX(previous), HazardService.unpackY(previous),
                        HazardService.unpackZ(previous), x, y, z) || previous == cell);
                previous = cell;
            }
        }
        // Out of the footprint, out of the map, and anywhere else in the world: cancelled.
        assertFalse(service.allows(first.x(), first.y(), first.z(), first.x(), first.y() + 1, first.z()));
        assertFalse(service.allows(first.x(), first.y(), first.z(), bounds.maxX() + 1, first.y(), first.z()));
        assertFalse(service.allows(bounds.maxX() + 1, first.y(), first.z(), bounds.maxX() + 2, first.y(), first.z()));
    }

    @Test
    void theSourceCapsHoldInEachMapAndAcrossMaps() {
        GeneratedMaps.Placed one = place("generated:1", SEED);
        GeneratedMaps.Placed two = place("generated:2", SEED);
        HazardService service = service(new HazardSettings(5, 6, 300));
        MatchView first = match("generated:1");
        MatchView second = match("generated:2");
        service.prepareRound(first);
        service.prepareRound(second);
        service.startFight(first);
        assertEquals(4, service.openSources(one.arena().cell()), "5 rounds down to the same number on both sides");
        service.startFight(second);
        assertEquals(2, service.openSources(two.arena().cell()), "only 6 across every map");
        assertEquals(6, service.openSources());
        assertEquals(0, service.openLava(second, HazardPlan.Kind.FALL, 10));
        assertEquals(0, service.openLava(first, HazardPlan.Kind.HANGING, 10));
        drain();
        assertEquals(6, hazards.started.size());

        service.stopRound(first);
        assertEquals(2, service.openSources());
        assertEquals(2, service.openLava(second, HazardPlan.Kind.FALL, 3), "room again once a map stops");

        HazardService none = service(new HazardSettings(0, 1024, 300));
        none.prepareRound(first);
        none.startFight(first);
        assertEquals(0, none.openSources(), "0 keeps all lava still");
    }

    @Test
    void theRoundEndStopsAllFlowAndTakesEverySourceAwayAndTheNextRoundPutsThemBack() {
        GeneratedMaps.Placed placed = place("generated:1", SEED);
        HazardService.WorldPlan plan = HazardService.worldPlan(placed);
        HazardService service = service(HazardSettings.DEFAULTS);
        MatchView match = match("generated:1");
        phase(service, match, MatchPhase.WAITING, MatchPhase.PREP);
        phase(service, match, MatchPhase.PREP, MatchPhase.COMBAT);
        phase(service, match, MatchPhase.COMBAT, MatchPhase.OVERRUN);
        drain();
        HazardService.Source source = open(plan, 1).getFirst();
        assertTrue(service.allows(source.x(), source.y(), source.z(), source.x(), source.y() - 1, source.z()),
                "OVERRUN keeps the fight on");

        phase(service, match, MatchPhase.OVERRUN, MatchPhase.ROUND_END);
        assertFalse(service.allows(source.x(), source.y(), source.z(), source.x(), source.y() - 1, source.z()),
                "nothing flows the moment the round ends");
        assertEquals(0, service.openSources());
        assertTrue(drain() <= CellWriter.MAX_CHANGES_PER_TICK);
        for (HazardService.Source stopped : open(plan, Integer.MAX_VALUE)) {
            assertEquals(Material.AIR, blocks.get(stopped.x(), stopped.y(), stopped.z()), "every source is gone");
        }

        phase(service, match, MatchPhase.ROUND_END, MatchPhase.SHOP);
        phase(service, match, MatchPhase.SHOP, MatchPhase.PREP);
        drain();
        for (HazardService.Source back : open(plan, Integer.MAX_VALUE)) {
            assertEquals(Material.LAVA, blocks.get(back.x(), back.y(), back.z()), "the heads are back, still");
        }
        assertFalse(service.allows(source.x(), source.y(), source.z(), source.x(), source.y() - 1, source.z()));
        phase(service, match, MatchPhase.PREP, MatchPhase.COMBAT);
        assertTrue(service.allows(source.x(), source.y(), source.z(), source.x(), source.y() - 1, source.z()));
    }

    @Test
    void theMatchEndStopsAllFlowAndTakesEverySourceAway() {
        GeneratedMaps.Placed placed = place("generated:1", SEED);
        HazardService.WorldPlan plan = HazardService.worldPlan(placed);
        HazardService service = service(HazardSettings.DEFAULTS);
        MatchView match = match("generated:1");
        service.prepareRound(match);
        service.startFight(match);
        drain();
        service.onMatchEnd(new MatchEndEvent(match, null));
        assertEquals(0, service.openSources());
        drain();
        for (HazardService.Source source : open(plan, Integer.MAX_VALUE)) {
            assertEquals(Material.AIR, blocks.get(source.x(), source.y(), source.z()));
            assertFalse(service.allows(source.x(), source.y(), source.z(), source.x(), source.y() - 1, source.z()));
        }
        assertEquals(0, service.openLava(match, HazardPlan.Kind.FALL, 2), "nothing starts after the end");
    }

    @Test
    void freeingTheSlotDropsEverythingAndItsClearRemovesTheLava() {
        GeneratedMaps.Placed placed = place("generated:1", SEED);
        HazardService.WorldPlan plan = HazardService.worldPlan(placed);
        HazardService service = service(HazardSettings.DEFAULTS);
        MatchView match = match("generated:1");
        service.prepareRound(match);
        service.startFight(match);
        writer.tick();
        GridCell cell = placed.arena().cell();
        maps.free("generated:1");
        HazardService.Source source = open(plan, 1).getFirst();
        assertFalse(service.allows(source.x(), source.y(), source.z(), source.x(), source.y() - 1, source.z()));
        assertEquals(0, service.openSources(cell));
        assertFalse(service.isPlanned(cell));
        int before = hazards.calls;
        drain();
        assertEquals(before, hazards.calls, "queued changes of a freed map do nothing");
        assertEquals(0, blocks.countSolid(maps.region(cell)), "the clear took the lava with everything else");
    }

    @Test
    void aSourceIsTakenAwayAfterTheFlowTimeLimit() {
        GeneratedMaps.Placed placed = place("generated:1", SEED);
        HazardService.WorldPlan plan = HazardService.worldPlan(placed);
        HazardService service = service(new HazardSettings(256, 1024, 2));
        MatchView match = match("generated:1");
        service.prepareRound(match);
        service.startFight(match);
        drain();
        int open = service.openSources();
        assertTrue(open > 0);
        now += 39;
        service.tick();
        assertEquals(open, service.openSources());
        now += 1;
        service.tick();
        assertEquals(0, service.openSources(), "2 seconds of flow and no more");
        drain();
        HazardService.Source source = open(plan, 1).getFirst();
        assertEquals(Material.AIR, blocks.get(source.x(), source.y(), source.z()));
        assertFalse(service.allows(source.x(), source.y(), source.z(), source.x(), source.y() - 1, source.z()));
    }

    @Test
    void aCrumblingBlockCollapsesUnderAFightingPlayerAfterAWarningAndComesBackNextRound() {
        GeneratedMaps.Placed placed = place("generated:1", SEED);
        HazardService.WorldPlan plan = HazardService.worldPlan(placed);
        Map.Entry<Long, Material> crumble = plan.crumble().entrySet().iterator().next();
        long at = crumble.getKey();
        int x = HazardService.unpackX(at);
        int y = HazardService.unpackY(at);
        int z = HazardService.unpackZ(at);
        assertEquals(crumble.getValue(), blocks.get(x, y, z));
        HazardService service = service(HazardSettings.DEFAULTS);
        MatchView match = match("generated:1");
        service.prepareRound(match);
        feet = at;
        service.tick();
        assertEquals(List.of(), warned, "nothing cracks before the fight");

        service.startFight(match);
        warned.clear();
        drain();
        feet = HazardService.pack(x, placed.arena().bounds().maxY() + 10, z);
        service.tick();
        assertEquals(List.of(), warned, "only crumbling blocks crack");
        feet = at;
        service.tick();
        service.tick();
        assertEquals(List.of("crack " + ALICE), warned, "one warning per block");
        now += HazardService.COLLAPSE_DELAY_TICKS - 1;
        service.tick();
        drain();
        assertEquals(crumble.getValue(), blocks.get(x, y, z), "not before the delay");
        now += 1;
        service.tick();
        drain();
        assertEquals(Material.AIR, blocks.get(x, y, z));
        assertEquals(1, hazards.debris);

        service.stopRound(match);
        service.prepareRound(match);
        drain();
        assertEquals(crumble.getValue(), blocks.get(x, y, z), "put back for the next round");

        // A crack the round end comes before never collapses.
        service.startFight(match);
        feet = at;
        service.tick();
        service.stopRound(match);
        now += HazardService.COLLAPSE_DELAY_TICKS;
        service.tick();
        drain();
        assertEquals(crumble.getValue(), blocks.get(x, y, z));
        assertEquals(1, hazards.debris);
    }

    @Test
    void aBlockWaitingInABackedUpQueueIsWarnedAndCollapsedOnce() {
        GeneratedMaps.Placed placed = place("generated:1", SEED);
        HazardService.WorldPlan plan = HazardService.worldPlan(placed);
        Map.Entry<Long, Material> crumble = plan.crumble().entrySet().iterator().next();
        long at = crumble.getKey();
        HazardService service = service(HazardSettings.DEFAULTS);
        MatchView match = match("generated:1");
        service.prepareRound(match);
        service.startFight(match);
        warned.clear();
        // The falls' starts are queued ahead of the collapse and not worked yet.
        assertTrue(writer.pending() > 0);
        feet = at;
        service.tick();
        now += HazardService.COLLAPSE_DELAY_TICKS;
        service.tick();
        for (int i = 0; i < 3 * HazardService.COLLAPSE_DELAY_TICKS; i++) {
            now++;
            service.tick();
        }
        assertEquals(crumble.getValue(), blocks.get(HazardService.unpackX(at), HazardService.unpackY(at),
                HazardService.unpackZ(at)), "the collapse is still waiting in the queue");
        drain();
        service.tick();
        drain();
        assertEquals(List.of("crack " + ALICE), warned, "one warning");
        assertEquals(1, hazards.debris, "one collapse");
        assertEquals(Material.AIR, blocks.get(HazardService.unpackX(at), HazardService.unpackY(at),
                HazardService.unpackZ(at)));
    }

    @Test
    void handBuiltArenasAreNeverTouched() {
        place("generated:1", SEED);
        HazardService service = service(HazardSettings.DEFAULTS);
        MatchView match = match("dojo");
        int before = hazards.calls;
        phase(service, match, MatchPhase.WAITING, MatchPhase.PREP);
        phase(service, match, MatchPhase.PREP, MatchPhase.COMBAT);
        feet = HazardService.pack(0, 64, 0);
        service.tick();
        assertEquals(0, writer.pending());
        assertEquals(0, service.openSources());
        assertEquals(0, service.openLava(match, HazardPlan.Kind.FALL, 10));
        assertEquals(before, hazards.calls);
        assertEquals(List.of(), warned);
        assertFalse(service.allows(0, 64, 0, 0, 63, 0));
    }

    @Test
    void closeRemovesTheLavaOfEveryFootprintAtOnce() {
        GeneratedMaps.Placed placed = place("generated:1", SEED);
        HazardService.WorldPlan plan = HazardService.worldPlan(placed);
        HazardService service = service(HazardSettings.DEFAULTS);
        MatchView match = match("generated:1");
        service.prepareRound(match);
        service.startFight(match);
        drain();
        HazardService.Source source = open(plan, 1).getFirst();
        // Flowed lava, as the server would have it.
        for (long cell : source.footprint()) {
            blocks.set(HazardService.unpackX(cell), HazardService.unpackY(cell), HazardService.unpackZ(cell),
                    Material.LAVA);
        }
        service.close();
        for (long cell : source.footprint()) {
            assertEquals(Material.AIR, blocks.get(HazardService.unpackX(cell), HazardService.unpackY(cell),
                    HazardService.unpackZ(cell)));
        }
        assertTrue(hazards.debrisRemoved);
        assertFalse(service.allows(source.x(), source.y(), source.z(), source.x(), source.y() - 1, source.z()));
        service.prepareRound(match);
        service.startFight(match);
        assertEquals(0, service.openSources(), "nothing starts once closed");
    }

    @Test
    void positionsPackAndUnpackAcrossTheWorld() {
        for (int[] at : new int[][] {{0, 0, 0}, {-1, -64, -1}, {30_000_000, 319, -30_000_000}, {-5, 2047, 7},
                {12345, -2048, -54321}}) {
            long packed = HazardService.pack(at[0], at[1], at[2]);
            assertEquals(at[0], HazardService.unpackX(packed));
            assertEquals(at[1], HazardService.unpackY(packed));
            assertEquals(at[2], HazardService.unpackZ(packed));
        }
    }
}
