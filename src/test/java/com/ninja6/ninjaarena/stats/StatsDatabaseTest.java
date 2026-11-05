package com.ninja6.ninjaarena.stats;

import static com.ninja6.ninjaarena.stats.Records.ALICE;
import static com.ninja6.ninjaarena.stats.Records.BOB;
import static com.ninja6.ninjaarena.stats.Records.CAROL;
import static com.ninja6.ninjaarena.stats.Records.T0;
import static com.ninja6.ninjaarena.stats.Records.match;
import static com.ninja6.ninjaarena.stats.Records.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.reward.RewardSettings;
import com.ninja6.ninjaarena.stats.StatsDatabase.SeasonChange;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StatsDatabaseTest {

    private static final AtomicInteger NAMES = new AtomicInteger();

    @TempDir
    Path dir;

    private final List<StatsDatabase> open = new ArrayList<>();

    @AfterEach
    void closeAll() {
        open.forEach(StatsDatabase::close);
    }

    private ConnectionSource source(Dialect dialect) throws Exception {
        return dialect == Dialect.SQLITE ? Records.sqliteSource(dir.resolve("stats.db"))
                : Records.mysqlLike("stats" + NAMES.incrementAndGet());
    }

    private StatsDatabase open(Dialect dialect, ConnectionSource source) throws Exception {
        StatsDatabase database = StatsDatabase.open(source, dialect, "na_");
        open.add(database);
        return database;
    }

    private StatsDatabase seasoned(Dialect dialect) throws Exception {
        StatsDatabase database = open(dialect, source(dialect));
        assertEquals(SeasonChange.CREATED, database.useSeason("Season 1", T0).change());
        return database;
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void migrationCreatesTheVersionedSchemaAndReopeningIsIdempotent(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        StatsDatabase first = open(dialect, source);
        assertEquals(StatsDatabase.SCHEMA_VERSION, first.schemaVersion());
        first.useSeason("Season 1", T0);
        first.write(List.of(match(player(ALICE, "Alice", 1, 2, 3, 4, 5, 6))));
        first.close();
        StatsDatabase second = open(dialect, source);
        assertEquals(StatsDatabase.SCHEMA_VERSION, second.schemaVersion());
        assertEquals(3, second.stats(ALICE, StatsScope.ALL_TIME).orElseThrow().playerKills());
        assertEquals(1, second.seasons().size());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void archivedPlacingAndTrophyClaimsAreUniqueAcrossConnections(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        StatsDatabase first = open(dialect, source);
        first.useSeason("Season 1", T0);
        first.write(List.of(match(player(BOB, "Bob", 1, 0, 0, 0, 0, 0),
                player(ALICE, "Alice", 1, 0, 0, 0, 0, 0))));
        first.startSeason("Season 2", T0.plusSeconds(1));
        var rule = new RewardSettings.Rule("champion", Stat.WINS, 1, "Champion", "", "Cup");
        first.awardPlacing(1, rule);
        StatsDatabase second = open(dialect, source);
        second.awardPlacing(1, new RewardSettings.Rule("champion", Stat.WINS, 2, "Changed", "", "Changed"));
        assertTrue(second.awards(BOB).isEmpty());
        var award = second.awards(ALICE).getFirst();
        assertEquals("Champion", award.title());
        assertTrue(award.pending());
        UUID claim = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertTrue(first.trophyClaim(ALICE, award, claim, false));
        assertFalse(second.trophyClaim(ALICE, award, other, false));
        assertFalse(second.trophyClaim(ALICE, award, other, true));
        assertTrue(first.trophyClaim(ALICE, award, claim, true));
        assertTrue(second.trophyClaim(ALICE, award, other, false));
        assertFalse(first.trophyClaim(ALICE, award, claim, true));
        assertFalse(first.awards(ALICE).getFirst().pending());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void payoutCapAndMatchLedgerSurviveReopening(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        StatsDatabase first = open(dialect, source);
        var payout = new RewardSettings.Payout(true, 700, 1000);
        UUID match = UUID.randomUUID();
        assertEquals(0, first.reservePayout(ALICE, match, 10, RewardSettings.OFF.payout()));
        assertEquals(700, first.reservePayout(ALICE, match, 10, payout));
        first.close();
        StatsDatabase second = open(dialect, source);
        assertEquals(0, second.reservePayout(ALICE, match, 10, payout));
        assertEquals(0, second.reservePayout(ALICE, match, 11, payout));
        assertEquals(300, second.reservePayout(ALICE, UUID.randomUUID(), 10, payout));
        assertEquals(0, second.reservePayout(ALICE, UUID.randomUUID(), 10, payout));
        assertEquals(700, second.reservePayout(BOB, match, 10, payout));
        assertEquals(700, second.reservePayout(ALICE, UUID.randomUUID(), 11, payout));
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void concurrentPayoutsShareOneDailyCap(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        StatsDatabase first = open(dialect, source);
        StatsDatabase second = open(dialect, source);
        var payout = new RewardSettings.Payout(true, 700, 1000);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var left = workers.submit(() -> {
                start.await();
                return first.reservePayout(ALICE, UUID.randomUUID(), 10, payout);
            });
            var right = workers.submit(() -> {
                start.await();
                return second.reservePayout(ALICE, UUID.randomUUID(), 10, payout);
            });
            start.countDown();
            assertEquals(1000, left.get(10, TimeUnit.SECONDS) + right.get(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void writesAddUpAllTimeAndSeasonTotalsAndKeepTheBestOverrun(Dialect dialect) throws Exception {
        StatsDatabase database = seasoned(dialect);
        database.write(List.of(match(player(ALICE, "Alice", 1, 2, 3, 4, 1, 30), player(BOB, "Bob", 0, 1, 1, 2, 3, 50))));
        database.write(List.of(match(T0.plusSeconds(60), player(ALICE, "Alice", 0, 1, 2, 5, 2, 10))));
        for (StatsScope scope : List.of(StatsScope.ALL_TIME, StatsScope.CURRENT_SEASON, StatsScope.season(1))) {
            assertEquals(new PlayerStats(ALICE, "Alice", 1, 3, 5, 9, 3, 30, 2),
                    database.stats(ALICE, scope).orElseThrow(), scope.toString());
            assertEquals(new PlayerStats(BOB, "Bob", 0, 1, 1, 2, 3, 50, 1), database.stats(BOB, scope).orElseThrow());
        }
        assertEquals(Optional.empty(), database.stats(CAROL, StatsScope.ALL_TIME));
        assertEquals(Optional.empty(), database.stats(ALICE, StatsScope.season(2)));
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void oneBatchMergesAPlayerAcrossMatches(Dialect dialect) throws Exception {
        StatsDatabase database = seasoned(dialect);
        database.write(List.of(match(player(ALICE, null, 1, 1, 1, 1, 1, 5)),
                match(T0.plusSeconds(5), player(ALICE, "Alice", 0, 1, 1, 1, 1, 9)),
                match(T0.plusSeconds(9), player(ALICE, null, 1, 0, 0, 0, 0, 2))));
        assertEquals(new PlayerStats(ALICE, "Alice", 2, 2, 2, 2, 2, 9, 3),
                database.stats(ALICE, StatsScope.ALL_TIME).orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void topTenCutsOffTiesByUuidAndCurrentSeasonHasItsOwnBoard(Dialect dialect) throws Exception {
        StatsDatabase database = seasoned(dialect);
        for (int id = 12; id >= 1; id--) {
            database.write(List.of(match(player(new UUID(0, id), "Player" + id, 1, 0, 0, 0, 0, 0))));
        }
        database.startSeason("Season 2", T0.plusSeconds(1));
        UUID winner = new UUID(0, 12);
        database.write(List.of(match(T0.plusSeconds(2), player(winner, "Winner", 1, 0, 0, 0, 0, 0))));

        List<LeaderboardEntry> allTime = database.top(Stat.WINS, StatsScope.ALL_TIME, Leaderboards.LIMIT);
        assertEquals(10, allTime.size());
        assertEquals(new LeaderboardEntry(1, winner, "Winner", 2), allTime.getFirst());
        for (int index = 1; index < allTime.size(); index++) {
            assertEquals(new LeaderboardEntry(index + 1, new UUID(0, index), "Player" + index, 1),
                    allTime.get(index));
        }
        assertEquals(List.of(new LeaderboardEntry(1, winner, "Winner", 1)),
                database.top(Stat.WINS, StatsScope.CURRENT_SEASON, Leaderboards.LIMIT));
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void totalsStopAtTheIntegerLimitInsteadOfFailingTheBatch(Dialect dialect) throws Exception {
        StatsDatabase database = seasoned(dialect);
        int max = Integer.MAX_VALUE;
        database.write(List.of(match(new PlayerStats(ALICE, "Alice", max - 1, 0, 0, 0, 0, 0, 1))));
        database.write(List.of(match(new PlayerStats(ALICE, "Alice", 5, 0, 0, 0, 0, 0, 1))));
        assertEquals(max, database.stats(ALICE, StatsScope.ALL_TIME).orElseThrow().wins());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void aNewSeasonArchivesTheOldOneWithoutDeletingItsStats(Dialect dialect) throws Exception {
        StatsDatabase database = seasoned(dialect);
        database.write(List.of(match(player(ALICE, "Alice", 1, 1, 4, 0, 0, 0))));
        var started = database.useSeason("Season 2", T0.plus(Duration.ofDays(30)));
        assertEquals(SeasonChange.STARTED, started.change());
        assertEquals(2, started.season().id());
        database.write(List.of(match(player(ALICE, "Alice", 0, 0, 1, 0, 0, 0))));

        assertEquals(5, database.stats(ALICE, StatsScope.ALL_TIME).orElseThrow().playerKills());
        assertEquals(1, database.stats(ALICE, StatsScope.CURRENT_SEASON).orElseThrow().playerKills());
        assertEquals(4, database.stats(ALICE, StatsScope.season(1)).orElseThrow().playerKills());
        List<Season> seasons = database.seasons();
        assertEquals(List.of("Season 1", "Season 2"), seasons.stream().map(Season::name).toList());
        assertEquals(Optional.of(T0.plus(Duration.ofDays(30))), seasons.getFirst().ended());
        assertFalse(seasons.getFirst().isCurrent());
        assertTrue(seasons.getLast().isCurrent());
        assertEquals("Season 2", database.currentSeason().orElseThrow().name());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void theConfiguredSeasonIsKeptAndAnArchivedNameIsRefused(Dialect dialect) throws Exception {
        StatsDatabase database = seasoned(dialect);
        assertEquals(SeasonChange.UNCHANGED, database.useSeason("Season 1", T0.plusSeconds(1)).change());
        database.useSeason("Season 2", T0.plusSeconds(2));
        var refused = database.useSeason("Season 1", T0.plusSeconds(3));
        assertEquals(SeasonChange.REFUSED, refused.change());
        assertEquals("Season 2", refused.season().name());
        assertEquals(2, database.seasons().size());
        assertThrows(IllegalArgumentException.class, () -> database.startSeason("Season 1", T0.plusSeconds(4)));
        assertEquals("Season 3", database.startSeason("Season 3", T0.plusSeconds(5)).name());
        assertEquals(1, database.seasons().stream().filter(Season::isCurrent).count());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void topListsRankBestFirstBreakTiesByPlayerAndLeaveOutZeros(Dialect dialect) throws Exception {
        StatsDatabase database = seasoned(dialect);
        UUID dave = new UUID(0, 4);
        database.write(List.of(match(player(CAROL, "Carol", 0, 0, 7, 0, 0, 0), player(BOB, "Bob", 0, 0, 7, 0, 0, 0),
                player(ALICE, "Alice", 1, 0, 9, 0, 0, 0), player(dave, null, 0, 0, 0, 0, 0, 0))));
        assertEquals(List.of(new LeaderboardEntry(1, ALICE, "Alice", 9), new LeaderboardEntry(2, BOB, "Bob", 7),
                new LeaderboardEntry(3, CAROL, "Carol", 7)), database.top(Stat.PLAYER_KILLS, StatsScope.ALL_TIME, 10));
        assertEquals(List.of(new LeaderboardEntry(1, ALICE, "Alice", 9)),
                database.top(Stat.PLAYER_KILLS, StatsScope.CURRENT_SEASON, 1));
        assertEquals(List.of(new LeaderboardEntry(1, ALICE, "Alice", 1)),
                database.top(Stat.WINS, StatsScope.ALL_TIME, 10));
        assertEquals(4, database.top(Stat.MATCHES_PLAYED, StatsScope.ALL_TIME, 10).size());
        assertEquals(null, database.top(Stat.MATCHES_PLAYED, StatsScope.ALL_TIME, 10).getLast().name());
        assertEquals(List.of(), database.top(Stat.WINS, StatsScope.season(9), 10));
        assertThrows(IllegalArgumentException.class, () -> database.top(Stat.WINS, StatsScope.ALL_TIME, 0));
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void theLatestNameWinsEvenWhenAnOlderMatchIsWrittenLater(Dialect dialect) throws Exception {
        StatsDatabase database = seasoned(dialect);
        database.write(List.of(match(T0.plusSeconds(100), player(ALICE, "NewName", 0, 0, 0, 0, 0, 0))));
        database.write(List.of(match(T0, player(ALICE, "OldName", 0, 0, 0, 0, 0, 0))));
        database.write(List.of(match(T0.plusSeconds(200), player(ALICE, null, 0, 0, 0, 0, 0, 0))));
        assertEquals("NewName", database.stats(ALICE, StatsScope.ALL_TIME).orElseThrow().name());
        String longName = "x".repeat(StatsDatabase.NAME_LENGTH + 8);
        database.write(List.of(match(T0.plusSeconds(300), player(ALICE, longName, 0, 0, 0, 0, 0, 0))));
        assertEquals("x".repeat(StatsDatabase.NAME_LENGTH), database.stats(ALICE, StatsScope.ALL_TIME).orElseThrow().name());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void aDatabaseFromANewerBuildIsNeverTouched(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        open(dialect, source).close();
        try (Connection connection = source.connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE na_schema_version SET version = 99");
        }
        var failure = assertThrows(StatsDatabase.SchemaTooNew.class, () -> StatsDatabase.open(source, dialect, "na_"));
        assertTrue(failure.getMessage().contains("version 99"), failure.getMessage());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void tablePrefixesKeepTwoSetsOfStatsApartInOneDatabase(Dialect dialect) throws Exception {
        ConnectionSource source = source(dialect);
        StatsDatabase first = StatsDatabase.open(source, dialect, "a_");
        StatsDatabase second = StatsDatabase.open(source, dialect, "b_");
        open.add(first);
        open.add(second);
        first.useSeason("One", T0);
        second.useSeason("Two", T0);
        first.write(List.of(match(player(ALICE, "Alice", 1, 0, 0, 0, 0, 0))));
        assertEquals(1, first.stats(ALICE, StatsScope.ALL_TIME).orElseThrow().wins());
        assertEquals(Optional.empty(), second.stats(ALICE, StatsScope.ALL_TIME));
        assertEquals("Two", second.currentSeason().orElseThrow().name());
    }

    @Test
    void aFailedWriteRollsBackEveryRowOfTheBatch() throws Exception {
        ConnectionSource real = Records.sqliteSource(dir.resolve("stats.db"));
        Faults faults = new Faults(real);
        StatsDatabase database = open(Dialect.SQLITE, faults);
        database.useSeason("Season 1", T0);
        faults.failPlayers = true;
        assertThrows(SQLException.class, () -> database.write(List.of(match(player(ALICE, "Alice", 1, 1, 1, 1, 1, 1)))));
        faults.failPlayers = false;
        assertEquals(Optional.empty(), database.stats(ALICE, StatsScope.ALL_TIME));
        database.write(List.of(match(player(ALICE, "Alice", 1, 1, 1, 1, 1, 1))));
        assertEquals(1, database.stats(ALICE, StatsScope.ALL_TIME).orElseThrow().wins());
    }

    @Test
    void sqliteNeedsItsDriverAndReportsItMissing() {
        var failure = assertThrows(ConnectionSource.MissingDriver.class,
                () -> ConnectionSource.sqlite(dir.resolve("stats.db"), "org.example.NoSuchDriver"));
        assertTrue(failure.getMessage().contains("not available"), failure.getMessage());
    }
}
