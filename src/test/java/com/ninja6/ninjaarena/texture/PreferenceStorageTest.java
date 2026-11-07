package com.ninja6.ninjaarena.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.resourcepack.BedrockSelections.Quality;
import com.ninja6.ninjaarena.stats.ConnectionSource;
import com.ninja6.ninjaarena.stats.MatchRecord;
import com.ninja6.ninjaarena.stats.PlayerStats;
import com.ninja6.ninjaarena.stats.StatsSettings;
import com.ninja6.ninjaarena.stats.StatsStore;
import com.ninja6.ninjaarena.texture.PreferenceBackend.SchemaTooNew;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests against a real SQLite file. */
class PreferenceStorageTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;

    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
    }

    private StatsSettings settings(boolean statsEnabled) {
        return new StatsSettings(statsEnabled, StatsSettings.Storage.SQLITE, "Season 1", "na_", "stats.db",
                StatsSettings.DEFAULTS.mysql());
    }

    private PreferenceStore store(boolean statsEnabled) throws Exception {
        PreferenceStore store = PreferenceStore.open(settings(statsEnabled), dir, LOG);
        open.add(store);
        awaitReady(store);
        return store;
    }

    static void awaitReady(PreferenceStore store) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!store.isReady() && System.nanoTime() < deadline) {
            if (store.health() == PreferenceStore.Health.UNSUPPORTED) {
                return;
            }
            Thread.sleep(10);
        }
    }

    private ConnectionSource source() throws Exception {
        return ConnectionSource.sqlite(dir.resolve("stats.db"), ConnectionSource.SQLITE_DRIVER);
    }

    private int count(String sql) throws Exception {
        try (Connection connection = source().connect(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = source().connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void newPlayersHaveNoRecordAndFallBackToStandard() throws Exception {
        PreferenceStore store = store(true);
        PlayerKey key = PlayerKey.java(ALICE);
        assertEquals(Quality.STANDARD, store.snapshot(key));
        assertTrue(store.explicit(key).isEmpty());
        assertTrue(store.load(key).get().isEmpty());
        assertEquals(0, count("SELECT COUNT(*) FROM na_texture_preferences"));
    }

    @Test
    void explicitChoicesSurviveClosingAndReopeningTheFile() throws Exception {
        PreferenceStore first = store(true);
        Stored saved = first.save(PlayerKey.java(ALICE), Quality.DETAILED).get();
        first.save(PlayerKey.bedrock("2535400000001"), Quality.DETAILED).get();
        assertEquals(Quality.DETAILED, saved.quality());
        assertEquals(1, saved.revision());
        assertEquals(2, first.save(PlayerKey.java(ALICE), Quality.STANDARD).get().revision());
        first.save(PlayerKey.java(ALICE), Quality.DETAILED).get();
        first.close();

        PreferenceStore second = store(true);
        assertEquals(Quality.DETAILED, second.snapshot(PlayerKey.java(ALICE)));
        assertEquals(Quality.DETAILED, second.snapshot(PlayerKey.bedrock("2535400000001")));
        assertEquals(3, second.load(PlayerKey.java(ALICE)).get().orElseThrow().revision());
        assertEquals(Quality.STANDARD, second.snapshot(PlayerKey.java(BOB)));
    }

    @Test
    void explicitStandardIsStoredAndDiffersFromNoRecord() throws Exception {
        PreferenceStore store = store(true);
        store.save(PlayerKey.java(ALICE), Quality.STANDARD).get();
        assertTrue(store.explicit(PlayerKey.java(ALICE)).isPresent());
        assertTrue(store.explicit(PlayerKey.java(BOB)).isEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM na_texture_preferences"));
    }

    @Test
    void javaAndBedrockIdentitiesNeverMergeEvenWithSimilarIds() throws Exception {
        PreferenceStore store = store(true);
        store.save(PlayerKey.java(ALICE), Quality.DETAILED).get();
        store.save(PlayerKey.bedrock("10"), Quality.DETAILED).get();
        store.save(PlayerKey.bedrock("11"), Quality.STANDARD).get();
        assertEquals(Quality.DETAILED, store.snapshot(PlayerKey.java(ALICE)));
        assertEquals(Quality.STANDARD, store.snapshot(PlayerKey.java(BOB)));
        assertEquals(Quality.DETAILED, store.snapshot(PlayerKey.bedrock("10")));
        assertEquals(Quality.STANDARD, store.snapshot(PlayerKey.bedrock("11")));
        assertEquals(Quality.STANDARD, store.snapshot(PlayerKey.bedrock("1")));
        assertEquals(3, count("SELECT COUNT(DISTINCT identity) FROM na_texture_preferences"));
    }

    @Test
    void worksWithStatisticsDisabledAndLeavesTheStatsTablesAlone() throws Exception {
        PreferenceStore store = store(false);
        store.save(PlayerKey.java(ALICE), Quality.DETAILED).get();
        assertEquals(Quality.DETAILED, store.snapshot(PlayerKey.java(ALICE)));
        assertEquals(1, count("SELECT version FROM na_texture_schema WHERE id = 1"));
        assertEquals(0, count("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('na_player_stats', 'na_seasons',"
                + " 'na_schema_version')"));
    }

    @Test
    void seasonChangesAndClearedSeasonalRecordsLeavePreferencesIntact() throws Exception {
        PreferenceStore prefs = store(true);
        prefs.save(PlayerKey.java(ALICE), Quality.DETAILED).get();
        StatsStore stats = StatsStore.open(settings(true), dir, LOG);
        open.add(stats::close);
        stats.record(new MatchRecord(Instant.parse("2026-11-01T12:00:00Z"),
                List.of(new PlayerStats(ALICE, "Alice", 1, 1, 0, 0, 0, 0, 1))));
        stats.flush().get(10, TimeUnit.SECONDS);
        stats.useSeason("Season 2").get(10, TimeUnit.SECONDS);
        stats.startSeason("Season 3").get(10, TimeUnit.SECONDS);
        stats.flush().get(10, TimeUnit.SECONDS);
        assertTrue(count("SELECT COUNT(*) FROM na_player_stats") > 0);
        execute("DELETE FROM na_player_stats");
        execute("DELETE FROM na_seasons");
        assertEquals(Quality.DETAILED, prefs.load(PlayerKey.java(ALICE)).get().orElseThrow().quality());
        prefs.close();
        assertEquals(Quality.DETAILED, store(true).snapshot(PlayerKey.java(ALICE)));
        assertEquals(1, count("SELECT version FROM na_texture_schema WHERE id = 1"));
        assertEquals(2, count("SELECT version FROM na_schema_version WHERE id = 1"));
    }

    @Test
    void anUnreadableRowActsAsNoChoiceAndAnUnknownQualityIsIgnored() throws Exception {
        store(true).close();
        execute("INSERT INTO na_texture_preferences VALUES ('java:" + ALICE + "', 'ultra', 1, 1)");
        execute("INSERT INTO na_texture_preferences VALUES ('not a key', 'detailed', 1, 1)");
        execute("INSERT INTO na_texture_preferences VALUES ('bedrock:42', 'detailed', 1, 1)");
        PreferenceStore store = store(true);
        assertEquals(Quality.STANDARD, store.snapshot(PlayerKey.java(ALICE)));
        assertEquals(Quality.DETAILED, store.snapshot(PlayerKey.bedrock("42")));
    }

    // Schema versioning, on the real file.

    @Test
    void migrationsRunOnceInOrderAndAreRecordedLast() throws Exception {
        List<List<String>> versions = new ArrayList<>(SqlPreferenceBackend.migrations(StatsSettings.Storage.SQLITE, "na_"));
        versions.add(List.of("CREATE TABLE na_order (n INTEGER)", "INSERT INTO na_order VALUES (2)"));
        versions.add(List.of("INSERT INTO na_order VALUES (3)"));
        SqlPreferenceBackend.open(source(), StatsSettings.Storage.SQLITE, "na_", versions).close();
        SqlPreferenceBackend.open(source(), StatsSettings.Storage.SQLITE, "na_", versions).close();
        assertEquals(3, count("SELECT version FROM na_texture_schema WHERE id = 1"));
        assertEquals(2, count("SELECT COUNT(*) FROM na_order"));
        assertEquals(2, count("SELECT n FROM na_order ORDER BY rowid LIMIT 1"));
        assertEquals(3, count("SELECT n FROM na_order ORDER BY rowid DESC LIMIT 1"));
    }

    @Test
    void aFailedMigrationLeavesTheVersionAndTheNextStartRepeatsIt() throws Exception {
        List<List<String>> base = SqlPreferenceBackend.migrations(StatsSettings.Storage.SQLITE, "na_");
        SqlPreferenceBackend.open(source(), StatsSettings.Storage.SQLITE, "na_", base).close();
        List<List<String>> broken = new ArrayList<>(base);
        broken.add(List.of("CREATE TABLE na_extra (n INTEGER)", "THIS IS NOT SQL"));
        assertThrows(SQLException.class,
                () -> SqlPreferenceBackend.open(source(), StatsSettings.Storage.SQLITE, "na_", broken));
        assertEquals(1, count("SELECT version FROM na_texture_schema WHERE id = 1"));
        assertEquals(0, count("SELECT COUNT(*) FROM sqlite_master WHERE name = 'na_extra'"));
        List<List<String>> fixed = new ArrayList<>(base);
        fixed.add(List.of("CREATE TABLE na_extra (n INTEGER)"));
        SqlPreferenceBackend.open(source(), StatsSettings.Storage.SQLITE, "na_", fixed).close();
        assertEquals(2, count("SELECT version FROM na_texture_schema WHERE id = 1"));
    }

    @Test
    void aNewerSchemaIsLeftUntouchedAndTheStoreStaysUnsupported() throws Exception {
        store(true).close();
        execute("UPDATE na_texture_schema SET version = 99 WHERE id = 1");
        execute("INSERT INTO na_texture_preferences VALUES ('java:" + ALICE + "', 'detailed', 5, 1)");
        assertThrows(SchemaTooNew.class, () -> SqlPreferenceBackend.open(source(), StatsSettings.Storage.SQLITE, "na_"));
        PreferenceStore store = store(true);
        assertEquals(PreferenceStore.Health.UNSUPPORTED, store.health());
        assertEquals(Quality.STANDARD, store.snapshot(PlayerKey.java(ALICE)));
        assertTrue(store.problem().contains("version 99"));
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> store.save(PlayerKey.java(ALICE), Quality.STANDARD).get());
        assertEquals(99, count("SELECT version FROM na_texture_schema WHERE id = 1"));
        assertEquals(5, count("SELECT revision FROM na_texture_preferences"));
        assertEquals(1, count("SELECT COUNT(*) FROM na_texture_preferences WHERE quality = 'detailed'"));
    }

    @Test
    void closingWaitsForQueuedWritesAndRefusesNewOnes() throws Exception {
        PreferenceStore store = store(true);
        var pending = store.save(PlayerKey.java(ALICE), Quality.DETAILED);
        store.close();
        assertTrue(pending.isDone() && !pending.isCompletedExceptionally());
        assertTrue(store.save(PlayerKey.java(BOB), Quality.DETAILED).isCompletedExceptionally());
        assertFalse(store.explicit(PlayerKey.java(BOB)).isPresent());
        assertEquals(Quality.DETAILED, store(true).snapshot(PlayerKey.java(ALICE)));
    }
}
