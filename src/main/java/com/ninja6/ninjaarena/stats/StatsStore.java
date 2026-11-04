package com.ninja6.ninjaarena.stats;

import com.ninja6.ninjaarena.reward.Award;
import com.ninja6.ninjaarena.reward.RewardSettings;
import com.ninja6.ninjaarena.stats.StatsDatabase.SchemaTooNew;
import com.ninja6.ninjaarena.stats.StatsDatabase.SeasonResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Player stats and seasons, stored in SQLite or MySQL on a thread of their own.
 *
 * <p>Every method returns at once and is safe to call from any thread. Matches are queued by
 * {@link #record} and written together in one transaction per batch; a batch that cannot be
 * written stays queued and is retried with a growing delay, so a database outage loses
 * nothing short of the queue limit or a shutdown that cannot reach the database.
 *
 * <p>Reads return futures that complete on the stats thread, after every match recorded
 * before the read was asked for has been written. Hop back to the server thread (for example
 * with the Bukkit scheduler) before touching the game with the result. A read fails with
 * {@link StatsUnavailableException} while the database cannot be reached.
 */
public final class StatsStore {

    /** Opens the database; only the stats thread calls it. */
    @FunctionalInterface
    interface Opener {
        StatsDatabase open() throws SQLException, SchemaTooNew, ConnectionSource.MissingDriver;
    }

    /** Matches held for writing at most; older ones are dropped, with a warning, beyond it. */
    static final int MAX_PENDING = 10_000;
    /** Matches written in one transaction at most. */
    static final int BATCH = 500;
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final Opener opener;
    private final Logger logger;
    private final Supplier<Instant> clock;
    private final Duration firstRetry;
    private final Duration maxRetry;
    private final ScheduledThreadPoolExecutor executor;
    private final ConcurrentLinkedQueue<MatchRecord> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger batches = new AtomicInteger();
    private volatile boolean closed;
    private volatile boolean available;
    private volatile String permanentFailure;

    // Owned by the stats thread.
    private String season;
    private final ArrayDeque<MatchRecord> unsent = new ArrayDeque<>();
    private StatsDatabase database;
    private Duration retryDelay;
    private ScheduledFuture<?> retry;
    private boolean droppedWarned;
    private boolean failing;

    StatsStore(Opener opener, String season, Logger logger, Supplier<Instant> clock, Duration firstRetry,
            Duration maxRetry) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.season = Objects.requireNonNull(season, "season");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.firstRetry = firstRetry;
        this.maxRetry = maxRetry;
        this.retryDelay = firstRetry;
        executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "NinjaArena stats");
            thread.setDaemon(true);
            return thread;
        });
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
    }

    /**
     * Starts the store for the configured database and connects in the background. A missing
     * driver or an unreachable server is logged; the store then keeps matches queued and
     * retries, and never throws into the caller.
     */
    public static StatsStore open(StatsSettings settings, Path dataFolder, Logger logger) {
        Opener opener = switch (settings.storage()) {
            case SQLITE -> () -> {
                Path file = dataFolder.resolve(settings.sqliteFile());
                try {
                    Files.createDirectories(dataFolder);
                } catch (IOException e) {
                    throw new SQLException("cannot create " + dataFolder + ": " + e.getMessage(), e);
                }
                return StatsDatabase.open(ConnectionSource.sqlite(file, ConnectionSource.SQLITE_DRIVER), Dialect.SQLITE,
                        settings.tablePrefix());
            };
            case MYSQL -> () -> StatsDatabase.open(ConnectionSource.mysql(settings.mysql(), ConnectionSource.MYSQL_DRIVER),
                    Dialect.MYSQL, settings.tablePrefix());
        };
        StatsStore store = new StatsStore(opener, settings.season(), logger, Instant::now, Duration.ofSeconds(5),
                Duration.ofMinutes(5));
        store.connect();
        return store;
    }

    /** Opens the database now, in the background, so a configuration problem shows at startup. */
    void connect() {
        run(this::connection);
    }

    /**
     * Queues a finished match. Never blocks; the write happens on the stats thread, batched
     * with any other match waiting for it.
     */
    public void record(MatchRecord match) {
        Objects.requireNonNull(match, "match");
        if (closed || permanentFailure != null) {
            logger.warning("Stats: a match was not recorded: "
                    + (closed ? "stats are closed" : permanentFailure) + ".");
            return;
        }
        inbox.add(match);
        pending.incrementAndGet();
        if (!run(this::drain)) {
            logger.warning("Stats: a match was not recorded: stats are closed.");
        }
    }

    /** Matches queued and not yet written. */
    public int pending() {
        return pending.get();
    }

    /** Whether the last use of the database succeeded. */
    public boolean isAvailable() {
        return available && permanentFailure == null;
    }

    /** Writes everything queued; fails if any of it could not be written. */
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        boolean queued = run(() -> {
            drain();
            if (unsent.isEmpty()) {
                future.complete(null);
            } else {
                future.completeExceptionally(new StatsUnavailableException("the stats database cannot be reached; "
                        + unsent.size() + " match(es) wait to be written"));
            }
        }, future);
        if (!queued) {
            future.completeExceptionally(new StatsUnavailableException("stats are closed"));
        }
        return future;
    }

    /** One player's stats; empty if nothing is recorded for them in the scope. */
    public CompletableFuture<Optional<PlayerStats>> stats(UUID player, StatsScope scope) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(scope, "scope");
        return submit(database -> database.stats(player, scope));
    }

    /**
     * The best players of a stat, at most {@code limit} of them, best first. Players whose
     * value is zero are left out.
     */
    public CompletableFuture<List<LeaderboardEntry>> top(Stat stat, StatsScope scope, int limit) {
        Objects.requireNonNull(stat, "stat");
        Objects.requireNonNull(scope, "scope");
        if (limit < 1 || limit > 1000) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("limit must be from 1 to 1000"));
        }
        return submit(database -> database.top(stat, scope, limit));
    }

    public CompletableFuture<List<Award>> awards(UUID player) {
        return submit(database -> database.awards(player));
    }

    public CompletableFuture<List<Award>> evaluateMilestones(UUID player, List<RewardSettings.Rule> rules) {
        return submit(database -> {
            if (!rules.isEmpty()) {
                PlayerStats totals = database.stats(player, StatsScope.ALL_TIME).orElseGet(() -> PlayerStats.empty(player));
                for (RewardSettings.Rule rule : rules) {
                    if (totals.value(rule.stat()) >= rule.target()) {
                        database.grant(player, 0, rule);
                    }
                }
            }
            return database.awards(player);
        });
    }

    public CompletableFuture<Void> awardPlacings(List<RewardSettings.Rule> rules) {
        return submit(database -> {
            for (Season archived : database.seasons()) {
                if (archived.isCurrent()) {
                    continue;
                }
                for (RewardSettings.Rule rule : rules) {
                    database.awardPlacing(archived.id(), rule);
                }
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> trophyClaim(UUID player, Award award, UUID claim, boolean release) {
        return submit(database -> database.trophyClaim(player, award, claim, release));
    }

    public CompletableFuture<Long> reservePayout(UUID player, UUID match, long day, RewardSettings.Payout payout) {
        return submit(database -> database.reservePayout(player, match, day, payout));
    }

    /** The current season; empty only before the first connection created one. */
    public CompletableFuture<Optional<Season>> currentSeason() {
        return submit(StatsDatabase::currentSeason);
    }

    /** Every season, oldest first, the current one included. */
    public CompletableFuture<List<Season>> seasons() {
        return submit(StatsDatabase::seasons);
    }

    /**
     * Archives the current season and starts a new one. Matches recorded before this call
     * still count towards the archived season. The archived season's stats are kept and stay
     * readable by its id.
     *
     * <p>{@code stats.season} in {@code config.yml} still names the season the server
     * expects at startup; set it to the same name, or the startup check reports that it names
     * an archived season.
     *
     * @return the new season; fails with {@link IllegalArgumentException} if the name is
     *         invalid or already used
     */
    public CompletableFuture<Season> startSeason(String name) {
        String problem = name == null ? "expected a name" : StatsSettings.problem("stats.season", name);
        if (problem != null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("season name: " + problem));
        }
        return submit(database -> {
            Season started = database.startSeason(name, clock.get());
            season = name;
            logger.info("Stats: started season " + name + "; the previous season was archived.");
            return started;
        });
    }

    /**
     * Makes the configured season current: as at startup, a new name archives the current
     * season and a name an archived season uses is refused. Used by the config reload.
     *
     * @return the season current afterwards
     */
    public CompletableFuture<Optional<Season>> useSeason(String name) {
        String problem = name == null ? "expected a name" : StatsSettings.problem("stats.season", name);
        if (problem != null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("season name: " + problem));
        }
        // Set on the stats thread, in order: a connection opened before this keeps the old name.
        run(() -> season = name);
        return submit(database -> Optional.ofNullable(applySeason(database).season()));
    }

    /**
     * Writes what is queued and stops the stats thread, waiting up to ten seconds. Call it on
     * plugin disable, after the matches have ended. Matches still unwritten are reported.
     */
    public void close() {
        close(CLOSE_TIMEOUT);
    }

    void close(Duration timeout) {
        if (closed) {
            return;
        }
        closed = true;
        run(() -> {
            if (retry != null) {
                retry.cancel(false);
                retry = null;
            }
            drain();
            if (database != null) {
                database.close();
                database = null;
            }
            available = false;
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                logger.severe("Stats: the database did not finish within " + timeout.toSeconds() + " seconds; "
                        + pending.get() + " match(es) were not recorded.");
                return;
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (pending.get() > 0) {
            logger.severe("Stats: " + pending.get() + " match(es) could not be written before shutdown and were lost.");
        }
    }

    /** Transactions that wrote at least one match; for tests. */
    int batches() {
        return batches.get();
    }

    @FunctionalInterface
    private interface Query<T> {
        T run(StatsDatabase database) throws SQLException;
    }

    private <T> CompletableFuture<T> submit(Query<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        boolean queued = run(() -> {
            drain();
            if (!unsent.isEmpty()) {
                future.completeExceptionally(new StatsUnavailableException(permanentFailure != null ? permanentFailure
                        : "the stats database cannot be reached; " + unsent.size() + " match(es) wait to be written"));
                return;
            }
            StatsDatabase open = connection();
            if (open == null) {
                future.completeExceptionally(new StatsUnavailableException(permanentFailure != null ? permanentFailure
                        : "the stats database cannot be reached"));
                return;
            }
            try {
                future.complete(query.run(open));
            } catch (SQLException e) {
                lost(e);
                future.completeExceptionally(new StatsUnavailableException("the stats database failed: "
                        + e.getMessage(), e));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }, future);
        if (!queued) {
            future.completeExceptionally(new StatsUnavailableException("stats are closed"));
        }
        return future;
    }

    private boolean run(Runnable task) {
        return run(task, null);
    }

    /** Queues a task; an unexpected failure is logged and, if given, fails the future. */
    private boolean run(Runnable task, CompletableFuture<?> future) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException | Error e) {
                    logger.log(Level.SEVERE, "Stats: unexpected failure", e);
                    if (future != null) {
                        future.completeExceptionally(e);
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Writes queued matches in batches; on failure keeps them and schedules a retry. */
    private void drain() {
        for (MatchRecord match; (match = inbox.poll()) != null; ) {
            unsent.addLast(match);
        }
        int dropped = 0;
        while (unsent.size() > MAX_PENDING) {
            unsent.removeFirst();
            pending.decrementAndGet();
            dropped++;
        }
        if (dropped > 0) {
            logger.severe("Stats: dropped the " + dropped + " oldest unwritten match(es); the queue holds at most "
                    + MAX_PENDING + ".");
        }
        if (permanentFailure != null && !unsent.isEmpty()) {
            if (!droppedWarned) {
                droppedWarned = true;
                logger.severe("Stats: " + unsent.size() + " match(es) were not recorded: " + permanentFailure + ".");
            }
            pending.addAndGet(-unsent.size());
            unsent.clear();
            return;
        }
        while (!unsent.isEmpty()) {
            StatsDatabase open = connection();
            if (open == null) {
                scheduleRetry();
                return;
            }
            List<MatchRecord> batch = new ArrayList<>(Math.min(BATCH, unsent.size()));
            var iterator = unsent.iterator();
            while (batch.size() < BATCH && iterator.hasNext()) {
                batch.add(iterator.next());
            }
            try {
                open.write(batch);
                batches.incrementAndGet();
            } catch (SQLException e) {
                if (!rejected(e)) {
                    lost(e);
                    scheduleRetry();
                    return;
                }
                // The database refused the data itself; retrying the same batch would block the
                // queue for good, so write the matches one at a time and drop only those refused.
                if (!writeEach(open, batch)) {
                    scheduleRetry();
                    return;
                }
            }
            for (int i = 0; i < batch.size(); i++) {
                unsent.removeFirst();
            }
            pending.addAndGet(-batch.size());
        }
    }

    /** Whether the database refused the statement or data, rather than the connection failing. */
    private static boolean rejected(SQLException e) {
        return e instanceof SQLNonTransientException && !(e instanceof SQLNonTransientConnectionException);
    }

    /**
     * Writes a refused batch match by match. Returns false, leaving the batch queued, when the
     * connection fails part way; matches already written are then removed from the batch.
     */
    private boolean writeEach(StatsDatabase open, List<MatchRecord> batch) {
        var iterator = batch.iterator();
        while (iterator.hasNext()) {
            MatchRecord match = iterator.next();
            try {
                open.write(List.of(match));
                batches.incrementAndGet();
            } catch (SQLException e) {
                if (!rejected(e)) {
                    lost(e);
                    return false;
                }
                logger.severe("Stats: the database refused a match ended at " + match.endedAt() + "; it was dropped: "
                        + e.getMessage());
            }
            iterator.remove();
            unsent.remove(match);
            pending.decrementAndGet();
        }
        return true;
    }

    /** The open database, reconnecting if needed; {@code null} when it cannot be reached. */
    private StatsDatabase connection() {
        if (permanentFailure != null) {
            return null;
        }
        if (database != null) {
            if (database.isValid()) {
                return database;
            }
            database.close();
            database = null;
        }
        try {
            StatsDatabase opened = opener.open();
            database = opened;
            applySeason(opened);
            retryDelay = firstRetry;
            available = true;
            if (failing) {
                failing = false;
                logger.info("Stats: reconnected to the stats database.");
            }
            return opened;
        } catch (SchemaTooNew | ConnectionSource.MissingDriver e) {
            permanentFailure = e.getMessage();
            available = false;
            if (database != null) {
                database.close();
                database = null;
            }
            logger.severe("Stats: stats are off: " + e.getMessage() + ". Matches still run; nothing is recorded.");
            return null;
        } catch (SQLException | RuntimeException e) {
            lost(e);
            return null;
        }
    }

    private SeasonResult applySeason(StatsDatabase open) throws SQLException {
        String name = season;
        SeasonResult result = open.useSeason(name, clock.get());
        String current = result.season() == null ? "none" : result.season().name();
        switch (result.change()) {
            case CREATED -> logger.info("Stats: created season " + name + ".");
            case STARTED -> logger.info("Stats: archived the previous season and started " + name + ".");
            case REFUSED -> logger.warning("Stats: stats.season names " + name
                    + ", an archived season; the current season stays " + current + ".");
            case UNCHANGED -> { }
        }
        return result;
    }

    private void lost(Exception e) {
        available = false;
        failing = true;
        if (database != null) {
            database.close();
            database = null;
        }
        logger.warning("Stats: the stats database cannot be used: " + e.getMessage()
                + (unsent.isEmpty() ? "" : "; " + unsent.size() + " match(es) wait to be written"));
    }

    private void scheduleRetry() {
        if (closed || retry != null && !retry.isDone()) {
            return;
        }
        Duration delay = retryDelay;
        retryDelay = retryDelay.multipliedBy(2).compareTo(maxRetry) > 0 ? maxRetry : retryDelay.multipliedBy(2);
        try {
            retry = executor.schedule(() -> {
                retry = null;
                try {
                    drain();
                } catch (RuntimeException e) {
                    logger.log(Level.SEVERE, "Stats: unexpected failure", e);
                }
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Shutting down: the final drain is the last attempt.
        }
    }
}
