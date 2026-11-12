package com.ninja6.ninjaarena.terrain;

/**
 * The terrain generator's random numbers: SplitMix64 (Steele, Lea and Flood, 2014), written
 * out here so its output depends on nothing but this file. {@link java.util.Random} and
 * {@link java.util.random.RandomGenerator} are avoided because their algorithms are a JDK
 * choice, not a contract this plugin controls. Not thread-safe; each generation run makes
 * its own instances.
 */
final class SplitMix64 {

    private static final long GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    SplitMix64(long seed) {
        state = seed;
    }

    /** The SplitMix64 finaliser: a fixed bijection that scatters the bits of {@code z}. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * An independent seed for one feature of one map. Each feature draws from its own stream,
     * so adding, removing or retuning one feature never shifts the numbers another one sees.
     */
    static long derive(long seed, long stream) {
        return mix(seed ^ mix(stream * GAMMA + GAMMA));
    }

    long nextLong() {
        state += GAMMA;
        return mix(state);
    }

    /** A value in {@code [0, bound)}, by multiplying 31 random bits and shifting. */
    int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return (int) (((nextLong() >>> 33) * bound) >>> 31);
    }

    /** A value in {@code [0, 1)} with 53 random bits. */
    double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }
}
