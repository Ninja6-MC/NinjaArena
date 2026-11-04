package com.ninja6.ninjaarena.portal;

import com.ninja6.ninjaarena.arena.ArenaBounds;
import java.util.Objects;
import java.util.random.RandomGenerator;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * A curtain of coloured dust filling the opening, scattered at random positions on each
 * draw so it shimmers. Dust is a vanilla particle with its colour in the packet, so Java and
 * Bedrock players see it without a resource pack. The server sends each particle only to
 * players within particle range of it.
 *
 * <p>Each draw spawns two particles per block of the opening, at most {@link #MAX_PARTICLES};
 * at five draws a second that keeps the largest opening at a few hundred particles a second.
 * Holds nothing between draws, so hiding a portal needs no work.
 */
public final class ParticleCurtain implements PortalSurface {

    static final int MAX_PARTICLES = 48;
    static final float SIZE = 1.2f;

    private final RandomGenerator random;
    private Particle.DustOptions dust;

    /** @param color the curtain colour as {@code 0xRRGGBB} */
    public ParticleCurtain(int color, RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
        setColor(color);
    }

    /** Changes the colour from the next draw on. */
    public void setColor(int color) {
        dust = new Particle.DustOptions(Color.fromRGB(color), SIZE);
    }

    public Color color() {
        return dust.getColor();
    }

    @Override
    public void draw(World world, Portal portal) {
        ArenaBounds opening = portal.region();
        long blocks = (long) (opening.maxX() - opening.minX() + 1) * (opening.maxY() - opening.minY() + 1)
                * (opening.maxZ() - opening.minZ() + 1);
        int count = (int) Math.min(MAX_PARTICLES, blocks * 2);
        for (int i = 0; i < count; i++) {
            world.spawnParticle(Particle.DUST, spread(opening.minX(), opening.maxX()),
                    spread(opening.minY(), opening.maxY()), spread(opening.minZ(), opening.maxZ()),
                    1, 0, 0, 0, 0, dust);
        }
    }

    /**
     * A random coordinate across the blocks from min to max. Across the opening's depth of a
     * single block it stays near the middle, so the curtain reads as a flat sheet.
     */
    private double spread(int min, int max) {
        if (min == max) {
            return min + 0.4 + random.nextDouble() * 0.2;
        }
        return min + random.nextDouble() * (max - min + 1);
    }
}
