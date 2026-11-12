package com.ninja6.ninjaarena.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionSourceTest {

    @TempDir
    Path dir;

    /** The SQLite driver, noting whether the database file existed when it was asked to open it. */
    public static final class RecordingDriver implements Driver {
        static final List<Boolean> FILE_EXISTED = Collections.synchronizedList(new ArrayList<>());

        private final Driver real;

        public RecordingDriver() throws ReflectiveOperationException {
            real = (Driver) Class.forName(ConnectionSource.SQLITE_DRIVER).getDeclaredConstructor().newInstance();
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            FILE_EXISTED.add(Files.exists(Path.of(url.substring("jdbc:sqlite:".length()))));
            return real.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return real.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return real.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return real.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return real.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return real.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return real.getParentLogger();
        }
    }

    @Test
    void theSqliteFileExistsBeforeTheDriverOpensIt() throws Exception {
        // Given a missing file, the driver Paper bundles creates and deletes it to test write
        // access, which can unlink a database another server has just opened.
        RecordingDriver.FILE_EXISTED.clear();
        ConnectionSource source = ConnectionSource.sqlite(dir.resolve("stats.db"), RecordingDriver.class.getName());
        try (Connection connection = source.connect()) {
            assertTrue(connection.isValid(1));
        }
        assertEquals(List.of(true), RecordingDriver.FILE_EXISTED);
    }

    @Test
    void aTransactionStartsWithoutWaitingForAnotherServersWriteLock() throws Exception {
        // commit() ends with the same statement that starts a transaction. If that statement
        // needed the write lock, commit() could fail after the data was committed, and the
        // store would write the batch again.
        ConnectionSource source = ConnectionSource.sqlite(dir.resolve("stats.db"), ConnectionSource.SQLITE_DRIVER);
        try (Connection other = source.connect(); Statement holding = other.createStatement();
                Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE matches (id INTEGER)");
            statement.execute("PRAGMA busy_timeout = 100");
            holding.execute("BEGIN IMMEDIATE");
            connection.setAutoCommit(false);
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM matches")) {
                assertTrue(rows.next());
            }
            connection.commit();
            connection.setAutoCommit(true);
            holding.execute("ROLLBACK");
        }
    }

    @Test
    void serversCreatingTheFileTogetherWriteToOneDatabase() throws Exception {
        for (int round = 0; round < 50; round++) {
            Path file = dir.resolve("stats-" + round + ".db");
            CyclicBarrier together = new CyclicBarrier(2);
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            List<Thread> servers = new ArrayList<>();
            for (int server = 0; server < 2; server++) {
                Thread thread = new Thread(() -> {
                    try {
                        ConnectionSource source = ConnectionSource.sqlite(file, ConnectionSource.SQLITE_DRIVER);
                        together.await(10, TimeUnit.SECONDS);
                        try (Connection connection = source.connect();
                                Statement statement = connection.createStatement()) {
                            statement.execute("CREATE TABLE IF NOT EXISTS matches (id INTEGER)");
                            statement.execute("INSERT INTO matches VALUES (1)");
                        }
                    } catch (Exception e) {
                        failures.add(e);
                    }
                });
                servers.add(thread);
                thread.start();
            }
            for (Thread server : servers) {
                server.join();
            }
            assertEquals(List.of(), failures, "round " + round);
            try (Connection connection = ConnectionSource.sqlite(file, ConnectionSource.SQLITE_DRIVER).connect();
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM matches")) {
                rows.next();
                assertEquals(2, rows.getInt(1), "round " + round);
            }
        }
    }
}
