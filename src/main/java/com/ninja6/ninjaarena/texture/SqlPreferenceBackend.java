package com.ninja6.ninjaarena.texture;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import com.ninja6.ninjaarena.stats.ConnectionSource;
import com.ninja6.ninjaarena.stats.StatsSettings.Storage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Texture choices in two tables of their own beside the stats tables: the preferences and a
 * schema version that is independent of the stats schema. Nothing here reads or writes
 * seasons or statistics.
 */
final class SqlPreferenceBackend implements PreferenceBackend {

    /** The schema version this build writes. */
    static final int SCHEMA_VERSION = 1;

    private final Connection connection;
    private final Storage storage;
    private final String preferences;

    private SqlPreferenceBackend(Connection connection, Storage storage, String prefix) {
        this.connection = connection;
        this.storage = storage;
        this.preferences = prefix + "texture_preferences";
    }

    /** The statements of every schema version, in order. Each must be safe to run twice. */
    static List<List<String>> migrations(Storage storage, String prefix) {
        String table = prefix + "texture_preferences";
        String engine = storage == Storage.MYSQL ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        String identity = storage == Storage.MYSQL
                ? "identity VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL" : "identity VARCHAR(64) NOT NULL";
        return List.of(List.of("CREATE TABLE IF NOT EXISTS " + table + " (" + identity
                + ", quality VARCHAR(16) NOT NULL, revision BIGINT NOT NULL, updated_at BIGINT NOT NULL,"
                + " PRIMARY KEY (identity))" + engine));
    }

    static SqlPreferenceBackend open(ConnectionSource source, Storage storage, String prefix)
            throws SQLException, SchemaTooNew {
        return open(source, storage, prefix, migrations(storage, prefix));
    }

    /** Connects and brings the schema up to the last of {@code versions}. */
    static SqlPreferenceBackend open(ConnectionSource source, Storage storage, String prefix,
            List<List<String>> versions) throws SQLException, SchemaTooNew {
        Connection connection = source.connect();
        SqlPreferenceBackend backend = new SqlPreferenceBackend(connection, storage, prefix);
        try {
            backend.migrate(prefix + "texture_schema", versions);
            return backend;
        } catch (SQLException | SchemaTooNew | RuntimeException e) {
            backend.close();
            throw e;
        }
    }

    private void migrate(String schema, List<List<String>> versions) throws SQLException, SchemaTooNew {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + schema
                    + " (id INT NOT NULL PRIMARY KEY, version INT NOT NULL)");
        }
        int found = 0;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT version FROM " + schema + " WHERE id = 1")) {
            if (rows.next()) {
                found = rows.getInt(1);
            }
        }
        if (found > versions.size()) {
            throw new SchemaTooNew(found, versions.size());
        }
        String record = storage == Storage.SQLITE
                ? "INSERT INTO " + schema + " (id, version) VALUES (1, ?)"
                        + " ON CONFLICT (id) DO UPDATE SET version = MAX(version, excluded.version)"
                : "INSERT INTO " + schema + " (id, version) VALUES (1, ?)"
                        + " ON DUPLICATE KEY UPDATE version = GREATEST(version, VALUES(version))";
        for (int next = found + 1; next <= versions.size(); next++) {
            int target = next;
            // MySQL commits DDL on its own, so a version's statements are idempotent and the
            // number is recorded last: a crash in between repeats the version, never skips it.
            transaction(() -> {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : versions.get(target - 1)) {
                        statement.execute(sql);
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(record)) {
                    update.setInt(1, target);
                    update.executeUpdate();
                }
                return null;
            });
        }
    }

    @Override
    public Map<PlayerKey, Stored> loadAll() throws SQLException {
        Map<PlayerKey, Stored> all = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT identity, quality, revision, updated_at FROM " + preferences)) {
            while (rows.next()) {
                Optional<Stored> entry = read(rows, 1);
                if (entry.isPresent()) {
                    try {
                        all.put(PlayerKey.parse(rows.getString(1)), entry.get());
                    } catch (IllegalArgumentException ignored) {
                        // A row this build cannot interpret behaves as no choice at all.
                    }
                }
            }
        }
        return all;
    }

    @Override
    public Optional<Stored> load(PlayerKey key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT quality, revision, updated_at FROM " + preferences + " WHERE identity = ?")) {
            query.setString(1, key.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? read(rows, 0) : Optional.empty();
            }
        }
    }

    private static Optional<Stored> read(ResultSet rows, int offset) throws SQLException {
        String quality = rows.getString(offset + 1);
        for (Quality candidate : Quality.values()) {
            if (candidate.name().toLowerCase(Locale.ROOT).equals(quality)) {
                return Optional.of(new Stored(candidate, rows.getLong(offset + 2), rows.getLong(offset + 3)));
            }
        }
        return Optional.empty();
    }

    @Override
    public Stored save(PlayerKey key, Quality quality, long now) throws SQLException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(quality, "quality");
        String insert = "INSERT INTO " + preferences + " (identity, quality, revision, updated_at) VALUES (?, ?, 1, ?)";
        String upsert = storage == Storage.SQLITE
                ? insert + " ON CONFLICT (identity) DO UPDATE SET quality = excluded.quality,"
                        + " revision = revision + 1, updated_at = excluded.updated_at"
                : insert + " ON DUPLICATE KEY UPDATE quality = VALUES(quality), revision = revision + 1,"
                        + " updated_at = VALUES(updated_at)";
        return transaction(() -> {
            try (PreparedStatement write = connection.prepareStatement(upsert)) {
                write.setString(1, key.toString());
                write.setString(2, quality.name().toLowerCase(Locale.ROOT));
                write.setLong(3, now);
                write.executeUpdate();
            }
            return load(key).orElseThrow(() -> new SQLException("the saved choice was not stored"));
        });
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws SQLException;
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // A broken connection is dropped by the store before its next use.
            }
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Every write has committed or rolled back already.
        }
    }
}
