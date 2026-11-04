package com.ninja6.ninjaarena.placeholder;

import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Bounded snapshots refreshed on demand. Failed reads are retried after the same lifetime.
 *
 * <p>An expired value keeps being served while its refresh waits for another read, a failure
 * cooldown, or its own completion. If refreshes keep failing, the value is dropped once it is
 * more than {@link #MAX_STALE} older than its lifetime; a refresh still in flight for the key
 * always serves the last value.
 */
final class SnapshotCache<K, V> {

    static final long MAX_STALE = TimeUnit.MINUTES.toNanos(5);

    private record Entry<V>(V value, long valueAt, long checkedAt, boolean pending) {
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
        long now = clock.getAsLong();
        Entry<V> entry = entries.get(key);
        if (entry != null && (entry.pending() || now - entry.checkedAt() < lifetime)) {
            return entry.value();
        }
        CompletableFuture<V> result = reads.start(read);
        if (result == null) {
            return entry == null || now - entry.valueAt() - lifetime >= MAX_STALE ? null : entry.value();
        }
        if (entry == null && entries.size() == capacity) {
            entries.pollFirstEntry();
        }
        Entry<V> pending = entry == null ? new Entry<>(null, 0, 0, true)
                : new Entry<>(entry.value(), entry.valueAt(), entry.checkedAt(), true);
        entries.put(key, pending);
        result.whenComplete((value, error) -> complete(key, pending, value, error, lifetime));
        return pending.value();
    }

    private synchronized void complete(K key, Entry<V> pending, V value, Throwable error, long lifetime) {
        if (entries.get(key) != pending) {
            return;
        }
        long now = clock.getAsLong();
        if (error == null) {
            entries.put(key, new Entry<>(value, now, now, false));
        } else {
            boolean keep = pending.value() != null && now - pending.valueAt() - lifetime < MAX_STALE;
            entries.put(key, new Entry<>(keep ? pending.value() : null, pending.valueAt(), now, false));
        }
    }

    synchronized void invalidate() {
        entries.clear();
    }
}
