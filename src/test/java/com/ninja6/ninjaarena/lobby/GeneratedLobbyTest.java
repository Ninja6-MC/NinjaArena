package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import com.ninja6.ninjaarena.lobby.GeneratedLobby.Blueprint;
import com.ninja6.ninjaarena.lobby.LobbyPad.Side;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class GeneratedLobbyTest {

    private static final LobbyPieces PIECES = LobbyFixture.pieces();
    private static final int TREES = PIECES.trees().size();

    /** pads, north, east, south, west, centre, emblem scale, floor side. */
    @ParameterizedTest
    @CsvSource({
        "1, 1, 0, 0, 0, 13, 1, 27",
        "2, 1, 0, 1, 0, 13, 1, 27",
        "3, 1, 1, 0, 1, 13, 1, 27",
        "4, 1, 1, 1, 1, 13, 1, 27",
        "8, 2, 2, 2, 2, 13, 1, 27",
        "9, 3, 2, 2, 2, 19, 1, 33",
        "24, 6, 6, 6, 6, 37, 2, 51",
        "32, 8, 8, 8, 8, 49, 3, 63",
    })
    void padsPerSideCentreEmblemAndFloorFollowThePadCount(int pads, int north, int east, int south, int west,
            int centre, int scale, int floor) {
        GeneratedLobby lobby = GeneratedLobby.plan(pads, 1, TREES);
        assertEquals(Map.of(Side.NORTH, north, Side.EAST, east, Side.SOUTH, south, Side.WEST, west), lobby.sides());
        assertEquals(centre, lobby.centre());
        assertEquals(scale, lobby.emblemScale());
        assertEquals(floor, lobby.floorSize());
        assertEquals(pads, lobby.padList().size());
        assertEquals(pads, lobby.layout().pads().size());
        int half = (floor - 1) / 2;
        assertEquals(new ArenaBounds(-half, 64, -half, half, 64, half), lobby.floor());
        // The build reaches three blocks past the floor for the trees, from the stone under
        // the centre to the top of the wall; the bounds four more, eight below and four above.
        assertEquals(new ArenaBounds(-half - 3, 63, -half - 3, half + 3, 79, half + 3), lobby.layout().build());
        assertEquals(new ArenaBounds(-half - 7, 55, -half - 7, half + 7, 83, half + 7), lobby.layout().bounds());
        // The shop is the south-east corner square inside the wall.
        assertEquals(new ArenaBounds(half - 6, 65, half - 6, half - 1, 67, half - 1), lobby.layout().shopArea());
        assertEquals(new ArenaPoint(0.5, 65, 0.5, 180, 0), lobby.layout().spawn());
        assertEquals(List.of("create-room", "room-list", "shop"), List.copyOf(lobby.layout().boards().keySet()));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 8, 9, 24, 32})
    void piecesNeverOverlapAndEveryPadIsAFramedPlateInItsBand(int pads) {
        GeneratedLobby lobby = GeneratedLobby.plan(pads, 1, TREES);
        ArenaBounds floor = lobby.floor();
        ArenaBounds centre = lobby.centreSquare();
        List<ArenaBounds> taken = new ArrayList<>(lobby.corners().values());
        taken.add(centre);
        Set<Long> plates = new HashSet<>();
        for (LobbyPad pad : lobby.padList()) {
            assertTrue(floor.contains(pad.frame()), "pad " + pad.index());
            assertEquals(5, pad.plate().maxX() - pad.plate().minX() + 1);
            assertEquals(5, pad.plate().maxZ() - pad.plate().minZ() + 1);
            assertEquals(16, pad.ring().size());
            for (ArenaBounds other : taken) {
                assertFalse(overlaps(pad.frame(), other), "pad " + pad.index() + " overlaps " + other.describe());
            }
            for (int x = pad.plate().minX(); x <= pad.plate().maxX(); x++) {
                for (int z = pad.plate().minZ(); z <= pad.plate().maxZ(); z++) {
                    assertTrue(plates.add(Blueprint.key(x, 64, z)), "plates overlap at pad " + pad.index());
                }
            }
            // Each side's pads stand in its band, against the wall.
            switch (pad.side()) {
                case NORTH -> assertEquals(floor.minZ(), pad.frame().minZ());
                case SOUTH -> assertEquals(floor.maxZ(), pad.frame().maxZ());
                case WEST -> assertEquals(floor.minX(), pad.frame().minX());
                case EAST -> assertEquals(floor.maxX(), pad.frame().maxX());
            }
            assertTrue(lobby.layout().roomArea().contains(pad.label().blockX(), 65, pad.label().blockZ()));
        }
        for (int i = 0; i < taken.size(); i++) {
            for (int j = i + 1; j < taken.size(); j++) {
                assertFalse(overlaps(taken.get(i), taken.get(j)));
            }
        }
        assertTrue(lobby.layout().build().contains(lobby.layout().shopArea()));
        for (ArenaPoint board : lobby.layout().boards().values()) {
            assertTrue(lobby.layout().roomArea().contains(board.blockX(), 65, board.blockZ()));
        }
    }

    @Test
    void eightPadsMatchTheReferenceBuild() {
        GeneratedLobby lobby = GeneratedLobby.plan(8, 1, TREES);
        List<LobbyPad> pads = lobby.padList();
        // Clockwise from the north-west: two north, two east, two south, two west.
        assertEquals(List.of(Side.NORTH, Side.NORTH, Side.EAST, Side.EAST, Side.SOUTH, Side.SOUTH, Side.WEST,
                Side.WEST), pads.stream().map(LobbyPad::side).toList());
        assertEquals(new ArenaBounds(-6, 64, -13, 0, 64, -7), pads.get(0).frame());
        assertEquals(new ArenaBounds(-5, 64, -12, -1, 64, -8), pads.get(0).plate());
        assertEquals(new ArenaBounds(0, 64, -13, 6, 64, -7), pads.get(1).frame());
        assertEquals(new ArenaBounds(7, 64, -6, 13, 64, 0), pads.get(2).frame());
        assertEquals(new ArenaBounds(0, 64, 7, 6, 64, 13), pads.get(4).frame());
        assertEquals(new ArenaBounds(-13, 64, 0, -7, 64, 6), pads.get(6).frame());
        assertEquals(new ArenaPoint(-2.5, 67, -9.5, 0, 0), pads.get(0).label());
        // Posts at both corners and on every frame line: 0, 7, 13, 19 and 26 along each side.
        assertEquals(16, lobby.posts().size());
        assertEquals(List.of(-13, -6, 0, 6, 13), lobby.posts().subList(0, 5).stream().map(GeneratedLobby.Post::x)
                .toList());

        Blueprint print = lobby.blueprint(PIECES);
        assertEquals("minecraft:black_wool", print.at(0, 64, 0), "the emblem's middle");
        assertEquals("minecraft:green_wool", print.at(-6, 64, -6), "the centre's corner");
        assertEquals("minecraft:stone", print.at(0, 63, 0));
        assertEquals("minecraft:diamond_block", print.at(-3, 64, -10));
        assertEquals("minecraft:gold_block", print.at(0, 64, -10), "the shared frame line");
        assertEquals("minecraft:barrier", print.at(-10, 79, -13), "the wall's top");
        assertNull(print.at(-10, 80, -13));
        assertEquals("minecraft:warped_stem", print.at(-13, 65, -13), "the corner post");
        assertEquals(12, print.cushions().size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 8, 9, 24, 32})
    void theEmblemIsDrawnAtItsScaleAndSpawnShopAndPadsHaveRoomToStand(int pads) {
        GeneratedLobby lobby = GeneratedLobby.plan(pads, 1, TREES);
        Blueprint print = lobby.blueprint(PIECES);
        int scale = lobby.emblemScale();
        long black = print.blocks().values().stream().filter("minecraft:black_wool"::equals).count();
        assertEquals(29L * scale * scale, black);
        ArenaBounds floor = lobby.floor();
        for (int x = floor.minX(); x <= floor.maxX(); x++) {
            for (int z = floor.minZ(); z <= floor.maxZ(); z++) {
                assertTrue(print.at(x, 64, z) != null, "the floor is whole at " + x + ", " + z);
            }
        }
        standsOn(print, lobby.layout().spawn().blockX(), lobby.layout().spawn().blockZ());
        Location shop = lobby.layout().shop(null);
        assertTrue(lobby.layout().shopArea().contains(shop.getBlockX(), shop.getBlockY(), shop.getBlockZ()));
        standsOn(print, shop.getBlockX(), shop.getBlockZ());
        for (LobbyPad pad : lobby.padList()) {
            for (int x = pad.plate().minX(); x <= pad.plate().maxX(); x++) {
                for (int z = pad.plate().minZ(); z <= pad.plate().maxZ(); z++) {
                    assertEquals("minecraft:diamond_block", print.at(x, 64, z));
                    standsOn(print, x, z);
                }
            }
            assertEquals("minecraft:gold_block", print.at(pad.frame().minX(), 64, pad.frame().minZ()));
        }
    }

    private static void standsOn(Blueprint print, int x, int z) {
        assertTrue(print.at(x, 64, z) != null, x + ", " + z);
        assertNull(print.at(x, 65, z), x + ", " + z);
        assertNull(print.at(x, 66, z), x + ", " + z);
    }

    @Test
    void theSameSeedPicksTheSameTreesAndAnotherSeedOthers() {
        GeneratedLobby once = GeneratedLobby.plan(24, 42, TREES);
        GeneratedLobby again = GeneratedLobby.plan(24, 42, TREES);
        assertEquals(once.posts(), again.posts());
        assertEquals(once.blueprint(PIECES).blocks(), again.blueprint(PIECES).blocks());
        assertNotEquals(once.posts(), GeneratedLobby.plan(24, 43, TREES).posts());
        assertTrue(once.posts().stream().allMatch(post -> post.tree() >= 0 && post.tree() < TREES));
    }

    @Test
    void padsFollowMaxActiveUpToThirtyTwo() {
        assertEquals(1, GeneratedLobby.padsFor(0));
        assertEquals(4, GeneratedLobby.padsFor(4));
        assertEquals(32, GeneratedLobby.padsFor(32));
        assertEquals(32, GeneratedLobby.padsFor(100));
        assertThrows(IllegalArgumentException.class, () -> GeneratedLobby.plan(33, 1, TREES));
        assertThrows(IllegalArgumentException.class, () -> GeneratedLobby.plan(0, 1, TREES));
    }

    private static boolean overlaps(ArenaBounds a, ArenaBounds b) {
        return a.minX() <= b.maxX() && b.minX() <= a.maxX() && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ()
                && a.minY() <= b.maxY() && b.minY() <= a.maxY();
    }
}
