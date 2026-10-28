package com.ninja6.ninjaarena.director;

import java.util.Arrays;

/** Damage taken over the last few seconds, in one-second buckets. Not thread-safe. */
public final class DamageWindow {

    private final double[] buckets;
    private int current;

    /** @param seconds how many seconds count, the current one included; at least 1 */
    public DamageWindow(int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("the window must be at least a second");
        }
        buckets = new double[seconds];
    }

    /** Adds damage to the current second. Negative and non-finite amounts are ignored. */
    public void add(double amount) {
        if (Double.isFinite(amount) && amount > 0) {
            buckets[current] += amount;
        }
    }

    /** Starts the next second, dropping the oldest one. */
    public void advance() {
        current = (current + 1) % buckets.length;
        buckets[current] = 0;
    }

    /** Damage in the window. */
    public double total() {
        double sum = 0;
        for (double bucket : buckets) {
            sum += bucket;
        }
        return sum;
    }

    public void clear() {
        Arrays.fill(buckets, 0);
    }
}
