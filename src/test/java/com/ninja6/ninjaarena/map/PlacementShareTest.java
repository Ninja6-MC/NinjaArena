package com.ninja6.ninjaarena.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlacementShareTest {

    private long now;

    /** Spends the budget it is given, plus a slice of overshoot, on the test's clock. */
    private final class Spender implements PlacementShare.Writer {
        boolean busy = true;
        final List<Long> given = new ArrayList<>();

        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public void work(long budgetNanos) {
            given.add(budgetNanos);
            now += budgetNanos + 10_000;
        }
    }

    @Test
    void mapsAndTheLobbyTogetherStayWithinOneBudget() {
        long budget = 10_000_000;
        PlacementShare share = new PlacementShare(() -> budget, () -> now);
        Spender maps = new Spender();
        Spender lobby = new Spender();
        for (int tick = 0; tick < 5; tick++) {
            long spent = share.tick(maps, lobby);
            // One budget, overrun at most by the last slice of each.
            assertTrue(spent <= budget + 20_000, "spent " + spent);
        }
        assertEquals(budget / 2, maps.given.getFirst());
        assertEquals(budget / 2 - 10_000, lobby.given.getFirst());

        lobby.busy = false;
        share.tick(maps, lobby);
        assertEquals(budget, maps.given.getLast(), "alone, the maps get it all");
        maps.busy = false;
        lobby.busy = true;
        share.tick(maps, lobby);
        assertEquals(budget, lobby.given.getLast(), "alone, the lobby gets it all");
        lobby.busy = false;
        assertEquals(0, share.tick(maps, lobby));
    }
}
