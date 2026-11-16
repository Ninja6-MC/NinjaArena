package com.ninja6.ninjaarena.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An accepted map stays crossable from the team spawn whichever crumbling blocks collapse,
 * in any combination, because its guaranteed route never relies on one.
 */
class CollapseCrossingTest {

    private static final int[] CRUMBLE_KINDS = {BlockKind.CRUMBLE.ordinal(), BlockKind.CRUMBLE_PLATFORM.ordinal()};

    /**
     * Single collapses that strand a team although the side is crossable intact and with every
     * crumbling block gone, so checking only those two would accept them. Found again after
     * the chasms changed every map at difficulties 4 and 5.
     */
    @Test
    void collapsesThatOnceStrandedATeamNoLongerDo() {
        record Case(long seed, int difficulty, int x, int y, int z) { }
        List<Case> cases = List.of(
                new Case(-3_294_968_442_888_373_315L, 4, 28, 20, 28),
                new Case(-7_573_232_559_504_912_326L, 4, 62, 24, 43),
                new Case(792_725_471_689_316_877L, 5, 22, 21, 49),
                new Case(-3_389_602_350_861_902_783L, 5, 19, 19, 30));
        for (Case c : cases) {
            TerrainModel side = TerrainGenerator.generate(c.seed(), c.difficulty());
            assertTrue(isCrumble(side.at(c.x(), c.y(), c.z()).ordinal()), c.toString());
            CrossingReport report = MovementSolver.solve(side, MovementModel.DEFAULT).report();
            if (!report.accepted(ChallengeMinimums.forDifficulty(c.difficulty()))) {
                assertTrue(report.problems(ChallengeMinimums.forDifficulty(c.difficulty()))
                        .contains(CrossingReport.Problem.NEEDS_CRUMBLE), c.toString());
                System.out.println(c + ": rejected, the crossing relies on crumbling blocks");
                continue;
            }
            byte[] cells = side.copyCells();
            cells[index(side, c.x(), c.y(), c.z())] = (byte) BlockKind.AIR.ordinal();
            assertTrue(MovementSolver.solve(rebuild(side, cells), MovementModel.DEFAULT).report().crossable(),
                    c.toString());
            System.out.println(c + ": accepted, still crossable with the block collapsed");
        }
    }

    @Test
    void acceptedMapsStayCrossableUnderRandomPartialCollapses() {
        int checked = 0;
        for (int difficulty = 2; difficulty <= TerrainGenerator.MAX_DIFFICULTY; difficulty++) {
            for (long seed = 0; seed < 40; seed++) {
                MapSearch.Outcome outcome = MapSearch.search(seed * 31 + difficulty, difficulty, 2);
                if (!(outcome instanceof MapSearch.Accepted map)) {
                    continue;
                }
                TerrainModel side = map.layout().side();
                byte[] original = side.copyCells();
                List<Integer> crumbling = new ArrayList<>();
                for (int i = 0; i < original.length; i++) {
                    if (isCrumble(original[i])) {
                        crumbling.add(i);
                    }
                }
                if (crumbling.isEmpty()) {
                    continue;
                }
                SplitMix64 random = new SplitMix64(side.seed());
                for (int trial = 0; trial < 6; trial++) {
                    // From a few blocks to nearly all of them, with every collapsed block chosen at random.
                    double share = (trial + 1) / 7.0;
                    byte[] cells = original.clone();
                    for (int i : crumbling) {
                        if (random.nextDouble() < share) {
                            cells[i] = (byte) BlockKind.AIR.ordinal();
                        }
                    }
                    CrossingReport after = MovementSolver.solve(rebuild(side, cells), MovementModel.DEFAULT).report();
                    assertTrue(after.crossable(), "seed " + side.seed() + " difficulty " + difficulty
                            + " trial " + trial);
                    assertEquals(map.report().crossableWithoutCrumble(), after.crossableWithoutCrumble());
                    checked++;
                }
            }
        }
        assertTrue(checked >= 100, "only " + checked + " partial collapses were checked");
        System.out.println(checked + " random partial collapses of accepted maps left every team able to cross");
    }

    private static boolean isCrumble(int ordinal) {
        return ordinal == CRUMBLE_KINDS[0] || ordinal == CRUMBLE_KINDS[1];
    }

    private static int index(TerrainModel side, int x, int y, int z) {
        SideDimensions size = side.dimensions();
        return (z * size.width() + x) * size.height() + y;
    }

    private static TerrainModel rebuild(TerrainModel side, byte[] cells) {
        return new TerrainModel(side.seed(), side.difficulty(), side.dimensions(), side.theme(), side.spawnSurfaceY(),
                side.centreSurfaceY(), cells);
    }
}
