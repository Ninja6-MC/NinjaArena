package com.ninja6.ninjaarena.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bukkit.GameMode;

/** Test fixtures. */
final class Snapshots {

    private Snapshots() {
    }

    static PlayerSnapshot sample(UUID playerId) {
        return sample(playerId, 7);
    }

    static PlayerSnapshot sample(UUID playerId, int level) {
        return new PlayerSnapshot(
                playerId,
                "Steve",
                Instant.parse("2026-10-26T12:34:56.789Z"),
                new SnapshotLocation(
                        UUID.fromString("7a1b2c3d-0000-4000-8000-000000000001"),
                        "world",
                        123.456789012345,
                        64.0,
                        -987.000000001,
                        179.9f,
                        -12.25f),
                new byte[] {1, 2, 3, 0, -1, 127, -128},
                new byte[] {9, 8, 7},
                new byte[] {42},
                level,
                0.3333f,
                345,
                17.5,
                19,
                4.8f,
                1.2345f,
                List.of(
                        new SnapshotEffect("minecraft:speed", 1200, 1, false, true, true),
                        new SnapshotEffect("minecraft:night_vision", -1, 0, true, false, false)),
                GameMode.SURVIVAL,
                40,
                2.75f);
    }
}
