package com.ninja6.ninjaarena.stats;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The SQL that differs between SQLite and MySQL/MariaDB. Table names carry the configured
 * prefix, which validation limits to lower-case letters, digits and underscores, and column
 * names come from {@link Stat}, so nothing from a player reaches the SQL text.
 */
enum Dialect {
    SQLITE,
    MYSQL;

    /** Stats columns in the order {@link StatsDatabase} binds them. */
    static final List<Stat> COLUMNS = List.of(Stat.values());
    /** Longest stored map name: a hand-built arena's name or {@link MatchRecord#GENERATED}. */
    static final int MAP_LENGTH = 32;
    /** Longest stored list of winning teams. */
    static final int WINNERS_LENGTH = 32;

    /** The statements of one schema version, run in order. */
    List<String> migration(int version, String prefix) {
        return switch (version) {
            case 1 -> this == SQLITE ? sqliteV1(prefix) : mysqlV1(prefix);
            case 2 -> rewards(prefix);
            case 3 -> matches(prefix);
            default -> throw new IllegalArgumentException("unknown schema version " + version);
        };
    }

    private static List<String> sqliteV1(String p) {
        Stream<String> tables = Stream.of(
                "CREATE TABLE IF NOT EXISTS " + p + "seasons (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " name VARCHAR(64) NOT NULL UNIQUE, started_at BIGINT NOT NULL, ended_at BIGINT)",
                "CREATE TABLE IF NOT EXISTS " + p + "player_stats (player CHAR(36) NOT NULL, season INTEGER NOT NULL,"
                        + statColumns() + ", PRIMARY KEY (player, season))",
                "CREATE TABLE IF NOT EXISTS " + p + "players (player CHAR(36) NOT NULL PRIMARY KEY,"
                        + " name VARCHAR(" + StatsDatabase.NAME_LENGTH + ") NOT NULL, last_seen BIGINT NOT NULL)");
        Stream<String> indexes = COLUMNS.stream().map(stat -> "CREATE INDEX IF NOT EXISTS " + p + "player_stats_"
                + stat.column() + " ON " + p + "player_stats (season, " + stat.column() + ")");
        return Stream.concat(tables, indexes).toList();
    }

    private static List<String> mysqlV1(String p) {
        String indexes = COLUMNS.stream().map(stat -> "INDEX " + p + "player_stats_" + stat.column()
                + " (season, " + stat.column() + ")").collect(Collectors.joining(", "));
        String table = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + p + "seasons (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        // Binary, so season names compare exactly as they do in SQLite.
                        + " name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,"
                        + " started_at BIGINT NOT NULL, ended_at BIGINT NULL,"
                        + " UNIQUE KEY " + p + "seasons_name (name))" + table,
                "CREATE TABLE IF NOT EXISTS " + p + "player_stats (player CHAR(36) NOT NULL, season INT NOT NULL,"
                        + statColumns() + ", PRIMARY KEY (player, season), " + indexes + ")" + table,
                "CREATE TABLE IF NOT EXISTS " + p + "players (player CHAR(36) NOT NULL PRIMARY KEY,"
                        + " name VARCHAR(" + StatsDatabase.NAME_LENGTH + ") NOT NULL, last_seen BIGINT NOT NULL)" + table);
    }

    private List<String> rewards(String p) {
        String suffix = this == MYSQL ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + p + "awards (player CHAR(36) NOT NULL, season INT NOT NULL,"
                        + " rule_id VARCHAR(48) NOT NULL, title VARCHAR(80) NOT NULL, tag VARCHAR(80) NOT NULL,"
                        + " trophy VARCHAR(80) NOT NULL, claim_id CHAR(36),"
                        + " PRIMARY KEY (player, season, rule_id))" + suffix,
                "CREATE TABLE IF NOT EXISTS " + p + "placing_slots (season INT NOT NULL, rule_id VARCHAR(48)"
                        + " NOT NULL, player CHAR(36) NOT NULL, PRIMARY KEY (season, rule_id))" + suffix,
                "CREATE TABLE IF NOT EXISTS " + p + "payout_days (player CHAR(36) NOT NULL, day_id BIGINT NOT NULL,"
                        + " spent BIGINT NOT NULL, PRIMARY KEY (player, day_id))" + suffix,
                "CREATE TABLE IF NOT EXISTS " + p + "payout_matches (player CHAR(36) NOT NULL, match_id CHAR(36)"
                        + " NOT NULL, PRIMARY KEY (player, match_id))" + suffix);
    }

    /**
     * Version 3: one row per map a match was played on, so a match on a map generated again
     * between rounds has a row for each map, and every seed played can be looked up. A
     * generated map is stored by its seed, with {@link MatchRecord#GENERATED} as its name.
     */
    private List<String> matches(String p) {
        String columns = "match_id CHAR(36) NOT NULL, first_round INT NOT NULL, rounds INT NOT NULL,"
                + " ended_at BIGINT NOT NULL, season INT, map VARCHAR(" + MAP_LENGTH + ") NOT NULL, seed BIGINT,"
                + " difficulty INT, teams INT NOT NULL, winners VARCHAR(" + WINNERS_LENGTH + ") NOT NULL,"
                + " reason VARCHAR(" + MatchRecord.REASON_LENGTH + ") NOT NULL, PRIMARY KEY (match_id, first_round)";
        if (this == MYSQL) {
            return List.of("CREATE TABLE IF NOT EXISTS " + p + "matches (" + columns + ", INDEX " + p
                    + "matches_seed (seed, difficulty))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        return List.of("CREATE TABLE IF NOT EXISTS " + p + "matches (" + columns + ")",
                "CREATE INDEX IF NOT EXISTS " + p + "matches_seed ON " + p + "matches (seed, difficulty)");
    }

    String keepExisting(String keys, String firstKey) {
        return this == SQLITE ? " ON CONFLICT (" + keys + ") DO NOTHING"
                : " ON DUPLICATE KEY UPDATE " + firstKey + " = " + firstKey;
    }

    private static String statColumns() {
        return COLUMNS.stream().map(stat -> " " + stat.column() + " INT NOT NULL DEFAULT 0")
                .collect(Collectors.joining(","));
    }

    String createSchemaTable(String p) {
        return "CREATE TABLE IF NOT EXISTS " + p + "schema_version (id INT NOT NULL PRIMARY KEY,"
                + " version INT NOT NULL)";
    }

    /** Adds a match to a row, keeping the best value of best-only stats. */
    String upsertStats(String p) {
        String columns = COLUMNS.stream().map(Stat::column).collect(Collectors.joining(", "));
        String values = COLUMNS.stream().map(stat -> "?").collect(Collectors.joining(", "));
        String insert = "INSERT INTO " + p + "player_stats (player, season, " + columns + ") VALUES (?, ?, " + values
                + ")";
        return switch (this) {
            case SQLITE -> insert + " ON CONFLICT (player, season) DO UPDATE SET " + COLUMNS.stream()
                    .map(stat -> stat.column() + " = " + (stat.isBest()
                            ? "MAX(" + stat.column() + ", excluded." + stat.column() + ")"
                            : "MIN(" + stat.column() + " + excluded." + stat.column() + ", " + Integer.MAX_VALUE + ")"))
                    .collect(Collectors.joining(", "));
            case MYSQL -> insert + " ON DUPLICATE KEY UPDATE " + COLUMNS.stream()
                    .map(stat -> stat.column() + " = " + (stat.isBest()
                            ? "GREATEST(" + stat.column() + ", VALUES(" + stat.column() + "))"
                            // Compared before adding, so the sum never leaves the INT range.
                            : "CASE WHEN " + stat.column() + " > " + Integer.MAX_VALUE + " - VALUES(" + stat.column()
                                    + ") THEN " + Integer.MAX_VALUE + " ELSE " + stat.column() + " + VALUES("
                                    + stat.column() + ") END"))
                    .collect(Collectors.joining(", "));
        };
    }

    String upsertPlayer(String p) {
        String insert = "INSERT INTO " + p + "players (player, name, last_seen) VALUES (?, ?, ?)";
        return switch (this) {
            case SQLITE -> insert + " ON CONFLICT (player) DO UPDATE SET name = excluded.name,"
                    + " last_seen = excluded.last_seen WHERE excluded.last_seen >= last_seen";
            case MYSQL -> insert + " ON DUPLICATE KEY UPDATE"
                    + " name = CASE WHEN VALUES(last_seen) >= last_seen THEN VALUES(name) ELSE name END,"
                    + " last_seen = GREATEST(last_seen, VALUES(last_seen))";
        };
    }

    String upsertSchemaVersion(String p) {
        String insert = "INSERT INTO " + p + "schema_version (id, version) VALUES (1, ?)";
        return switch (this) {
            case SQLITE -> insert + " ON CONFLICT (id) DO UPDATE SET version = MAX(version, excluded.version)";
            case MYSQL -> insert + " ON DUPLICATE KEY UPDATE version = GREATEST(version, VALUES(version))";
        };
    }
}
