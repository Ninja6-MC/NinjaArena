package com.ninja6.ninjaarena.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlotLeasesTest {

    private final SlotLeases<String> slots = new SlotLeases<>(2);

    @Test
    void withTwoSlotsAndThreeArenasTheThirdRoomQueuesUntilALeaseIsReleased() {
        assertTrue(slots.request("first", "dojo"));
        assertTrue(slots.request("second", "temple"));
        assertFalse(slots.request("third", "garden"));

        assertEquals(OptionalInt.of(1), slots.position("third"));
        assertEquals(Optional.of("garden"), slots.wanted("third"));
        assertEquals(Optional.empty(), slots.lease("third"));
        assertEquals(2, slots.used());
        assertFalse(slots.hasFree());

        assertEquals(List.of(), slots.pump(), "nothing frees up by asking again");
        assertEquals(List.of("third"), slots.release("first"));
        assertEquals(Optional.of("garden"), slots.lease("third"));
        assertEquals(OptionalInt.empty(), slots.position("third"));
        assertEquals(Optional.empty(), slots.lease("first"));
    }

    @Test
    void aRoomThatWantsABusyArenaQueuesForThatArenaAndOthersGoAhead() {
        slots.setMax(3);
        assertTrue(slots.request("first", "dojo"));
        assertFalse(slots.request("second", "DOJO"), "one match per hand-built arena");
        assertTrue(slots.request("third", "temple"), "a free arena is not held up by the queue");
        assertEquals(OptionalInt.of(1), slots.position("second"));

        assertEquals(List.of("second"), slots.release("first"));
        assertEquals(Optional.of("dojo"), slots.lease("second"));
    }

    @Test
    void positionsFollowTheOrderRoomsQueuedIn() {
        slots.request("a", "one");
        slots.request("b", "two");
        slots.request("c", "three");
        slots.request("d", "four");
        assertEquals(OptionalInt.of(1), slots.position("c"));
        assertEquals(OptionalInt.of(2), slots.position("d"));

        slots.release("c");
        assertEquals(OptionalInt.of(1), slots.position("d"));
        assertEquals(List.of("d"), slots.release("a"));
    }

    @Test
    void aLowerMaximumNeverEndsAGrantedLease() {
        slots.setMax(3);
        slots.request("a", "one");
        slots.request("b", "two");
        slots.request("c", "three");

        assertEquals(List.of(), slots.setMax(1));
        assertEquals(1, slots.max());
        assertEquals(3, slots.leased());
        assertTrue(slots.lease("a").isPresent() && slots.lease("b").isPresent() && slots.lease("c").isPresent());

        assertFalse(slots.request("d", "four"));
        assertEquals(List.of(), slots.release("a"), "two leases still hold the one slot");
        assertEquals(List.of(), slots.release("b"));
        assertEquals(List.of(), slots.pump());
        assertEquals(List.of("d"), slots.release("c"));
    }

    @Test
    void aHigherMaximumLetsQueuedRoomsInAtOnce() {
        slots.request("a", "one");
        slots.request("b", "two");
        slots.request("c", "three");
        assertEquals(List.of("c"), slots.setMax(3));
    }

    @Test
    void anArenaThatCannotBeLeasedNowWaitsInTheQueue() {
        Set<String> taken = new HashSet<>(Set.of("dojo"));
        slots.setArenaTaken(taken::contains);
        assertTrue(slots.request("a", "temple"));
        assertEquals(1, slots.used(), "only leases use slots");
        assertFalse(slots.request("c", "dojo"), "the arena is in edit mode");
        taken.clear();
        assertEquals(List.of("c"), slots.pump());
    }

    @Test
    void askingForAnotherArenaMovesTheLeaseOrTheQueuePlace() {
        slots.request("a", "one");
        assertTrue(slots.request("a", "one"), "asking again changes nothing");
        assertTrue(slots.request("a", "two"));
        assertEquals(Optional.of("two"), slots.lease("a"));
        assertFalse(slots.isClaimed("one"));
        assertTrue(slots.isClaimed("TWO"));
        assertTrue(slots.isLeased("two"));

        slots.request("b", "three");
        assertFalse(slots.request("c", "four"));
        assertTrue(slots.isClaimed("four"), "a queued room claims its arena too");
        assertFalse(slots.isLeased("four"));
        assertFalse(slots.request("c", "four"));
        assertEquals(OptionalInt.of(1), slots.position("c"));
    }

    @Test
    void theMaximumIsAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new SlotLeases<String>(0));
        assertThrows(IllegalArgumentException.class, () -> slots.setMax(0));
        assertEquals(List.of(), slots.release("nobody"));
    }
}
