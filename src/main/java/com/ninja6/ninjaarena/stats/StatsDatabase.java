package com.ninja6.ninjaarena.stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking access to the stats tables over one connection. Not thread-safe: only the stats
 * thread of {@link StatsStore} uses it, so the server thread never waits on the database.
 *
 * <p>Rows of {@code player_stats} with season 0 hold the all-time totals; other rows hold a
 * season's, keyed by {@link Season#id()}. Every write adds to the stored values in the same
 * statement, so servers sharing one MySQL database never overwrite each other's matches.
 */
final class StatsDatabase implements AutoCloseable {

    /** The schema version this build writes. Each version's statements live in {@link Dialect}. */
    static final int SCHEMA_VERSION = 1;
    static final int ALL_TIME = 0;
    /** Longest stored player name; longer names, such as some proxy-prefixed ones, are cut. */
    static final int NAME_LENGTH = 32;

    /** The database was written by a newer plugin build; this one must not touch it. */
    static final class SchemaTooNew extends Exception {
        SchemaTooNew(int found) {
            super("the stats schema is version " + found + " but this build supports up to " + SCHEMA_VERSION
                    + "; update NinjaArena or point it at another database");
        }
    }

    /** What applying the configured season name did. */
    enum SeasonChange {
        /** It was already the current season. */
        UNCHANGED,
        /** There was no season yet; it was created. */
        CREATED,
        /** The previous season was archived and this one started. */
        STARTED,
        /** The name belongs to an archived season, so the current season stays. */
        REFUSED
    }

    record SeasonResult(SeasonChange change, Season season) {
    }

    private final Connection connection;
    private final Dialect dialect;
    private final String prefix;

    private StatsDatabase(Connection connection, Dialect dialect, String prefix) {
        this.connection = connection;
        this.dialect = dialect;
        this.prefix = prefix;
    }

    /** Connects and brings the schema up to {@link #SCHEMA_VERSION}. */
    static StatsDatabase open(ConnectionSource source, Dialect dialect, String prefix)
            throws SQLException, SchemaTooNew {
        Connection connection = source.connect();
        StatsDatabase database = new StatsDatabase(connection, dialect, prefix);
        try {
            database.migrate();
            return database;
        } catch (SQLException | SchemaTooNew | RuntimeException e) {
            database.close();
            throw e;
        }
    }

    int schemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT version FROM " + prefix + "schema_version WHERE id = 1")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private void migrate() throws SQLException, SchemaTooNew {
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.createSchemaTable(prefix));
        }
        int version = schemaVersion();
        if (version > SCHEMA_VERSION) {
            throw new SchemaTooNew(version);
        }
        for (int next = version + 1; next <= SCHEMA_VERSION; next++) {
            int target = next;
            // MySQL commits DDL on its own; each version's statements are idempotent instead.
            transaction(() -> {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : dialect.migration(target, prefix)) {
                        statement.execute(sql);
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(dialect.upsertSchemaVersion(prefix))) {
                    update.setInt(1, target);
                    update.executeUpdate();
                }
                return null;
            });
        }
    }

    boolean isValid() {
        try {
            return !connection.isClosed() && connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Makes {@code name} the current season: creates it when there is none, archives the
     * current one when the name is new, and refuses a name an archived season already uses.
     */
    SeasonResult useSeason(String name, Instant now) throws SQLException {
        return transaction(() -> {
            Optional<Season> current = currentSeason();
            if (current.isPresent() && current.get().name().equals(name)) {
                return new SeasonResult(SeasonChange.UNCHANGED, current.get());
            }
            if (season(name).isPresent()) {
                return new SeasonResult(SeasonChange.REFUSED, current.orElse(null));
            }
            Season started = insertSeason(name, now);
            return new SeasonResult(current.isPresent() ? SeasonChange.STARTED : SeasonChange.CREATED, started);
        });
    }

    /**
     * Archives the current season and starts {@code name}. The archived season's rows are kept.
     *
     * @throws IllegalArgumentException when a season already has the name
     */
    Season startSeason(String name, Instant now) throws SQLException {
        return transaction(() -> {
            if (season(name).isPresent()) {
                throw new IllegalArgumentException("a season named " + name + " already exists");
            }
            return insertSeason(name, now);
        });
    }

    private Season insertSeason(String name, Instant now) throws SQLException {
        try (PreparedStatement archive = connection.prepareStatement(
                "UPDATE " + prefix + "seasons SET ended_at = ? WHERE ended_at IS NULL")) {
            archive.setLong(1, now.toEpochMilli());
            archive.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + prefix + "seasons (name, started_at) VALUES (?, ?)")) {
            insert.setString(1, name);
            insert.setLong(2, now.toEpochMilli());
            insert.executeUpdate();
        }
        return season(name).orElseThrow(() -> new SQLException("the new season was not stored"));
    }

    Optional<Season> currentSeason() throws SQLException {
        List<Season> open = seasons("WHERE ended_at IS NULL ORDER BY id DESC");
        return open.isEmpty() ? Optional.empty() : Optional.of(open.getFirst());
    }

    List<Season> seasons() throws SQLException {
        return seasons("ORDER BY id");
    }

    private Optional<Season> season(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id, name, started_at, ended_at FROM " + prefix + "seasons WHERE name = ?")) {
            query.setString(1, name);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(season(rows)) : Optional.empty();
            }
        }
    }

    private List<Season> seasons(String clause) throws SQLException {
        List<Season> seasons = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT id, name, started_at, ended_at FROM " + prefix + "seasons " + clause)) {
            while (rows.next()) {
                seasons.add(season(rows));
            }
        }
        return seasons;
    }

    private static Season season(ResultSet row) throws SQLException {
        long ended = row.getLong(4);
        boolean open = row.wasNull();
        return new Season(row.getInt(1), row.getString(2), Instant.ofEpochMilli(row.getLong(3)),
                open ? null : Instant.ofEpochMilli(ended));
    }

    /**
     * Adds the matches to the all-time totals and to the season current at the time of the
     * write, in one transaction. A player in several matches is written once.
     *
     * @throws SQLException with nothing written
     */
    void write(List<MatchRecord> matches) throws SQLException {
        Map<UUID, PlayerStats> merged = new LinkedHashMap<>();
        Map<UUID, Instant> seen = new LinkedHashMap<>();
        for (MatchRecord match : matches) {
            for (PlayerStats delta : match.players()) {
                merged.merge(delta.player(), delta, PlayerStats::plus);
                if (delta.name() != null) {
                    seen.merge(delta.player(), match.endedAt(), (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        }
        if (merged.isEmpty()) {
            return;
        }
        transaction(() -> {
            Optional<Season> season = currentSeason();
            try (PreparedStatement upsert = connection.prepareStatement(dialect.upsertStats(prefix))) {
                for (PlayerStats delta : merged.values()) {
                    add(upsert, delta, ALL_TIME);
                    if (season.isPresent()) {
                        add(upsert, delta, season.get().id());
                    }
                }
                upsert.executeBatch();
            }
            if (!seen.isEmpty()) {
                try (PreparedStatement names = connection.prepareStatement(dialect.upsertPlayer(prefix))) {
                    for (var entry : seen.entrySet()) {
                        names.setString(1, entry.getKey().toString());
                        String name = merged.get(entry.getKey()).name();
                        names.setString(2, name.length() > NAME_LENGTH ? name.substring(0, NAME_LENGTH) : name);
                        names.setLong(3, entry.getValue().toEpochMilli());
                        names.addBatch();
                    }
                    names.executeBatch();
                }
            }
            return null;
        });
    }

    private static void add(PreparedStatement upsert, PlayerStats delta, int season) throws SQLException {
        upsert.setString(1, delta.player().toString());
        upsert.setInt(2, season);
        int index = 3;
        for (Stat stat : Dialect.COLUMNS) {
            upsert.setInt(index++, delta.value(stat));
        }
        upsert.addBatch();
    }

    Optional<PlayerStats> stats(UUID player, StatsScope scope) throws SQLException {
        Optional<Integer> season = seasonId(scope);
        if (season.isEmpty()) {
            return Optional.empty();
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT " + statColumns("s") + ", n.name FROM "
                + prefix + "player_stats s LEFT JOIN " + prefix + "players n ON n.player = s.player"
                + " WHERE s.player = ? AND s.season = ?")) {
            query.setString(1, player.toString());
            query.setInt(2, season.get());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                int[] values = new int[Dialect.COLUMNS.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = row.getInt(i + 1);
                }
                return Optional.of(new PlayerStats(player, row.getString(values.length + 1), values[0], values[1],
                        values[2], values[3], values[4], values[5], values[6]));
            }
        }
    }

    /** Players with a value above zero, best first; ties go to the lower UUID. */
    List<LeaderboardEntry> top(Stat stat, StatsScope scope, int limit) throws SQLException {
        Objects.requireNonNull(stat, "stat");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        Optional<Integer> season = seasonId(scope);
        if (season.isEmpty()) {
            return List.of();
        }
        String column = "s." + stat.column();
        List<LeaderboardEntry> entries = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT s.player, n.name, " + column + " FROM "
                + prefix + "player_stats s LEFT JOIN " + prefix + "players n ON n.player = s.player"
                + " WHERE s.season = ? AND " + column + " > 0 ORDER BY " + column + " DESC, s.player LIMIT ?")) {
            query.setInt(1, season.get());
            query.setInt(2, limit);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    entries.add(new LeaderboardEntry(entries.size() + 1, UUID.fromString(rows.getString(1)),
                            rows.getString(2), rows.getInt(3)));
                }
            }
        }
        return entries;
    }

    private Optional<Integer> seasonId(StatsScope scope) throws SQLException {
        return switch (scope) {
            case StatsScope.AllTime all -> Optional.of(ALL_TIME);
            case StatsScope.CurrentSeason current -> currentSeason().map(Season::id);
            case StatsScope.OneSeason one -> Optional.of(one.id());
        };
    }

    private static String statColumns(String alias) {
        return String.join(", ", Dialect.COLUMNS.stream().map(stat -> alias + "." + stat.column()).toList());
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
                // The connection is broken; the store reconnects before its next use.
            }
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing is pending on a closed connection: every write commits or rolls back.
        }
    }
}
