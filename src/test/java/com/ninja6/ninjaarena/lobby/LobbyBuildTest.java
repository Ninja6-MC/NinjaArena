package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import com.ninja6.ninjaarena.arena.ArenaPoint;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class LobbyBuildTest {

    private static final Path SPEC = Path.of("src/main/resources", LobbyLayout.SPEC);
    private static final Path STRUCTURE = Path.of("src/main/resources", LobbyLayout.STRUCTURE);

    /** If this fails, run ./gradlew generateLobbyBuild and commit both lobby files. */
    @Test
    void theCommittedStructureIsWhatTheGeneratorWritesFromTheSpec() throws IOException {
        byte[] expected;
        try (var reader = Files.newBufferedReader(SPEC, StandardCharsets.UTF_8)) {
            expected = LobbyBuildGenerator.generate(reader);
        }
        byte[] committed;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(STRUCTURE))) {
            committed = in.readAllBytes();
        }
        assertArrayEquals(expected, committed);
    }

    @Test
    void theJarCarriesTheSpecAndTheStructure() throws IOException {
        for (String resource : List.of(LobbyLayout.SPEC, LobbyLayout.STRUCTURE)) {
            try (InputStream in = LobbyLayout.class.getClassLoader().getResourceAsStream(resource)) {
                assertTrue(in != null && in.readAllBytes().length > 0, resource);
            }
        }
    }

    @Test
    void theBundledLayoutMarksSpawnRoomShopAndBoardsInsideTheBuild() {
        LobbyLayout layout = LobbyLayout.bundled();
        assertEquals(new ArenaBounds(-20, 64, -16, 20, 67, 16), layout.build());
        assertEquals(new ArenaBounds(-24, 56, -20, 24, 76, 20), layout.bounds());
        assertEquals(new ArenaPoint(0.5, 65, 12.5, 180, 0), layout.spawn());
        assertEquals(new ArenaBounds(-18, 65, -14, -3, 67, 6), layout.roomArea());
        assertEquals(new ArenaBounds(3, 65, -14, 18, 67, 6), layout.shopArea());
        assertEquals(List.of("create-room", "room-list", "shop"), List.copyOf(layout.boards().keySet()));
        assertEquals(new ArenaPoint(-13.5, 66, -14.5, 0, 0), layout.boards().get("create-room"));
    }

    @Test
    void theSpawnStandsOnTheBuildsFloor() throws IOException {
        LobbyLayout layout = LobbyLayout.bundled();
        byte[] nbt;
        try (var reader = Files.newBufferedReader(SPEC, StandardCharsets.UTF_8)) {
            nbt = LobbyBuildGenerator.generate(reader);
        }
        // The block under the spawn, relative to the build corner, is in the structure.
        int x = layout.spawn().blockX() - layout.build().minX();
        int y = layout.spawn().blockY() - 1 - layout.build().minY();
        int z = layout.spawn().blockZ() - layout.build().minZ();
        assertTrue(containsPos(nbt, x, y, z), x + " " + y + " " + z);
        assertFalse(containsPos(nbt, x, y + 1, z));
    }

    @Test
    void theShopSpotStandsOnTheFloorInTheMiddleOfTheShopArea() throws IOException {
        LobbyLayout layout = LobbyLayout.bundled();
        var spot = layout.shop(null);
        assertEquals(11.0, spot.getX());
        assertEquals(65.0, spot.getY());
        assertEquals(-3.5, spot.getZ());
        assertEquals(180f, spot.getYaw(), "facing the shop board on the north wall");
        assertTrue(layout.shopArea().contains(spot.getBlockX(), spot.getBlockY(), spot.getBlockZ()));
        byte[] nbt;
        try (var reader = Files.newBufferedReader(SPEC, StandardCharsets.UTF_8)) {
            nbt = LobbyBuildGenerator.generate(reader);
        }
        int x = spot.getBlockX() - layout.build().minX();
        int y = spot.getBlockY() - 1 - layout.build().minY();
        int z = spot.getBlockZ() - layout.build().minZ();
        assertTrue(containsPos(nbt, x, y, z), x + " " + y + " " + z);
        assertFalse(containsPos(nbt, x, y + 1, z));
        assertFalse(containsPos(nbt, x, y + 2, z));
    }

    @Test
    void aSpecWithMarksOutsideTheBuildIsRefused() {
        String spec = """
                data-version: 1
                size: [5, 2, 5]
                origin: [0, 64, 0]
                bounds: {from: [-1, -1, -1], to: [6, 3, 6]}
                blocks: []
                marks:
                  spawn: {at: [9, 1, 2]}
                  room-area: {from: [0, 1, 0], to: [1, 1, 1]}
                  shop-area: {from: [3, 1, 3], to: [4, 1, 4]}
                  boards: {}
                """;
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> LobbyBuildGenerator.generate(new StringReader(spec)));
        assertTrue(refused.getMessage().contains("inside the build"), refused.getMessage());
    }

    @Test
    void aBoxOutsideTheSizeOrABadBlockIdIsRefused() {
        String spec = """
                data-version: 1
                size: [5, 2, 5]
                origin: [0, 64, 0]
                bounds: {from: [-1, -1, -1], to: [6, 3, 6]}
                blocks:
                  - {block: %s, from: [0, 0, 0], to: [%d, 0, 4]}
                marks:
                  spawn: {at: [2.5, 1, 2.5]}
                  room-area: {from: [0, 1, 0], to: [1, 1, 1]}
                  shop-area: {from: [3, 1, 3], to: [4, 1, 4]}
                  boards: {}
                """;
        assertThrows(IllegalArgumentException.class,
                () -> LobbyBuildGenerator.generate(new StringReader(spec.formatted("obsidian", 5))));
        assertThrows(IllegalArgumentException.class,
                () -> LobbyBuildGenerator.generate(new StringReader(spec.formatted("\"minecraft:obsidian\"", 4))));
    }

    @Test
    void aNewWorldGetsTheBuildOnceAndKeepsItOnEveryLaterStart() {
        RecordingSite site = new RecordingSite();
        LobbyLayout layout = LobbyLayout.bundled();

        assertTrue(new LobbyBuild(layout, site, quiet()).placeIfNew());
        assertEquals(List.of("place -20 64 -16", "mark"), site.calls);

        // Restarts: the world remembers it has the build, so admin changes stay.
        site.calls.clear();
        assertFalse(new LobbyBuild(layout, site, quiet()).placeIfNew());
        assertFalse(new LobbyBuild(layout, site, quiet()).placeIfNew());
        assertEquals(List.of(), site.calls);
    }

    @Test
    void aFailedPlacementIsNotRecordedSoTheNextStartTriesAgain() {
        RecordingSite site = new RecordingSite();
        site.failing = true;
        LobbyLayout layout = LobbyLayout.bundled();

        assertFalse(new LobbyBuild(layout, site, quiet()).placeIfNew());
        assertFalse(site.placed());

        site.failing = false;
        assertTrue(new LobbyBuild(layout, site, quiet()).placeIfNew());
        assertTrue(site.placed());
    }

    @Test
    void resetClearsTheWholeBoundsAndPlacesTheBundledBuildAgain() throws IOException {
        RecordingSite site = new RecordingSite();
        LobbyLayout layout = LobbyLayout.bundled();
        LobbyBuild build = new LobbyBuild(layout, site, quiet());
        build.placeIfNew();
        site.calls.clear();

        build.reset();

        assertEquals(List.of("clear " + layout.bounds().describe(), "place -20 64 -16", "mark"), site.calls);
    }

    static Logger quiet() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }

    /** A world that remembers its marker across restarts, as the lobby world's own data does. */
    static final class RecordingSite implements LobbyBuild.Site {
        final List<String> calls = new ArrayList<>();
        boolean marked;
        boolean failing;

        @Override
        public boolean placed() {
            return marked;
        }

        @Override
        public void markPlaced() {
            marked = true;
            calls.add("mark");
        }

        @Override
        public void clear(ArenaBounds box) {
            calls.add("clear " + box.describe());
        }

        @Override
        public void place(int x, int y, int z) throws IOException {
            if (failing) {
                throw new IOException("no structure");
            }
            calls.add("place " + x + " " + y + " " + z);
        }
    }

    /** Whether the uncompressed structure holds a block at the relative position. */
    private static boolean containsPos(byte[] nbt, int x, int y, int z) {
        byte[] pos = new byte[] {9, 0, 3, 'p', 'o', 's', 3, 0, 0, 0, 3,
            (byte) (x >>> 24), (byte) (x >>> 16), (byte) (x >>> 8), (byte) x,
            (byte) (y >>> 24), (byte) (y >>> 16), (byte) (y >>> 8), (byte) y,
            (byte) (z >>> 24), (byte) (z >>> 16), (byte) (z >>> 8), (byte) z};
        outer:
        for (int i = 0; i <= nbt.length - pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                if (nbt[i + j] != pos[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    void theSpecReadsTheSameThroughTheJarAndTheSourceTree() throws IOException {
        try (var reader = new InputStreamReader(Files.newInputStream(SPEC), StandardCharsets.UTF_8)) {
            assertEquals(LobbyLayout.read(reader), LobbyLayout.bundled());
        }
    }
}
