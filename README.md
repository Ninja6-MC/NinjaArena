<p align="center">
  <img src="docs/assets/icon-256.png" width="128" height="128" alt="NinjaArena">
</p>

# NinjaArena

Team vs Team vs Enemies arena plugin for PaperMC: walk through a portal, outplay rival
teams and the horde, and return to where you left.

NinjaArena targets Paper 26.3 and does not run on Folia.

## Building

Requires JDK 25, which paper-api 26.3 is built for. Gradle finds a matching local JDK
through its toolchain support; the wrapper downloads Gradle itself.

```sh
./gradlew build
```

On Windows, use `gradlew.bat build`. The plugin JAR is written to `build/libs/`, and
`./gradlew test` runs the unit tests on their own.

## Verification

A local Paper 26.3 build 226 run passed 600 measured seconds after 60 seconds of
warmup across four concurrent matches. Separate small and large fixtures verified
startup and match restoration, idle retirement, collection and reconnect behavior.
See the [stability and lifecycle report](docs/stability-soak.md) for measurements,
save-padding evidence and remaining containment and continuous-residency limits.

## Setting up an arena

Admins need `ninjaarena.admin`. `/narena` on its own lists the commands allowed by your
permissions, including the `arena`, `portal` and `director` setup groups. A first arena,
in the order you need it:

1. `/narena arena create dojo` registers the arena in the next free cell of the arena
   world and puts it in edit mode. The reply shows the cell's coordinates.
2. Switch to creative, then `/narena arena tp dojo` takes you to the middle of the cell.
   Build the arena inside the cell.
3. `/narena arena setbounds dojo <x1> <y1> <z1> <x2> <y2> <z2>` marks the playable box.
   It must fit inside the cell, and everything below must be inside it. `~` and `~n` are
   relative to where you stand.
4. Stand at each team base and run `/narena arena setteamspawn dojo 1`, then `2`, up to
   four teams. Two are required.
5. Stand where players wait before the match and run `/narena arena setwaitingroom dojo`.
6. Stand where enemies should appear and run `/narena arena addenemyspawn dojo`. At least
   one is required; enemies spawn there during rounds.
7. `/narena arena edit dojo off` saves the arena and leaves edit mode. If anything is
   still missing it is listed; `/narena arena edit dojo on` lets you fix it.
   `/narena arena info dojo` shows the current setup and problems at any time.
8. Back in the main world, build a doorway frame for the portal. Stand on the floor
   inside it and run `/narena portal setcorner 1`, then `/narena portal setcorner 2 ~ ~2 ~`
   so the portal is a doorway rather than a flat pad. Move sideways first for a wider
   door. `/narena portal create dojo-door dojo` binds it to the arena.

## Lobbies and player caps

Before a match, players wait either in a dedicated waiting room or in one shared hub. The
admin chooses per portal; a portal that does not choose, including player-built portals,
uses `waiting-room.lobby` in `config.yml` (`dedicated` by default).

- **Dedicated:** each arena has its own waiting room. Arriving players are put on the
  smallest team, and the countdown starts once `min-players` are there. When the room
  already holds as many players as one match takes, the portal turns more away with a
  message before their snapshot is saved.
- **Shared:** every shared portal leads to the hub. Once `min-players` are there, or
  enough to fill a free arena that holds fewer, the countdown runs; when it ends, the
  players who arrived first go together to a free arena's teams, up to that arena's cap,
  and the match starts. The rest stay in the hub for the next free arena. A free arena is
  ready, has no match, no open waiting room and no host reservation; of those the players
  waiting can start, the hub picks the smallest that takes everyone waiting, or the
  largest if none does. While arenas are in edit mode, or every arena or match slot is in
  use, the countdown holds at its last second and the hub keeps waiting; if the only free
  arena it could fill is taken, the countdown stops.

Stand where players should gather in the arena world, outside every arena's bounds, and
run `/narena portal sethub`, or give coordinates as for `setwaitingroom`. Later
`arena setbounds` that would enclose the hub is refused; move the hub first.
`/narena portal lobby <portal> <dedicated|shared|default>` sets a portal's choice;
`default` returns it to the config value. `portal create <name> <arena> shared` sets it at
creation. `portal list` and `portal info` show the hub and where each portal leads.
Moving or removing the hub sends anyone waiting there home.

The hub follows the waiting-room rules: an empty inventory and adventure mode on arrival,
the same inventory lockdown, no damage, the same `radius`, and leaving by walking out,
teleporting away, dying, `/narena leave` or disconnecting restores the snapshot as
leaving a waiting room does. Hub players can use `/narena spectate <arena>`. Hosted
matches always use the host's dedicated room.

`waiting-room.max-players` caps the players in one match and `waiting-room.team-size` the
players on one team; 0 turns either off. Both apply in both modes, and an arena takes
the lower of the two (team size times its team count). An arena that holds fewer than
`min-players` starts once it is full, from its waiting room or from the hub. Spectators are outside the teams and never count
toward either cap.

## Player-built portals

Players with `ninjaarena.play` can build their own way into the Arena, as they would a
nether portal, but from crying obsidian:

1. Build an upright frame with an empty opening 2 to 21 blocks wide and 3 to 21 tall,
   along either axis. The four corners are optional, so the smallest frame takes 10
   crying obsidian, or 14 with corners.
2. Use flint and steel on the inside of the frame. Instead of a fire, a curtain of
   coloured particles fills the opening, and the player is told the portal is open.
3. Walking through it takes the player in with the same checks, snapshot and empty
   inventory as an admin portal.

A player-built portal belongs to no arena and always follows `waiting-room.lobby`: under
`shared` it leads to the hub; under `dedicated` it leads to the waiting room with the most
players that still has room, else to a free arena, and otherwise turns the player away
with "All arenas are busy". Arenas a host has reserved are skipped. Vanilla does nothing
with crying obsidian, so these portals never clash with nether portals; obsidian cannot be
chosen as the frame block.

Breaking, burning, blowing up or pushing any block of the frame removes the portal; the
corners, the opening and blocks around the frame do not count, so ordinary block updates
nearby leave it alone. The curtain is drawn only while a player is within two chunks,
for at most 16 portals at a time, and while a player is near, the frame is checked
every five seconds, which also catches changes no event reports, such as a frame edited
while the server was down. A frame block in an unloaded chunk is never judged missing.

Portals are saved in `portals.yml` with the admin ones, named `built-1`, `built-2` and so
on, so they survive restarts and appear in `/narena portal list` and `info` with their
builder. An admin removes one with `/narena portal delete <name>`; the frame stays. A
player-built portal cannot choose its own lobby. Versions from before player-built portals
refuse a `portals.yml` that holds one rather than drop it.

```yaml
player-portals:
  worlds:                     # where players may build; [] turns building off
    - world
  frame-material: crying_obsidian
  max-per-player: 2           # 0 for no limit
  min-distance: 32            # blocks between portal centres in a world; 0 turns it off
  color: '#40E0D0'            # the particle curtain
```

The arena world is never allowed. Each portal keeps the frame block it was built from, so
changing `frame-material` affects only new portals. The curtain is a vanilla dust
particle, which needs no resource pack on Java or Bedrock.

## Commands and permissions

`/ninjaarena` is the registered root; `/narena` is its permanent short alias. Run either
root on its own, or add `help`, to list the commands your permissions allow. Replies use
plain text, with no clickable chat. Existing `arena`, `portal` and `director` paths stay
under the root.

| Command | Permission | Behavior |
| :--- | :--- | :--- |
| `help` | Any tier below | List permitted commands |
| `leave` | `ninjaarena.play` or `ninjaarena.spectate` | Return home and restore your snapshot |
| `leaderboard <stat> [all_time\|season]` | `ninjaarena.leaderboard` | Show the [top ten for a stat](docs/leaderboards.md) |
| `spectate <arena>` | `ninjaarena.spectate` | Watch a live match from a waiting room |
| `accept <host>` | `ninjaarena.play` | Accept an invitation and enter the waiting room |
| `host <arena>` | `ninjaarena.host` | Open a free arena for a hosted match without moving |
| `invite <player>` | `ninjaarena.host` | Invite an online player with a plain accept command |
| `arena <subcommand>` | `ninjaarena.admin` | Register, build and inspect arenas |
| `portal <subcommand>` | `ninjaarena.admin` | Select and manage entrance portals, their lobby and the shared hub |
| `director [arena]` | `ninjaarena.admin` | Inspect a running match's enemy director |
| `admin reload` | `ninjaarena.admin` | Validate and safely reload configuration and kits |

Enter the Arena by walking through a portal or explicitly accepting an invitation.
Leaderboard and admin reload also work from the console.

`ninjaarena.play`, `ninjaarena.spectate` and `ninjaarena.leaderboard` default to everyone.
`ninjaarena.host` and `ninjaarena.admin` default to operators. Play grants spectate and
leaderboard. Host grants play, and admin grants host. The root accepts any of these permissions.
Dispatch, help and tab completion check the permissions for each route.

## Hosting and invitations

With `ninjaarena.host`, run `/narena host dojo` to open a ready arena with no match,
waiting players or other host. Opening reserves it for one host and does not move anyone.
Walk through its portal to join, and use `/narena invite Bob` to invite an online player.
Inviting only sends a plain message; Bob enters by typing `/narena accept <host-name>`.
Invitations may be sent and accepted before the host walks in. The ordinary waiting-room
countdown starts the match once enough players have arrived; portal entrants can still join.

Accepting loads the room's chunk, checks admission again, saves the player's snapshot and
teleports them through the same entry flow as a portal. Pending snapshots, loose cursor or
crafting-grid items, death and spectator mode prevent entry. A failed load, snapshot,
teleport or room preparation leaves the invitation open for a retry. `/narena leave`
cancels an entry that is still loading. A player already waiting in that arena can accept
without another snapshot or teleport; leave a different room or match before accepting.

Waiting-room teams stay within one player of each other. The host and accepted invitees
stay together where one balanced team has room; otherwise the host and earliest accepted
players take that team's capacity. Later arrivals reunite the party when room permits,
and departures move unrelated players before splitting those kept together.

A host's `/narena leave`, departure from the waiting room, disconnect, room closure,
match start, arena edit or deletion, and plugin shutdown cancel the reservation and its
unaccepted invitations. Losing the host permission also invalidates it at the next hosting
operation or pending entry check. Players already in the waiting room remain there and
can leave through the normal snapshot restore.

## Watching live matches

From any waiting room, run `/narena spectate <arena>` to watch an in-progress match.
A successful transfer removes you from the queue and keeps your pre-entry snapshot.
To offer access from another world, mark portal corners as usual and run
`/narena portal create <name> <arena> spectator`. The portal admits viewers only while
the named match is open to spectators. Ordinary portals still lead to the waiting room.

Viewers can fly within the arena's three-dimensional bounds. Entity cameras, arena
interactions, and teleports outside the bounds are blocked. Viewers are outside the
team rosters, countdown minimum, scoring, and enemy director. Use `/narena leave` to
return home, or wait for the match to end. Disconnecting frees the spectator slot
immediately and restores your snapshot on the next join.

Set `spectating.enabled` to control admission and `spectating.max-per-match` to set the
number of viewers per match (1 to 64, default 16). Both options apply to new admissions
on a successful reload. Current viewers can stay until they leave or the match ends.

## Safe configuration reload

Run `/narena admin reload` with `ninjaarena.admin` after editing `config.yml` or `kits.yml`.
Both files are read and validated before anything changes. A missing file, malformed YAML,
duplicate or unknown key, wrong type, out-of-range or non-finite number, invalid kit entry,
unregistered item ID or excessive stack amount rejects the whole reload. The reply names
the file, setting and source line; the previous settings and kit remain active. Omitted
config settings use the bundled defaults. Reload never reads arenas, portals or snapshots,
and never moves match players or restarts a match.

| Settings | Reload behavior |
| :--- | :--- |
| Waiting-room minimum, countdown and radius | Apply immediately to waiting rooms and the hub; a viable countdown keeps its remaining length. A new minimum can cancel or start a countdown, and players outside a reduced radius leave through normal restore. |
| Default lobby and player caps | Apply immediately. The lobby applies to the next portal entry; players already waiting stay where they are. A lower cap turns away later arrivals but never removes waiting players. |
| Match timers, rounds, extra rounds, friendly fire and scoring | Apply to the next match. Running matches retain all their values, even if no damage or point award has occurred yet. |
| Spectator admission | Enable or disable new admissions and change the cap immediately. Existing viewers may finish watching. |
| Player-built portals | Apply to the next portal lit; the curtain colour changes immediately. Portals already built stay, with their own frame block. |
| Tool values | New uses take the new values immediately. Existing clouds retain their radius, effects and duration; existing marks and dash cooldowns retain their deadlines. |
| Kits | Apply from the next round. Delayed respawns receive the definition selected for their current round. |
| Arena-world view and simulation distance | Apply immediately without resetting world rules, time or saving. |
| Grid cell size and world border | Apply only before any arena exists and while no match runs. Otherwise refuse the change and require a restart. |
| Placeholder cache | `placeholders.cache-seconds` applies to cached stats immediately. |
| Stats season | A new `stats.season` archives the current season and starts it. Other stats settings require a restart. |

The reply separates changed settings, postponed changes (next match or round), and refused
changes. Refused settings remain unchanged in memory; the files are not rewritten. Director
and stability settings are validated but remain restart-only. An invalid value is rejected
even in a setting whose valid change would require a restart.

Kit reload validation does not call player-dependent item factories. The built-in tool
providers declare stack limits of 16 for shuriken and smoke bombs and 1 for dash. Custom
providers can declare a safe limit with `kitItems.register(id, maxStackSize, provider)`;
the existing two-argument registration conservatively permits one item during reload
validation. Item providers still resolve at round-kit application time.

## Icon artwork

The geometry master is `docs/assets/icon-master.svg`. All other SVG and PNG icons
are exported from it; edit the master and run `node scripts/export-icons.mjs`.
The exporter requires Node.js 22 or newer and installs its pinned rasterizer under
the ignored `build/icon-tools/` directory on first use.

Run `node --test scripts/export-icons.test.mjs` for the geometry and export tests,
and `node scripts/export-icons.mjs --check` to verify the committed exports.
The small cut covers 16–64 px, including a 20 px preview. Full-size, rounded,
transparent light and dark, and monochrome variants live beside the master.

## Building an arena

Arenas are built in the void world `ninjaarena_arenas`. `/narena arena create <name>` puts
the new arena in edit mode in an empty grid cell; `/narena arena tp <name>` takes you there
and reports the cell and its bounds. Switch to creative or turn on flight before building:
until a waiting room is set, `tp` lands you over the cell centre with nothing below, and
turns flight on for survival and adventure players once the teleport is done (after
plugins such as EssentialsX have reset flight for the world change). The arena world
keeps inventories on death, so a fall while building loses no items; match deaths are
handled by the match as described below.

When fully idle, the arena world unloads without saving. Its next use reopens it and
restores the saved arena copies before entry; active edits, players, matches, tickets
and pending teleports keep it loaded. Its hard diameter limit is 2048 blocks, and
visits and chunk-producing operations enforce that extent even while continuously
busy. Gameplay freezes redstone and piston physics; editing restores their normal
behavior. See [arena world retirement](docs/arena-world-lifecycle.md)
for save behavior, failure handling and the remaining continuous-use limitation.

## Round kits and inventory

Players arrive in the waiting room with an empty inventory. Once their snapshot is saved
and they are in the room, their storage, armour, offhand, cursor and crafting grid are
cleared, their potion effects and fire are removed, and they are put in adventure mode.
The waiting room is locked like a match: no item drops or pickups, no containers or ender
chests (including ones opened by a command), and no armour stands. A player whose
inventory cannot be emptied is sent home through the restore instead. Leaving returns
exactly what they came in with, game mode and effects included.

`plugins/NinjaArena/kits.yml` defines one shared kit. Each round clears storage, armour,
offhand, cursor and the personal crafting grid before applying the kit fresh. A player
still on the death screen receives it after being placed on respawn; eliminated players
who respawn to spectate receive no new kit. Leaving or match close uses the existing
snapshot restore to return the player's own inventory.

The bundled template holds the ninja kit tools (see below). On a fresh install, the plugin
writes that template before loading it. If creation fails or the file is still missing, a
clear message explains the empty-kit fallback. Malformed files also use an empty kit and
remain available for repair. Add weapons and armour before enabling the plugin:

```yaml
format: 1
items:
  - slot: 0
    material: WOODEN_SWORD
  - slot: 1
    id: ninjaarena:shuriken
    amount: 8
  - slot: 2
    id: ninjaarena:smoke-bomb
    amount: 2
  - slot: 3
    id: ninjaarena:dash
```

Slots 0-8 are the hotbar, 9-35 storage, 36 boots, 37 leggings, 38 chestplate, 39 helmet and
40 offhand. Each slot may appear once. Entries use exactly one of `material` or `id`;
amount defaults to 1, must be 1-99, and cannot exceed the resolved item's stack limit.
Unknown keys and malformed entries reject the whole definition. Known materials that
cannot be items (such as WATER, FIRE or NETHER_PORTAL) and air entries are skipped at load
with a warning naming the file and entry, while valid entries remain. An unresolved item ID,
an empty provider result or a provider failure logs the cause and applies an empty kit
instead of a partial one.

Custom tools can register a named provider on the server thread before the first round:

```java
plugin.kitItems().register("ninjaarena:smoke-bomb", player -> createSmokeBomb(player));
```

Then a kit entry can use `id: ninjaarena:smoke-bomb` instead of `material`. IDs are
namespaced and lowercase. Providers are resolved when applying a kit, and each returned
stack is cloned before its amount is set, preserving custom item data. The provider seam
does not implement the tool itself.

Players in a match cannot drop items, pick up items or arrows, open external inventories
(including ender chests), or transfer kit items to container blocks or entities. They can
arrange their personal inventory. Lockdown uses match membership, so admins building in
edit mode and other nonparticipants retain normal inventory access.
Every match exit clears kit storage, armour, offhand, cursor and crafting contents before
the snapshot restore, including deferred teleport exits, match close and plugin disable.
Quitting clears the kit before player data is saved. A failed restore therefore leaves an
empty inventory rather than allowing arena items to be stashed before the snapshot retry;
the snapshot still holds the player's real inventory.

## Ninja kit tools

Three tools ship with the plugin, each a vanilla item recognised by a data tag, so
Bedrock players see the base item. The kit decides who gets them: `amount` is how many
each player receives per round, and leaving an entry out turns that tool off for the kit.

| Kit ID | Item | Default kit | Effect |
| :--- | :--- | :--- | :--- |
| `ninjaarena:smoke-bomb` | Snowball | 2 | Thrown. Where it lands, a 3-block cloud lasts 4 seconds and blinds rival players inside it. |
| `ninjaarena:shuriken` | Snowball | 8 | Thrown. A hit deals 2 damage and makes a rival glow for 3 seconds. |
| `ninjaarena:dash` | Feather | 1 | Right-click for a short forward burst. 8-second cooldown, shown on the item. Not consumed. |

Tools work only for players alive in the round during COMBAT and OVERRUN. During PREP,
between rounds, or once eliminated, a throw or dash is cancelled and the item kept. Smoke
never blinds the thrower, teammates, eliminated players or anyone outside the match,
whatever `match.friendly-fire` says. Shuriken damage goes through the normal damage
rules, including friendly fire. A dash replaces the player's velocity instead of adding
to it, is shortened so it cannot carry them past the arena bounds, and is refused while
gliding, flying, riptiding or riding. Flying tools, clouds, cooldowns and glow marks are
cleared at the end of each round and match, when a player leaves or is eliminated, and on
plugin disable. Durations, radius, damage, cooldown and dash speed are under `tools` in
`config.yml`.

`plugin.tools().isMarked(uuid)` reports whether a player is glowing from a shuriken hit,
and `plugin.tools().inSmoke(location)` whether a place is inside a smoke cloud, for enemy
targeting.

### Stealth and enemy aggro

Enemies pick their targets by weight, and stealth and the tools steer that weight:

- A player who crouches where no enemy can see them, or crouches in low light (level 7 or
  below), counts as hidden and weighs a quarter as much.
- A player marked by a shuriken weighs twice as much while the glow lasts. The hidden cut,
  the mark and the leading-team bias multiply together.
- An enemy that has not seen the player it chases for 3 seconds loses track of them and
  goes for another player.
- An enemy inside a smoke cloud drops its target and takes no other while inside.

Each enemy type has an `ignores-stealth` setting that skips the hidden cut and the
lose-track timer; smoke and marks still apply. It is off by default for every type except
the Shadow Stalker. Every weight and timing is under `director.stealth` in `config.yml`.

### Shadow Stalker

The Shadow Stalker is the signature enemy and the one stealth does not fool. It is a
vanilla wither skeleton changed only through attributes (size, health, damage, speed),
equipment (a netherite sword that adds no damage) and particles, so Bedrock players
through Geyser see it as Java players do, without a resource pack.

- **Hunts the leader.** While one team has the most points, a Stalker targets only that
  team's players, and switches when the lead changes hands. With level teams, or nobody
  left on the leading team, it goes for anyone. Its vanilla target goals are removed, so
  only the director picks whom it hunts.
- **Blinks.** When its target is out of reach (8 blocks or more away, or nearer with no
  walking path) but within 24 blocks, it stands still for a second with a purple particle
  tell, then lands 2 blocks behind the target, facing their back, in a puff of smoke. The
  landing must be inside the arena, with room for its body and floor beneath it; it tries
  wider angles round the target, closer spots and a block up or down before giving up for a
  second. Blinks are 8 seconds apart, and a new Stalker waits that long before its first.
- **Marks.** The target of a blink glows for 3 seconds.
- **Counterplay.** Smoke still blinds it and shuriken marks still draw it. Its hits do not
  wither. Killing one scores its threat cost, 8 points by default.

The director spends its cost (`director.enemy-costs.shadow-stalker`, default 8) from the
threat budget like any other enemy, counts it towards the mob caps and the tick-time
guard, places it at the enemy spawn points with room for its larger body, and removes it
with the other enemies at match end. At most one is alive per match by default.

All settings are under `director.shadow-stalker` and, like every `director` setting, need
a restart:

| Key | Default | Range | Meaning |
| :--- | :--- | :--- | :--- |
| `max-alive` | 1 | 0 to 5 | Stalkers alive at once in one match; 0 turns it off |
| `health` | 40.0 | 1 to 1024 | Health in half hearts |
| `damage` | 6.0 | 0 to 100 | Melee damage per hit in half hearts, before armour |
| `speed` | 0.3 | 0.1 to 0.5 | Movement speed attribute (vanilla wither skeleton: 0.25) |
| `scale` | 1.1 | 0.5 to 2 | Size multiplier |
| `blink.distance` | 8.0 | 2 to 32 | Distance at which a target is out of reach |
| `blink.range` | 24.0 | 4 to 64 | Farthest blink; never below `blink.distance` |
| `blink.tell-ticks` | 20 | 5 to 100 | Length of the particle tell, in ticks (20 a second) |
| `blink.behind` | 2.0 | 1 to 6 | How far behind the target it lands |
| `blink.cooldown-seconds` | 8 | 1 to 120 | Time between blinks |
| `glow-seconds` | 3 | 1 to 60 | How long the target glows after a blink |

### Enemy spawns

Enemies appear only at the arena's enemy spawn points. A point is used when its chunk is
loaded, the enemy has room there, it is more than 8 blocks from every player still alive
in the round, and it is hidden from each of them: out of their line of sight, or more than
100 degrees away from where they look (`director.spawn.view-angle`; 180 makes cover the
only way to hide a point). Spectators, eliminated players and anyone outside the match do
not count. When no point qualifies nothing spawns that second; no other place is used and
no chunk is loaded. One point is enough as long as players do not all face it,
and more points spread the enemies out.

`/narena director [arena]` shows an admin the director of the match they are in, or of the
match running in the named arena: the round budget, the intensity, the enemies in play with
their threat, and the latest spawn that was refused or cancelled, with the reason for each
point. The same reasons are logged at most once per round each, at FINE by default; set
`director.debug: true` to log them at INFO.

## Deaths and spectating

Players in a match drop no items or XP on death and keep their inventory and levels.
After respawning, eliminated players spectate living teammates until the next round.
Spectator cameras can target only their own team; movement and teleports stay inside the
arena and within 16 blocks of a living teammate. If nobody on the team remains alive and
inside the arena, spectators wait near their own team base.
Spectators can detach the camera to move within that limit or choose another teammate;
the tether reattaches only after an invalid target or movement outside the allowed area.

The next round returns spectators to Adventure mode at their team base. A player still
on the death screen at round start is placed and readied when they respawn. Use
`/narena leave` to leave while spectating: the existing restore returns the saved
belongings, game mode and location. Quitting leaves the restore to the next join.
Every exit first clears spectator mode, so an unsuccessful restore leaves the player in
Adventure mode while their snapshot remains available for a retry.

## Round feedback

During a match, the bossbar shows the round and the time left in preparation or combat.
Overrun turns it red and shows its remaining time; an unlimited Overrun keeps a full bar
without a countdown. Between rounds it shows the next-round countdown. The actionbar
shows each starting team's points and the number of living enemies currently in play,
including enemies carried over from an earlier round; it is not a fixed wave total.
Eliminated teammates keep seeing this feedback while spectating.

Titles mark preparation, the start of combat and the round result. A quiet cue announces
each successful enemy spawn to that match, and a level-up sound marks each round end,
including a draw. These use standard client sounds and plain text for Java and Bedrock.
Leaving, match end and plugin disable clear the bossbar, actionbar and titles.

## Match scoring

Teams earn 5 points for a round win and 2 for killing an opposing player in the same
match. Configure these awards with `scoring.round-win-points` and
`scoring.player-kill-points`; zero disables an award and negative values become zero.
Teamkills, suicides, environmental deaths, leaving and drawn rounds award nothing.
Points decide the match standings and whether an extra round is needed. The final points
table is shown to the match's online players before they return home.

Other features can award team points on the server thread through
`plugin.scores().addPoints(match, team, points)`, using the live `MatchView` from
`plugin.matches()`. Awards are accepted from PREP through ROUND_END for teams that started
with players. Totals stop at `Integer.MAX_VALUE`. Once the match ends its result keeps the
final scores, further awards are refused, and a new match starts at zero.

## Player stats

Each player's wins, rounds won, player kills, enemy kills, deaths, best Overrun survival
and matches played are recorded all-time and per season. They are written in the
background when a match ends, to SQLite by default or to a MySQL or MariaDB database
that several servers share. Changing `stats.season` archives the current season; its
stats are kept. A missing driver or an unreachable database never stops a match:
finished matches stay queued and are retried. Configuration, the schema and the read
API for leaderboards and rewards are in [`docs/player-stats.md`](docs/player-stats.md).

## Licence

GPL-3.0. See [`LICENSE`](LICENSE).
