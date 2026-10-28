package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.director.Intensity.Inputs;
import com.ninja6.ninjaarena.director.Intensity.Stage;
import com.ninja6.ninjaarena.match.MatchPhase;
import org.junit.jupiter.api.Test;

class IntensityTest {

    private static final double DELTA = 1e-9;

    /** The defaults with the time ramp, team and damage terms off, so the cycle shows alone. */
    private static final IntensitySettings CYCLE_ONLY = new IntensitySettings(40, 20, 10, 0.6, 0.1, 0.25, 0, 0, 10,
            20, 0, 0.25, 0.25, 2);

    private static Inputs combat(int round, int seconds) {
        return new Inputs(MatchPhase.COMBAT, round, seconds, 240, 2, 2, 0);
    }

    @Test
    void stagesFollowTheCycle() {
        var settings = IntensitySettings.DEFAULTS;
        assertEquals(Stage.BUILD_UP, Intensity.stage(settings, 0));
        assertEquals(Stage.BUILD_UP, Intensity.stage(settings, 39));
        assertEquals(Stage.PEAK, Intensity.stage(settings, 40));
        assertEquals(Stage.PEAK, Intensity.stage(settings, 59));
        assertEquals(Stage.RELAX, Intensity.stage(settings, 60));
        assertEquals(Stage.RELAX, Intensity.stage(settings, 69));
        assertEquals(Stage.BUILD_UP, Intensity.stage(settings, 70));
        assertEquals(Stage.PEAK, Intensity.stage(settings, 110));
        assertEquals(Stage.BUILD_UP, Intensity.stage(settings, -5));
    }

    @Test
    void buildUpRisesEvenlyFromRelaxToPeakThenHoldsAndDrops() {
        assertEquals(0.15, Intensity.cycle(CYCLE_ONLY, 1, 0), DELTA);
        assertEquals(0.375, Intensity.cycle(CYCLE_ONLY, 1, 20), DELTA);
        assertEquals(0.6, Intensity.cycle(CYCLE_ONLY, 1, 40), DELTA);
        assertEquals(0.6, Intensity.cycle(CYCLE_ONLY, 1, 59), DELTA);
        assertEquals(0.15, Intensity.cycle(CYCLE_ONLY, 1, 60), DELTA);
        assertEquals(0.15, Intensity.cycle(CYCLE_ONLY, 1, 70), DELTA);
        double previous = -1;
        for (int second = 0; second < 40; second++) {
            double level = Intensity.cycle(CYCLE_ONLY, 1, second);
            assertTrue(level > previous, "build-up rises at second " + second);
            previous = level;
        }
        assertEquals(Intensity.target(CYCLE_ONLY, combat(1, 25)), Intensity.cycle(CYCLE_ONLY, 1, 25), DELTA);
    }

    @Test
    void everyRoundPeaksAndRelaxesHigherUntilTheCap() {
        double peak = 0;
        double relax = 0;
        for (int round = 1; round <= 5; round++) {
            double roundPeak = Intensity.target(CYCLE_ONLY, combat(round, 45));
            double roundRelax = Intensity.target(CYCLE_ONLY, combat(round, 65));
            assertTrue(roundPeak > peak, "round " + round + " peaks higher");
            assertTrue(roundRelax > relax, "round " + round + " relaxes higher");
            assertTrue(roundRelax < roundPeak);
            peak = roundPeak;
            relax = roundRelax;
        }
        assertEquals(1, peak, DELTA);
        assertEquals(1, Intensity.target(CYCLE_ONLY, combat(9, 45)), DELTA);
    }

    @Test
    void overrunIsMaximumAndOtherPhasesAreZero() {
        var settings = IntensitySettings.DEFAULTS;
        assertEquals(1, Intensity.target(settings, new Inputs(MatchPhase.OVERRUN, 1, 300, 240, 1, 2, 1000)));
        for (MatchPhase phase : new MatchPhase[] {MatchPhase.WAITING, MatchPhase.PREP, MatchPhase.ROUND_END,
                MatchPhase.MATCH_END}) {
            assertEquals(0, Intensity.target(settings, new Inputs(phase, 3, 50, 240, 2, 2, 0)), phase.name());
        }
        assertEquals(0, Intensity.target(settings, new Inputs(MatchPhase.COMBAT, 3, 50, 240, 0, 2, 0)));
    }

    @Test
    void combatTimeRampsTowardsTheEndOfTheRoundTimer() {
        var settings = new IntensitySettings(40, 20, 10, 0.6, 0.1, 0.25, 0.2, 0, 10, 20, 0, 0.25, 0.25, 2);
        // Seconds 0, 70, 140 and 210 all start a build-up, so only the ramp differs.
        assertEquals(0.15, Intensity.target(settings, combat(1, 0)), DELTA);
        assertEquals(0.15 + 0.2 * 140 / 240, Intensity.target(settings, combat(1, 140)), DELTA);
        assertEquals(0.15 + 0.2 * 210 / 240, Intensity.target(settings, combat(1, 210)), DELTA);
        assertEquals(0.15 + 0.2, Intensity.target(settings, new Inputs(MatchPhase.COMBAT, 1, 280, 240, 2, 2, 0)),
                DELTA);
    }

    @Test
    void fewerTeamsAliveLowerTheIntensityByItsWeight() {
        var settings = new IntensitySettings(40, 20, 10, 0.6, 0.1, 0.25, 0, 0.5, 10, 20, 0, 0.25, 0.25, 2);
        assertEquals(0.6, Intensity.target(settings, new Inputs(MatchPhase.COMBAT, 1, 45, 240, 4, 4, 0)), DELTA);
        assertEquals(0.6 * 0.75, Intensity.target(settings, new Inputs(MatchPhase.COMBAT, 1, 45, 240, 2, 4, 0)),
                DELTA);
        assertEquals(0.6 * 0.625, Intensity.target(settings, new Inputs(MatchPhase.COMBAT, 1, 45, 240, 1, 4, 0)),
                DELTA);
        // Teams vs enemies: one starting team, alive.
        assertEquals(0.6, Intensity.target(settings, new Inputs(MatchPhase.COMBAT, 1, 45, 240, 1, 1, 0)), DELTA);
    }

    @Test
    void recentDamageEasesThePressureUpToTheRelief() {
        var settings = new IntensitySettings(40, 20, 10, 0.6, 0.1, 0.25, 0, 0, 10, 20, 0.5, 0.25, 0.25, 2);
        assertEquals(0.6, Intensity.target(settings, damaged(0)), DELTA);
        assertEquals(0.6 * 0.75, Intensity.target(settings, damaged(10)), DELTA);
        assertEquals(0.3, Intensity.target(settings, damaged(20)), DELTA);
        assertEquals(0.3, Intensity.target(settings, damaged(500)), DELTA);
        assertEquals(0.6, Intensity.target(settings, damaged(-4)), DELTA);
        assertEquals(0.6, Intensity.target(settings, damaged(Double.NaN)), DELTA);
    }

    @Test
    void neverLeavesZeroToOne() {
        var settings = new IntensitySettings(40, 20, 10, 1, 1, 1, 1, 0, 10, 20, 0, 0.25, 0.25, 2);
        assertEquals(1, Intensity.target(settings, combat(5, 239)), DELTA);
    }

    private static Inputs damaged(double perPlayer) {
        return new Inputs(MatchPhase.COMBAT, 1, 45, 240, 2, 2, perPlayer);
    }
}
