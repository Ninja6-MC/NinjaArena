package com.ninja6.ninjaarena.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class NoiseAndRandomTest {

    @Test
    void splitMixMatchesTheReferenceSequence() {
        // The published SplitMix64 output for seed 0.
        SplitMix64 random = new SplitMix64(0);
        assertEquals(0xE220A8397B1DCDAFL, random.nextLong());
        assertEquals(0x6E789E6AA1B965F4L, random.nextLong());
        assertEquals(0x06C45D188009454FL, random.nextLong());
    }

    @Test
    void boundedDrawsStayInRange() {
        SplitMix64 random = new SplitMix64(77);
        for (int i = 0; i < 10_000; i++) {
            int value = random.nextInt(7);
            assertTrue(value >= 0 && value < 7);
            double fraction = random.nextDouble();
            assertTrue(fraction >= 0 && fraction < 1);
        }
    }

    @Test
    void derivedStreamsDiffer() {
        assertNotEquals(SplitMix64.derive(1, 1), SplitMix64.derive(1, 2));
        assertNotEquals(SplitMix64.derive(1, 1), SplitMix64.derive(2, 1));
        assertEquals(SplitMix64.derive(5, 3), SplitMix64.derive(5, 3));
    }

    @Test
    void noiseStaysInRangeAndMatchesItsLatticePoints() {
        ValueNoise noise = new ValueNoise(11);
        for (int i = -200; i < 200; i++) {
            double x = i * 0.37;
            double z = i * -0.61;
            double flat = noise.fractal(x, z, 4);
            double solid = noise.fractal(x, i * 0.13, z, 3);
            assertTrue(flat >= -1 && flat < 1);
            assertTrue(solid >= -1 && solid < 1);
            assertEquals(noise.at(x, z), new ValueNoise(11).at(x, z));
        }
        assertEquals(noise.at(3, 0, -4), noise.at(3.0, -4.0));
    }

    @Test
    void everyThemeHasABlockForEveryKind() {
        for (TerrainTheme theme : TerrainTheme.values()) {
            assertEquals(Material.AIR, ThemePalette.material(theme, BlockKind.AIR));
            for (BlockKind kind : BlockKind.values()) {
                if (kind != BlockKind.AIR) {
                    Material material = ThemePalette.material(theme, kind);
                    assertNotEquals(Material.AIR, material, theme + " " + kind);
                }
            }
        }
    }
}
