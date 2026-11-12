package com.ninja6.ninjaarena.terrain;

/**
 * The block palette a generated map is drawn in, chosen by its seed. The model stores only
 * the theme; {@link ThemePalette} maps it to blocks when the map is placed.
 *
 * <p>The seed picks a theme by index into this enum, so appending a theme changes which
 * theme existing seeds get (the golden digests catch it). Never reorder the constants.
 */
public enum TerrainTheme {
    /** Grass over grey stone and andesite. */
    CRAGS,
    /** Banded terracotta and red sandstone. */
    MESA,
    /** Basalt and blackstone. */
    BASALT,
    /** Moss over mossy stone ruins. */
    OVERGROWN,
    /** Snow and packed ice over deepslate. */
    GLACIER;

    private static final TerrainTheme[] VALUES = values();

    static TerrainTheme ofOrdinal(int ordinal) {
        return VALUES[ordinal];
    }

    static int count() {
        return VALUES.length;
    }
}
