package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardTicketsTest {
    @Test void sharedShopAndRoomChunksReleaseOnlyAfterLastOwnerAndIgnoreUnownedCoordinates() {
        List<String> changes = new ArrayList<>();
        BoardTickets tickets = new BoardTickets((x, z) -> changes.add("add " + x + "," + z),
                (x, z) -> changes.add("remove " + x + "," + z));
        tickets.acquire(-1, 3); // Shop.
        tickets.acquire(-1, 3); // Room board in the same chunk.
        tickets.acquire(2, 4);
        tickets.release(-1, 3);
        tickets.release(9, 9);
        assertEquals(List.of("add -1,3", "add 2,4"), changes);
        tickets.release(2, 4);
        tickets.release(-1, 3);
        tickets.release(-1, 3);
        assertEquals(List.of("add -1,3", "add 2,4", "remove 2,4", "remove -1,3"), changes);
    }
}
