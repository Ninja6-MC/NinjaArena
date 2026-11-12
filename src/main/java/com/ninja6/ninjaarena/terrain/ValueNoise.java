package com.ninja6.ninjaarena.terrain;

/**
 * Seeded value noise in two and three dimensions, with fractal sums of octaves. Each integer
 * lattice point gets a value in {@code [-1, 1)} by hashing its coordinates with the seed
 * through {@link SplitMix64#mix}; points between are blended with the quintic fade
 * {@code 6t^5 - 15t^4 + 10t^3}. Only addition, multiplication, division and
 * {@link Math#floor} are used, which Java defines exactly, so every JVM gives the same bits.
 * There are no permutation tables or caches, so an instance is immutable and safe to share.
 */
final class ValueNoise {

    private static final long PRIME_X = 0x632BE59BD9B4E019L;
    private static final long PRIME_Y = 0x8CB92BA72F3D8DD7L;
    private static final long PRIME_Z = 0xABC98388FB8FAC03L;

    private final long seed;

    ValueNoise(long seed) {
        this.seed = seed;
    }

    /** Two-dimensional noise in {@code [-1, 1)}. */
    double at(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = fade(x - x0);
        double fz = fade(z - z0);
        double a = lerp(lattice(x0, 0, z0), lattice(x0 + 1, 0, z0), fx);
        double b = lerp(lattice(x0, 0, z0 + 1), lattice(x0 + 1, 0, z0 + 1), fx);
        return lerp(a, b, fz);
    }

    /** Three-dimensional noise in {@code [-1, 1)}. */
    double at(double x, double y, double z) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = fade(x - x0);
        double fy = fade(y - y0);
        double fz = fade(z - z0);
        double a = lerp(lattice(x0, y0, z0), lattice(x0 + 1, y0, z0), fx);
        double b = lerp(lattice(x0, y0 + 1, z0), lattice(x0 + 1, y0 + 1, z0), fx);
        double c = lerp(lattice(x0, y0, z0 + 1), lattice(x0 + 1, y0, z0 + 1), fx);
        double d = lerp(lattice(x0, y0 + 1, z0 + 1), lattice(x0 + 1, y0 + 1, z0 + 1), fx);
        return lerp(lerp(a, b, fy), lerp(c, d, fy), fz);
    }

    /**
     * Fractal two-dimensional noise: {@code octaves} layers, each at twice the frequency and
     * half the weight of the one before, scaled back into {@code [-1, 1)}.
     */
    double fractal(double x, double z, int octaves) {
        double sum = 0;
        double weight = 1;
        double total = 0;
        double frequency = 1;
        for (int octave = 0; octave < octaves; octave++) {
            sum += weight * shifted(octave).at(x * frequency, z * frequency);
            total += weight;
            weight *= 0.5;
            frequency *= 2;
        }
        return sum / total;
    }

    /** Fractal three-dimensional noise, as {@link #fractal(double, double, int)}. */
    double fractal(double x, double y, double z, int octaves) {
        double sum = 0;
        double weight = 1;
        double total = 0;
        double frequency = 1;
        for (int octave = 0; octave < octaves; octave++) {
            sum += weight * shifted(octave).at(x * frequency, y * frequency, z * frequency);
            total += weight;
            weight *= 0.5;
            frequency *= 2;
        }
        return sum / total;
    }

    /** Each octave hashes with its own seed, so octaves do not line up on the lattice. */
    private ValueNoise shifted(int octave) {
        return octave == 0 ? this : new ValueNoise(SplitMix64.derive(seed, octave));
    }

    private double lattice(int x, int y, int z) {
        long hash = SplitMix64.mix(seed + x * PRIME_X + y * PRIME_Y + z * PRIME_Z);
        return (hash >>> 11) * 0x1.0p-52 - 1.0;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
