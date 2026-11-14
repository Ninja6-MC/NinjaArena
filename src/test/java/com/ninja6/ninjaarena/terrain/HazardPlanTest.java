package com.ninja6.ninjaarena.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HazardPlanTest {

    private static boolean lava(BlockKind kind) {
        return kind == BlockKind.LAVA_SOURCE || kind == BlockKind.LAVA || kind == BlockKind.HANGING_LAVA;
    }

    /** Whether the map cell is over a spawn zone or the centre, at any height. */
    private static boolean spawnOrCentre(ArenaLayout layout, ArenaLayout.Position cell) {
        if (layout.centreBounds().contains(cell.x(), 0, cell.z())) {
            return true;
        }
        SideDimensions size = layout.side().dimensions();
        for (int team = 1; team <= layout.teamCount(); team++) {
            ArenaLayout.Position local = layout.toSide(team, cell.x(), cell.y(), cell.z());
            if (local.x() >= 0 && local.x() < size.width() && local.z() >= 0 && local.z() < size.length()
                    && (local.z() < size.spawnDepth() + size.blendDepth()
                    || local.z() >= size.length() - size.centreDepth() - size.blendDepth())) {
                return true;
            }
        }
        return false;
    }

    private static boolean[] sideLava(ArenaLayout layout, HazardPlan plan) {
        SideDimensions size = layout.side().dimensions();
        boolean[] lava = new boolean[size.volume()];
        for (HazardPlan.LavaSource source : plan.sources()) {
            if (source.team() != 1) {
                continue;
            }
            for (ArenaLayout.Position cell : source.footprint()) {
                ArenaLayout.Position local = layout.toSide(1, cell.x(), cell.y(), cell.z());
                lava[(local.z() * size.width() + local.x()) * size.height() + local.y()] = true;
            }
        }
        return lava;
    }

    @Test
    void lavaNeverReachesOutsideTheMapASpawnOrTheCentreAndCrossingStillHolds() {
        int maps = 0;
        int sources = 0;
        for (int difficulty = 1; difficulty <= TerrainGenerator.MAX_DIFFICULTY; difficulty++) {
            for (long seed = 0; seed < 6; seed++) {
                int teams = seed % 2 == 0 ? 2 : 4;
                MapSearch.Outcome outcome = MapSearch.search(seed * 7919 + difficulty, difficulty, teams);
                if (!(outcome instanceof MapSearch.Accepted accepted)) {
                    continue;
                }
                ArenaLayout layout = accepted.layout();
                MovementSolver solver = MovementSolver.solve(layout.side(), MovementModel.DEFAULT);
                HazardPlan plan = HazardPlan.of(layout, solver);
                maps++;
                assertEquals(teams, plan.teams());
                assertEquals(0, plan.sources().size() % teams, "the same sources on every side");
                for (HazardPlan.LavaSource source : plan.sources()) {
                    sources++;
                    assertEquals(source.source(), source.footprint().getFirst());
                    BlockKind head = layout.at(source.source().x(), source.source().y(), source.source().z());
                    assertEquals(source.kind() == HazardPlan.Kind.FALL ? BlockKind.LAVA_SOURCE : BlockKind.HANGING_LAVA,
                            head);
                    for (ArenaLayout.Position cell : source.footprint()) {
                        assertTrue(layout.bounds().contains(cell.x(), cell.y(), cell.z()), "inside the map: " + cell);
                        assertTrue(HazardPlan.inHazardZone(layout, cell.x(), cell.y(), cell.z()), cell.toString());
                        assertFalse(spawnOrCentre(layout, cell), "never over a spawn or the centre: " + cell);
                        assertFalse(layout.at(cell.x(), cell.y(), cell.z()).solid(), "lava fills only open cells");
                    }
                }
                for (HazardPlan.Crumble crumble : plan.crumble()) {
                    ArenaLayout.Position at = crumble.block();
                    assertEquals(crumble.kind(), layout.at(at.x(), at.y(), at.z()));
                    assertTrue(HazardPlan.inHazardZone(layout, at.x(), at.y(), at.z()));
                }
                // Every source flowing at once, every crumbling block gone: still crossable.
                assertTrue(solver.crossableWithLava(sideLava(layout, plan)), "seed " + accepted.seed());
                assertEquals(plan.sources(), HazardPlan.of(layout, MovementModel.DEFAULT).sources(),
                        "the same layout gives the same plan");
            }
        }
        assertTrue(maps >= 10, "enough maps checked: " + maps);
        assertTrue(sources > 0, "some maps have lava that flows");
    }

    @Test
    void newLavaKeepsOffTheRouteAndTheEnemySpawns() {
        for (long seed = 0; seed < 8; seed++) {
            MapSearch.Outcome outcome = MapSearch.search(seed * 104_729 + 3, 4, 2);
            if (!(outcome instanceof MapSearch.Accepted accepted)) {
                continue;
            }
            ArenaLayout layout = accepted.layout();
            TerrainModel side = layout.side();
            SideDimensions size = side.dimensions();
            MovementSolver solver = MovementSolver.solve(side, MovementModel.DEFAULT);
            boolean[] route = solver.firmRouteSpace(HazardPlan.ROUTE_MARGIN);
            boolean[] lava = sideLava(layout, HazardPlan.of(layout, solver));
            for (int i = 0; i < lava.length; i++) {
                int y = i % size.height();
                int x = i / size.height() % size.width();
                int z = i / size.height() / size.width();
                if (lava[i] && !lava(side.at(x, y, z))) {
                    assertFalse(route[i], "new lava in the route's space at " + x + ", " + y + ", " + z);
                    for (ArenaLayout.Position enemy : solver.enemySpawns()) {
                        assertFalse(Math.abs(enemy.x() - x) <= 1 && Math.abs(enemy.z() - z) <= 1
                                && y >= enemy.y() - 1 && y <= enemy.y() + 2, "new lava beside an enemy spawn");
                    }
                }
            }
        }
    }

    /** A ceiling over the middle of the side, with lava held in it above and beside the route. */
    private static TestSide heldLava() {
        return new TestSide()
                .fill(0, 16, 4, TestSide.WIDTH - 1, 17, 12, BlockKind.ROCK)
                .fill(TestSide.spawnX(), 16, 11, TestSide.spawnX(), 16, 11, BlockKind.HANGING_LAVA)
                .fill(1, 16, 5, 1, 16, 5, BlockKind.HANGING_LAVA);
    }

    @Test
    void lavaThatWouldFlowOverTheRouteIsLeftOutAndLavaBesideItKept() {
        TerrainModel side = heldLava().build();
        ArenaLayout layout = new ArenaLayout(side, 2);
        MovementSolver solver = MovementSolver.solve(side, MovementModel.DEFAULT);
        assertTrue(solver.report().crossableWithoutCrumble());
        HazardPlan plan = HazardPlan.of(layout, solver);
        List<ArenaLayout.Position> heads = plan.sources().stream().filter(source -> source.team() == 1)
                .map(HazardPlan.LavaSource::source).toList();
        assertEquals(List.of(layout.toMap(1, 1, 16, 5)), heads);
        assertEquals(2, plan.sources().size(), "one for each team");
        HazardPlan.LavaSource kept = plan.sources().getFirst();
        assertEquals(HazardPlan.Kind.HANGING, kept.kind());
        // Down to the ground, then a pool of reach 3 kept out of the spawn's blend rows.
        assertTrue(kept.footprint().contains(layout.toMap(1, 1, TestSide.GROUND + 1, 5)));
        assertTrue(kept.footprint().contains(layout.toMap(1, 4, TestSide.GROUND + 1, 5)));
        assertFalse(kept.footprint().contains(layout.toMap(1, 5, TestSide.GROUND + 1, 5)), "lava spreads 3 blocks");
        assertFalse(kept.footprint().contains(layout.toMap(1, 1, TestSide.GROUND + 1, 3)), "not into the blend rows");
        assertFalse(kept.footprint().contains(layout.toMap(1, 1, TestSide.GROUND, 5)), "never into the ground");
        assertTrue(solver.crossableWithLava(sideLava(layout, plan)));
    }

    @Test
    void aPoolOverADropFallsToTheNextFloorAndStops() {
        // A pit two blocks deep just beside where the lava lands.
        TerrainModel side = heldLava().fill(3, TestSide.GROUND - 1, 4, 5, TestSide.GROUND, 6, BlockKind.AIR).build();
        ArenaLayout layout = new ArenaLayout(side, 2);
        HazardPlan.LavaSource kept = HazardPlan.of(layout, MovementModel.DEFAULT).sources().getFirst();
        assertTrue(kept.footprint().contains(layout.toMap(1, 3, TestSide.GROUND + 1, 5)));
        assertTrue(kept.footprint().contains(layout.toMap(1, 3, TestSide.GROUND - 1, 5)), "falls into the pit");
        assertFalse(kept.footprint().contains(layout.toMap(1, 4, TestSide.GROUND + 1, 5)),
                "the pool does not spread on past a drop");
        assertFalse(kept.footprint().contains(layout.toMap(1, 4, TestSide.GROUND - 1, 5)),
                "nor across the floor it falls to");
    }

    @Test
    void aSideWithoutARouteKeepsNoLava() {
        // A wall the full height across the side: nothing crosses.
        TerrainModel side = heldLava().fill(0, 0, 14, TestSide.WIDTH - 1, TestSide.HEIGHT - 1, 14, BlockKind.ROCK)
                .build();
        MovementSolver solver = MovementSolver.solve(side, MovementModel.DEFAULT);
        assertFalse(solver.report().crossable());
        HazardPlan plan = HazardPlan.of(new ArenaLayout(side, 2), solver);
        assertEquals(List.of(), plan.sources());
        assertFalse(solver.crossableWithLava(new boolean[side.dimensions().volume()]));
    }

    @Test
    void theHazardZoneIsTheSidesBetweenTheirBlends() {
        TerrainModel side = new TestSide().build();
        SideDimensions size = side.dimensions();
        for (int teams : new int[] {2, 4}) {
            ArenaLayout layout = new ArenaLayout(side, teams);
            for (int team = 1; team <= teams; team++) {
                for (int z = 0; z < size.length(); z++) {
                    ArenaLayout.Position cell = layout.toMap(team, 3, 5, z);
                    assertEquals(size.hazardRow(z), HazardPlan.inHazardZone(layout, cell.x(), cell.y(), cell.z()));
                }
            }
            ArenaLayout.Bounds centre = layout.centreBounds();
            assertFalse(HazardPlan.inHazardZone(layout, centre.minX(), 5, centre.minZ()));
            assertFalse(HazardPlan.inHazardZone(layout, -1, 5, 0));
            assertFalse(HazardPlan.inHazardZone(layout, layout.width(), 5, 0));
            ArenaLayout.Position top = layout.toMap(1, 3, 5, size.length() / 2);
            assertFalse(HazardPlan.inHazardZone(layout, top.x(), layout.height(), top.z()), "above the map");
        }
        // Spawn and blend rows, then the hazard rows, then the centre's blend and ledge.
        assertFalse(size.hazardRow(size.spawnDepth() + size.blendDepth() - 1));
        assertTrue(size.hazardRow(size.spawnDepth() + size.blendDepth()));
        assertTrue(size.hazardRow(size.length() - size.centreDepth() - size.blendDepth() - 1));
        assertFalse(size.hazardRow(size.length() - size.centreDepth() - size.blendDepth()));
    }
}
