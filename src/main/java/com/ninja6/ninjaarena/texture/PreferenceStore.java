package com.ninja6.ninjaarena.texture;

import com.ninja6.ninjaarena.message.Keyed;
import com.ninja6.ninjaarena.message.Messages;
import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import com.ninja6.ninjaarena.stats.ConnectionSource;
import com.ninja6.ninjaarena.stats.StatsSettings;
import com.ninja6.ninjaarena.texture.PreferenceBackend.SchemaTooNew;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Explicit texture choices, stored in the same database as the stats but in tables of their
 * own, and kept in a committed in-memory snapshot.
 *
 * <p>All database work runs on one thread of the store's own, so writes are serialized and
 * the server thread never waits. A write publishes its committed value to the snapshot
 * before its future completes. {@link #snapshot} and {@link #explicit} read only that
 * memory and are safe from any thread, including Geyser's event thread.
 *
 * <p>While the database cannot be reached the store is {@link Health#DEGRADED}: reads of the
 * snapshot answer Standard, writes fail, and no stored choice is touched. It reconnects in
 * the background. A database written by a newer build is left alone.
 */
public final class PreferenceStore implements AutoCloseable {

    public enum Health {
        /** Connecting and loading the snapshot. */
        STARTING,
        READY,
        /** The database cannot be reached; retrying. */
        DEGRADED,
        /** A newer schema or a missing driver; retrying cannot help. */
        UNSUPPORTED
    }

    /** The database could not be used for this request. */
    public static final class Unavailable extends RuntimeException {
        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Opens the database; only the store thread calls it. */
    @FunctionalInterface
    interface Opener {
        PreferenceBackend open() throws SQLException, SchemaTooNew, ConnectionSource.MissingDriver;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws SQLException;
    }

    private final Opener opener;
    private final Logger logger;
    private final LongSupplier clock;
    private final Duration retryDelay;
    private final ScheduledThreadPoolExecutor executor;
    private final Map<PlayerKey, Stored> cache = new ConcurrentHashMap<>();
    private final List<Runnable> readyListeners = new CopyOnWriteArrayList<>();
    private volatile Health health = Health.STARTING;
    private volatile String problem = "";
    /** The UNSUPPORTED cause, worded by messages.yml for players; null otherwise. */
    private volatile Keyed unsupported;
    private volatile boolean closed;
    private volatile boolean everReady;

    // Owned by the store thread.
    private PreferenceBackend backend;
    private ScheduledFuture<?> retry;
    private boolean failureLogged;

    PreferenceStore(Opener opener, Logger logger, LongSupplier clock, Duration retryDelay) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "NinjaArena texture preferences");
            thread.setDaemon(true);
            return thread;
        });
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
    }

    /**
     * Starts the store on the storage the stats settings choose, whether or not statistics are
     * enabled, and connects in the background. It never throws into the caller.
     */
    public static PreferenceStore open(StatsSettings settings, Path dataFolder, Logger logger) {
        Opener opener = switch (settings.storage()) {
            case SQLITE -> () -> {
                try {
                    Files.createDirectories(dataFolder);
                } catch (IOException e) {
                    throw new SQLException("cannot create " + dataFolder + ": " + e.getMessage(), e);
                }
                return SqlPreferenceBackend.open(
                        ConnectionSource.sqlite(dataFolder.resolve(settings.sqliteFile()), ConnectionSource.SQLITE_DRIVER),
                        StatsSettings.Storage.SQLITE, settings.tablePrefix());
            };
            case MYSQL -> () -> SqlPreferenceBackend.open(
                    ConnectionSource.mysql(settings.mysql(), ConnectionSource.MYSQL_DRIVER),
                    StatsSettings.Storage.MYSQL, settings.tablePrefix());
        };
        PreferenceStore store = new PreferenceStore(opener, logger, System::currentTimeMillis, Duration.ofSeconds(30));
        store.start();
        return store;
    }

    void start() {
        try {
            executor.execute(this::ensureOpen);
        } catch (RejectedExecutionException ignored) {
            // Closed before it started.
        }
    }

    public Health health() {
        return health;
    }

    public boolean isReady() {
        return health == Health.READY;
    }

    /** Why the store is not ready, or an empty text, in the words players see. */
    public String problem() {
        Keyed cause = unsupported;
        return health == Health.UNSUPPORTED && cause != null
                ? Messages.text(cause.key(), cause.placeholders()) : problem;
    }

    /**
     * Runs {@code action} once the snapshot has first loaded; at once if it already has. It
     * runs on the store thread or the caller's, so it must be quick and thread-safe.
     */
    public void whenReady(Runnable action) {
        Objects.requireNonNull(action, "action");
        readyListeners.add(action);
        if (everReady) {
            action.run();
        }
    }

    /**
     * The choice for the identity, from memory only: Standard when nothing is stored, and
     * Standard for everyone while the store is not ready.
     */
    public Quality snapshot(PlayerKey key) {
        if (health != Health.READY) {
            return Quality.STANDARD;
        }
        Stored stored = cache.get(key);
        return stored == null ? Quality.STANDARD : stored.quality();
    }

    /** The explicit choice last committed or loaded, from memory; empty when there is none. */
    public Optional<Quality> explicit(PlayerKey key) {
        return Optional.ofNullable(cache.get(key)).map(Stored::quality);
    }

    /** Reads the identity's row, refreshing the snapshot. Completes off the server thread. */
    public CompletableFuture<Optional<Stored>> load(PlayerKey key) {
        Objects.requireNonNull(key, "key");
        return submit(() -> {
            Optional<Stored> stored = backend.load(key);
            stored.ifPresent(value -> merge(key, value));
            return stored;
        });
    }

    /**
     * Commits an explicit choice, publishes it to the snapshot and only then completes. A
     * failure leaves both the database and the snapshot as they were.
     */
    public CompletableFuture<Stored> save(PlayerKey key, Quality quality) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(quality, "quality");
        return submit(() -> {
            Stored stored = backend.save(key, quality, clock.getAsLong());
            cache.merge(key, stored, (old, written) -> written);
            return stored;
        });
    }

    private void merge(PlayerKey key, Stored loaded) {
        cache.merge(key, loaded, (old, fresh) -> fresh.revision() >= old.revision() ? fresh : old);
    }

    private <T> CompletableFuture<T> submit(Work<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    if (!ensureOpen()) {
                        future.completeExceptionally(new Unavailable(problem.isEmpty()
                                ? "texture preference storage is not available" : problem, null));
                        return;
                    }
                    future.complete(work.run());
                } catch (SQLException e) {
                    failed(e);
                    future.completeExceptionally(new Unavailable("texture preference storage failed: "
                            + e.getMessage(), e));
                } catch (RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new Unavailable("texture preference storage is closed", e));
        }
        return future;
    }

    /** Opens the database and loads the snapshot when it is not open. Store thread only. */
    private boolean ensureOpen() {
        if (backend != null) {
            return true;
        }
        if (closed || health == Health.UNSUPPORTED) {
            return false;
        }
        try {
            PreferenceBackend opened = opener.open();
            try {
                opened.loadAll().forEach(this::merge);
            } catch (SQLException | RuntimeException e) {
                opened.close();
                throw e;
            }
            backend = opened;
        } catch (SchemaTooNew | ConnectionSource.MissingDriver e) {
            health = Health.UNSUPPORTED;
            problem = e.getMessage();
            unsupported = (Keyed) e;
            logger.severe("Texture preferences are off: " + e.getMessage() + ".");
            return false;
        } catch (SQLException | RuntimeException e) {
            failed(e);
            return false;
        }
        health = Health.READY;
        problem = "";
        if (failureLogged) {
            logger.info("Texture preference storage is available again.");
            failureLogged = false;
        }
        if (!everReady) {
            everReady = true;
            readyListeners.forEach(Runnable::run);
        }
        return true;
    }

    /** Drops the connection, reports once and schedules a reconnect. Store thread only. */
    private void failed(Exception failure) {
        if (backend != null) {
            backend.close();
            backend = null;
        }
        if (health != Health.UNSUPPORTED) {
            health = Health.DEGRADED;
        }
        problem = "texture preference storage cannot be reached (" + failure.getMessage() + ")";
        if (!failureLogged) {
            failureLogged = true;
            logger.warning("Texture preferences: " + problem + ". Players are given Standard and nothing is"
                    + " overwritten until it recovers.");
        }
        if (!closed && health == Health.DEGRADED && (retry == null || retry.isDone())) {
            try {
                retry = executor.schedule(this::ensureOpen, retryDelay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // Closed meanwhile.
            }
        }
    }

    /** Finishes queued writes, then closes the database. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            executor.execute(() -> {
                if (backend != null) {
                    backend.close();
                    backend = null;
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Already shut down.
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("Texture preferences: storage did not finish closing within 5 seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
