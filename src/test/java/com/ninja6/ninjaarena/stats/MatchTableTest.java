package com.ninja6.ninjaarena.stats;

import static com.ninja6.ninjaarena.stats.Records.ALICE;
import static com.ninja6.ninjaarena.stats.Records.BOB;
import static com.ninja6.ninjaarena.stats.Records.T0;
import static com.ninja6.ninjaarena.stats.Records.match;
import static com.ninja6.ninjaarena.stats.Records.played;
import static com.ninja6.ninjaarena.stats.Records.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.reward.RewardSettings;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Schema version 3: the migration from a version 2 database on SQLite and on H2 in MySQL mode,
 * and the match rows it adds.
 */
class MatchTableTest {

    private static final AtomicInteger NAMES = new AtomicInteger();
    private static final UUID MATCH = new UUID(1, 1);
    private static final UUID OTHER = new UUID(1, 2);

    @TempDir
    Path dir;

    private final List<StatsDatabase> open = new ArrayList<>();

    @AfterEach
    void closeAll() {
        open.forEach(StatsDatabase::close);
    }

    private ConnectionSource source(Dialect dialect) throws Exception {
        return dialect == Dialect.SQLITE ? Records.sqliteSource(dir.resolve("stats.db"))
                : Records.mysqlLike("matches" + NAMES.incrementAndGet());
    }

    private StatsDatabase open(Dialect dialect, ConnectionSource source) throws Exception {
        StatsDatabase database = StatsDatabase.open(source, dialect, "na_");
        open.add(database);
        return database;
    }

    /**
     * A database as a version 2 build leaves it: the version 1 and 2 statements, which this
     * build still runs unchanged, and rows in every table they made.
     */
    private static void versionTwo(Dialect dialect, ConnectionSource source) throws SQLException {
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute(dialect.createSchemaTable("na_"));
            for (int version = 1; version <= 2; version++) {
                for (String sql : dialect.migration(version, "na_")) {
                    statement.execute(sql);
                }
            }
            statement.executeUpdate("INSERT INTO na_schema_version (id, version) VALUES (1, 2)");
            statement.executeUpdate("INSERT INTO na_seasons (name, started_at) VALUES ('Season 1', "
                    + T0.toEpochMilli() + ")");
            for (int season : new int[] {0, 1}) {
                statement.executeUpdate("INSERT INTO na_player_stats (player, season, wins, rounds_won, player_kills,"
                        + " enemy_kills, deaths, best_overrun_seconds, matches_played) VALUES ('" + ALICE + "', "
                        + season + ", 3, 5, 7, 11, 2, 40, 6)");
            }
            statement.executeUpdate("INSERT INTO na_players (player, name, last_seen) VALUES ('" + ALICE
                    + "', 'Alice', " + T0.toEpochMilli() + ")");
            statement.executeUpdate("INSERT INTO na_awards (player, season, rule_id, title, tag, trophy)"
                    + " VALUES ('" + ALICE + "', 0, 'veteran', 'Veteran', '', '')");
            statement.executeUpdate("INSERT INTO na_payout_days (player, day_id, spent) VALUES ('" + ALICE
                    + "', 10, 700)");
            statement.executeUpdate("INSERT INTO na_payout_matches (player, match_id) VALUES ('" + ALICE + "', '"
                    + MATCH + "')");
        }
    }

    private static int count(ConnectionSource source, String sql) throws SQLException {
        try (Connection connection = source.connect(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private static boolean hasMatchesTable(ConnectionSource source) throws SQLException {
        // Only the connection's own database: a MySQL server may hold other na_matches tables.
        try (Connection connection = source.connect(); ResultSet tables = connection.getMetaData()
                .getTables(connection.getCatalog(), connection.getSchema(), "%", null)) {
            while (tables.next()) {
                if (tables.getString("TABLE_NAME").equalsIgnoreCase("na_matches")) {
                    return true;
                }
            }
            return false;
        }
    }

    private static void assertVersionTwoRowsKept(StatsDatabase database, ConnectionSource source) throws Exception {
        PlayerStats alice = new PlayerStats(ALICE, "Alice", 3, 5, 7, 11, 2, 40, 6);
        assertEquals(Optional.of(alice), database.stats(ALICE, StatsScope.ALL_TIME));
        assertEquals(Optional.of(alice), database.stats(ALICE, StatsScope.season(1)));
        assertEquals("Season 1", database.currentSeason().orElseThrow().name());
        assertEquals("Veteran", database.awards(ALICE).getFirst().title());
        assertEquals(700, count(source, "SELECT spent FROM na_payout_days"));
        // The ledger still knows the match: the same payout is not made twice.
        assertEquals(0, database.reservePayout(ALICE, MATCH, 10, new RewardSettings.Payout(true, 100, 1000)));
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void aVersionTwoDatabaseIsMigratedToThreeWithEveryRowKept(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        versionTwo(dialect, source);
        assertFalse(hasMatchesTable(source));
        StatsDatabase database = open(dialect, source);
        assertEquals(3, database.schemaVersion());
        assertTrue(hasMatchesTable(source));
        assertEquals(0, count(source, "SELECT COUNT(*) FROM na_matches"));
        assertVersionTwoRowsKept(database, source);
        assertEquals(1, count(source, "SELECT COUNT(*) FROM na_seasons"));
        assertEquals(2, count(source, "SELECT COUNT(*) FROM na_player_stats"));
        // The migrated database takes match rows.
        database.write(List.of(played(OTHER, T0, List.of(MatchRecord.Played.generated(42, 3, 1, 3)),
                player(BOB, "Bob", 1, 2, 0, 0, 0, 0))));
        assertEquals(1, database.matchesOnSeed(42, 10).size());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void aFreshInstallReachesVersionThreeAndReopeningIsANoOp(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        StatsDatabase first = open(dialect, source);
        assertEquals(3, first.schemaVersion());
        first.useSeason("Season 1", T0);
        first.write(List.of(played(MATCH, T0, List.of(MatchRecord.Played.generated(42, 3, 1, 3)),
                player(ALICE, "Alice", 1, 3, 0, 0, 0, 0))));
        first.close();
        // Running the version 3 statements again changes nothing.
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            for (String sql : dialect.migration(3, "na_")) {
                statement.execute(sql);
            }
        }
        StatsDatabase second = open(dialect, source);
        assertEquals(3, second.schemaVersion());
        assertEquals(1, count(source, "SELECT COUNT(*) FROM na_matches"));
        assertEquals(1, second.stats(ALICE, StatsScope.ALL_TIME).orElseThrow().wins());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void aMigrationThatFailsBeforeItIsRecordedLeavesVersionTwoAndTheNextStartRetriesIt(Dialect dialect)
            throws Exception {
        ConnectionSource source = source(dialect);
        versionTwo(dialect, source);
        // The version 3 tables are made; recording the new version then fails.
        ConnectionSource failing = failingOn(source, sql -> sql.contains("INTO na_schema_version (id, version)"));
        assertThrows(SQLException.class, () -> StatsDatabase.open(failing, dialect, "na_"));
        assertEquals(2, count(source, "SELECT version FROM na_schema_version WHERE id = 1"));
        if (dialect == Dialect.SQLITE) {
            assertFalse(hasMatchesTable(source), "SQLite rolls the new table back");
        }
        StatsDatabase database = open(dialect, source);
        assertEquals(3, database.schemaVersion());
        assertVersionTwoRowsKept(database, source);
    }

    @Test
    void aMigrationThatFailsBetweenItsStatementsIsRolledBackOnSqlite() throws Exception {
        ConnectionSource source = source(Dialect.SQLITE);
        versionTwo(Dialect.SQLITE, source);
        ConnectionSource failing = failingOn(source, sql -> sql.contains("INDEX IF NOT EXISTS na_matches_seed"));
        assertThrows(SQLException.class, () -> StatsDatabase.open(failing, Dialect.SQLITE, "na_"));
        assertEquals(2, count(source, "SELECT version FROM na_schema_version WHERE id = 1"));
        assertFalse(hasMatchesTable(source));
        assertEquals(3, open(Dialect.SQLITE, source).schemaVersion());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void aVersionFromANewerBuildIsRefusedAndLeftAsItIs(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        versionTwo(dialect, source);
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE na_schema_version SET version = " + (StatsDatabase.SCHEMA_VERSION + 1));
        }
        // No compatible_from column: only a build of version 4 itself may use it.
        var failure = assertThrows(StatsDatabase.SchemaTooNew.class, () -> StatsDatabase.open(source, dialect, "na_"));
        assertTrue(failure.getMessage().contains("version 4 but this build supports up to 3"), failure.getMessage());
        assertEquals(4, count(source, "SELECT version FROM na_schema_version WHERE id = 1"));
        assertFalse(hasMatchesTable(source), "nothing is created in a newer schema");
        assertEquals(2, count(source, "SELECT COUNT(*) FROM na_player_stats"));
    }

    private static void setSchema(ConnectionSource source, String assignments) throws SQLException {
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE na_schema_version SET " + assignments + " WHERE id = 1");
        }
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void versionThreeIsMarkedUsableByVersionTwoBuildsOnAFreshAndAMigratedDatabase(Dialect dialect)
            throws Exception {
        ConnectionSource fresh = source(dialect);
        open(dialect, fresh).close();
        assertEquals(2, count(fresh, "SELECT compatible_from FROM na_schema_version WHERE id = 1"));
        // A version 3 database marked so opens on this version 3 build, and stays marked.
        assertEquals(new StatsDatabase.StoredSchema(3, 2), open(dialect, fresh).storedSchema());

        ConnectionSource migrated = dialect == Dialect.SQLITE ? Records.sqliteSource(dir.resolve("migrated.db"))
                : Records.mysqlLike("matches" + NAMES.incrementAndGet());
        versionTwo(dialect, migrated);
        assertEquals(new StatsDatabase.StoredSchema(3, 2), open(dialect, migrated).storedSchema());
        assertEquals(2, count(migrated, "SELECT compatible_from FROM na_schema_version WHERE id = 1"));
    }

    @Test
    void serversOpeningOneVersionTwoSqliteFileAtOnceAllMigrateIt() throws Exception {
        int servers = 8;
        for (int round = 0; round < 5; round++) {
            ConnectionSource source = Records.sqliteSource(dir.resolve("shared" + round + ".db"));
            versionTwo(Dialect.SQLITE, source);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<StatsDatabase.StoredSchema>> opens = new ArrayList<>();
            try (ExecutorService workers = Executors.newFixedThreadPool(servers)) {
                for (int server = 0; server < servers; server++) {
                    opens.add(workers.submit(() -> {
                        start.await();
                        try (StatsDatabase database = StatsDatabase.open(source, Dialect.SQLITE, "na_")) {
                            return database.storedSchema();
                        }
                    }));
                }
                start.countDown();
                for (Future<StatsDatabase.StoredSchema> open : opens) {
                    // Each waits for the others under busy_timeout instead of failing with SQLITE_BUSY.
                    assertEquals(new StatsDatabase.StoredSchema(3, 2), open.get(30, TimeUnit.SECONDS),
                            "round " + round);
                }
            }
            StatsDatabase database = open(Dialect.SQLITE, source);
            assertEquals(new StatsDatabase.StoredSchema(3, 2), database.storedSchema());
            assertVersionTwoRowsKept(database, source);
            assertEquals(2, count(source, "SELECT COUNT(*) FROM na_player_stats"));
            assertEquals(1, count(source, "SELECT COUNT(*) FROM na_seasons"));
            assertEquals(0, count(source, "SELECT COUNT(*) FROM na_matches"));
        }
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void aColumnLeftByAnInterruptedMigrationIsKeptAndTheRetryCompletes(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        versionTwo(dialect, source);
        // As MySQL leaves it when the ALTER committed on its own and the rest of the step failed.
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute(dialect.addCompatibleFrom("na_"));
        }
        StatsDatabase database = open(dialect, source);
        assertEquals(new StatsDatabase.StoredSchema(3, 2), database.storedSchema());
        assertVersionTwoRowsKept(database, source);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void aNewerSchemaThatKeepsThisVersionUsableIsUsedAsItIs(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        open(dialect, source).close();
        setSchema(source, "version = 4, compatible_from = 3");
        StatsDatabase database = open(dialect, source);
        database.write(List.of(played(MATCH, T0, List.of(MatchRecord.Played.generated(42, 3, 1, 1)),
                player(ALICE, "Alice", 1, 1, 0, 0, 0, 0))));
        assertEquals(1, database.matchesOnSeed(42, 10).size());
        assertEquals(new StatsDatabase.StoredSchema(4, 3), database.storedSchema(), "never lowered");
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void aNewerSchemaThatNeedsANewerBuildIsRefused(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        open(dialect, source).close();
        for (String marker : List.of("compatible_from = 4", "compatible_from = NULL")) {
            setSchema(source, "version = 4, " + marker);
            var failure = assertThrows(StatsDatabase.SchemaTooNew.class,
                    () -> StatsDatabase.open(source, dialect, "na_"));
            assertTrue(failure.getMessage().contains("version 4 but this build supports up to 3"),
                    failure.getMessage());
            assertEquals(4, count(source, "SELECT version FROM na_schema_version WHERE id = 1"));
        }
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void matchRowsKeepGeneratedMapsBySeedAndHandBuiltOnesByName(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        StatsDatabase database = open(dialect, source);
        database.useSeason("Season 1", T0);
        // Generated again between rounds: rounds 1 and 2 on seed 42, round 3 on seed 43.
        database.write(List.of(
                played(MATCH, T0.plusSeconds(10), List.of(MatchRecord.Played.generated(42, 3, 1, 2),
                        MatchRecord.Played.generated(43, 3, 3, 1)), player(ALICE, "Alice", 1, 2, 0, 0, 0, 0)),
                played(OTHER, T0.plusSeconds(20), List.of(MatchRecord.Played.handBuilt("dojo", 1, 3)),
                        player(BOB, "Bob", 1, 3, 0, 0, 0, 0))));
        // Written again, as after a lost commit acknowledgement: the rows stay as they are.
        database.write(List.of(played(MATCH, T0.plusSeconds(10), List.of(MatchRecord.Played.generated(42, 3, 1, 2)),
                player(ALICE, "Alice", 0, 0, 0, 0, 0, 0))));

        assertEquals(3, count(source, "SELECT COUNT(*) FROM na_matches"));
        assertEquals(List.of(new MatchOnSeed(MATCH, T0.plusSeconds(10), 1, 3, 1, 2, 2, List.of(1), "COMPLETED")),
                database.matchesOnSeed(42, 10));
        assertEquals(List.of(new MatchOnSeed(MATCH, T0.plusSeconds(10), 1, 3, 3, 1, 2, List.of(1), "COMPLETED")),
                database.matchesOnSeed(43, 10));
        assertEquals(List.of(), database.matchesOnSeed(44, 10));
        assertEquals(1, count(source, "SELECT COUNT(*) FROM na_matches WHERE map = 'dojo' AND seed IS NULL"
                + " AND difficulty IS NULL AND match_id = '" + OTHER + "' AND rounds = 3"));
        assertEquals(2, count(source, "SELECT COUNT(*) FROM na_matches WHERE map = '" + MatchRecord.GENERATED
                + "' AND seed IS NOT NULL"));
        assertEquals(0, count(source, "SELECT COUNT(*) FROM na_matches WHERE map LIKE 'generated:%'"));
        assertThrows(IllegalArgumentException.class, () -> database.matchesOnSeed(42, 0));
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void matchesOnASeedComeLatestFirstAndAMatchWithoutRowsAddsNone(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        StatsDatabase database = open(dialect, source);
        database.write(List.of(played(MATCH, T0, List.of(MatchRecord.Played.generated(-7, 1, 1, 1)),
                player(ALICE, "Alice", 0, 0, 0, 0, 0, 0))));
        database.write(List.of(played(OTHER, T0.plusSeconds(60), List.of(MatchRecord.Played.generated(-7, 5, 1, 1)),
                player(ALICE, "Alice", 0, 0, 0, 0, 0, 0))));
        database.write(List.of(match(player(BOB, "Bob", 1, 0, 0, 0, 0, 0))));
        List<MatchOnSeed> found = database.matchesOnSeed(-7, 10);
        assertEquals(List.of(OTHER, MATCH), found.stream().map(MatchOnSeed::match).toList());
        assertEquals(List.of(5, 1), found.stream().map(MatchOnSeed::difficulty).toList());
        assertEquals(null, found.getFirst().season(), "written before any season");
        assertEquals(List.of(OTHER), database.matchesOnSeed(-7, 1).stream().map(MatchOnSeed::match).toList());
        assertEquals(2, count(source, "SELECT COUNT(*) FROM na_matches"));
    }

    @Test
    void aGeneratedMapIsNeverStoredUnderItsSlotName() {
        assertThrows(IllegalArgumentException.class, () -> MatchRecord.Played.handBuilt("generated:1", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MatchRecord.Played("generated:1", 42L, 3, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MatchRecord.Played("dojo", 42L, 3, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MatchRecord.Played(MatchRecord.GENERATED, 42L, null,
                1, 1));
        assertEquals(MatchRecord.GENERATED, MatchRecord.Played.generated(42, 3, 1, 1).map());
    }

    /** The source's connections, failing any statement whose SQL matches. */
    private static ConnectionSource failingOn(ConnectionSource real, Predicate<String> fails) {
        return () -> {
            Connection connection = real.connect();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && fails.test((String) args[0])) {
                            throw new SQLException("injected failure");
                        }
                        Object result = call(connection, method, args);
                        if (method.getName().equals("createStatement")) {
                            Statement statement = (Statement) result;
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                                    new Class<?>[] {Statement.class}, (inner, call, values) -> {
                                        if (call.getName().equals("execute") && fails.test((String) values[0])) {
                                            throw new SQLException("injected failure");
                                        }
                                        return call(statement, call, values);
                                    });
                        }
                        return result;
                    });
        };
    }

    private static Object call(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
