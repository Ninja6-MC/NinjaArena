package com.ninja6.ninjaarena.waiting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedHubTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);

    @Test
    void theCountdownStartsAtTheMinimumAndReportsReadyAtZero() {
        SharedHub hub = new SharedHub(2, 3);
        assertFalse(hub.join(A));
        assertEquals(SharedHub.Phase.IDLE, hub.tick());
        assertTrue(hub.join(B));
        assertEquals(3, hub.secondsLeft());
        assertEquals(SharedHub.Phase.COUNTING, hub.tick());
        assertEquals(SharedHub.Phase.COUNTING, hub.tick());
        assertEquals(SharedHub.Phase.READY, hub.tick());
        // The queue is kept until a group is taken.
        assertEquals(List.of(A, B), hub.players());
    }

    @Test
    void aGroupIsTakenFirstComeFirstServedAndTheRestKeepTheirPlace() {
        SharedHub hub = new SharedHub(2, 5);
        hub.join(A);
        hub.join(B);
        hub.join(C);
        hub.join(D);
        assertEquals(List.of(A, B), hub.take(2));
        assertEquals(List.of(C, D), hub.players());
        // Enough are left for another match, so the countdown starts again in full.
        assertEquals(5, hub.secondsLeft());

        assertEquals(List.of(C), hub.take(1));
        assertFalse(hub.isCounting());
        assertEquals(List.of(D), hub.take(5));
        assertTrue(hub.isEmpty());
    }

    @Test
    void aDepartureBelowTheMinimumStopsTheCountdown() {
        SharedHub hub = new SharedHub(2, 5);
        hub.join(A);
        hub.join(B);
        hub.tick();
        assertEquals(Optional.of(true), hub.leave(A));
        assertFalse(hub.isCounting());
        assertEquals(Optional.empty(), hub.leave(A));
        hub.join(C);
        assertEquals(5, hub.secondsLeft(), "the countdown starts again from the full length");
    }

    @Test
    void newSettingsKeepAViableCountdownAndStartOrStopOne() {
        SharedHub hub = new SharedHub(3, 10);
        hub.join(A);
        hub.join(B);
        hub.setSettings(2, 4);
        assertEquals(4, hub.secondsLeft());
        hub.tick();
        hub.setSettings(2, 9);
        assertEquals(3, hub.secondsLeft());
        hub.setSettings(5, 9);
        assertFalse(hub.isCounting());
    }

    @Test
    void aPlayerCannotWaitTwiceAndSettingsMustBePositive() {
        SharedHub hub = new SharedHub(2, 5);
        hub.join(A);
        assertThrows(IllegalStateException.class, () -> hub.join(A));
        assertThrows(IllegalArgumentException.class, () -> new SharedHub(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new SharedHub(2, 0));
    }
}
