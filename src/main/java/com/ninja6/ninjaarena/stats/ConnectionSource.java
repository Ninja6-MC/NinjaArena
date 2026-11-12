package com.ninja6.ninjaarena.stats;

import com.ninja6.ninjaarena.message.Keyed;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/** Opens connections to the shared storage database. Only the owning storage thread calls it. */
@FunctionalInterface
public interface ConnectionSource {

    /** Bundled with Paper; looked up at runtime because the plugin does not ship drivers. */
    String SQLITE_DRIVER = "org.sqlite.JDBC";
    String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    int BUSY_TIMEOUT_MILLIS = 5000;
    /** SQLite's primary result code for a lock held by another connection. */
    int SQLITE_BUSY = 5;

    Connection connect() throws SQLException;

    /** A driver class that is not on the server; retrying cannot help. */
    final class MissingDriver extends Exception implements Keyed {
        private final String name;
        private final String className;

        public MissingDriver(String name, String className, Throwable cause) {
            super("the " + name + " driver (" + className + ") is not available on this server", cause);
            this.name = name;
            this.className = className;
        }

        @Override
        public String key() {
            return "texture.problem.missing-driver";
        }

        @Override
        public Object[] placeholders() {
            return new Object[] {"driver", name, "class", className};
        }
    }

    public static ConnectionSource sqlite(Path file, String driverClass) throws MissingDriver {
        Driver driver = driver(driverClass, "SQLite");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        Properties properties = new Properties();
        // Take the write lock when a transaction begins. A deferred one reads first, and when
        // another server commits before it writes, SQLite fails it at once instead of waiting.
        properties.setProperty("transaction_mode", "IMMEDIATE");
        return () -> {
            createIfMissing(file);
            Connection connection = connect(driver, url, properties);
            try (Statement statement = connection.createStatement()) {
                // Another server or a backup tool may hold the file briefly.
                statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
                useWal(statement);
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
            return connection;
        };
    }

    /**
     * Switches the file to WAL. The switch takes its lock without waiting, so it fails at once
     * while another server converts the same new file; it is tried again for as long as any
     * other statement would wait.
     */
    private static void useWal(Statement statement) throws SQLException {
        long deadline = System.nanoTime() + BUSY_TIMEOUT_MILLIS * 1_000_000L;
        while (true) {
            try {
                statement.execute("PRAGMA journal_mode = WAL");
                return;
            } catch (SQLException e) {
                if ((e.getErrorCode() & 0xff) != SQLITE_BUSY || System.nanoTime() > deadline) {
                    throw e;
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted while switching to WAL", e);
            }
        }
    }

    /**
     * Creates the database file before the driver sees it. Given a missing file, sqlite-jdbc
     * before 3.50.1.0 (the one Paper bundles) creates and then deletes it to test write access;
     * when another server opens the new file in between, that delete unlinks the database it is
     * writing to, and everything it writes afterwards is lost without an error.
     */
    static void createIfMissing(Path file) throws SQLException {
        try {
            Files.createFile(file);
        } catch (FileAlreadyExistsException e) {
            // Another server created it first; an empty file is an empty database.
        } catch (IOException e) {
            throw new SQLException("cannot create " + file + ": " + e.getMessage(), e);
        }
    }

    public static ConnectionSource mysql(StatsSettings.MySql settings, String driverClass) throws MissingDriver {
        Driver driver = driver(driverClass, "MySQL");
        String host = settings.host().contains(":") && !settings.host().startsWith("[")
                ? "[" + settings.host() + "]" : settings.host();
        String url = "jdbc:mysql://" + host + ":" + settings.port() + "/" + settings.database();
        Properties properties = new Properties();
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "30000");
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("characterEncoding", "UTF-8");
        return () -> connect(driver, url, properties);
    }

    private static Connection connect(Driver driver, String url, Properties properties) throws SQLException {
        // The driver is used directly, so the server's DriverManager registration does not matter.
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new SQLException("the driver does not accept " + url.substring(0, url.indexOf(':', 5)));
        }
        return connection;
    }

    private static Driver driver(String className, String name) throws MissingDriver {
        try {
            return (Driver) Class.forName(className, true, ConnectionSource.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            throw new MissingDriver(name, className, e);
        }
    }
}
