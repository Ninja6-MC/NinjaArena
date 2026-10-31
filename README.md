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

## Commands and permissions

`/ninjaarena` is the registered root; `/narena` is its permanent short alias. Run either
root on its own, or add `help`, to list the commands your permissions allow. Replies use
plain text, with no clickable chat. Existing `arena`, `portal` and `director` paths stay
under the root.

| Command | Permission | Behavior |
| :--- | :--- | :--- |
| `help` | Any tier below | List permitted commands |
| `leave` | `ninjaarena.play` | Leave the waiting room or match and restore your snapshot |
| `accept <host>` | `ninjaarena.play` | Accept an invitation and enter the waiting room |
| `host <arena>` | `ninjaarena.host` | Open a free arena for a hosted match without moving |
| `invite <player>` | `ninjaarena.host` | Invite an online player with a plain accept command |
| `arena <subcommand>` | `ninjaarena.admin` | Register, build and inspect arenas |
| `portal <subcommand>` | `ninjaarena.admin` | Select and manage entrance portals |
| `director [arena]` | `ninjaarena.admin` | Inspect a running match's enemy director |
| `admin reload` | `ninjaarena.admin` | Validate and safely reload configuration and kits |

The player and host commands require a player sender. Enter the Arena by walking
through a portal or explicitly accepting an invitation. Admin reload also works from
the console.

`ninjaarena.play` defaults to everyone. `ninjaarena.host` and `ninjaarena.admin` default
to operators. The declared permission children grant play from host, and host from admin;
the play leaf has an explicit empty children block. The root accepts any of these tiers,
and dispatch, help and tab completion check the route's own permission. Denying play
explicitly therefore blocks player routes without blocking an otherwise granted admin
or host route.

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
| Waiting-room minimum, countdown and radius | Apply immediately; a viable countdown keeps its remaining length. A new minimum can cancel or start a countdown, and players outside a reduced radius leave through normal restore. |
| Match timers, rounds, extra rounds, friendly fire and scoring | Apply to the next match. Running matches retain all their values, even if no damage or point award has occurred yet. |
| Tool values | New uses take the new values immediately. Existing clouds retain their radius, effects and duration; existing marks and dash cooldowns retain their deadlines. |
| Kits | Apply from the next round. Delayed respawns receive the definition selected for their current round. |
| Arena-world view and simulation distance | Apply immediately without resetting world rules, time or saving. |
| Grid cell size and world border | Apply only before any arena exists and while no match runs. Otherwise refuse the change and require a restart. |

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
and pending teleports keep it loaded. See [arena world retirement](docs/arena-world-lifecycle.md)
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

Each enemy type has an `ignores-stealth` setting, off by default, that skips the hidden cut
and the lose-track timer; smoke and marks still apply. Every weight and timing is under
`director.stealth` in `config.yml`.

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

## Licence

GPL-3.0. See [`LICENSE`](LICENSE).
