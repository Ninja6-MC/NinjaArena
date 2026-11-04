package com.ninja6.ninjaarena.placeholder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Keeps placeholder traffic from queuing repeated connection attempts on the stats executor. */
final class SnapshotReads {

    private static final long FAILURE_COOLDOWN = TimeUnit.SECONDS.toNanos(5);

    private final LongSupplier clock;
    private boolean pending;
    private boolean failed;
    private long failureTime;

    SnapshotReads(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized <V> CompletableFuture<V> start(Supplier<CompletableFuture<V>> read) {
        if (pending || failed && clock.getAsLong() - failureTime < FAILURE_COOLDOWN) {
            return null;
        }
        pending = true;
        return read.get().whenComplete((value, error) -> complete(error));
    }

    private synchronized void complete(Throwable error) {
        pending = false;
        failed = error != null;
        if (failed) {
            failureTime = clock.getAsLong();
        }
    }
}
