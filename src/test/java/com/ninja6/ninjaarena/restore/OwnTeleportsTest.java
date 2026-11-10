package com.ninja6.ninjaarena.restore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OwnTeleportsTest {

    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");

    @AfterEach
    void clear() {
        OwnTeleports.clear(ALICE);
    }

    @Test
    void theMarkCoversTheCallAndEndsAfterIt() {
        assertTrue(OwnTeleports.during(ALICE, () -> OwnTeleports.isActive(ALICE)));
        assertFalse(OwnTeleports.isActive(ALICE));
    }

    @Test
    void aThrowingCallStillEndsTheMark() {
        assertThrows(IllegalStateException.class, () -> OwnTeleports.during(ALICE, () -> {
            throw new IllegalStateException("refused");
        }));
        assertFalse(OwnTeleports.isActive(ALICE));
    }

    @Test
    void nestedMarksEndTogether() {
        OwnTeleports.begin(ALICE);
        OwnTeleports.during(ALICE, () -> true);
        assertTrue(OwnTeleports.isActive(ALICE));
        OwnTeleports.end(ALICE);
        assertFalse(OwnTeleports.isActive(ALICE));
    }

    @Test
    void clearDropsEveryMarkOfAPlayer() {
        OwnTeleports.begin(ALICE);
        OwnTeleports.begin(ALICE);
        OwnTeleports.clear(ALICE);
        assertFalse(OwnTeleports.isActive(ALICE));
    }
}
