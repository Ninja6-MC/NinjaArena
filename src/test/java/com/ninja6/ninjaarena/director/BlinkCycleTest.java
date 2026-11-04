package com.ninja6.ninjaarena.director;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlinkCycleTest {

    /** Cooldown 8 s (160 ticks), tell 20 ticks. */
    private final BlinkCycle cycle = new BlinkCycle(StalkerSettings.DEFAULTS, 1000);

    @Test
    void aNewStalkerWaitsAFullCooldownBeforeItsFirstBlink() {
        assertFalse(cycle.ready(1000));
        assertFalse(cycle.ready(1159));
        assertTrue(cycle.ready(1160));
    }

    @Test
    void theTellRunsItsLengthAndTheBlinkStartsTheCooldown() {
        cycle.tell(1160);
        assertTrue(cycle.telling());
        assertFalse(cycle.ready(1160), "no second tell while one runs");
        assertFalse(cycle.tellOver(1179));
        assertTrue(cycle.tellOver(1180));
        cycle.blinked(1180);
        assertFalse(cycle.telling());
        assertFalse(cycle.tellOver(1180));
        assertFalse(cycle.ready(1339));
        assertTrue(cycle.ready(1340));
    }

    @Test
    void aMissedBlinkIsTriedAgainSoon() {
        cycle.tell(1160);
        cycle.missed(1170);
        assertFalse(cycle.telling());
        assertFalse(cycle.ready(1189));
        assertTrue(cycle.ready(1170 + BlinkCycle.RETRY_TICKS));
    }

    @Test
    void pathChecksAreRationedToOneASecond() {
        assertTrue(cycle.mayCheckPath(1160));
        assertFalse(cycle.mayCheckPath(1161));
        assertFalse(cycle.mayCheckPath(1179));
        assertTrue(cycle.mayCheckPath(1180));
    }
}
