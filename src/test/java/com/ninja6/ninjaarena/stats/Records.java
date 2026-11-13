package com.ninja6.ninjaarena.stats;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Test data and databases for the stats tests. */
final class Records {

    static final UUID ALICE = new UUID(0, 1);
    static final UUID BOB = new UUID(0, 2);
    static final UUID CAROL = new UUID(0, 3);
    static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");

    private Records() {
    }

    /** One match: wins, rounds won, player kills, enemy kills, deaths, best overrun; played once. */
    static PlayerStats player(UUID id, String name, int wins, int rounds, int kills, int enemies, int deaths,
            int overrun) {
        return new PlayerStats(id, name, wins, rounds, kills, enemies, deaths, overrun, 1);
    }

    static MatchRecord match(Instant at, PlayerStats... players) {
        return new MatchRecord(at, List.of(players));
    }

    static MatchRecord match(PlayerStats... players) {
        return match(T0, players);
    }

    /** A completed two-team match that team 1 won, with its own rows. */
    static MatchRecord played(UUID id, Instant at, List<MatchRecord.Played> maps, PlayerStats... players) {
        return new MatchRecord(at, List.of(players), new MatchRecord.Details(id, "COMPLETED", 2, List.of(1), maps));
    }

    static ConnectionSource sqliteSource(Path file) throws ConnectionSource.MissingDriver {
        return ConnectionSource.sqlite(file, ConnectionSource.SQLITE_DRIVER);
    }

    static StatsDatabase sqlite(Path file) throws Exception {
        return StatsDatabase.open(sqliteSource(file), Dialect.SQLITE, "na_");
    }

    /** H2 in MySQL mode, standing in for a MySQL server; each name is a separate database. */
    static ConnectionSource mysqlLike(String name) {
        String url = "jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        return () -> DriverManager.getConnection(url);
    }
}
