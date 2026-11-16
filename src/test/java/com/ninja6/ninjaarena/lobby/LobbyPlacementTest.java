package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LobbyPlacementTest {

    /** A world whose block reads and writes take time on a clock the test owns. */
    private static final class Timed implements LobbyPlacement.Blocks {
        final MemorySite site = new MemorySite();
        final List<int[]> writes = new ArrayList<>();
        long now;

        @Override
        public boolean is(int x, int y, int z, String state) {
            now += 300;
            return site.is(x, y, z, state);
        }

        @Override
        public void set(int x, int y, int z, String state) {
            now += 900;
            writes.add(new int[] {x, y, z});
            site.set(x, y, z, state);
        }

        @Override
        public void removeEntities(ArenaBounds box) {
            now += 5_000;
            site.removeEntities(box);
        }

        @Override
        public void spawn(LobbyPiece.Cushion cushion) {
            now += 20_000;
            site.spawn(cushion);
        }
    }

    @Test
    void thirtyTwoPadsArePlacedOverManyTicksWithinTheBudgetFloorFirst() {
        GeneratedLobby lobby = GeneratedLobby.plan(32, 3, LobbyFixture.pieces().trees().size());
        GeneratedLobby.Blueprint print = lobby.blueprint(LobbyFixture.pieces());
        Timed world = new Timed();
        LobbyPlacement placement = new LobbyPlacement(world, print, lobby.floor(), lobby.layout().bounds());
        long budget = 2_000_000;
        int ticks = 0;
        while (!placement.finished()) {
            long before = world.now;
            placement.tick(budget, () -> world.now);
            ticks++;
            // A slice is sized from the time measured so far, so a tick ends within a slice of
            // its budget, as generated maps do.
            assertTrue(world.now - before <= budget * 5 / 4, "tick " + ticks + " took " + (world.now - before));
            assertTrue(ticks < 10_000);
        }
        assertTrue(ticks > 20, "spread over " + ticks + " ticks");
        assertEquals(print.blocks(), world.site.world, "the world holds exactly the lobby");
        assertEquals(print.cushions(), world.site.cushions);
        // The floor is laid before anything else is written.
        ArenaBounds floor = lobby.floor();
        int floorCount = (floor.maxX() - floor.minX() + 1) * (floor.maxZ() - floor.minZ() + 1);
        for (int i = 0; i < world.writes.size(); i++) {
            int[] at = world.writes.get(i);
            assertEquals(i < floorCount, floor.contains(at[0], at[1], at[2]), "write " + i);
        }
    }

    @Test
    void aSweepClearsWhatIsNotTheLobbyAndLeavesMatchingBlocksAlone() {
        GeneratedLobby lobby = GeneratedLobby.plan(2, 3, LobbyFixture.pieces().trees().size());
        GeneratedLobby.Blueprint print = lobby.blueprint(LobbyFixture.pieces());
        MemorySite site = new MemorySite();
        site.set(30, 70, 30, "minecraft:obsidian");
        site.set(0, 64, 0, "minecraft:black_wool");
        long before = site.writes;
        LobbyPlacement placement = new LobbyPlacement(site, print, lobby.floor(),
                new ArenaBounds(-40, 50, -40, 40, 90, 40));
        assertEquals(0, placement.tick(0, System::nanoTime), "no budget, no work");
        placement.run(Integer.MAX_VALUE);
        assertEquals(print.blocks(), site.world);
        assertEquals(print.size() - 1 + 1, site.writes - before, "every block but the matching one, and the clear");
    }
}
