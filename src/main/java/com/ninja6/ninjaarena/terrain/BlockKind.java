package com.ninja6.ninjaarena.terrain;

/**
 * What a cell of a {@link TerrainModel} holds, independent of the block it becomes. A
 * {@link TerrainTheme} turns each kind into a block through {@link ThemePalette}.
 *
 * <p>The model stores each cell as this enum's ordinal, and the golden digests hash those
 * bytes, so constants are only ever appended (hazard kinds from later tasks go after
 * {@link #SPAWN_PAD}); never reorder or remove one.
 */
public enum BlockKind {
    /** Nothing; placed as air. */
    AIR,
    /** Deep terrain fill, more than {@link TerrainGenerator#SOIL_DEPTH} below the column top. */
    ROCK,
    /** The layers just under the column top. */
    SOIL,
    /** A solid block with air directly above it: the ground players walk on. */
    SURFACE,
    /** A solid terrain block whose side is open to air: cliff faces, ravine and cave walls. */
    CLIFF,
    /** A floating platform, never touching the terrain below it. */
    PLATFORM,
    /** The top block of the flat spawn zone. */
    SPAWN_PAD;

    private static final BlockKind[] VALUES = values();

    /** Whether a player can stand on or collide with this kind. */
    public boolean solid() {
        return this != AIR;
    }

    static BlockKind ofOrdinal(int ordinal) {
        return VALUES[ordinal];
    }
}
