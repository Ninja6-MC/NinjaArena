# Player stats and seasons

NinjaArena records, for every player, all-time and per season:

| Stat | Counts |
| :--- | :--- |
| `WINS` | Matches won, a shared win included. Only players of a winning team still in the match when it ends. |
| `ROUNDS_WON` | Rounds won by the player's team while they were in the match. |
| `PLAYER_KILLS` | Opposing players killed in the same match. Teamkills, suicides and environmental deaths do not count. |
| `ENEMY_KILLS` | Director enemies killed while alive in COMBAT or OVERRUN, as for enemy kill points. |
| `DEATHS` | Deaths in a round. Leaving does not count. |
| `BEST_OVERRUN_SECONDS` | The longest single OVERRUN the player survived: from its start until they died in it or the round ended with them alive. Kept as the best value, not a total. |
| `MATCHES_PLAYED` | Matches the player started in, finished or not. |

Counts are kept in memory during a match and written once, when it ends, on a
background thread. A player who leaves keeps the kills and deaths they earned. An
abandoned match, or one stopped by a shutdown, is recorded without winners. Matches run
the same whether stats are on, off or the database is down.

## Configuration

The `stats` section of `config.yml`:

| Key | Default | Meaning |
| :--- | :--- | :--- |
| `stats.enabled` | `true` | Record stats at all. |
| `stats.storage` | `sqlite` | `sqlite` for a file in the plugin folder, `mysql` for a MySQL or MariaDB database several Arena servers share. |
| `stats.season` | `Season 1` | The current season's name, 1 to 64 characters. |
| `stats.table-prefix` | `ninjaarena_` | Prepended to every table name; up to 24 lower-case letters, digits or underscores. |
| `stats.sqlite.file` | `stats.db` | The SQLite file, a plain file name inside the plugin folder. |
| `stats.mysql.host` | `localhost` | Host name or address. |
| `stats.mysql.port` | `3306` | 1 to 65535. |
| `stats.mysql.database` | `ninjaarena` | The database, which must already exist. |
| `stats.mysql.username` | `ninjaarena` | The account; it needs `CREATE`, `SELECT`, `INSERT` and `UPDATE`. |
| `stats.mysql.password` | `''` | Never logged or shown in a reload reply. |

Text values must be YAML strings; quote a season name that looks like a number. An
invalid value at startup turns stats off with a log line naming the setting; the rest of
the plugin enables normally. `/narena admin reload` validates the section with the rest
of `config.yml`. A new `stats.season` applies at once; every other `stats` key is
refused until a restart.

Both JDBC drivers come with Paper 26.3 (`org.xerial:sqlite-jdbc` and
`com.mysql:mysql-connector-j`); the plugin ships neither. A server without the driver
for the chosen storage logs that stats are off and records nothing.

## Seasons

Exactly one season is current. Changing `stats.season`, by restart or reload, archives
the current season (its `ended_at` is set) and starts the new one. Nothing is deleted:
an archived season's stats stay readable by its id. A name already used by an archived
season is refused with a warning and the current season stays, so a server with a stale
name cannot reopen an old season.

With MySQL, every server shares one season list: the current season is read from the
database on each write, so a season started on one server applies to all of them. Give
every server the same `stats.season`; a server whose name is newer starts it for all.

## Writes and failures

Finished matches are queued and written together, one transaction per batch of up to
500 matches. Each write adds to the stored values in the statement itself, so servers
sharing a database never overwrite each other. Totals stop at 2147483647.

If the database cannot be reached, matches stay queued (at most 10000; the oldest are
dropped beyond that) and the write is retried after 5 seconds, doubling to at most 5
minutes. A match the database refuses as invalid data is dropped alone, with its end
time logged, so it cannot block the queue. On shutdown, after running matches are
stopped and recorded, the queue is flushed for up to 10 seconds; anything still
unwritten is reported in the log.

A database whose schema version is newer than the plugin understands is never written
to: stats turn off with a log line asking for an update.

## Schema

Version 1. `<p>` is `stats.table-prefix`.

```sql
<p>schema_version (id INT PRIMARY KEY, version INT NOT NULL)          -- one row, id 1
<p>seasons        (id INT AUTO PRIMARY KEY, name VARCHAR(64) UNIQUE NOT NULL,
                   started_at BIGINT NOT NULL, ended_at BIGINT NULL)  -- epoch millis
<p>player_stats   (player CHAR(36), season INT,
                   wins, rounds_won, player_kills, enemy_kills, deaths,
                   best_overrun_seconds, matches_played               -- INT NOT NULL DEFAULT 0
                   PRIMARY KEY (player, season))                      -- season 0 = all-time
<p>players        (player CHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL,
                   last_seen BIGINT NOT NULL)                         -- latest known name
```

`player_stats` has one index on `(season, <stat>)` per stat for top lists. The exact
DDL for each database is in `Dialect`. Migrations are numbered: on connect the plugin
reads `schema_version`, applies each later version's statements in order and records the
new version. Each version's statements are idempotent, because MySQL commits DDL on its
own and two servers may start together. A schema change adds a version; an existing
version is never edited.

## Read API

`plugin.stats()` returns the `StatsStore`, or `null` while the plugin is disabled or
stats are off. Every method returns at once and is safe from any thread:

| Method | Returns |
| :--- | :--- |
| `stats(uuid, scope)` | `Optional<PlayerStats>`: every stat and the last known name. |
| `top(stat, scope, limit)` | Up to `limit` (1 to 1000) `LeaderboardEntry` rows, best first; zeros left out, ties by UUID. |
| `currentSeason()`, `seasons()` | The current `Season`; every season, oldest first. |
| `startSeason(name)` | Archives the current season and starts `name`. |
| `useSeason(name)` | As a `stats.season` change: refuses an archived name. |
| `flush()` | Completes once everything queued is written. |
| `isAvailable()`, `pending()` | Whether the last database use succeeded; matches waiting. |

`scope` is `StatsScope.ALL_TIME`, `StatsScope.CURRENT_SEASON` or
`StatsScope.season(id)`. Results are `CompletableFuture`s completed on the stats thread,
after every match recorded before the call; return to the server thread before using
them in the game. They fail with `StatsUnavailableException` while the database is down.
Placeholders and scoreboards should cache results instead of reading per request.

## Cosmetic rewards and Vault payouts

Rewards use the configured stats database. Add rules to `config.yml` and run
`/narena reload` to apply them. An invalid rule or payout setting rejects the reload.

```yaml
rewards:
  milestones:
    - id: veteran
      stat: wins
      threshold: 25
      title: 'Arena Veteran'
      tag: '[Veteran]'
  placings:
    - id: wins-champion
      stat: wins
      rank: 1
      title: 'Season Champion'
      tag: '[Champion]'
      trophy: 'Arena Champion Trophy'
  vault:
    enabled: false
    amount-per-match: 10.0
    daily-cap: 100.0
```

- `milestones` use all-time totals. The threshold is a positive integer. They are
  evaluated after matches and for online players, including when they join.
- `placings` use the exact rank (1–1000) on each archived season's leaderboard.
  Players with zero for the stat are excluded and ties use the leaderboard's UUID
  order. Rules also apply to previously archived seasons. Use the same rules on all
  servers sharing the database.
- Use the stat names listed above. Each list supports up to 100 rules. IDs must
  be unique within their list and contain 1–48 lowercase letters, digits, hyphens
  or underscores. Supply at least one reward label of 1–80 characters. Only placings
  support `trophy`. Keep rule IDs stable because the first grant is permanent.
- In a PlaceholderAPI-enabled chat format, use `%ninjaarena_title%` and
  `%ninjaarena_chat_tag%`, for example
  `%ninjaarena_chat_tag% %player_name%: {message}` if the chat plugin uses
  `{message}`. Each placeholder chooses its first non-empty label from the newest
  awarded season, then by descending rule ID, with milestones last. An empty
  result means there is no cached label. Refreshes use `placeholders.cache-seconds`.
- Trophies are display items. Delivery waits for the recipient to be online with an
  empty inventory slot, outside the arena world and clear of pending snapshot
  restoration. The server checks on join and every 30 seconds. A process crash
  between recording the delivery claim and saving the player's inventory can lose
  that trophy.
- Vault payouts default to disabled. When enabled, online players still in a
  completed or forfeited match receive up to `amount-per-match` if they have
  `ninjaarena.rewards.payout`. The last payout can be reduced to fit `daily-cap`.
  Amounts support up to two decimal places and the cap resets at midnight UTC.
  The balance and match claims are shared by servers using the same stats database.
  An unavailable database or economy provider skips the payout. An attempt
  reserved in the database counts toward the cap even if the provider fails,
  because Vault has no way to identify a retried deposit.

Stats schema version 2 adds award, season placing and payout tables through the
existing migration. Upgrade every server that shares the database.
