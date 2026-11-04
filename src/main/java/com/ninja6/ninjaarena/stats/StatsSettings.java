package com.ninja6.ninjaarena.stats;

import java.util.Locale;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Where player stats are stored, read from the {@code stats} section of {@code config.yml}.
 * Unlike the gameplay settings, invalid values are not clamped: they would send stats to the
 * wrong place, so {@link #from} rejects them and stats stay off until they are fixed.
 *
 * @param enabled     whether stats are recorded at all
 * @param storage     the database kind
 * @param season      the name of the current season; a new name archives the current season
 * @param tablePrefix prepended to every table name, so several plugins can share a database
 * @param sqliteFile  the SQLite database file, inside the plugin's data folder
 */
public record StatsSettings(boolean enabled, Storage storage, String season, String tablePrefix, String sqliteFile,
        MySql mysql) {

    /** The supported databases. */
    public enum Storage {
        /** A local file; the default. */
        SQLITE,
        /** A MySQL or MariaDB server that several Arena servers can share. */
        MYSQL
    }

    /** MySQL or MariaDB connection settings. */
    public record MySql(String host, int port, String database, String username, String password) {

        public MySql {
            Objects.requireNonNull(password, "password");
            check("stats.mysql.host", host);
            check("stats.mysql.database", database);
            check("stats.mysql.username", username);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("stats.mysql.port: must be from 1 to 65535");
            }
        }

        @Override
        public String toString() {
            return "MySql[host=" + host + ", port=" + port + ", database=" + database + ", username=" + username + "]";
        }
    }

    public static final String DEFAULT_SEASON = "Season 1";
    public static final String DEFAULT_TABLE_PREFIX = "ninjaarena_";
    public static final String DEFAULT_SQLITE_FILE = "stats.db";

    public static final StatsSettings DEFAULTS = new StatsSettings(true, Storage.SQLITE, DEFAULT_SEASON,
            DEFAULT_TABLE_PREFIX, DEFAULT_SQLITE_FILE, new MySql("localhost", 3306, "ninjaarena", "ninjaarena", ""));

    public StatsSettings {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(mysql, "mysql");
        check("stats.season", season);
        check("stats.table-prefix", tablePrefix);
        check("stats.sqlite.file", sqliteFile);
    }

    /**
     * Reads the settings, using the defaults for a missing section or key.
     *
     * @throws IllegalArgumentException naming the setting, when a value is invalid
     */
    public static StatsSettings from(ConfigurationSection section) {
        if (section == null) {
            return DEFAULTS;
        }
        String storage = text(section, "storage", "sqlite");
        String storageProblem = problem("stats.storage", storage);
        if (storageProblem != null) {
            throw new IllegalArgumentException("stats.storage: " + storageProblem);
        }
        MySql defaults = DEFAULTS.mysql();
        return new StatsSettings(section.getBoolean("enabled", DEFAULTS.enabled()),
                Storage.valueOf(storage.toUpperCase(Locale.ROOT)), text(section, "season", DEFAULT_SEASON),
                text(section, "table-prefix", DEFAULT_TABLE_PREFIX), text(section, "sqlite.file", DEFAULT_SQLITE_FILE),
                new MySql(text(section, "mysql.host", defaults.host()), section.getInt("mysql.port", defaults.port()),
                        text(section, "mysql.database", defaults.database()),
                        text(section, "mysql.username", defaults.username()),
                        text(section, "mysql.password", defaults.password())));
    }

    private static String text(ConfigurationSection section, String path, String fallback) {
        Object value = section.get(path, fallback);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("stats." + path + ": expected text");
        }
        return text;
    }

    private static void check(String path, String value) {
        Objects.requireNonNull(value, path);
        String problem = problem(path, value);
        if (problem != null) {
            throw new IllegalArgumentException(path + ": " + problem);
        }
    }

    /**
     * What is wrong with a text setting under {@code stats}, or {@code null} when it is
     * valid. Used by both startup and the reload validation.
     *
     * @throws IllegalStateException for a path that is not a text setting
     */
    public static String problem(String path, String value) {
        return switch (path) {
            case "stats.storage" -> value.equals("sqlite") || value.equals("mysql") ? null
                    : "expected sqlite or mysql";
            case "stats.season" -> value.isBlank() || value.length() > 64 || !value.equals(value.strip())
                    || value.chars().anyMatch(Character::isISOControl)
                    ? "expected 1 to 64 characters, without control characters or surrounding spaces" : null;
            case "stats.table-prefix" -> value.matches("[a-z0-9_]{0,24}") ? null
                    : "expected up to 24 lower-case letters, digits or underscores";
            case "stats.sqlite.file" -> value.matches("[A-Za-z0-9._-]{1,64}") && !value.matches("\\.+")
                    ? null : "expected a plain file name";
            case "stats.mysql.host" -> value.matches("[A-Za-z0-9.:\\[\\]-]{1,253}") ? null
                    : "expected a host name or address";
            case "stats.mysql.database" -> value.matches("[A-Za-z0-9_$]{1,64}") ? null
                    : "expected 1 to 64 letters, digits, underscores or dollar signs";
            case "stats.mysql.username" -> value.isEmpty() || value.length() > 80
                    || value.chars().anyMatch(Character::isISOControl) ? "expected 1 to 80 characters" : null;
            case "stats.mysql.password" -> value.length() > 256 || value.chars().anyMatch(Character::isISOControl)
                    ? "expected at most 256 characters, without control characters" : null;
            default -> throw new IllegalStateException("No validation for " + path);
        };
    }
}
