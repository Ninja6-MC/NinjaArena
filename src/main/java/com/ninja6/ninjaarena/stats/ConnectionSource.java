package com.ninja6.ninjaarena.stats;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/** Opens connections to the stats database. Only the stats thread calls it. */
@FunctionalInterface
interface ConnectionSource {

    /** Bundled with Paper; looked up at runtime because the plugin does not ship drivers. */
    String SQLITE_DRIVER = "org.sqlite.JDBC";
    String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    Connection connect() throws SQLException;

    /** A driver class that is not on the server; retrying cannot help. */
    final class MissingDriver extends Exception {
        MissingDriver(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static ConnectionSource sqlite(Path file, String driverClass) throws MissingDriver {
        Driver driver = driver(driverClass, "SQLite");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        return () -> {
            Connection connection = connect(driver, url, new Properties());
            try (Statement statement = connection.createStatement()) {
                // Another server or a backup tool may hold the file briefly.
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA journal_mode = WAL");
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
            return connection;
        };
    }

    static ConnectionSource mysql(StatsSettings.MySql settings, String driverClass) throws MissingDriver {
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
            throw new MissingDriver("the " + name + " driver (" + className + ") is not available on this server", e);
        }
    }
}
