package com.ninja6.ninjaarena.placeholder;

import com.ninja6.ninjaarena.stats.LeaderboardEntry;
import com.ninja6.ninjaarena.stats.Leaderboards;
import com.ninja6.ninjaarena.stats.PlayerStats;
import com.ninja6.ninjaarena.stats.Season;
import com.ninja6.ninjaarena.stats.Stat;
import com.ninja6.ninjaarena.stats.StatsScope;
import com.ninja6.ninjaarena.stats.StatsStore;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Resolves placeholders from bounded snapshots without waiting for the stats thread. */
public final class StatsPlaceholders {

    public static final String CACHE_SECONDS = "placeholders.cache-seconds";
    public static final int DEFAULT_SECONDS = 30;
    public static final int MIN_SECONDS = 1;
    public static final int MAX_SECONDS = 300;
    private static final String MISSING = "N/A";
    private static final Pattern OWN = Pattern.compile("stat_(all_time|season)_([a-z_]+)");
    private static final Pattern TOP = Pattern.compile("top_(all_time|season)_([a-z_]+)_(10|[1-9])_(name|value)");

    private record PlayerKey(UUID player, StatsScope scope) {
    }

    private record BoardKey(Stat stat, StatsScope scope) {
    }

    private final SnapshotCache<String, Season> seasons;
    private final SnapshotCache<PlayerKey, PlayerStats> players;
    private final SnapshotCache<BoardKey, List<LeaderboardEntry>> boards;
    private final Supplier<CompletableFuture<Season>> seasonRead;
    private final BiFunction<UUID, StatsScope, CompletableFuture<PlayerStats>> playerRead;
    private final BiFunction<Stat, StatsScope, CompletableFuture<List<LeaderboardEntry>>> topRead;
    private volatile long lifetime;

    public StatsPlaceholders(StatsStore store, int seconds) {
        this(store == null ? () -> missing() : () -> store.currentSeason().thenApply(season -> season.orElse(null)),
                store == null ? (player, scope) -> missing()
                        : (player, scope) -> store.stats(player, scope)
                                .thenApply(stats -> stats.orElseGet(() -> PlayerStats.empty(player))),
                store == null ? (stat, scope) -> missing()
                        : (stat, scope) -> store.top(stat, scope, Leaderboards.LIMIT).thenApply(List::copyOf),
                System::nanoTime, seconds);
    }

    StatsPlaceholders(Supplier<CompletableFuture<Season>> seasonRead,
            BiFunction<UUID, StatsScope, CompletableFuture<PlayerStats>> playerRead,
            BiFunction<Stat, StatsScope, CompletableFuture<List<LeaderboardEntry>>> topRead,
            LongSupplier clock, int seconds) {
        this.seasonRead = seasonRead;
        this.playerRead = playerRead;
        this.topRead = topRead;
        seasons = new SnapshotCache<>(1, clock);
        players = new SnapshotCache<>(512, clock);
        boards = new SnapshotCache<>(2 * Stat.values().length, clock);
        setCacheSeconds(seconds);
    }

    private static <T> CompletableFuture<T> missing() {
        return CompletableFuture.completedFuture(null);
    }

    public void setCacheSeconds(int seconds) {
        lifetime = TimeUnit.SECONDS.toNanos(seconds);
    }

    public void invalidateSeason() {
        seasons.invalidate();
    }

    public String resolve(UUID player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("season_name")) {
            return seasonal(Season::name);
        }
        var own = OWN.matcher(key);
        if (own.matches()) {
            Stat stat = Leaderboards.stat(own.group(2));
            return stat == null || player == null ? MISSING
                    : scoped(own.group(1), scope -> ownValue(player, scope, stat));
        }
        var top = TOP.matcher(key);
        if (top.matches()) {
            Stat stat = Leaderboards.stat(top.group(2));
            int rank = Integer.parseInt(top.group(3));
            return stat == null ? MISSING
                    : scoped(top.group(1), scope -> topValue(stat, scope, rank, top.group(4).equals("name")));
        }
        return MISSING;
    }

    private String ownValue(UUID player, StatsScope scope, Stat stat) {
        PlayerStats stats = players.get(new PlayerKey(player, scope), lifetime, () -> playerRead.apply(player, scope));
        return stats == null ? MISSING : Integer.toString(stats.value(stat));
    }

    private String topValue(Stat stat, StatsScope scope, int rank, boolean name) {
        List<LeaderboardEntry> entries = boards.get(new BoardKey(stat, scope), lifetime, () -> topRead.apply(stat, scope));
        if (entries == null || entries.size() < rank) {
            return MISSING;
        }
        LeaderboardEntry entry = entries.get(rank - 1);
        return name ? Leaderboards.name(entry) : Integer.toString(entry.value());
    }

    private String scoped(String period, Function<StatsScope, String> value) {
        return period.equals("all_time") ? value.apply(StatsScope.ALL_TIME)
                : seasonal(season -> value.apply(StatsScope.season(season.id())));
    }

    private String seasonal(Function<Season, String> value) {
        Season season = seasons.get("current", lifetime, seasonRead);
        return season == null ? MISSING : value.apply(season);
    }
}
