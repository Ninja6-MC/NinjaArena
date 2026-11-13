package com.ninja6.ninjaarena.stats;

import com.ninja6.ninjaarena.reward.Award;
import com.ninja6.ninjaarena.reward.RewardSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p>{@code matches} has a row per map each match was played on (schema version 3): a
 * hand-built arena by name, a generated map by seed and difficulty, never by its slot name.
 */
final class StatsDatabase implements AutoCloseable {

    /** The schema version this build writes. Each version's statements live in {@link Dialect}. */
    static final int SCHEMA_VERSION = 3;
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
            lockForWrite();
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
            lockForWrite();
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
        return currentSeason(false);
    }

    private Optional<Season> currentSeason(boolean lock) throws SQLException {
        String suffix = lock && dialect == Dialect.MYSQL ? " FOR UPDATE" : "";
        List<Season> open = seasons("WHERE ended_at IS NULL ORDER BY id DESC" + suffix);
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
     * write, and stores each match's own rows, in one transaction. A player in several
     * matches is written once; a match row already stored is kept as it is.
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
        List<MatchRecord> details = matches.stream().filter(match -> match.match() != null).toList();
        if (merged.isEmpty() && details.isEmpty()) {
            return;
        }
        transaction(() -> {
            lockForWrite();
            // Finish writes to the old season before a concurrent server can archive it.
            Optional<Season> season = currentSeason(true);
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
            if (!details.isEmpty()) {
                insertMatches(details, season.map(Season::id).orElse(null));
            }
            return null;
        });
    }

    private void insertMatches(List<MatchRecord> matches, Integer season) throws SQLException {
        String sql = "INSERT INTO " + prefix + "matches (match_id, first_round, rounds, ended_at, season, map,"
                + " seed, difficulty, teams, winners, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + dialect.keepExisting("match_id, first_round", "match_id");
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (MatchRecord match : matches) {
                MatchRecord.Details details = match.match();
                for (MatchRecord.Played map : details.maps()) {
                    insert.setString(1, details.id().toString());
                    insert.setInt(2, map.firstRound());
                    insert.setInt(3, map.rounds());
                    insert.setLong(4, match.endedAt().toEpochMilli());
                    insert.setObject(5, season, Types.INTEGER);
                    insert.setString(6, map.map());
                    insert.setObject(7, map.seed(), Types.BIGINT);
                    insert.setObject(8, map.difficulty(), Types.INTEGER);
                    insert.setInt(9, details.teams());
                    insert.setString(10, details.winnersText());
                    insert.setString(11, details.reason());
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    /**
     * The matches played on a generated map from {@code seed}, at any difficulty, the latest
     * first: one entry per map row, so a match that played the seed in two stretches of
     * rounds has two.
     */
    List<MatchOnSeed> matchesOnSeed(long seed, int limit) throws SQLException {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT match_id, ended_at, season, difficulty,"
                + " first_round, rounds, teams, winners, reason FROM " + prefix + "matches WHERE seed = ?"
                + " ORDER BY ended_at DESC, match_id, first_round LIMIT ?")) {
            query.setLong(1, seed);
            query.setInt(2, limit);
            List<MatchOnSeed> found = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    int season = rows.getInt(3);
                    Integer seasonId = rows.wasNull() ? null : season;
                    String winners = rows.getString(8);
                    List<Integer> teams = winners.isEmpty() ? List.of()
                            : Arrays.stream(winners.split(",")).map(Integer::valueOf).toList();
                    found.add(new MatchOnSeed(UUID.fromString(rows.getString(1)),
                            Instant.ofEpochMilli(rows.getLong(2)), seasonId, rows.getInt(4), rows.getInt(5),
                            rows.getInt(6), rows.getInt(7), teams, rows.getString(9)));
                }
            }
            return List.copyOf(found);
        }
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

    void grant(UUID player, int season, RewardSettings.Rule rule) throws SQLException {
        update("INSERT INTO " + prefix + "awards (player, season, rule_id, title, tag, trophy)"
                + " VALUES (?, ?, ?, ?, ?, ?)" + dialect.keepExisting("player, season, rule_id", "player"),
                player.toString(), season, rule.id(), rule.title(), rule.tag(), rule.trophy());
    }

    List<Award> awards(UUID player) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT season, rule_id, title, tag, trophy, claim_id"
                + " FROM " + prefix + "awards WHERE player = ? ORDER BY season DESC, rule_id DESC")) {
            query.setString(1, player.toString());
            List<Award> awards = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    awards.add(new Award(rows.getInt(1), rows.getString(2), rows.getString(3), rows.getString(4),
                            rows.getString(5), !rows.getString(5).isEmpty() && rows.getString(6) == null));
                }
            }
            return List.copyOf(awards);
        }
    }

    boolean trophyClaim(UUID player, Award award, UUID claim, boolean release) throws SQLException {
        String row = " WHERE player = ? AND season = ? AND rule_id = ? AND trophy <> ''";
        if (release) {
            return update("UPDATE " + prefix + "awards SET claim_id = NULL" + row + " AND claim_id = ?",
                    player.toString(), award.season(), award.id(), claim.toString()) == 1;
        }
        return update("UPDATE " + prefix + "awards SET claim_id = ?" + row + " AND claim_id IS NULL",
                claim.toString(), player.toString(), award.season(), award.id()) == 1;
    }

    void awardPlacing(int season, RewardSettings.Rule rule) throws SQLException {
        String slot = prefix + "placing_slots";
        if (placingPlayer(slot, season, rule.id()) != null) {
            return;
        }
        List<LeaderboardEntry> board = top(rule.stat(), StatsScope.season(season), rule.target());
        if (board.size() < rule.target()) {
            return;
        }
        UUID player = board.getLast().player();
        transaction(() -> {
            update("INSERT INTO " + slot + " (season, rule_id, player) VALUES (?, ?, ?)"
                    + dialect.keepExisting("season, rule_id", "season"), season, rule.id(), player.toString());
            if (player.toString().equals(placingPlayer(slot, season, rule.id()))) {
                grant(player, season, rule);
            }
            return null;
        });
    }

    private String placingPlayer(String slot, int season, String rule) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player FROM " + slot + " WHERE season = ? AND rule_id = ?")) {
            query.setInt(1, season);
            query.setString(2, rule);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    long reservePayout(UUID player, UUID match, long day, RewardSettings.Payout payout) throws SQLException {
        if (!payout.enabled()) {
            return 0;
        }
        return transaction(() -> {
            String id = player.toString();
            // The upsert locks this player's day before the ledger and balance reads.
            update("INSERT INTO " + prefix + "payout_days (player, day_id, spent) VALUES (?, ?, 0)"
                    + dialect.keepExisting("player, day_id", "player"), id, day);
            if (payoutValue("SELECT 1 FROM " + prefix + "payout_matches WHERE player = ? AND match_id = ?",
                    id, match.toString()) != 0) {
                return 0L;
            }
            long spent = payoutValue("SELECT spent FROM " + prefix + "payout_days WHERE player = ? AND day_id = ?",
                    id, day);
            long amount = Math.max(0, Math.min(payout.amount(), payout.dailyCap() - spent));
            update("INSERT INTO " + prefix + "payout_matches (player, match_id) VALUES (?, ?)", id, match.toString());
            update("UPDATE " + prefix + "payout_days SET spent = spent + ? WHERE player = ? AND day_id = ?",
                    amount, id, day);
            return amount;
        });
    }

    private long payoutValue(String sql, String player, Object key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player);
            statement.setObject(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    private int update(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static String statColumns(String alias) {
        return String.join(", ", Dialect.COLUMNS.stream().map(stat -> alias + "." + stat.column()).toList());
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws SQLException;
    }

    /**
     * Takes SQLite's write lock before the transaction reads anything. A transaction that reads
     * first and then writes fails at once with SQLITE_BUSY_SNAPSHOT when another server commits
     * in between; one that starts with a write waits for the lock under busy_timeout instead.
     * MySQL locks the rows it reads with FOR UPDATE.
     */
    private void lockForWrite() throws SQLException {
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + prefix + "seasons SET id = id WHERE 0 = 1");
            }
        }
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
