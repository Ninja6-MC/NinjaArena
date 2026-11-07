package com.ninja6.ninjaarena.texture;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A controllable stand-in for the database; it outlives reconnects like a real one. */
final class MemoryBackend implements PreferenceBackend {
    final Map<PlayerKey, Stored> rows = new HashMap<>();
    volatile boolean failWrites;
    volatile boolean failOpen;
    volatile boolean failReads;
    volatile CountDownLatch gate;
    final AtomicInteger opens = new AtomicInteger();
    final AtomicInteger writes = new AtomicInteger();
    final AtomicInteger loads = new AtomicInteger();

    PreferenceStore.Opener opener() {
        return () -> {
            opens.incrementAndGet();
            if (failOpen) {
                throw new SQLException("unreachable");
            }
            return this;
        };
    }

    @Override
    public synchronized Map<PlayerKey, Stored> loadAll() throws SQLException {
        if (failReads) {
            throw new SQLException("read failed");
        }
        return new HashMap<>(rows);
    }

    @Override
    public Optional<Stored> load(PlayerKey key) throws SQLException {
        if (failReads) {
            loads.incrementAndGet();
            throw new SQLException("read failed");
        }
        Optional<Stored> value;
        synchronized (this) {
            value = Optional.ofNullable(rows.get(key));
        }
        loads.incrementAndGet();
        CountDownLatch latch = gate;
        if (latch != null) {
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return value;
    }

    @Override
    public synchronized Stored save(PlayerKey key, Quality quality, long now) throws SQLException {
        if (failWrites) {
            throw new SQLException("write failed");
        }
        writes.incrementAndGet();
        Stored old = rows.get(key);
        Stored written = new Stored(quality, old == null ? 1 : old.revision() + 1, now);
        rows.put(key, written);
        return written;
    }

    @Override
    public void close() {
    }
}
