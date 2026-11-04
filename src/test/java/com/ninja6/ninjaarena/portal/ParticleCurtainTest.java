package com.ninja6.ninjaarena.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.util.SplittableRandom;
import java.util.UUID;
import org.bukkit.Color;
import org.junit.jupiter.api.Test;

class ParticleCurtainTest {

    private static Portal portal(ArenaBounds opening) {
        return Portal.built("built-1", "world", opening, new Portal.Built(new UUID(0, 1), "crying_obsidian"));
    }

    @Test
    void theCurtainFillsTheOpeningAsAFlatSheet() {
        BlockWorld world = new BlockWorld("world");
        ParticleCurtain curtain = new ParticleCurtain(0x40E0D0, new SplittableRandom(7));
        curtain.draw(world.world, portal(new ArenaBounds(10, 64, 5, 11, 66, 5)));
        assertEquals(12, world.particles.size(), "two for each block of a 2x3 opening");
        for (double[] at : world.particles) {
            assertTrue(at[0] >= 10 && at[0] < 12, "x " + at[0]);
            assertTrue(at[1] >= 64 && at[1] < 67, "y " + at[1]);
            assertTrue(at[2] >= 5.4 && at[2] <= 5.6, "z " + at[2]);
        }

        world.particles.clear();
        curtain.draw(world.world, portal(new ArenaBounds(3, 70, -20, 3, 90, 0)));
        assertEquals(ParticleCurtain.MAX_PARTICLES, world.particles.size(), "a 21x21 opening is capped");
        for (double[] at : world.particles) {
            assertTrue(at[0] >= 3.4 && at[0] <= 3.6, "x " + at[0]);
            assertTrue(at[2] >= -20 && at[2] < 1, "z " + at[2]);
        }
    }

    @Test
    void theColourChangesFromTheNextDraw() {
        ParticleCurtain curtain = new ParticleCurtain(0x40E0D0, new SplittableRandom(7));
        assertEquals(Color.fromRGB(0x40E0D0), curtain.color());
        curtain.setColor(0xFF8800);
        assertEquals(Color.fromRGB(0xFF8800), curtain.color());
    }
}
