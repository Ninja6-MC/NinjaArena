package com.ninja6.ninjaarena.terrain;

import org.bukkit.Material;

/**
 * The blocks each {@link TerrainTheme} uses for each {@link BlockKind}. This is the only
 * class in the package that names a Bukkit type, and it only reads enum constants, so it is
 * safe on any thread. Every block here is solid, has no gravity and does not melt or decay,
 * so a placed map stays as generated.
 */
public final class ThemePalette {

    private ThemePalette() {
    }

    public static Material material(TerrainTheme theme, BlockKind kind) {
        if (kind == BlockKind.AIR) {
            return Material.AIR;
        }
        return switch (theme) {
            case CRAGS -> switch (kind) {
                case ROCK -> Material.STONE;
                case SOIL -> Material.DIRT;
                case SURFACE -> Material.GRASS_BLOCK;
                case CLIFF -> Material.ANDESITE;
                case PLATFORM -> Material.MOSSY_COBBLESTONE;
                case SPAWN_PAD, AIR -> Material.POLISHED_ANDESITE;
            };
            case MESA -> switch (kind) {
                case ROCK -> Material.TERRACOTTA;
                case SOIL -> Material.RED_TERRACOTTA;
                case SURFACE -> Material.ORANGE_TERRACOTTA;
                case CLIFF -> Material.RED_SANDSTONE;
                case PLATFORM -> Material.CUT_RED_SANDSTONE;
                case SPAWN_PAD, AIR -> Material.SMOOTH_RED_SANDSTONE;
            };
            case BASALT -> switch (kind) {
                case ROCK -> Material.BLACKSTONE;
                case SOIL -> Material.BASALT;
                case SURFACE -> Material.SMOOTH_BASALT;
                case CLIFF -> Material.POLISHED_BASALT;
                case PLATFORM -> Material.POLISHED_BLACKSTONE_BRICKS;
                case SPAWN_PAD, AIR -> Material.POLISHED_BLACKSTONE;
            };
            case OVERGROWN -> switch (kind) {
                case ROCK -> Material.STONE;
                case SOIL -> Material.DIRT;
                case SURFACE -> Material.MOSS_BLOCK;
                case CLIFF -> Material.MOSSY_STONE_BRICKS;
                case PLATFORM -> Material.MOSSY_COBBLESTONE;
                case SPAWN_PAD, AIR -> Material.STONE_BRICKS;
            };
            case GLACIER -> switch (kind) {
                case ROCK -> Material.DEEPSLATE;
                case SOIL -> Material.PACKED_ICE;
                case SURFACE -> Material.SNOW_BLOCK;
                case CLIFF -> Material.BLUE_ICE;
                case PLATFORM -> Material.PACKED_ICE;
                case SPAWN_PAD, AIR -> Material.POLISHED_DIORITE;
            };
        };
    }
}
