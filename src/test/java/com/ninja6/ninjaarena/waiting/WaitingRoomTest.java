package com.ninja6.ninjaarena.waiting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.waiting.WaitingRoom.Left;
import com.ninja6.ninjaarena.waiting.WaitingRoom.Move;
import com.ninja6.ninjaarena.waiting.WaitingRoom.Phase;
import com.ninja6.ninjaarena.waiting.WaitingRoom.Tick;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WaitingRoomTest {

    private static UUID player(int n) {
        return new UUID(0, n);
    }

    private static List<Integer> sizes(WaitingRoom room) {
        List<Integer> sizes = new ArrayList<>();
        for (List<UUID> team : room.teams()) {
            sizes.add(team.size());
        }
        return sizes;
    }

    @Test
    void arrivalsFillTheSmallestTeamLowestFirst() {
        WaitingRoom room = new WaitingRoom(3, 10, 30);
        List<Integer> teams = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            teams.add(room.join(player(i)).team());
        }
        assertEquals(List.of(1, 2, 3, 1, 2, 3, 1), teams);
        assertEquals(List.of(3, 2, 2), sizes(room));
        assertEquals(2, room.teamOf(player(5)));
        assertEquals(0, room.teamOf(player(99)));
    }

    @Test
    void aDepartureThatLeavesTheTeamsTwoApartMovesTheLastArrival() {
        WaitingRoom room = new WaitingRoom(2, 10, 30);
        for (int i = 1; i <= 4; i++) {
            room.join(player(i));
        }
        // Team 1: 1, 3. Team 2: 2, 4. Losing 1 leaves 1 against 2: still even enough.
        Left first = room.leave(player(1)).orElseThrow();
        assertEquals(1, first.team());
        assertEquals(Optional.empty(), first.move());

        // Losing 3 as well leaves 0 against 2, so the last to join team 2 moves over.
        Left second = room.leave(player(3)).orElseThrow();
        assertEquals(Optional.of(new Move(player(4), 2, 1)), second.move());
        assertEquals(List.of(List.of(player(4)), List.of(player(2))), room.teams());
    }

    @Test
    void teamsNeverDifferByMoreThanOne() {
        WaitingRoom room = new WaitingRoom(4, 100, 30);
        List<UUID> present = new ArrayList<>();
        int next = 0;
        // A fixed pattern of arrivals and departures from all over the room.
        for (int step = 0; step < 200; step++) {
            if (present.isEmpty() || step % 3 != 2) {
                UUID id = player(next++);
                room.join(id);
                present.add(id);
            } else {
                UUID id = present.remove((step * 7) % present.size());
                room.leave(id).orElseThrow();
            }
            List<Integer> sizes = sizes(room);
            int max = sizes.stream().mapToInt(Integer::intValue).max().orElseThrow();
            int min = sizes.stream().mapToInt(Integer::intValue).min().orElseThrow();
            assertTrue(max - min <= 1, "step " + step + ": " + sizes);
            assertEquals(present.size(), room.size());
        }
    }

    @Test
    void joiningTwiceIsRefusedAndLeavingAStrangerDoesNothing() {
        WaitingRoom room = new WaitingRoom(2, 2, 30);
        room.join(player(1));
        assertThrows(IllegalStateException.class, () -> room.join(player(1)));
        assertEquals(Optional.empty(), room.leave(player(2)));
        assertEquals(1, room.size());
    }

    @Test
    void theCountdownStartsAtTheMinimum() {
        WaitingRoom room = new WaitingRoom(2, 3, 5);
        assertFalse(room.join(player(1)).countdownStarted());
        assertFalse(room.join(player(2)).countdownStarted());
        assertEquals(Phase.IDLE, room.tick().phase());
        assertTrue(room.join(player(3)).countdownStarted());
        assertTrue(room.isCounting());
        assertEquals(5, room.secondsLeft());
        // A fourth arrival does not restart it.
        room.tick();
        assertFalse(room.join(player(4)).countdownStarted());
        assertEquals(4, room.secondsLeft());
    }

    @Test
    void theCountdownRunsDownAndHandsOverTheTeams() {
        WaitingRoom room = new WaitingRoom(2, 2, 3);
        room.join(player(1));
        room.join(player(2));
        room.join(player(3));

        assertEquals(new Tick(Phase.COUNTING, 2, List.of()), room.tick());
        assertEquals(new Tick(Phase.COUNTING, 1, List.of()), room.tick());
        Tick start = room.tick();
        assertEquals(Phase.START, start.phase());
        assertEquals(List.of(List.of(player(1), player(3)), List.of(player(2))), start.teams());

        assertTrue(room.isEmpty());
        assertFalse(room.isCounting());
        assertEquals(Phase.IDLE, room.tick().phase());
    }

    @Test
    void droppingBelowTheMinimumCancelsAndReachingItAgainRestartsInFull() {
        WaitingRoom room = new WaitingRoom(2, 2, 10);
        room.join(player(1));
        room.join(player(2));
        room.tick();
        room.tick();
        assertEquals(8, room.secondsLeft());

        Left left = room.leave(player(2)).orElseThrow();
        assertTrue(left.countdownCancelled());
        assertFalse(room.isCounting());
        assertEquals(Phase.IDLE, room.tick().phase());

        assertTrue(room.join(player(3)).countdownStarted());
        assertEquals(10, room.secondsLeft());
    }

    @Test
    void leavingAboveTheMinimumKeepsTheCountdown() {
        WaitingRoom room = new WaitingRoom(2, 2, 10);
        room.join(player(1));
        room.join(player(2));
        room.join(player(3));
        room.tick();
        assertFalse(room.leave(player(3)).orElseThrow().countdownCancelled());
        assertEquals(9, room.secondsLeft());
    }

    @Test
    void aMinimumOfOneCountsDownForASinglePlayer() {
        WaitingRoom room = new WaitingRoom(2, 1, 1);
        assertTrue(room.join(player(1)).countdownStarted());
        assertEquals(List.of(List.of(player(1)), List.of()), room.tick().teams());
    }

    @Test
    void invalidSizesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoom(0, 2, 30));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoom(2, 0, 30));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoom(2, 2, 0));
    }

    @Test
    void reloadChangesMinimumAndFutureCountdownButKeepsAViableCountdownLength() {
        WaitingRoom room = new WaitingRoom(2, 2, 5);
        room.join(player(1));
        room.join(player(2));
        room.tick();
        room.setSettings(2, 9);
        assertEquals(4, room.secondsLeft());
        room.setSettings(3, 9);
        assertFalse(room.isCounting());
        room.setSettings(2, 9);
        assertEquals(9, room.secondsLeft());
        room.leave(player(2));
        assertFalse(room.isCounting());
        room.join(player(3));
        assertEquals(9, room.secondsLeft());
    }

}
