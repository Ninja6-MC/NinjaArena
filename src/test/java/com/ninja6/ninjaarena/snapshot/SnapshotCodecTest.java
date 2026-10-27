package com.ninja6.ninjaarena.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ninja6.ninjaarena.snapshot.SnapshotCodec.SnapshotFormatException;
import java.util.List;
import java.util.UUID;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

class SnapshotCodecTest {

    private static final UUID PLAYER = UUID.fromString("0f000000-0000-4000-8000-0000000000aa");

    @Test
    void roundTripKeepsEveryField() throws Exception {
        PlayerSnapshot original = Snapshots.sample(PLAYER);
        PlayerSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));

        assertEquals(original, decoded);
        assertArrayEquals(original.storage(), decoded.storage());
        assertArrayEquals(original.armour(), decoded.armour());
        assertArrayEquals(original.offhand(), decoded.offhand());
        assertEquals(original.location(), decoded.location());
        assertEquals(original.effects(), decoded.effects());
        assertEquals(0.3333f, decoded.exp());
        assertEquals(2.75f, decoded.fallDistance());
    }

    @Test
    void roundTripWithEmptyInventoryAndNoEffects() throws Exception {
        PlayerSnapshot sample = Snapshots.sample(PLAYER);
        PlayerSnapshot empty = new PlayerSnapshot(sample.playerId(), sample.playerName(),
                sample.capturedAt(), sample.location(), new byte[0], new byte[0], new byte[0],
                0, 0f, 0, 20.0, 20, 5f, 0f, List.of(), GameMode.ADVENTURE, -20, 0f);

        assertEquals(empty, SnapshotCodec.decode(SnapshotCodec.encode(empty)));
    }

    @Test
    void rejectsAnUnknownFormatVersion() {
        String text = SnapshotCodec.encode(Snapshots.sample(PLAYER))
                .replace("format: " + SnapshotCodec.FORMAT_VERSION, "format: 99");

        assertThrows(SnapshotFormatException.class, () -> SnapshotCodec.decode(text));
    }

    @Test
    void rejectsAMissingField() {
        String text = SnapshotCodec.encode(Snapshots.sample(PLAYER)).replace("game-mode:", "other:");

        assertThrows(SnapshotFormatException.class, () -> SnapshotCodec.decode(text));
    }

    @Test
    void rejectsATruncatedFile() {
        String text = SnapshotCodec.encode(Snapshots.sample(PLAYER));

        assertThrows(SnapshotFormatException.class,
                () -> SnapshotCodec.decode(text.substring(0, text.length() / 2)));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(SnapshotFormatException.class, () -> SnapshotCodec.decode("{{{ not yaml"));
    }
}
