# Scheduled arena events

Schedule weekly arena nights in `config.yml`. The default schedule is empty.

```yaml
events:
  time-zone: Europe/Paris
  announcement-lead-minutes: [30, 10, 1]
  webhook-url: ''
  schedule:
    - id: friday-dojo
      day: FRIDAY
      time: '19:30'
      arena: dojo
      duration-minutes: 90
      bonus:
        vault-per-match: 2.50
        reward-id: champion
```

The announcements are `event.upcoming` and `event.start` in `messages.yml`, in
MiniMessage format with the `<id>`, `<arena>` and `<minutes>` placeholders:

```yaml
event:
  upcoming: "<gold>Arena event <id> starts in <minutes> minute(s) at <arena>."
  start: "<gold>Arena event <id> has started at <arena>."
```

The old `events.messages` keys in `config.yml` are deprecated. On the first start that
creates `messages.yml`, texts changed there are moved into it, with `{id}` written as
`<id>`, and the server log says so. After that they are no longer read.

Use a unique lowercase ID (letters, digits, underscores or hyphens), a day of the
week, a quoted 24-hour time, and an existing arena name. The section uses one time
zone, UTC by default. A time in a daylight-saving gap moves forward by the gap.
A repeated local time starts once at the earlier offset. Duration is elapsed
time, from 1 to 1440 minutes. If windows overlap, automatic matchmaking uses the
earliest active start, with ties broken by event ID. A manual start takes
precedence for its duration.

Lead times are distinct whole minutes from 1 to 1440. Use `[]` for start
announcements only. The server announces thresholds crossed while it is running.
After a delay that crosses multiple thresholds for one event, it sends the latest
applicable notice. On restart the current window is derived from the clock.

## Admission and rewards

During the window, unbound player-built portals in dedicated mode use the featured
arena's waiting room, and shared-hub matchmaking selects only the featured arena.
If that arena is busy, reserved or unavailable, the hub waits and unbound
dedicated portals refuse entry. Explicit dedicated portals and hosted invitations
can still use other arenas. Existing matches and dedicated queues continue.
Automatic matchmaking resumes its usual arena selection when no event is active.

The optional bonus is captured when a match starts in the featured arena. Players
still in a completed or forfeited match qualify, including when the event closes
before the match finishes. The extra Vault amount is combined with the regular
payout, under the same permission, Vault enablement and UTC daily cap.
A `reward-id` references one existing milestone or placing reward with a title
or trophy. The event grants that same reward without its threshold or placing
requirement. Milestone IDs are granted once per player, and placing IDs once per
player in the season current at match end. The normal award and trophy delivery
flow handles them. See [cosmetic rewards and Vault payouts](player-stats.md#cosmetic-rewards-and-vault-payouts).

## Discord

Leave `webhook-url` empty to disable delivery. Set it to an HTTPS Discord
webhook URL to post the same announcement text to Discord, as plain text without
its formatting. Requests run
asynchronously, time out after three seconds and send no mention notifications.
Delivery failures log one warning per attempt. Keep the endpoint private.

## Commands

The `ninjaarena.event.manage` permission grants all four commands and their tab
completions. Operators and holders of `ninjaarena.admin` inherit it.

| Command | Behavior |
| --- | --- |
| `/narena event list` | Show the configured weekly schedule. |
| `/narena event next` | Show the active event and the next scheduled occurrence. |
| `/narena event start <id>` | Start an extra occurrence immediately if no event is active. |
| `/narena event cancel <id>` | End the matching manual occurrence, or suppress the current or next scheduled occurrence of that ID. |

Manual starts and cancellations are kept in memory. Restarting restores the weekly
schedule, and changing a schedule entry clears overrides for that entry. Run
`/narena admin reload` to apply edits. An invalid event rejects the entire reload
and keeps the last accepted settings.
