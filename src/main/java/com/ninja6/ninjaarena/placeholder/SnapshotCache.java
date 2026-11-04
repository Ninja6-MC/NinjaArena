package com.ninja6.ninjaarena.placeholder;

import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded snapshots refreshed on demand. Failed reads are retried after the same lifetime. */
final class SnapshotCache<K, V> {

    private record Entry<V>(V value, long completedAt, boolean pending) {
    }

    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final int capacity;
    private final LongSupplier clock;

    private final SnapshotReads reads;

    SnapshotCache(int capacity, LongSupplier clock, SnapshotReads reads) {
        this.capacity = capacity;
        this.clock = clock;
        this.reads = reads;
    }

    synchronized V get(K key, long lifetime, Supplier<CompletableFuture<V>> read) {
        Entry<V> entry = entries.get(key);
        if (entry != null && (entry.pending() || clock.getAsLong() - entry.completedAt() < lifetime)) {
            return entry.value();
        }
        CompletableFuture<V> result = reads.start(read);
        if (result == null) {
            return null;
        }
        if (entry == null && entries.size() == capacity) {
            entries.pollFirstEntry();
        }
        Entry<V> pending = new Entry<>(null, 0, true);
        entries.put(key, pending);
        result.whenComplete((value, error) -> complete(key, pending, error == null ? value : null));
        return null;
    }

    private synchronized void complete(K key, Entry<V> pending, V value) {
        if (entries.get(key) == pending) {
            entries.put(key, new Entry<>(value, clock.getAsLong(), false));
        }
    }

    synchronized void invalidate() {
        entries.clear();
    }
}
