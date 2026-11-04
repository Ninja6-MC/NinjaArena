# Leaderboards and placeholders

Run `/ninjaarena leaderboard <stat> [all_time|season]` or use the `/narena` alias.
Omitting the period shows the all-time board. Use `season` for the current season.
For example, `/narena leaderboard player_kills season` shows the current season's
top ten for player kills. The command works from chat and the console.

The `ninjaarena.leaderboard` permission defaults to everyone and is also a child
of `ninjaarena.play`. Leaderboards include players with a positive score, even
when they are offline. Ties are ordered by player UUID and occupy consecutive
positions. The names shown are the last names recorded by Arena stats.

## Stats

Use these keys for both the command and placeholders.

| Stat key | Meaning |
| --- | --- |
| `wins` | Matches won |
| `rounds_won` | Rounds won |
| `player_kills` | Opponents killed |
| `enemy_kills` | Director enemies killed |
| `deaths` | Deaths in rounds |
| `best_overrun_seconds` | Longest single overrun survival time in seconds |
| `matches_played` | Matches started |

## PlaceholderAPI

Install PlaceholderAPI alongside NinjaArena to enable these placeholders.
Use any stat key above in place of `<stat>` and an integer from 1 through 10
in place of `<rank>`.

| Placeholder | Result |
| --- | --- |
| `%ninjaarena_stat_all_time_<stat>%` | The selected player's all-time stat |
| `%ninjaarena_stat_season_<stat>%` | The selected player's stat for the current season |
| `%ninjaarena_top_all_time_<stat>_<rank>_name%` | Player name at that all-time rank |
| `%ninjaarena_top_all_time_<stat>_<rank>_value%` | Score at that all-time rank |
| `%ninjaarena_top_season_<stat>_<rank>_name%` | Player name at that current-season rank |
| `%ninjaarena_top_season_<stat>_<rank>_value%` | Score at that current-season rank |
| `%ninjaarena_season_name%` | The current season's name |

Top ranks and the season name work without a viewer. Own stats require a
player context and support offline players when the calling plugin supplies
one. A player with no recorded stats shows `0` after the read succeeds. An
unfilled rank, missing player context, disabled stats, or unavailable data
shows `N/A`. A ranked row without a recorded name shows `Unknown player`.

Example lines for a hologram that supports PlaceholderAPI:

```text
Top wins - %ninjaarena_season_name%
1. %ninjaarena_top_season_wins_1_name% - %ninjaarena_top_season_wins_1_value%
```

Example lines for a per-player scoreboard that supports PlaceholderAPI:

```text
Season wins: %ninjaarena_stat_season_wins%
All-time player kills: %ninjaarena_stat_all_time_player_kills%
```

Use `placeholders.cache-seconds` in `config.yml` to set the snapshot lifetime.
It defaults to 30 seconds and accepts integers from 1 through 300. Apply
changes with `/narena admin reload`. Requests for missing or expired data
trigger asynchronous reads, one at a time across the expansion. A display
can show `N/A` until a read completes and picks up the result on a subsequent
request. A failed read pauses new placeholder reads for at least five seconds.
