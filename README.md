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

The bundled lobby structure, `src/main/resources/lobby/obsidian-lobby.nbt`, is generated
from the readable spec beside it, `obsidian-lobby.yml`, which also marks the lobby's
spawn, room area, shop area and board positions. After changing the spec, run
`./gradlew generateLobbyBuild` and commit both files; the tests fail while they differ.

The seeded terrain generator for generated maps, its determinism rules and how to
update its golden digests are described in the
[terrain generator notes](docs/terrain-generator.md).

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
   world and puts it in edit mode. The reply shows the cell's coordinates. It never
   picks a slot cell reserved for generated maps (see [Arena slots](#arena-slots)).
2. Switch to creative, then `/narena arena tp dojo` takes you to the middle of the cell.
   Build the arena inside the cell.
3. `/narena arena setbounds dojo <x1> <y1> <z1> <x2> <y2> <z2>` marks the playable box.
   It must fit inside the cell, and everything below must be inside it. `~` and `~n` are
   relative to where you stand.
4. Stand at each team base and run `/narena arena setteamspawn dojo 1`, then `2`, up to
   four teams. Two are required.
5. Optionally, stand where `/narena arena tp dojo` should land you and run
   `/narena arena setwaitingroom dojo`. This only sets the teleport landing point;
   players wait in the lobby, and the arena is ready without this point.
6. Stand where enemies should appear and run `/narena arena addenemyspawn dojo`. At least
   one is required; enemies spawn there during rounds.
7. `/narena arena edit dojo off` saves the arena and leaves edit mode. If anything is
   still missing it is listed; `/narena arena edit dojo on` lets you fix it.
   `/narena arena info dojo` shows the current setup and problems at any time.
8. Back in the main world, build a doorway frame for the portal. Stand on the floor
   inside it and run `/narena portal setcorner 1`, then `/narena portal setcorner 2 ~ ~2 ~`
   so the portal is a doorway rather than a flat pad. Move sideways first for a wider
   door. `/narena portal create dojo-door dojo` binds it to the arena. Walking through it
   takes players to the lobby, into an open room on `dojo` when there is one.

Restoration keeps the saved position inside the gate when it is safe (#107); it does not
move the player outside. Standing still or walking forward within the same portal region
cannot enter again. Leave the region and walk back in to enter normally. Entry checks the
old and new blocks on each move, so this guard has no timer to expire and no per-player
state to lose on logout, death or restart. Teleporting into a gate also requires leaving
and walking back in. Existing pending snapshots keep their saved coordinates and facing;
the usual unsafe-landing fallbacks still apply.

## Moving from waiting rooms to rooms

No match starts by itself any more. Waiting rooms with their automatic countdown and the
shared hub are gone; every match is a [room](#rooms)'s, started by its host or an admin,
and every match holds an [arena slot](#arena-slots). An existing `config.yml` and
`portals.yml` still load: each old setting logs one warning at startup, and on reload,
that points here, and keeps working through the lobby and rooms as follows.

| Old | Now |
| :--- | :--- |
| A play portal bound to an arena (`lobby: dedicated`, or no choice under a dedicated default) | Takes the player to the lobby, saving their snapshot, and into the open room on that arena with the most players that admits them and has space. With none, they stay in the lobby and see the room list. The arena may be in use or edited meanwhile. |
| A hub portal (`lobby: shared`, or no choice under a shared default) | The same, but into any open room, rooms on an event's featured map first. Nothing goes to the old hub point. |
| A player-built portal | As a hub portal: the lobby and any open room. |
| A spectator portal | Unchanged: it seats the player at the live match on its arena. Leaving goes to the lobby. |
| `/narena spectate <arena>` from a waiting room or the hub | Works from the lobby, for a player in no room. |
| `/narena host <arena>` | Opens an invite-only room on that arena, which need not be pickable. Needs `ninjaarena.host` as before. |
| `/narena invite <player>`, `/narena accept <host>` | The same as `room invite` and `room accept`. |
| `/narena portal lobby <portal> <dedicated\|shared\|default>` | Deprecated alias. It still sets the portal's choice, which now only picks which open rooms a player joins, and says so. |
| `/narena portal sethub` | Deprecated alias. It checks its arguments and changes nothing: the lobby took the hub's place. `portal removehub` clears an old hub point. |
| `waiting-room.lobby` | Deprecated, still read: the default for portals with no choice of their own, with the meaning above. |
| `waiting-room.max-players`, `countdown-seconds`, `radius` | No longer used. Remove them. |
| `waiting-room.min-players`, `team-size` | The settings a new room starts from. |
| `lobby:` per portal and `hub:` in `portals.yml` | Still read, with a warning naming the portals; `portal lobby <portal> default` and `portal removehub` clear them. |
| Scheduled events | They never started matches and still do not: their arena is the featured map. See [Events](#events). |

### Events

While a [scheduled event](docs/arena-events.md) runs, its arena is the featured map. The
room list (`/narena room` outside a room) names it and marks rooms on it, a room on it
says so in `/narena room`, a new room plays on it when it is ready and no other room
holds it, and hub and player-built portals prefer rooms on it. A room's match on the
featured map, started while the event runs, earns the event's reward bonus; the bonus is
captured when the match starts, so a match the event outlasts keeps it.

## Player-built portals

Players with `ninjaarena.play` can build their own way into the Arena, as they would a
nether portal, but from crying obsidian:

1. Build an upright frame with an empty opening 2 to 21 blocks wide and 3 to 21 tall,
   along either axis. The four corners are optional, so the smallest frame takes 10
   crying obsidian, or 14 with corners.
2. Use flint and steel on the inside of the frame. Instead of a fire, a curtain of
   coloured particles fills the opening, and the player is told the portal is open.
3. Walking through it takes the player to the lobby with the same checks, snapshot and
   empty inventory as an admin portal.

A player-built portal belongs to no arena: it puts the player in an open room with space
that admits them (a public room, or an invite-only one that invited them), one on an
event's featured map first, then the one with the most players. With none, it leaves
them in the lobby to create or be invited to one. Vanilla does nothing with crying
obsidian, so these portals never clash with nether portals; obsidian cannot be chosen as
the frame block.

Breaking, burning, blowing up or pushing any block of the frame removes the portal; the
corners, the opening and blocks around the frame do not count, so ordinary block updates
nearby leave it alone. The curtain is drawn only while a player is within two chunks,
for at most 16 portals at a time, and while a player is near, the frame is checked
every five seconds, which also catches changes no event reports, such as a frame edited
while the server was down. A frame block in an unloaded chunk is never judged missing.

Portals are saved in `portals.yml` with the admin ones, named `built-1`, `built-2` and
so on, so they survive restarts and appear in `/narena portal list` and `info` with
their builder. An admin removes one with `/narena portal delete <name>`; the frame
stays. A player-built portal refuses the deprecated `portal lobby`. Versions from before
player-built portals refuse a `portals.yml` that holds one rather than drop it.

```yaml
player-portals:
  worlds:                     # where players may build; [] turns building off
    - world
  frame-material: crying_obsidian
  max-per-player: 2           # 0 for no limit
  min-distance: 32            # blocks between portal centres in a world; 0 turns it off
  color: '#40E0D0'            # the particle curtain
```

Neither the arena world nor the lobby world is allowed. Each portal keeps the frame
block it was built from, so changing `frame-material` affects only new portals. The
curtain is a vanilla dust particle, which needs no resource pack on Java or Bedrock.

## Commands and permissions

`/ninjaarena` is the registered root; `/narena` is its permanent short alias. Run either
root on its own, or add `help`, to list the commands your permissions allow. Replies use
plain text; the only clickable chat is the room buttons described under [Rooms](#rooms).
Existing `arena`, `portal` and `director` paths stay under the root.

| Command | Permission | Behavior |
| :--- | :--- | :--- |
| `help` | Any tier below | List permitted commands |
| `intro` | `ninjaarena.play` | Show the introduction again in the lobby |
| `leave` | `ninjaarena.play` or `ninjaarena.spectate` | Leave your room, match or spectating for the lobby; from the lobby, return home and restore your snapshot |
| `leaderboard <stat> [all_time\|season]` | `ninjaarena.leaderboard` | Show the [top ten for a stat](docs/leaderboards.md) |
| `spectate <arena>` | `ninjaarena.spectate` | Watch a live match from the lobby, while in no room |
| `accept <host>` | `ninjaarena.play` | Join a host's room; the same as `room accept` |
| `room` | `ninjaarena.play` | Show your room, with remove buttons for its host; outside a room, list the open rooms and an event's featured map |
| `room create` | `ninjaarena.room.create` | Open a room in the lobby and become its host |
| `room settings [players\|teams\|team-size\|privacy <value>]` | Host | Show or change the room's settings |
| `room invite [player]` | Host | Invite a player with a join button, or list lobby players with invite buttons |
| `room accept <host>` | `ninjaarena.play` | Join a host's room: a public one, or one that invited you |
| `room kick <player>` | Host | Remove a player from the room to the lobby |
| `room start [host]` | Host, or `ninjaarena.admin` for any room | Start the room's match |
| `room leave` | `ninjaarena.play` | Leave the room and stay in the lobby |
| `map list` | `ninjaarena.play` | List pickable maps, and generated maps when they are on |
| `map set <arena>` | `ninjaarena.map.change`, host | Change your room's map to a pickable hand-built arena while it is in the lobby |
| `map set generated [seed] [difficulty]` | `ninjaarena.map.change`, host | Play [generated maps](#seed-modes): from the seed modes, or from a seed set by hand |
| `map next` | `ninjaarena.map.change`, host | The next seed of the rotation at your map's next generation |
| `map reroll` | `ninjaarena.map.change`, host | A new random seed at your map's next generation |
| `shop ready` | `ninjaarena.play` | Say you are ready in the [shop between rounds](#shop-phase) |
| `host <arena>` | `ninjaarena.host` | Open an invite-only room on the named arena |
| `invite <player>` | `ninjaarena.host` | Invite a player to your room; the same as `room invite <player>` |
| `arena <subcommand>` | `ninjaarena.admin` | Register, build and inspect arenas |
| `portal <subcommand>` | `ninjaarena.admin` | Select and manage entrance portals; `lobby` and `sethub` are deprecated aliases |
| `director [arena]` | `ninjaarena.admin` | Inspect a running match's enemy director |
| `event <subcommand>` | `ninjaarena.event.manage` | List, inspect, start or cancel scheduled arena events |
| `admin reload` | `ninjaarena.admin` | Validate and safely reload configuration, kits and messages |
| `admin lobby <edit\|done\|reset>` | `ninjaarena.admin.lobby` | Change the lobby in edit mode, or put the bundled lobby back |
| `admin generate <seed> [difficulty] [2\|4]` | `ninjaarena.admin.generate` | [Reproduce a seed's layout](#reproducing-a-layout) in a free slot cell |
| `admin generate free` | `ninjaarena.admin.generate` | Clear that layout and free its cell |

Enter the Arena by walking through a portal into the lobby, or by opening or joining a
room. Leaderboard, event management, admin reload, lobby reset, admin generate and
`room start <host>` also work from the console.

`ninjaarena.play`, `ninjaarena.spectate`, `ninjaarena.leaderboard` and
`ninjaarena.room.create` default to everyone, so a server where nobody has op can open
rooms and play. `ninjaarena.host`, `ninjaarena.admin` and `ninjaarena.admin.lobby`
default to operators. Play grants spectate, leaderboard and `ninjaarena.room.create`,
which can be taken away on its own to stop a group opening rooms. Host grants play, and
admin grants host, `ninjaarena.map.change`, `ninjaarena.admin.lobby` and
`ninjaarena.admin.generate`. The last two default to operators and can also be given on
their own: to someone who only edits the lobby, or who only reproduces layouts. The root
accepts any of these permissions. Dispatch, help and tab completion check the
permissions for each route.

## Hosting and invitations

`/narena host`, `invite` and `accept` keep their names and permissions and work on rooms.
With `ninjaarena.host`, `/narena host dojo` opens an invite-only room on `dojo` with you
as host, taking you to the lobby as `room create` does; the arena must exist and have two
team spawns, and need not be pickable. `/narena invite Bob` sends Bob a **[Join]**
button, and Bob joins with it or `/narena accept <host-name>`, exactly as with
`room invite` and `room accept`. The room then follows the rules under [Rooms](#rooms):
it leases a slot or queues, and its match starts only when the host or an admin presses
start. Nothing is reserved without a room any more.

## Rooms

Rooms are how players start matches themselves. `/narena room create` takes the player
to the lobby, saving their snapshot as a portal does, and opens a room there with them
as its host. The room plays on a hand-built arena: an event's featured map when it is
ready and no other room holds it, else the first by name that is ready and that no other
room holds. When no hand-built arena is ready, the room plays [generated
maps](#seed-modes) from the seed modes instead, for two teams, so a server with no
hand-built arena at all works out of the box. Nothing in a room counts down, and no
match starts any other way. The host, or an admin, presses start with `/narena room
start` once the room has its required player count; below it the start is refused.

Hosts with `ninjaarena.map.change` (default op, inherited from `ninjaarena.admin`) can
use `/narena map set <arena>` while their room is in the lobby. Admins mark a hand-built
arena with `/narena arena pickable <arena> <on|off>`; `arena info` shows the flag. It is
saved as optional `pickable: true` in `arenas.yml`, still format 1; missing means not
pickable. A chosen arena needs at least two team spawns. Everyone in the room hears the
old map, new map and who changed it, plus the queue position if it is busy. Changing
maps moves the lease and lowers the team count to fit the new arena. Any room member can
use `/narena map list` to see pickable arenas, with the current map marked, and
`generated` when generated maps are on. Hosts without the node keep the default arena
selection described above.

The host sets the room up with `/narena room settings`:

- `players`: the players needed to start (1 to 64; by default `waiting-room.min-players`).
- `teams`: how many teams (1 to the arena's team spawns; by default all of them).
- `team-size`: the most players on one team (0 for no limit; by default
  `waiting-room.team-size`). The room is full at the team size times the team count.
- `privacy`: `public`, which anyone may join, or `invite`, which only invited players may.

`/narena room invite Bob` sends Bob a **[Join]** button; `/narena room invite` on its own
lists the lobby players in no room, each with an **[Invite]** button. A player joins with
the button or `/narena room accept <host>`; a public room needs no invitation. In
`/narena room` the host sees a **[Remove]** button beside each player, the same as
`/narena room kick <player>`. A removed player goes back to the lobby and cannot rejoin a
public room unless invited again. Players are put on the smallest team and kept within one
player of each other. Settings and removals wait until the room is back in the lobby.

A player who comes through a play portal also joins an open room, as described under
[Moving from waiting rooms to rooms](#moving-from-waiting-rooms-to-rooms).

When the host leaves, the room passes to the player who has waited in it longest and has
`ninjaarena.room.create`. When nobody left has it, the room closes and its players stay in
the lobby. A running match goes on either way.

### Arena slots

A room leases an arena slot as soon as it has its arena, which for now is when it is
created. The lease lasts through the start, every round and the end of the match, and is
given up when the room is back in the lobby, where it queues again for its next match, or
when it closes. `arenas.max-active` in `config.yml` (default 4) caps the slots in use at
once; every match is a room's and holds one. It is also never more than the cells
of the arena grid. While every slot is leased, more rooms queue, and their players see
their place in the queue. A hand-built arena holds one match at a time, so a room whose
arena is in use queues for that arena; rooms behind it that want a free arena go first.
A room that closes while its match runs keeps the lease until the match ends.

`arenas.max-active` was called `stability.max-concurrent-matches`. The old name is still
read, with a warning at startup and on reload, when the new one is not set; set beside
it, the old name is ignored. It applies on reload, and a lower value never ends a lease
already granted: the rooms above it keep their slots, and no new room is let in until
enough are given up.

#### Reserved slot cells

Generated maps are played in slot cells: `arenas.max-active` cells of the arena grid,
reserved at startup, one per slot. They are taken from the edge of the world border
inwards, farthest from the origin first, which is the opposite end of the grid from the
cells `/narena arena create` fills. `arena create` never picks a reserved cell, and
naming one with `/narena arena create <name> <cell x> <cell z>` is refused. A hand-built
arena that is already in a cell keeps it: that cell is passed over, the next free one is
reserved instead, and the server log says so. The same cells are reserved after every restart
while `config.yml` and `arenas.yml` are unchanged. A reload that changes
`arenas.max-active` keeps the cells already reserved; a higher value adds cells after
them, a lower one gives up the last ones, but a cell a generated map still uses stays
reserved until the map is done with it.

Slot cells are cleared on startup, whenever the arena world reopens after being retired,
and before every map is placed in one, so a generated map never survives a restart or
a retire. Generated maps are kept in memory only and never written to `arenas.yml`;
their names (`generated:1`, `generated:2`, ...) cannot be a hand-built arena's name, and
they are not listed by `/narena map list`, `/narena arena list` or tab completion. While
any slot cell is in use, being generated, placed, played or cleared, `/narena arena
create` and `/narena arena edit` are refused, as they are during a match, because edit
mode turns protection off across the whole arena world; and no generated map starts
while an arena is in edit mode.

#### Seed modes

A room plays generated maps when its host picks them with `/narena map set generated`,
or when it opens with no hand-built arena ready. Its map is generated in a slot cell
before each match, and with `regenerate: round` also in each shop break. The `maps`
section of `config.yml` says which seed and difficulty each slot cell's next map gets:

| Key | Default | Meaning |
| :--- | :--- | :--- |
| `maps.default.mode` | `random` | `static`: always `seed`. `rotation`: the `seeds` list in order. `random`: a random seed. |
| `maps.default.seed` | `''` (none) | The seed for `static`, a whole number. `static` without one is refused. |
| `maps.default.seeds` | `[]` | The seeds for `rotation`, in order, each once. `rotation` without any is refused. |
| `maps.default.rotate-every` | `0` | How long a rotation stays on one seed, as a duration such as `30m`, `2h` or `1h30m` (units `d`, `h`, `m`, `s`; at most `365d`). `0` moves on at every generation (under `shared`, see below). |
| `maps.default.reroll-every` | `0` | How long a random seed is kept before a new one is picked; `0` picks again at every generation (under `shared`, see below). |
| `maps.default.difficulty` | `2` | How hard the map is to cross, 1 to 5. |
| `maps.default.regenerate` | `match` | `match`: a new map before each match. `round`: also in each shop break. |
| `maps.slots.<n>` | none | Any of the keys above, for slot cell `n` (1 to 100) only. |
| `maps.seed-sharing` | `distinct` | `distinct`: no two active slots play the same seed at once. `shared`: every slot follows `maps.default` and one seed at once. |

Slot cell numbers follow the reserved cells, slot 1 first, plus one spare cell after
them when a slot regenerates between rounds. A map takes the first free cell when it is
generated, and a map generated again between rounds takes its old slot number with it.

A rotation starts on its first seed for slot 1, its second for slot 2, and so on, and
moves on once `rotate-every` has passed since it last moved. A random seed is kept until
`reroll-every` has passed. Either way the change happens at the slot's next generation:
a timer that runs out during a match never touches the map being played. Under `shared`
every active slot plays one seed, from one rotation or one random seed. With `0` it
moves on only at a generation while no other active slot still plays it, so a slot on
its own gets a new seed each match or round while slots active together stay on one;
with a timer, once the timer has run out, at the next generation. `map next` and `map
reroll` move it on for every slot, a rotation by one step. A reroll is refused when the
shared seed is static because the server settings fix it. The rotation place and the
random seed in force are kept in memory only: a restart starts each rotation again and
picks new random seeds.

Under `distinct`, a seed is active while its map is being generated, placed or played,
or while a room has it set by hand, and no other slot gets it:

- A static seed set on several slots warns at startup and on reload; the first slot
  plays it and the later ones fall back to random seeds. A static seed that another slot
  has set by hand gets a random seed for that map, with a log line.
- When more slots are active than a rotation has free seeds, the extra slots get random
  seeds, with a log line.
- A random seed is never one that is active.

Under `shared`, `maps.slots` is ignored with a warning, and active slots play one seed.
A default install, `random` and `distinct`, gives no warning.

Hosts with `ninjaarena.map.change` control the room's seed:

- `/narena map set generated` plays generated maps from the seed modes.
- `/narena map set generated <seed> [difficulty]` sets the seed by hand, for every map
  until changed, at `maps.default.difficulty` unless given. Under `distinct`, a seed
  another slot has is refused with a message; it is never silently replaced.
- `/narena map next` asks for the next rotation seed (a new random one in `random`
  mode), and `/narena map reroll` for a new random seed. Both end a seed set by hand,
  except a reroll under `shared` with `static` mode is refused and keeps that seed.

In the lobby these generate the room's map again at once. While the room plays, the map
being played never changes: they apply at the next generation, which is the next shop
break with `regenerate: round`, else the map after the match. `/narena map set <arena>`
goes back to a hand-built arena. When no map comes from a seed, the room plays its last
good seed; with none, a hand-built arena; with none of those, one more random seed.

With `regenerate: round` (it needs the shop phase), the next map is generated in a spare
cell in each shop break while the current one stays as it is. The shop waits for it, up
to `shop.map-wait-limit-seconds` past its time, and the next round starts on it only
once it is fully placed; the old cell is then cleared. A map that fails, or is not
placed in time, is dropped and the round is played on the current map, the last good
seed. With no cell free in the break, the round keeps the current map too. A seed set by
hand is not generated again between rounds.

Changing the map, mode or difficulty from the room dialog will call the same room
service methods as these commands.

#### Reproducing a layout

`/narena admin generate <seed> [difficulty] [2|4]` (`ninjaarena.admin.generate`, from
`ninjaarena.admin`) places the map a room would get from that seed, at
`maps.default.difficulty` and for two teams unless given, in a free slot cell. The reply
names the cell and its block bounds. Nobody plays it or is moved there, it takes no
arena slot, and its seed does not count under `distinct`, so the layout a room plays now
can be looked at. It is refused while no cell is free beyond those rooms wait for, so it
never holds up a room. Only one is placed at a time; `/narena admin generate free`
clears it and frees the cell. While it is placed, arena edits are refused as for any
slot cell in use, and it is gone after a restart.

#### Placing generated maps

A generated map is cleared and placed over many server ticks, within a time budget per
tick shared by every slot cell, under `stability.placement` in `config.yml`:

| Key | Default | Range | Meaning |
| :--- | :--- | :--- | :--- |
| `budget-ms` | 10.0 | 0.1 to 50 | Server time per tick, in milliseconds, spent placing and clearing maps across every slot cell. |
| `slow-budget-ms` | 1.0 | 0 to 50, at most `budget-ms` | The time per tick while the tick-time guard holds enemy spawning back. 0 pauses placement then. |
| `recovery-seconds` | 10 | 0 to 600 | Once the average tick time is back below `stability.resume-below-mspt`, how long the budget takes to climb back to `budget-ms`. |
| `max-map-size` | 256 | 32 to 4096 | The largest grid cell a generated map is sized for. In a larger `arena-grid.cell-size` the map is the one a cell of this size gets, centred in the cell. |

The budget backs off on the same tick-time guard as enemy spawning: above
`stability.pause-spawning-above-mspt` placement drops to `slow-budget-ms` until the
average falls below `stability.resume-below-mspt`. Slots generating at once take turns,
so they move on together and four of them take no more time per tick than one.
`max-map-size` caps the blocks each map clears and places on a shared host: 256 leaves
the default 256-block cell as it is (a 240-block map, about 3.7 million blocks to clear
and as many to place for four teams), and 128 gives a 120-block map with a quarter of
the blocks. Like the rest of `stability`, these settings are validated on reload but
apply after a restart. See [Placement](docs/terrain-generator.md#placement) for the
details.

### Where players go

| From | How | To |
| :--- | :--- | :--- |
| A room's match | The match ends | The lobby, still in the room |
| A room or a match | `/narena leave` | The lobby, out of the room |
| A room | `/narena room leave` | The lobby, out of the room |
| The lobby | `/narena leave` | Home, restoring the snapshot |
| Spectating | `/narena leave`, or the match ends | The lobby |
| Anywhere in the plugin's worlds | A teleport out of them | Home, restoring the snapshot |
| A match | Quit, then rejoin within `match.rejoin-grace-seconds` | Back in the match (see below) |
| A room, a match, the lobby or spectating | Quit, then rejoin | The lobby, with the snapshot kept |

A player the lobby cannot take is sent home instead. Rejoining puts a player in the lobby
only while the server keeps running; after a restart they are restored home on joining, as
before.

### Rejoining a match

A player who disconnects from a match before it ends is held in it for
`match.rejoin-grace-seconds` (default 60, 0 to 600). Everyone in the match is told how long
they have. While held, they:

* stay in the match, on their team and in their room; a host stays the host;
* are out of the current round, as if eliminated, and out of every round that starts while
  they are away, so their team can lose rounds without them;
* keep their team in the match: a team does not forfeit while any member is held, even if
  nobody on it is connected;
* keep their points and any shop purchase not yet delivered. A purchase still pending at
  the next round start is refunded to their points, as for a player on the death screen;
* are left out of the shop's ready check, so the others can still cut the shop short.

Like every exit, the disconnect clears their kit and spectator mode before player data is
saved; their snapshot stays on disk for the final restore. Rejoining within the grace puts
them back:

| Phase when they rejoin | Where they go |
| :--- | :--- |
| PREP | Their team spawn, alive in the round, with the round kit |
| COMBAT, OVERRUN or ROUND_END, extra rounds included | Spectating their team until the next round's PREP |
| SHOP | The shop area, able to buy and run `/narena shop ready` |

Every round's PREP places all players at once when it begins, so a rejoin during PREP
counts as that placement. When the grace runs out, the player leaves the match exactly as a
disconnect did before: their points are dropped, pending purchases are refunded into
nothing, a team with nobody left forfeits, and their next join takes them to the lobby with
their snapshot kept, or home after a restart. A match that ends or closes first lets them
go the same way; their result counts with their team. A host held past the grace, or past
the end of the match, hands the room on by the usual rules. While nobody in a match is
connected, its timers wait, and the match is abandoned once the last grace runs out.
Disabling the plugin ends every grace as a leave. `match.rejoin-grace-seconds: 0` keeps a
disconnect a leave, as before.

### Disconnecting during a fight

A player who disconnects while alive in COMBAT or OVERRUN dies for the round, held or not.
The kill goes to the last enemy player still in the match who damaged them within
`match.disconnect-kill-window-seconds` (default 0, the whole round so far; 0 to 3600), by
melee, projectile or lingering cloud. That player gets everything a kill gives: the team's
`scoring.player-kill-points`, their own `points.player-kill`, and the kill and the death in
the stats. If the last damager has left the match, the one before them is credited. With
nobody to credit, the player's team loses `match.disconnect-penalty-points` (default 2; 0
turns it off), never going below 0, and the death still counts. Everyone in the match is
told who got the kill, or what the team lost. A disconnect in PREP, SHOP or ROUND_END is
no death and costs nothing.

The death stands if the player rejoins in the same round: they spectate their team until
the next round as any rejoin during the fight does, are not brought back into the round,
and a second disconnect in that round counts nothing more, since they are no longer alive
in it. The leave command and teleporting out of the arena are not disconnects and are
scored as before.

## Watching live matches

From the lobby, while in no room, run `/narena spectate <arena>` to watch an
in-progress match. The transfer keeps your snapshot from entering the lobby. To offer
access from another world, mark portal corners as usual and run
`/narena portal create <name> <arena> spectator`. The portal admits viewers only while
the named match is open to spectators. Play portals lead to the lobby.

Viewers can fly within the arena's three-dimensional bounds. Entity cameras, arena
interactions, and teleports outside the bounds are blocked. Viewers are outside the
team rosters, countdown minimum, scoring, and enemy director. Use `/narena leave` to go
to the lobby, or wait for the match to end, which takes you there too. Disconnecting frees
the spectator slot immediately and puts you in the lobby on your next join.

Set `spectating.enabled` to control admission and `spectating.max-per-match` to set the
number of viewers per match (1 to 64, default 16). Both options apply to new admissions
on a successful reload. Current viewers can stay until they leave or the match ends.

## Shop catalogue

`plugins/NinjaArena/shop.yml` uses `format: 1` and an `items` list. Each entry has a
unique `id`, positive points `price`, per-round `limit` of at least one, `category`
(`armour`, `tools`, `potions`, `consumables`) and `gives`. Armour gives a `tier` of
leather, chain, iron or diamond. Tools give a thrown `tool` id and extra-use `count`;
unlimited tools warn because they gain no benefit. Supply enough tool items in the kit.
Potions give an `effect` id, zero-based `amplifier` and `duration` in ticks. Consumables
give either `material` or a registered plugin `id`, plus an `amount` within its stack
limit. The bundled file includes examples and comments for each key.

Buying requires `ninjaarena.shop.use`, default true and inherited by `ninjaarena.play`.
It is open only while the buyer's match is in its [shop phase](#shop-phase); there is no
buy command or shop screen yet. A match keeps its starting catalogue.
Purchases apply after its round kit is rebuilt, including delayed respawns, and last
one round. Potion effects and extra-use bonuses are explicitly cleared at round end;
armour and consumables disappear at the next kit rebuild.

Pending purchases that cannot be delivered at round start (including players still on
the death screen) are cancelled and refunded to an existing ledger. Failed item delivery,
such as a full inventory, is also refunded. Leaving drops both the ledger and purchases;
a refund never recreates a departed player's ledger.

## Shop phase

With `shop.enabled: true`, the default, a round end that another round follows, an extra
round included, leads to a SHOP phase before the next prep. The last round, the final
extra round and a forfeit end the match without one. Everyone still in the match goes to
the lobby world's shop area, eliminated players out of spectator mode and players on the
death screen when they respawn, and so do the match's spectators, who may fly within the
lobby bounds meanwhile. They stay in the match: inventories stay locked and untouched,
snapshots are kept, nobody takes damage and points are not lost. The next prep brings
everyone back to their team spawns, and spectators to the arena. While the shop is open
nobody new is let in to watch.

The shop lasts `shop.max-seconds` (default 45). `/narena shop ready` says a player is
done; once everyone in the match is ready, the time left is cut to
`shop.quick-start-seconds` (default 5), if it is shorter. The boss bar counts down and
shows how many are ready.

The shop never closes before the map for the next round is ready. Hand-built arenas and
generated maps with `regenerate: match` are always ready, and every round is played on
the match's arena. A generated map with [`regenerate: round`](#seed-modes) is generated
again in the break: a shop whose time is up waits for it, and when it is still not ready
`shop.map-wait-limit-seconds` (default 30) after the time limit, the round is played on
the current map, a hand-built arena reset from its saved copy first. 30
seconds caps the whole pause between rounds at well over a minute without leaving players
waiting on a map that is stuck; 0 replays at the time limit.

Leaving during the shop works as in a round: `/narena leave` goes to the lobby, a
teleport by another plugin takes the player out of the match, and so does a disconnect
once its [rejoin grace](#rejoining-a-match) runs out; leaving drops their points and
refunds nothing into a ledger they no longer have. When only one team is left, the match
ends at once as a forfeit. A room keeps its arena slot lease through the shop until its
match closes. `shop.enabled: false` goes straight from one round to the next as before,
and buying never opens.

Other plugins see the phase as `MatchPhase.SHOP` in `MatchPhaseChangeEvent`: ROUND_END
to SHOP, with the players already in the shop area, then SHOP to PREP.

## Safe configuration reload

Run `/narena admin reload` with `ninjaarena.admin` after editing `config.yml`,
`kits.yml`, `shop.yml` or `messages.yml`. All four are read and validated before
anything changes. For config and kit files, a missing file, malformed YAML, duplicate or
unknown key, wrong type, out-of-range or non-finite number, invalid kit entry,
unregistered item ID or excessive stack amount rejects the whole reload. The reply names
the file, setting and source line; the previous settings and kit remain active. Omitted
config settings use the bundled defaults.

For `shop.yml`, whole-file problems such as a missing file, malformed YAML or wrong
format reject the reload and keep the previous catalogue. A bad item is skipped with one
server log warning; the remaining valid items load. Shop diagnostics do not necessarily
include a source line. Reload never reads arenas, portals or snapshots, and never moves
match players or restarts a match.

| Settings | Reload behavior |
| :--- | :--- |
| `arenas.max-active` | Applies immediately. A higher value lets queued rooms in; a lower one never ends a lease already granted. Reserved slot cells stay where they are: a higher value reserves and clears more after them, a lower one gives up the last ones once no generated map uses them. The old name `stability.max-concurrent-matches` is read with a warning when the new one is not set. |
| `waiting-room.min-players` and `team-size` | Apply to the next room opened; rooms already open keep their settings. |
| Deprecated `waiting-room.lobby` | Applies to the next portal entry. It and the unused `max-players`, `countdown-seconds` and `radius` are accepted with a warning in the reply and the log (see [Moving from waiting rooms to rooms](#moving-from-waiting-rooms-to-rooms)). |
| Match timers, rounds, extra rounds, friendly fire, team scoring and player points | Apply to the next match. Running matches retain all their values, even if no damage or point award has occurred yet. |
| `match.rejoin-grace-seconds` | Applies immediately to new disconnects, in running matches too. Players already held keep the grace they were given. |
| Shop phase | `shop.enabled`, `shop.max-seconds`, `shop.quick-start-seconds` and `shop.map-wait-limit-seconds` apply to the next match; a running match keeps its own. |
| Seed modes | Every `maps` key applies at each slot's next generation, including the spare cell for `regenerate: round`. A map already placed, a running match and a shop break under way keep theirs. A rotation keeps its place while its `seeds` list is the same; a change of `seed-sharing` starts every rotation and random seed again. Warnings about the new settings go to the log. |
| Spectator admission | Enable or disable new admissions and change the cap immediately. Existing viewers may finish watching. |
| Player-built portals | Apply to the next portal lit; the curtain colour changes immediately. Portals already built stay, with their own frame block. |
| Tool values | New uses take the new values immediately. Existing clouds retain their radius, effects and duration; existing marks and dash cooldowns retain their deadlines. |
| Resource packs | `resource-pack.thrown-visuals` applies to the next throw. All delivery settings, URLs, hashes and identities require a restart. |
| Shop catalogue | `shop.yml` applies to the next match. Bad entries warn and are skipped; a broken file rejects reload and keeps the previous catalogue. |
| Kits | Apply from the next round. Delayed respawns receive the definition selected for their current round. |
| Arena-world view and simulation distance | Apply immediately without resetting world rules, time or saving. |
| Grid cell size and world border | Apply only before any arena exists and while no match runs. Otherwise refuse the change and require a restart. |
| Placeholder cache | `placeholders.cache-seconds` applies to cached stats immediately. |
| Stats season | A new `stats.season` archives the current season and starts it. Other stats settings require a restart. |
| Sounds | `sounds.*` applies immediately, including to running matches. |
| Messages | `messages.yml` applies immediately to every later message. Malformed YAML rejects the whole reload; a missing or unreadable key uses its default with one warning. |

The reply separates changed settings, postponed changes (next match or round), and refused
changes. Refused settings remain unchanged in memory; the files are not rewritten. Director
and stability settings are validated but remain restart-only. An invalid value is rejected
even in a setting whose valid change would require a restart.

Kit reload validation does not call player-dependent item factories. The built-in tool
providers declare stack limits of 64 for shuriken and smoke bombs and 1 for dash. Custom
providers can declare a safe limit with `kitItems.register(id, maxStackSize, provider)`;
the existing two-argument registration conservatively permits one item during reload
validation. Item providers still resolve at round-kit application time.

### Sounds

`sounds:` configures `room-join`, `ready`, `match-start`, `shop-buy`,
`cooldown-ready`, `enemy-spawn`, `round-end` and `hazard-warning`. Each has an
`enabled` switch, a vanilla namespaced `sound`, `volume` (0 to 1) and `pitch`
(0.5 to 2). Invalid fields warn once per load and use their bundled default.
Reload applies immediately. `room-join` plays when someone joins your room, by
`room accept`, `/narena accept` or a play portal, and reaches its members. Match cues include spectators. Purchases and cooldown
completions reach only their owner. Cooldown resets at round boundaries or leaving
are silent. `hazard-warning` is reserved
for the hazard features and currently has no caller.

## Player messages

The text the plugin shows players and admins is in `messages.yml`, saved to the plugin
folder on first start. Edit it to reword or translate the plugin, then run `/narena admin
reload`. Texts use [MiniMessage](https://docs.advntr.dev/minimessage/format.html) tags
such as `<red>` or `<bold>`, and named placeholders such as `<arena>`, `<player>` or
`<seconds>`. Player names and other typed input are inserted as plain text, so they cannot
add formatting or click actions. A key that is missing or holds a tag MiniMessage cannot
read falls back to the bundled default, with one warning in the server log per key. The
comments at the top of the file say which sections are shown as plain text, such as menu
items, kick screens and the Discord webhook. Delete the file to get the defaults back on
the next start. A few things stay outside it, as the comments at its top list: command
argument syntax; names of arenas, portals, worlds, files and settings; errors from the
system itself; and the reasons `/narena admin reload` gives for rejecting `config.yml` or
`kits.yml`, which stay in English.

## Tool resource packs

Both qualities use the NinjaArena plugin icon as their Java and Bedrock pack-list
icon. Icon updates change pack hashes: when cutting the next release,
follow [the release checklist](RELEASE_PROCESS.md#after-publication) to
refresh Java URL/SHA-1 and Bedrock filename/SHA-256 examples from verified assets.

Resource-pack delivery is off by default. Enable it only after hosting the versioned
Standard ZIP and configuring its URL, SHA-1 and UUID. Required packs disconnect a player
once on decline, failure or timeout; optional packs retain vanilla tool behaviour.
`resource-pack.required: true` is only as reliable as the pack host. Use a host you
control or a CDN; GitHub release URLs are a convenience for small or test setups.
If a client cannot reach the pack host, reconnecting usually works.
Acceptance alone never enables custom tool models. The Detailed overlay is optional and
is added only through the API, ready for the later quality menu.

See [resourcepack/README.md](resourcepack/README.md) for settings, hosting, hashes,
release pinning, the per-throw visibility rule and the optional-pack held-item limitation.
The same guide covers complete Standard and Detailed Bedrock packs, generated Geyser
mappings and the same-server Geyser-Spigot/Floodgate setup. Bedrock custom held models
require an operator assertion that Geyser enforces packs; load outcomes are not exposed
by its public API. Otherwise tools retain vanilla visuals. Bedrock flight uses snowballs.
Real-client loading, held/thrown appearance, team colours and live negotiation still
need verification. Portal curtain artwork remains deferred.

Texture quality choices are saved by authenticated account. On an offline-mode server,
set `resource-pack.java-login-gate: true` only where a login plugin (AuthMe by default)
blocks commands before login: Java accounts then count as authenticated, but whoever
holds a player name can change that name's cosmetic quality. With it off, Java players
may still choose a quality for the current connection only; it is not saved and resets
when they leave. `/narena pack status` shows which case applies.

`/ninjaarena textures` opens a native dialog screen (title, status text and Standard,
Detailed and Close buttons) by default; Bedrock players through Geyser 2.8.2 or newer see
it as a form. Set `resource-pack.texture-menu: chest` to use the chest menu instead, for
example with an older Geyser or a proxy setup where the dialog does not show. Both menus
behave the same, and `/narena pack status` reports which one is in use. You can choose
textures in the lobby, including while your room is waiting to start. Both menus are
locked during a match (including shop breaks) and while spectating.

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
without an optional landing point set by `/narena arena setwaitingroom <name>`, `tp`
lands you over the cell centre with nothing below and turns flight on for survival and
adventure players once the teleport is done (after plugins such as EssentialsX have reset
flight for the world change). The arena world keeps inventories on death, so a fall while
building loses no items; match deaths are handled by the match as described below.

When fully idle, the arena world unloads without saving. Its next use reopens it and
restores the saved arena copies before entry; active edits, players, matches, tickets
and pending teleports keep it loaded. Its hard diameter limit is 2048 blocks, and
visits and chunk-producing operations enforce that extent even while continuously
busy. Gameplay freezes redstone and piston physics; editing restores their normal
behavior. See [arena world retirement](docs/arena-world-lifecycle.md)
for save behavior, failure handling and the remaining continuous-use limitation.

## The lobby world

The first successful lobby entry shows a short introduction to tools, the shop and
arena hazards. It is remembered in player data across restarts and reloads. Use
`/narena intro` in the lobby to read it again; `texture-menu: chest` sends it in chat.

Startup also creates or loads a second void world, `ninjaarena_lobby`. Unlike the arena
world it is saved normally and never unloaded. It has the same rules: inventories are
kept on death, time stays at noon, the weather stays clear, no mobs spawn and fire,
explosions, fluids and other environmental block changes are blocked at all times.

The first time the plugin finds the lobby world without its build, it places the bundled
obsidian lobby there: a walled platform around 0, 64, 0 with a spawn, a room area, a
shop area and spots for boards. The world records that it has the build, so every later
start and every retirement of the arena world leaves the lobby as it is. The world spawn
is set to the lobby spawn.

`/narena admin lobby edit` puts an admin in lobby edit mode, taking them to the lobby
spawn if they are elsewhere. While in edit mode they may break and place blocks and
change item frames and armour stands there; nobody else can. `/narena admin lobby done`
saves the lobby world, ends edit mode and returns the admin to where they were. Leaving
the lobby world or the server also ends it. `/narena admin lobby reset` clears the lobby
area, places the bundled build again, removing every change made in edit mode, and puts
everyone in the lobby world at the spawn.

Nothing takes damage in the lobby world. Anyone who falls into its void is put back at
the lobby spawn; such a fall never kills and never counts as a death in a match, and no
death there counts in a match.

Lobby membership is a player state. A member's inventory is locked, the join restore
treats them as busy, and a member who walks or falls out of the lobby area is put back at
the spawn. Rooms and play portals bring their
players here, and the end of a match, leaving a room, match or spectating, and rejoining
after a quit from any of them lead back here (see [Rooms](#rooms)).

Neither world is a home. No snapshot is taken in either: entering an arena from the arena
or lobby world is refused, so leave it first. A restore never lands in either: a saved
spot there is skipped for the player's respawn point, and a respawn point there for the
main world's spawn. A match player teleported into the lobby world, by NinjaArena or by
any other plugin, leaves the match and is restored, as with a teleport to any other
world; only a room's own players are moved between the two worlds without leaving their
match, and a match's shop phase moves its players and spectators to the lobby world and
back without taking them out of it. Portals cannot be created in either world. A claimed
season trophy is delivered only once the player is outside both worlds and not a lobby
member.

See the [plugin compatibility audit](docs/plugin-compatibility.md) for setup and
exclusions when running NinjaArena alongside other server plugins.

## Round kits and inventory

Players arrive in the lobby with an empty inventory. Once their snapshot is saved and
they are in the lobby, their storage, armour, offhand, cursor and crafting grid are
cleared, their potion effects and fire are removed, and they are put in adventure mode.
The lobby is locked like a match: no item drops or pickups, no containers or ender
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
    amount: 1
  - slot: 2
    id: ninjaarena:smoke-bomb
    amount: 1
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
Bedrock players see the base item unless the configured Geyser integration enables held
artwork. The kit decides who gets them: `amount` is how many
each player receives per round, and leaving an entry out turns that tool off for the kit.

| Kit ID | Item | Default kit | Effect |
| :--- | :--- | :--- | :--- |
| `ninjaarena:smoke-bomb` | Snowball | 1 (unlimited) | Thrown. Where it lands, a 3-block cloud lasts 4 seconds and blinds rival players inside it. |
| `ninjaarena:shuriken` | Snowball | 1 (unlimited) | Thrown. A hit deals 2 damage and makes a rival glow for 3 seconds. |
| `ninjaarena:dash` | Feather | 1 | Right-click for a short forward burst. 8-second cooldown, shown on the item on Java and, with the Geyser mappings installed, on Bedrock (see the Bedrock note below). Not consumed. |

Bedrock shows the dash cooldown on the hotbar slot only when the Geyser mappings
are installed: they give the Bedrock dash item a cooldown category matching the one the
server sends. Mappings from before this change leave the slot without an overlay, though
dash is still refused until ready. The mapped duration is the default 8 seconds; the
server restarts the overlay with the configured time on each use. Not verified on a real
Bedrock client.

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

Each thrown tool has its own Java item cooldown group, so using a shuriken never greys
out a smoke bomb. Under each of `tools.shuriken` and `tools.smoke-bomb`:

- `cooldown-seconds`: 0-60, default `0` (no cooldown).
- `uses-per-round`: 0-64, default `0` (unlimited throws without consuming the stack).

The match action bar shows each tool's name and remaining cooldown seconds, rounded up,
or "used up this round" when its limit is reached. Used up takes priority over cooldown;
ready tools add no hint. This works for Java and Bedrock players.

A positive limit consumes one item per throw and refuses throws after that many uses in
the round. Counts refill at round start, including delayed respawns into the new round.
`kits.yml` still decides which tools are available. Give one item for unlimited use, or
match its total `amount` to the limit; a mismatch logs one warning per tool per load.
Reload changes the settings for new throws and keeps running cooldown deadlines and
throws already counted this round. Match the kit amount when changing a limit; kit
changes apply next round. Bedrock cooldown mappings for thrown tools are pending.

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

The next round returns spectators to Adventure mode at their team base; with the
[shop phase](#shop-phase) on, that happens when the shop opens, in its area. A player
still on the death screen at round start is placed and readied when they respawn. Use
`/narena leave` to leave while spectating for the lobby; leaving the lobby then returns the
saved belongings, game mode and location. Quitting holds their place for the
[rejoin grace](#rejoining-a-match); after it, their next join puts them back in the lobby.
A join restore that another plugin cancels (for example a login plugin) is retried for about a minute, and the player is told if it still fails (a player who joins dead is restored on respawn instead, without retries, a failure message or the window below). For 5 seconds after a restore, any other plugin's teleport (a plot placement, but also a spawn or hub command) is reverted once; NinjaArena's own teleports and players in a match or the lobby are never reverted; see [plugin compatibility](docs/plugin-compatibility.md).
Every exit first clears spectator mode, so an unsuccessful restore leaves the player in
Adventure mode while their snapshot remains available for a retry.

## Round feedback

During a match, the bossbar shows the round and the time left in preparation or combat.
Overrun turns it red and shows its remaining time; an unlimited Overrun keeps a full bar
without a countdown. Between rounds it shows the next-round countdown. During a shop
phase it turns green and shows the time left and how many players are ready, or the wait
for the next map. The actionbar shows each starting team's points and the number of
living enemies currently in play, including enemies carried over from an earlier round;
it is not a fixed wave total. Eliminated teammates keep seeing this feedback while
spectating.

Titles mark preparation, the start of combat and the round result. A quiet cue announces
each successful enemy spawn to that match, and a level-up sound marks each round end,
including a draw. These use standard client sounds and plain text for Java and Bedrock.
Leaving, match end and plugin disable clear the bossbar, actionbar and titles.

## Match scoring

Teams earn 5 points for a round win and 2 for killing an opposing player in the same
match. Configure these awards with `scoring.round-win-points` and
`scoring.player-kill-points`; zero disables an award and negative values become zero.
Teamkills, suicides, environmental deaths, leaving and drawn rounds award nothing. A
[disconnect during a fight](#disconnecting-during-a-fight) is a kill for the last enemy who
hurt the player, or costs their team `match.disconnect-penalty-points`.
Points decide the match standings and whether an extra round is needed. The final points
table is shown to the match's online players before they go back to the lobby.

Other features can award team points on the server thread through
`plugin.scores().addPoints(match, team, points)`, using the live `MatchView` from
`plugin.matches()`. Awards are accepted from PREP through ROUND_END and SHOP for teams
that started with players. Totals stop at `Integer.MAX_VALUE`. Once the match ends its
result keeps the final scores, further awards are refused, and a new match starts at
zero.

## Player points

Each player also earns personal points, separate from team scoring and match standings.
The action bar shows their own total alongside team scores and enemies left, followed by
any shuriken or smoke bomb cooldown or per-round limit hints. The hints use the existing
names under `tool` and the `hud.tool-cooldown`, `hud.tool-used-up`, `hud.tool-separator`
and `hud.status-with-tools` messages; `hud.status` keeps its existing placeholders.
Configure awards under `points`: `player-kill: 2` for an opposing player kill,
`enemy-kill-per-cost: 1` times the enemy's configured cost for a kill while alive during a
fight, `round-survive: 2` for surviving a round (draws included), and `round-win: 6` as a
pool shared by the winning team's players still in the match, alive or eliminated.
Integer shares are equal, with remaining points handed out in roster order. Draws award
no win pool; teamkills, suicides, environmental deaths and leaving award no kill points.
Zero disables each award and negatives become zero. Reloaded values apply to new matches.

Personal points carry over across rounds, including extra rounds, and stop at
`Integer.MAX_VALUE`. Leaving discards that player's points; match end clears the ledger.
Points are spent in the [shop phase](#shop-phase) between rounds.

## Player stats

Each player's wins, rounds won, player kills, enemy kills, deaths, best Overrun survival
and matches played are recorded all-time and per season. They are written in the
background when a match ends, to SQLite by default or to a MySQL or MariaDB database
that several servers share. Changing `stats.season` archives the current season; its
stats are kept. A missing driver or an unreachable database never stops a match:
finished matches stay queued and are retried. Configuration, the schema and the read
API for leaderboards and rewards are in [`docs/player-stats.md`](docs/player-stats.md).

Each match is stored too, with when it ended, its season, team count, winners and map
(stats schema version 3). A hand-built arena is stored by name; a generated map by the
seed it was made from and its difficulty, never by its slot name, so map stats are keyed
by seed. A match whose map was generated again between rounds has a row per map. The
match result shows each generated map's seed and difficulty, to play it again with
`/narena map set generated <seed> <difficulty>`. The schema is migrated in place on the
first start and the existing stats are kept. Upgrade every server that shares a database
together: released builds before this one refuse a version 3 database, also when they
reconnect, and then drop matches. Version 3 only adds a table, so to go back to an
older build, set `schema_version` back to 2; see
[`docs/player-stats.md`](docs/player-stats.md).
Taking a backup before an upgrade stays the safe general rule.
For weekly events and admin commands, see [Scheduled arena events](docs/arena-events.md).

## Licence

GPL-3.0. See [`LICENSE`](LICENSE).
