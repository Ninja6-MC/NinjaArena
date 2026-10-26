package com.ninja6.ninjaarena.snapshot;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a player stood when the snapshot was taken. The world is kept by both UID and
 * name so a restore can still find it if the world was recreated under a new UID.
 */
public record SnapshotLocation(
        UUID worldId,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch) {

    public SnapshotLocation {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(worldName, "worldName");
    }
}
