package com.ninja6.ninjaarena.stats;

import static com.ninja6.ninjaarena.stats.Records.ALICE;
import static com.ninja6.ninjaarena.stats.Records.BOB;
import static com.ninja6.ninjaarena.stats.Records.CAROL;
import static com.ninja6.ninjaarena.stats.Records.T0;
import static com.ninja6.ninjaarena.stats.Records.match;
import static com.ninja6.ninjaarena.stats.Records.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsStoreTest {

    @TempDir
    Path dir;

    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private final List<StatsStore> stores = new ArrayList<>();
    private Logger logger;

    @BeforeEach
    void captureLog() {
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                log.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @AfterEach
    void closeAll() {
        stores.forEach(store -> store.close(Duration.ofSeconds(5)));
    }

    private StatsStore store(ConnectionSource source, String season) {
        StatsStore store = new StatsStore(() -> StatsDatabase.open(source, Dialect.SQLITE, "na_"), season, logger,
                Instant::now, Duration.ofMillis(20), Duration.ofMillis(80));
        stores.add(store);
        store.connect();
        return store;
    }

    private StatsStore store(ConnectionSource source) {
        return store(source, "Season 1");
    }

    private Faults faults() throws Exception {
        return new Faults(Records.sqliteSource(dir.resolve("stats.db")));
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS)).getCause();
    }

    private static void eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void recordedMatchesAreReadableAfterwardsAndTheSeasonIsCreatedOnConnect() throws Exception {
        StatsStore store = store(faults());
        store.record(match(player(ALICE, "Alice", 1, 2, 3, 4, 5, 6)));
        assertEquals(Optional.of(new PlayerStats(ALICE, "Alice", 1, 2, 3, 4, 5, 6, 1)),
                get(store.stats(ALICE, StatsScope.CURRENT_SEASON)));
        assertEquals("Season 1", get(store.currentSeason()).orElseThrow().name());
        assertEquals(1, get(store.top(Stat.WINS, StatsScope.ALL_TIME, 10)).size());
        assertTrue(store.isAvailable());
        assertEquals(0, store.pending());
        assertTrue(log.contains("INFO Stats: created season Season 1."), log.toString());
    }

    @Test
    void matchesWaitingTogetherAreWrittenInOneTransaction() throws Exception {
        Faults faults = faults();
        CountDownLatch gate = new CountDownLatch(1);
        faults.gate = gate;
        StatsStore store = store(faults);
        for (int i = 0; i < 25; i++) {
            store.record(match(T0.plusSeconds(i), player(ALICE, "Alice", 1, 0, 0, 0, 0, i)));
        }
        assertEquals(25, store.pending());
        faults.gate = null;
        gate.countDown();
        get(store.flush());
        assertEquals(1, store.batches());
        assertEquals(new PlayerStats(ALICE, "Alice", 25, 0, 0, 0, 0, 24, 25),
                get(store.stats(ALICE, StatsScope.ALL_TIME)).orElseThrow());
    }

    @Test
    void anUnreachableDatabaseKeepsMatchesQueuedAndWritesThemOnceItIsBack() throws Exception {
        Faults faults = faults();
        faults.connectFailures.set(3);
        StatsStore store = store(faults);
        store.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
        eventually(() -> store.pending() == 0);
        assertTrue(faults.connects.get() >= 4, "retried " + faults.connects.get());
        assertEquals(1, get(store.stats(ALICE, StatsScope.ALL_TIME)).orElseThrow().wins());
        assertTrue(log.stream().anyMatch(line -> line.startsWith("WARNING Stats: the stats database cannot be used")));
        assertTrue(log.contains("INFO Stats: reconnected to the stats database."), log.toString());
    }

    @Test
    void aConnectionLostMidWriteRollsBackAndTheRetryWritesEverythingOnce() throws Exception {
        Faults faults = faults();
        StatsStore store = store(faults);
        get(store.flush());
        faults.failPlayers = true;
        store.record(match(player(ALICE, "Alice", 1, 0, 2, 0, 0, 0)));
        CompletableFuture<Void> flushed = store.flush();
        assertInstanceOf(StatsUnavailableException.class, failure(flushed));
        assertEquals(1, store.pending());
        faults.failPlayers = false;
        eventually(() -> store.pending() == 0);
        PlayerStats alice = get(store.stats(ALICE, StatsScope.ALL_TIME)).orElseThrow();
        assertEquals(1, alice.wins());
        assertEquals(2, alice.playerKills());
    }

    @Test
    void readsFailCleanlyWhileTheDatabaseIsDown() throws Exception {
        Faults faults = faults();
        faults.connectFailures.set(1_000_000);
        StatsStore store = store(faults);
        assertInstanceOf(StatsUnavailableException.class, failure(store.stats(ALICE, StatsScope.ALL_TIME)));
        assertInstanceOf(StatsUnavailableException.class, failure(store.seasons()));
        assertFalse(store.isAvailable());
        store.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
        assertEquals(1, store.pending());
    }

    @Test
    void aMissingDriverTurnsStatsOffWithoutThrowingIntoTheGame() throws Exception {
        StatsStore store = new StatsStore(() -> StatsDatabase.open(ConnectionSource.sqlite(dir.resolve("x.db"),
                "org.example.NoSuchDriver"), Dialect.SQLITE, "na_"), "Season 1", logger, Instant::now,
                Duration.ofMillis(20), Duration.ofMillis(80));
        stores.add(store);
        store.connect();
        Throwable failure = failure(store.currentSeason());
        assertInstanceOf(StatsUnavailableException.class, failure);
        assertTrue(failure.getMessage().contains("org.example.NoSuchDriver"), failure.getMessage());
        store.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
        assertEquals(0, store.pending());
        assertFalse(store.isAvailable());
        assertTrue(log.stream().anyMatch(line -> line.startsWith("SEVERE Stats: stats are off")), log.toString());
    }

    @Test
    void anUnreachableMySqlServerIsRetriedWithoutBreakingThePlugin() throws Exception {
        var settings = new StatsSettings.MySql("127.0.0.1", 1, "ninjaarena", "ninjaarena", "secret");
        StatsStore store = new StatsStore(() -> StatsDatabase.open(ConnectionSource.mysql(settings,
                ConnectionSource.MYSQL_DRIVER), Dialect.MYSQL, "na_"), "Season 1", logger, Instant::now,
                Duration.ofSeconds(30), Duration.ofSeconds(60));
        stores.add(store);
        store.connect();
        store.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
        assertInstanceOf(StatsUnavailableException.class, failure(store.stats(ALICE, StatsScope.ALL_TIME)));
        assertEquals(1, store.pending());
        assertFalse(store.isAvailable());
        assertTrue(log.stream().noneMatch(line -> line.contains("secret")), log.toString());
        assertTrue(log.stream().noneMatch(line -> line.contains("stats are off")), log.toString());
    }

    @Test
    void aSchemaFromANewerBuildTurnsStatsOffForGood() throws Exception {
        Faults faults = faults();
        StatsDatabase.open(faults, Dialect.SQLITE, "na_").close();
        try (var connection = faults.connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE na_schema_version SET version = 7");
        }
        int before = faults.connects.get();
        StatsStore store = store(faults);
        assertInstanceOf(StatsUnavailableException.class, failure(store.currentSeason()));
        store.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
        Thread.sleep(150);
        assertEquals(before + 1, faults.connects.get());
        assertTrue(log.stream().anyMatch(line -> line.contains("schema is version 7")), log.toString());
    }

    @Test
    void refusedDataDropsOnlyTheMatchTheDatabaseRejects() throws Exception {
        Faults faults = faults();
        // Hold the stats thread in its first connect so all three matches meet in one batch.
        CountDownLatch gate = new CountDownLatch(1);
        faults.gate = gate;
        StatsStore store = store(faults);
        faults.reject = BOB;
        store.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
        store.record(match(player(BOB, "Bob", 1, 0, 0, 0, 0, 0)));
        store.record(match(player(CAROL, "Carol", 1, 0, 0, 0, 0, 0)));
        faults.gate = null;
        gate.countDown();
        get(store.flush());
        assertEquals(2, store.batches());
        faults.reject = null;
        assertEquals(0, store.pending());
        assertTrue(get(store.stats(ALICE, StatsScope.ALL_TIME)).isPresent());
        assertEquals(Optional.empty(), get(store.stats(BOB, StatsScope.ALL_TIME)));
        assertTrue(get(store.stats(CAROL, StatsScope.ALL_TIME)).isPresent());
        assertTrue(log.stream().anyMatch(line -> line.startsWith("SEVERE Stats: the database refused a match")));
    }

    @Test
    void startingASeasonArchivesTheOldOneAndEarlierMatchesStayInIt() throws Exception {
        StatsStore store = store(faults());
        store.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
        Season second = get(store.startSeason("Season 2"));
        store.record(match(player(ALICE, "Alice", 0, 0, 3, 0, 0, 0)));
        assertEquals(2, second.id());
        assertEquals(1, get(store.stats(ALICE, StatsScope.season(1))).orElseThrow().wins());
        assertEquals(0, get(store.stats(ALICE, StatsScope.season(1))).orElseThrow().playerKills());
        assertEquals(3, get(store.stats(ALICE, StatsScope.CURRENT_SEASON)).orElseThrow().playerKills());
        assertEquals(1, get(store.stats(ALICE, StatsScope.ALL_TIME)).orElseThrow().wins());
        assertEquals(2, get(store.seasons()).size());
        assertInstanceOf(IllegalArgumentException.class, failure(store.startSeason("Season 1")));
        assertInstanceOf(IllegalArgumentException.class, failure(store.startSeason(" padded ")));
        assertInstanceOf(IllegalArgumentException.class, failure(store.top(Stat.WINS, StatsScope.ALL_TIME, 0)));
    }

    @Test
    void theConfiguredSeasonArchivesOnChangeAndRefusesAnArchivedName() throws Exception {
        Faults faults = faults();
        StatsStore store = store(faults);
        assertEquals("Season 2", get(store.useSeason("Season 2")).orElseThrow().name());
        assertEquals("Season 2", get(store.useSeason("Season 1")).orElseThrow().name());
        assertTrue(log.stream().anyMatch(line -> line.contains("names Season 1, an archived season")), log.toString());
        store.close(Duration.ofSeconds(5));
        // A restart with a stale name keeps the current season too.
        StatsStore restarted = store(faults, "Season 1");
        assertEquals("Season 2", get(restarted.currentSeason()).orElseThrow().name());
        assertEquals(2, get(restarted.seasons()).size());
    }

    @Test
    void closeFlushesQueuedMatchesAndRefusesLaterOnes() throws Exception {
        Faults faults = faults();
        CountDownLatch gate = new CountDownLatch(1);
        faults.gate = gate;
        StatsStore store = store(faults);
        store.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
        faults.gate = null;
        gate.countDown();
        store.close(Duration.ofSeconds(5));
        assertEquals(0, store.pending());
        store.record(match(player(BOB, "Bob", 1, 0, 0, 0, 0, 0)));
        assertInstanceOf(StatsUnavailableException.class, failure(store.stats(ALICE, StatsScope.ALL_TIME)));
        StatsStore reopened = store(faults);
        assertEquals(1, get(reopened.stats(ALICE, StatsScope.ALL_TIME)).orElseThrow().wins());
        assertEquals(Optional.empty(), get(reopened.stats(BOB, StatsScope.ALL_TIME)));
    }

    @Test
    void closeReportsMatchesItCouldNotWrite() throws Exception {
        Faults faults = faults();
        faults.connectFailures.set(1_000_000);
        StatsStore store = store(faults);
        store.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
        store.close(Duration.ofSeconds(5));
        assertTrue(log.stream().anyMatch(line -> line.startsWith("SEVERE Stats: 1 match(es) could not be written")),
                log.toString());
    }

    @Test
    void recordsFromManyThreadsAndConcurrentReadsAllCount() throws Exception {
        StatsStore store = store(faults());
        int threads = 8;
        int each = 50;
        List<Thread> workers = new ArrayList<>();
        List<CompletableFuture<?>> reads = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            UUID id = new UUID(1, t);
            Thread worker = new Thread(() -> {
                for (int i = 0; i < each; i++) {
                    store.record(match(T0.plusSeconds(i), player(id, "P" + id.getLeastSignificantBits(), 0, 0, 1, 1,
                            0, i)));
                    store.record(match(T0.plusSeconds(i), player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
                    if (i % 10 == 0) {
                        reads.add(store.top(Stat.WINS, StatsScope.ALL_TIME, 5));
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        get(store.flush());
        for (CompletableFuture<?> read : reads) {
            get(read);
        }
        assertEquals(threads * each, get(store.stats(ALICE, StatsScope.ALL_TIME)).orElseThrow().wins());
        for (int t = 0; t < threads; t++) {
            PlayerStats stats = get(store.stats(new UUID(1, t), StatsScope.CURRENT_SEASON)).orElseThrow();
            assertEquals(each, stats.playerKills());
            assertEquals(each - 1, stats.bestOverrunSeconds());
        }
    }

    @Test
    void twoServersSharingOneDatabaseAddToEachOthersTotals() throws Exception {
        StatsStore first = store(faults());
        StatsStore second = store(faults());
        for (int i = 0; i < 40; i++) {
            first.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
            second.record(match(player(ALICE, "Alice", 0, 0, 1, 0, 0, 0)));
        }
        get(first.flush());
        get(second.flush());
        PlayerStats alice = get(first.stats(ALICE, StatsScope.ALL_TIME)).orElseThrow();
        assertEquals(40, alice.wins());
        assertEquals(40, alice.playerKills());
        assertEquals(80, alice.matchesPlayed());
        assertEquals(1, get(second.seasons()).size());
    }

    @Test
    void theFactoryOpensSqliteInTheDataFolder() throws Exception {
        var settings = new StatsSettings(true, StatsSettings.Storage.SQLITE, "Opening", "x_", "arena-stats.db",
                StatsSettings.DEFAULTS.mysql());
        StatsStore store = StatsStore.open(settings, dir.resolve("plugin"), logger);
        stores.add(store);
        store.record(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0)));
        assertEquals("Opening", get(store.currentSeason()).orElseThrow().name());
        assertTrue(java.nio.file.Files.exists(dir.resolve("plugin/arena-stats.db")));
        assertTrue(log.stream().noneMatch(line -> line.startsWith(Level.SEVERE.getName())), log.toString());
    }
}
