package com.ninja6.ninjaarena.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ninja6.ninjaarena.stats.LeaderboardEntry;
import com.ninja6.ninjaarena.stats.PlayerStats;
import com.ninja6.ninjaarena.stats.Season;
import com.ninja6.ninjaarena.stats.Stat;
import com.ninja6.ninjaarena.stats.StatsScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StatsPlaceholdersTest {

    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);

    @Test
    void ownStatsCoalesceWhilePendingAndWorkByOfflineUuid() {
        AtomicInteger reads = new AtomicInteger();
        CompletableFuture<PlayerStats> result = new CompletableFuture<>();
        StatsPlaceholders placeholders = new StatsPlaceholders(() -> CompletableFuture.completedFuture(null),
                (player, scope) -> {
                    assertEquals(ALICE, player);
                    assertEquals(StatsScope.ALL_TIME, scope);
                    reads.incrementAndGet();
                    return result;
                }, (stat, scope) -> { throw new AssertionError(); }, () -> 0, 30);

        assertEquals("N/A", placeholders.resolve(ALICE, "stat_all_time_wins"));
        assertEquals("N/A", placeholders.resolve(ALICE, "stat_all_time_enemy_kills"));
        assertEquals("N/A", placeholders.resolve(null, "stat_all_time_wins"));
        result.complete(new PlayerStats(ALICE, "Alice", 1, 2, 3, 4, 5, 6, 7));
        assertEquals("1", placeholders.resolve(ALICE, "STAT_ALL_TIME_WINS"));
        assertEquals("4", placeholders.resolve(ALICE, "stat_all_time_enemy_kills"));
        assertEquals("6", placeholders.resolve(ALICE, "stat_all_time_best_overrun_seconds"));
        assertEquals(1, reads.get());
    }

    @Test
    void topRowsRetainStoreOrderAndShareOneReadAcrossRanksAndFields() {
        AtomicInteger reads = new AtomicInteger();
        StatsPlaceholders placeholders = new StatsPlaceholders(() -> { throw new AssertionError(); },
                (player, scope) -> { throw new AssertionError(); },
                (stat, scope) -> {
                    assertEquals(Stat.PLAYER_KILLS, stat);
                    assertEquals(StatsScope.ALL_TIME, scope);
                    reads.incrementAndGet();
                    return CompletableFuture.completedFuture(List.of(
                            new LeaderboardEntry(1, ALICE, "Alice", 9), new LeaderboardEntry(2, BOB, null, 9)));
                }, () -> 0, 30);

        assertEquals("N/A", placeholders.resolve(null, "top_all_time_player_kills_1_name"));
        assertEquals("Alice", placeholders.resolve(null, "top_all_time_player_kills_1_name"));
        assertEquals("Unknown player", placeholders.resolve(null, "top_all_time_player_kills_2_name"));
        assertEquals("9", placeholders.resolve(null, "top_all_time_player_kills_2_value"));
        assertEquals("N/A", placeholders.resolve(null, "top_all_time_player_kills_10_name"));
        assertEquals(1, reads.get());
    }

    @Test
    void seasonRolloverUsesTheNewIdAndEmptyStatsBecomeZero() {
        AtomicReference<Season> current = new AtomicReference<>(new Season(1, "First", Instant.EPOCH, null));
        StatsPlaceholders placeholders = new StatsPlaceholders(() -> CompletableFuture.completedFuture(current.get()),
                (player, scope) -> {
                    PlayerStats stats = scope.equals(StatsScope.season(1))
                            ? new PlayerStats(player, "Alice", 4, 0, 0, 0, 0, 0, 0) : PlayerStats.empty(player);
                    return CompletableFuture.completedFuture(stats);
                }, (stat, scope) -> CompletableFuture.completedFuture(List.of()), () -> 0, 30);
        String wins = "stat_season_wins";

        assertEquals("N/A", placeholders.resolve(ALICE, wins));
        assertEquals("First", placeholders.resolve(null, "season_name"));
        assertEquals("N/A", placeholders.resolve(ALICE, wins));
        assertEquals("4", placeholders.resolve(ALICE, wins));

        current.set(new Season(2, "Second", Instant.EPOCH.plusSeconds(1), null));
        placeholders.invalidateSeason();
        assertEquals("N/A", placeholders.resolve(ALICE, wins));
        assertEquals("Second", placeholders.resolve(null, "season_name"));
        assertEquals("N/A", placeholders.resolve(ALICE, wins));
        assertEquals("0", placeholders.resolve(ALICE, wins));
        assertEquals("N/A", placeholders.resolve(null, "top_season_wins_1_value"));
        assertEquals("N/A", placeholders.resolve(null, "top_season_wins_1_value"));
    }

    @Test
    void malformedParametersAndDisabledStatsHaveAReadableFallback() {
        StatsPlaceholders parser = new StatsPlaceholders(() -> { throw new AssertionError(); },
                (player, scope) -> { throw new AssertionError(); },
                (stat, scope) -> { throw new AssertionError(); }, () -> 0, 30);
        for (String key : List.of("", "season", "stat_week_wins", "stat_season_unknown",
                "top_all_time_wins_0_name", "top_all_time_wins_11_name", "top_season_wins_999999999999_name",
                "top_all_time_wins_1_uuid", "top_all_time_unknown_1_name", "top_season_wins_01_name")) {
            assertEquals("N/A", parser.resolve(ALICE, key), key);
        }
        StatsPlaceholders disabled = new StatsPlaceholders(null, 30);
        assertEquals("N/A", disabled.resolve(ALICE, "stat_all_time_wins"));
        assertEquals("N/A", disabled.resolve(null, "season_name"));
    }

    @Test
    void expiryUsesTheReloadedLifetimeAndBacksOffOnFailure() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        StatsPlaceholders placeholders = new StatsPlaceholders(() -> {
            int attempt = reads.incrementAndGet();
            return attempt == 2 ? CompletableFuture.failedFuture(new IllegalStateException("database down"))
                    : CompletableFuture.completedFuture(new Season(1, "First", Instant.EPOCH, null));
        }, (player, scope) -> { throw new AssertionError(); },
                (stat, scope) -> { throw new AssertionError(); }, clock::get, 30);

        assertEquals("N/A", placeholders.resolve(null, "season_name"));
        clock.set(TimeUnit.SECONDS.toNanos(5));
        assertEquals("First", placeholders.resolve(null, "season_name"));
        placeholders.setCacheSeconds(5);
        assertEquals("N/A", placeholders.resolve(null, "season_name"));
        assertEquals("N/A", placeholders.resolve(null, "season_name"));
        assertEquals(2, reads.get());
        clock.set(TimeUnit.SECONDS.toNanos(10));
        assertEquals("N/A", placeholders.resolve(null, "season_name"));
        assertEquals("First", placeholders.resolve(null, "season_name"));
        assertEquals(3, reads.get());
    }

    @Test
    void differentKeysShareOneInFlightReadAndAConnectionFailureCooldown() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger playerReads = new AtomicInteger();
        CompletableFuture<PlayerStats> failed = new CompletableFuture<>();
        StatsPlaceholders placeholders = new StatsPlaceholders(() -> { throw new AssertionError(); },
                (player, scope) -> {
                    if (playerReads.incrementAndGet() == 1) {
                        return failed;
                    }
                    return CompletableFuture.completedFuture(PlayerStats.empty(player));
                }, (stat, scope) -> { throw new AssertionError(); }, clock::get, 30);
        String wins = "stat_all_time_wins";

        assertEquals("N/A", placeholders.resolve(ALICE, wins));
        assertEquals("N/A", placeholders.resolve(BOB, wins));
        assertEquals("N/A", placeholders.resolve(null, "top_all_time_wins_1_name"));
        assertEquals("N/A", placeholders.resolve(null, "season_name"));
        failed.completeExceptionally(new IllegalStateException("database down"));
        assertEquals("N/A", placeholders.resolve(BOB, wins));
        assertEquals("N/A", placeholders.resolve(null, "season_name"));
        assertEquals(1, playerReads.get());

        clock.set(TimeUnit.SECONDS.toNanos(5));
        assertEquals("N/A", placeholders.resolve(BOB, wins));
        assertEquals("0", placeholders.resolve(BOB, wins));
        assertEquals(2, playerReads.get());
    }

    @Test
    void boundedCacheKeepsInFlightReadsAndDiscardsInvalidatedCompletions() {
        SnapshotCache<String, String> cache = new SnapshotCache<>(1, () -> 0, new SnapshotReads(() -> 0));
        CompletableFuture<String> pending = new CompletableFuture<>();
        assertNull(cache.get("old", 10, () -> pending));
        assertNull(cache.get("new", 10, () -> { throw new AssertionError(); }));
        cache.invalidate();
        assertNull(cache.get("old", 10, () -> { throw new AssertionError(); }));
        pending.complete("stale");
        assertNull(cache.get("old", 10, () -> CompletableFuture.completedFuture("fresh")));
        assertEquals("fresh", cache.get("old", 10, () -> { throw new AssertionError(); }));
        assertNull(cache.get("new", 10, () -> CompletableFuture.completedFuture("new")));
        assertEquals("new", cache.get("new", 10, () -> { throw new AssertionError(); }));
    }
}
