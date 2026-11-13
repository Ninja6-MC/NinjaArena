package com.ninja6.ninjaarena.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.GridCell;
import com.ninja6.ninjaarena.director.StabilityGate;
import com.ninja6.ninjaarena.director.StabilityGate.Guard;
import com.ninja6.ninjaarena.director.StabilitySettings;
import com.ninja6.ninjaarena.director.TickTimeGuard;
import com.ninja6.ninjaarena.match.MatchView;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class PlacementBudgetTest {

    private static final long FULL = 10_000_000;
    private static final long SLOW = 1_000_000;
    /** Ten seconds of recovery, in ticks. */
    private static final int RAMP = 200;

    private int tick;
    private double mspt = 20;
    private int reads;
    private final TickTimeGuard guard = new TickTimeGuard(StabilitySettings.DEFAULTS, () -> tick, () -> {
        reads++;
        return mspt;
    }, quiet());
    private final PlacementBudget budget = new PlacementBudget(PlacementSettings.DEFAULTS, guard::read, () -> tick);

    private static Logger quiet() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }

    /** The budget on the next tick at this tick time. */
    private long next(double mspt) {
        this.mspt = mspt;
        tick++;
        return budget.getAsLong();
    }

    @Test
    void theFullBudgetWhileTheServerKeepsUp() {
        for (double at : new double[] {5, 20, 39.9, 40}) {
            assertEquals(FULL, next(at), at + " ms is not above the pause threshold");
        }
    }

    @Test
    void dropsToTheSlowBudgetAboveThePauseThresholdAndHoldsItUntilBelowResume() {
        assertEquals(FULL, next(20));
        assertEquals(SLOW, next(41), "above 40 ms");
        assertEquals(Guard.PAUSED, guard.guard());
        assertEquals(SLOW, next(38), "38 ms is under the pause threshold but not below resume");
        assertEquals(SLOW, next(35), "35 ms is not below resume");
        long first = next(34);
        assertEquals(Guard.NORMAL, guard.guard());
        assertEquals(SLOW + (FULL - SLOW) / RAMP, first, "the budget climbs back rather than jumping");
    }

    @Test
    void recoversInAStraightLineOverTheRecoveryTime() {
        next(41);
        long previous = SLOW;
        for (int i = 1; i < RAMP; i++) {
            long now = next(20);
            assertEquals(SLOW + (FULL - SLOW) * i / RAMP, now);
            assertTrue(now > previous);
            previous = now;
        }
        assertEquals(FULL, next(20), "back to full after the recovery time");
        assertEquals(FULL, next(39), "and it stays there below the pause threshold");
    }

    @Test
    void slowingAgainDuringRecoveryDropsToTheSlowBudgetAndStartsOver() {
        next(41);
        for (int i = 0; i < RAMP / 2; i++) {
            next(20);
        }
        assertEquals(SLOW, next(41));
        assertEquals(SLOW + (FULL - SLOW) / RAMP, next(20), "the recovery starts over");
    }

    @Test
    void aTierDropSlowsPlacementAndItsRecoveryPeriodLetsPlacementClimbBack() {
        assertEquals(SLOW, next(50), "above the drop-tier threshold");
        assertEquals(Guard.TIER_DROPPED, guard.guard());
        assertEquals(SLOW, next(41), "the tier stays dropped above the pause threshold");
        assertTrue(next(30) > SLOW);
        assertEquals(Guard.RECOVERING, guard.guard(), "spawning has resumed with the tier still lower");
    }

    @Test
    void aReadingThatIsNotANumberChangesNothing() {
        next(41);
        assertEquals(SLOW, next(Double.NaN));
        assertEquals(Guard.PAUSED, guard.guard());
    }

    @Test
    void aSlowBudgetOfZeroPausesPlacementAndNoRecoveryTimeReturnsAtOnce() {
        PlacementBudget instant = new PlacementBudget(new PlacementSettings(10, 0, 0, 256), guard::read, () -> tick);
        mspt = 41;
        tick++;
        assertEquals(0, instant.getAsLong());
        mspt = 20;
        tick++;
        assertEquals(FULL, instant.getAsLong());
    }

    @Test
    void enemySpawnsAndPlacementShareOneReadingPerTick() {
        StabilityGate gate = new StabilityGate(StabilitySettings.DEFAULTS, match -> 0, List::<MatchView>of,
                () -> tick, guard);
        tick = 1;
        mspt = 41;
        assertEquals(SLOW, budget.getAsLong());
        mspt = 20;
        assertFalse(gate.maySpawn(null), "the gate acts on the reading placement made this tick");
        assertEquals(Guard.PAUSED, gate.guard());
        assertEquals(1, reads);

        tick = 2;
        assertTrue(gate.maySpawn(null));
        assertTrue(budget.getAsLong() > SLOW, "placement acts on the reading the gate made");
        assertEquals(2, reads);
    }

    @Test
    void theWriterKeepsToTheShrunkBudgetWhileTheServerIsSlow() {
        long cost = 200;
        long[] idle = new long[1];
        BudgetedCellWriter[] holder = new BudgetedCellWriter[1];
        holder[0] = new BudgetedCellWriter(new FakeBlocks(), budget, () -> idle[0] + cost * holder[0].visited());
        BudgetedCellWriter writer = holder[0];
        writer.clear(new GridCell(0, 0), new ArenaBounds(0, 0, 0, 199, 199, 199));
        for (int i = 0; i < 300; i++) {
            mspt = i < 50 ? 20 : i < 100 ? 41 : 30;
            tick++;
            idle[0] += 50_000_000;
            // Asked again by the writer within the same tick: the same answer.
            long allowed = budget.getAsLong();
            long before = writer.visited();
            writer.tick();
            long used = cost * (writer.visited() - before);
            assertTrue(used <= allowed, "tick " + i + " used " + used + " of " + allowed);
            if (i >= 50 && i < 100) {
                assertTrue(used <= SLOW, "only the slow budget while the guard holds back");
            }
            if (i < 50 && i > 0) {
                assertTrue(used > FULL - cost, "the full budget while the server keeps up");
            }
        }
    }
}
