package com.ninja6.ninjaarena.lobby;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class LobbyPiecesTest {

    private static final Path SPEC = Path.of("src/main/resources", LobbyPieces.SPEC);
    private static final Path FOLDER = Path.of("src/main/resources", LobbyPieces.FOLDER);

    /** If this fails, run ./gradlew generateLobbyPieces and commit the spec and the structures. */
    @Test
    void theCommittedStructuresAreWhatTheGeneratorWritesFromTheSpec() throws IOException {
        Map<String, byte[]> expected;
        try (var reader = Files.newBufferedReader(SPEC, StandardCharsets.UTF_8)) {
            expected = LobbyPiecesGenerator.generate(reader);
        }
        try (Stream<Path> files = Files.list(FOLDER)) {
            assertEquals(new TreeSet<>(expected.keySet()), files.map(file -> file.getFileName().toString())
                    .map(name -> name.replaceFirst("\\.nbt$", "")).collect(Collectors.toCollection(TreeSet::new)));
        }
        for (Map.Entry<String, byte[]> piece : expected.entrySet()) {
            byte[] committed;
            try (InputStream in = new GZIPInputStream(Files.newInputStream(FOLDER.resolve(piece.getKey() + ".nbt")))) {
                committed = in.readAllBytes();
            }
            assertArrayEquals(piece.getValue(), committed, piece.getKey());
        }
    }

    @Test
    void theJarCarriesEveryPieceTheGeneratorNeeds() throws IOException {
        LobbyPieces pieces = LobbyPieces.bundled();
        assertEquals(16, pieces.trees().size());
        assertEquals("minecraft:green_wool", pieces.floorBlock());
        assertEquals(15, pieces.wall().blocks().size());
        assertEquals(13 * 13 * 2, pieces.centre().blocks().size());
        assertEquals(49, pieces.pad().blocks().size());
        for (String corner : LobbyPieces.CORNERS) {
            assertEquals(3, pieces.corners().get(corner).cushions().size(), corner);
        }
        LobbyPiece.Cushion cushion = pieces.corners().get("nw").cushions().getFirst();
        assertEquals(new LobbyPiece.Cushion(2.5, 1.5, 2.5, 90f, "white"), cushion);
    }

    @Test
    void aSpecWithUnknownLettersOrRowsOfTheWrongLengthIsRefused() {
        String spec = """
                data-version: 1
                palette: {"g": green_wool}
                pieces:
                  floor:
                    size: [2, 1, 1]
                    layers:
                      - - "%s"
                """;
        assertThrows(IllegalArgumentException.class,
                () -> LobbyPiecesGenerator.generate(new StringReader(spec.formatted("gx"))));
        assertThrows(IllegalArgumentException.class,
                () -> LobbyPiecesGenerator.generate(new StringReader(spec.formatted("g"))));
    }
}
