# Seeded terrain generator

`com.ninja6.ninjaarena.terrain` turns a seed and a difficulty (1 to 5) into one team's
side of a generated map. It is pure logic: no world, server, scheduler, clock or
configuration, so it runs on any thread and later tasks can build a side off the main
thread and place it afterwards. Rooms play the maps it makes in reserved slot cells;
see [Generated map lifecycle](#generated-map-lifecycle).

```java
TerrainModel side = TerrainGenerator.generate(seed, difficulty); // default size
SideDimensions size = SideDimensions.forCell(grid.cellSize());
TerrainModel fitted = TerrainGenerator.generate(seed, difficulty, size);
```

## The model

`TerrainModel` is immutable. Each cell holds a `BlockKind`: `AIR`, `ROCK`, `SOIL`,
`SURFACE`, `CLIFF`, `PLATFORM`, `SPAWN_PAD`, `LAVA_SOURCE`, `LAVA`,
`HANGING_LAVA`, `CRUMBLE` or `CRUMBLE_PLATFORM`. The model carries a `TerrainTheme`
picked by the seed, and `ThemePalette` turns a theme and a kind into a `Material` when
the side is placed. `ThemePalette` is the only class in the package that names a Bukkit type, so
the model can be compared, hashed and transformed without one.

Axes, as seen by a player at the spawn facing the centre:

* `x` runs across the side, `0` to `width - 1`, left to right.
* `z` runs from the spawn edge (`z = 0`, `spawnEdgeZ()`) to the centre-facing edge (`z =
  length - 1`, `centreEdgeZ()`).
* `y` is the height above the model floor, `0` to `height - 1`. Placement chooses the
  world height of `y = 0`.

The first `spawnDepth()` rows are a flat spawn zone at `spawnSurfaceY()` with a
`SPAWN_PAD` top and open sky. The last `centreDepth()` rows are a flat ledge at
`centreSurfaceY()` where the side meets the centre. Ravines, overhangs and platforms
stay out of both, and the terrain eases into them over `blendDepth()` rows. Rows `y = 0`
and `y = 1` are always solid; floating platforms keep at least two blocks of air above
the ground.

Overhang hollows are carved only above the lowest ground within a few blocks, so most of
them open sideways under a lip, but some stay sealed air pockets with no opening. Air
below a column's `terrainTopY` is therefore not necessarily reachable: the movement
solver and placement must not assume it is, and should flood-fill from the open sky if
they need to know.

Cells are stored one byte each in a single array, in column order (`(z * width + x) *
height + y`).

## Default size

A generated map is a centre square `width` blocks across with one side against each edge
a team uses: two opposite edges for 2 teams, all four for 4 teams. A side no wider than
the centre cannot overlap a turned neighbour, so the map spans `width + 2 * length`
blocks.

`SideDimensions.forCell(cellSize)` sizes a side for an `ArenaGrid` cell: three eighths
of the cell wide and long enough to leave one thirty-second of the cell spare at each
end.

| Cell | Side (width x length x height) | Map span | Model memory |
| :--- | :--- | :--- | :--- |
| 256 (default) | 96 x 72 x 64 | 240 | 432 KiB (442,368 cells) |
| 32 (minimum) | 8 x 11 x 64 | 30 | 5.5 KiB |
| 512 and above | 192 x 144 x 64 (capped) | 480 | 1.7 MiB |

Generated maps in the slot cells use at most the size for a 256-block cell unless
`stability.placement.max-map-size` is raised; see [Size limit](#size-limit).

The height of 64 leaves the ground between `y = 2` and `y = 51`, around a base level of
24, with 12 blocks above it for platforms. That is a sixth of the 384-block world
height.

## Full-map layout

`new ArenaLayout(side, teamCount)` is an immutable view for 2 or 4 teams; other counts
are refused. It retains the original side and transforms indices rather than copying
cells. Every kind passes through unchanged, including future hazard kinds. The centre
is a flat square at `side.centreSurfaceY()`, extending the centre ledge's column of
rock, soil, surface and air. Centre features can later replace this centre lookup.

Map coordinates start at `(0, 0, 0)` in the overall bounding box. Team numbers start at
1. Team 1 is north of the centre facing positive z. For two teams, team 2 is south facing
negative z, a 180-degree turn rather than a reflection. Turning preserves the original
left-to-right order for each player facing the centre, including asymmetric obstacles.
For four teams, teams 2, 3 and 4 are east, south and west, facing negative x, negative z
and positive x respectively: successive clockwise quarter-turns in the x/z plane.

* `toMap(team, x, y, z)` and `toSide(team, mapX, y, mapZ)` are inverse integer
  transforms. They preserve y and also accept coordinates outside the side, so a solver
  can step from its centre edge into the square without redoing the frame arithmetic.
* `sideBounds(team)` covers the whole side volume. `spawnArea(team)` covers the flat
  spawn pad's surface blocks; player positions above them are chosen by the later solver.
  `facing(team)` gives a unit `(dx, dz)` towards the centre.
* `bounds()` and `centreBounds()` use inclusive minima and exclusive maxima.
  `width()`, `length()` and `height()` give the overall size; `side()` supplies the
  original model, theme and flat-zone heights. `at(mapX, y, mapZ)` returns air outside
  every part, including the empty corners and coordinates outside the height.

At the default cell size, the two-team bounding box is 96 x 240 x 64 (1,474,560
cells); the four-team bounding box is 240 x 240 x 64 (3,686,400 cells), with empty
corners. Both retain only the side's 432 KiB cell array (442,368 bytes), plus constant
object metadata. Neither allocates centre cells or per-team cell arrays. All grid-sized
layouts fit their cell, with the existing sizing margin.

## Features and difficulty

Each feature is a separate pass over the side:

| Feature | How | Difficulty 1 to 5 |
| :--- | :--- | :--- |
| Height | Four-octave value noise around the base level | Range plus or minus 6 to 18 |
| Cliffs | Height pulled towards terraces where a second noise allows | Step 3 to 7, pull up to 0.3 to 1.0 |
| Ravines | V-shaped cuts where a ridge noise is near zero | Wider, up to 13 to 33 deep |
| Overhangs | 3D noise hollows under a two-block roof, only beside a drop | Deeper band, more hollows |
| Platforms | Floating ellipses, radius 2 to 4, 1 or 2 thick | 3 to 11 at default size |

Averaged over sixteen seeds at the default size (deep columns have their ground 10 or
more blocks under the base level):

| Difficulty | Deep columns | Air under overhangs | Platform cells | Height variance |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 105 | 364 | 80 | 4.4 |
| 3 | 729 | 1,619 | 160 | 29.8 |
| 5 | 1,488 | 3,585 | 260 | 62.7 |

Generating a default side takes about 3.2 to 4.1 ms after warm-up across difficulties
1 to 5 on a desktop CPU (median of three 320-side batches per difficulty over sixteen
fixed seeds). The hazard pass adds per-cell random draws in the ceiling and crumble
scans. `TerrainGeneratorTest` checks that feature measures grow with difficulty; it does
not assert times.

## Hazard layout

After labelling the terrain, hazards run in fixed order: gaps, rivers, ceiling lava,
then collapsing ledges. They use new streams, leaving streams 1 (theme), 2 (height),
3 (cliffs), 4 (ravines), 5 (overhangs) and 6 (platforms) unchanged. Ceiling and river
candidates read a snapshot of the original geometry. Existing hazards take precedence;
later passes do not overwrite them. Every candidate draws a fixed number of values,
including skipped candidates. Both flat zones and their entire blend rows stay clear.
This leaves a clear apron outside spawn, but does not certify crossability; #159 must
check that independently.

| Hazard | Stream | Placement and scaling from difficulty 1 to 5 |
| :--- | ---: | :--- |
| Falls | 7 | 1% to 5% per ceiling candidate; fill air down to the first obstacle |
| Rivers | 8 | 0.2% to 1% per low column; runs up to 4 to 12 cells long |
| Hanging lava | 9 | 0.8% to 4% per ceiling candidate with a solid roof above |
| Gaps | 10 | 3 to 15 attempts at default size; span 3–6 to 3–10, breadth 1 to 1–3 |
| Collapsing rims | 11 | 4% to 20% per exposed ground or platform rim cell |

Rivers occupy the air immediately above low ground (at least three blocks below the
base), following consecutive rows whose floors differ by at most one block. Falls
start directly below a ceiling with at least three air cells underneath it, including
platform undersides and overhang lips. Hanging lava replaces the underside of a
ceiling, retaining a solid block above. As with overhangs, some ceiling candidates may
lie in sealed hollows; consumers must check reachability when they need it. Rims are
solid top cells beside air one block below. Gaps cut short strips across the ground,
targeting depths of 4 to 8 blocks, clipped at `y = 2` or extended through an existing
hollow to its solid floor. They are ordinary `AIR`: placement and the solver already
inspect air and require no separate gap marker.
The exposed floor is labelled `SURFACE`; no supporting blocks are added to hollows.

Totals over sixteen seeds `0 * 7919` through `15 * 7919` at the default size:

| Difficulty | Sources | Lava | Hanging | Crumble | Platform crumble | Gap cells |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 14 | 88 | 14 | 277 | 29 | 547 |
| 2 | 62 | 625 | 49 | 1,085 | 85 | 2,312 |
| 3 | 185 | 1,658 | 126 | 2,085 | 166 | 4,199 |
| 4 | 327 | 2,973 | 227 | 3,194 | 303 | 7,993 |
| 5 | 572 | 4,994 | 470 | 4,534 | 487 | 10,878 |

All lava kinds have `solid() == false`; both collapse kinds are solid until activated.
`terrainTopY` ignores lava, platforms and collapsing platform rims, so it still reports
solid ground. `CRUMBLE_PLATFORM` preserves the distinction between floating platforms
and ground when a rim changes kind. A lava-covered floor can therefore have a reported
top below lava: the solver must inspect `at`, not treat the height alone as safe.

Placement (#160) must preserve these distinctions while mapping all three lava kinds
to lava and both collapse kinds to the theme's distinct tell block. Flow and collapse
control (#161) must register `LAVA_SOURCE` as a fall head, `LAVA` as the intended river
or fall footprint, `HANGING_LAVA` as a held ceiling cell and both collapse kinds as
removable ledges. Held lava needs containment until release; no kind authorizes vanilla
fluid physics on placement. All flow and collapse activation, bounds, limits and cleanup
belong to #161. Growth and warning timing belong to #162. This package touches no world.

The two generator golden digests changed deliberately for these passes. The digest byte
layout is unchanged, so `TerrainModel.DIGEST_FORMAT` remains 1. Tests also retain both
old digests for the pre-hazard passes to pin streams 1 through 6.

## Movement solver and challenge check

`MovementSolver.solve(side, movement)` checks one side and `MapSearch.search(seed,
difficulty, teamCount)` turns a requested seed into an accepted map. Both are pure, like
the generator: no world, server, scheduler, clock or configuration, safe on any thread,
and the same input always gives the same answer.

```java
MapSearch.Outcome outcome = MapSearch.search(seed, difficulty, teamCount);
if (outcome instanceof MapSearch.Accepted map) {
    // map.seed(), map.layout(), map.points(), map.report()
}
```

### Movement model

A standing cell is the block a player's feet are in: `AIR`, with `AIR` above it for the
head and a solid kind below it. Lava kinds are never stood on, stood in or passed
through. `CRUMBLE` and `CRUMBLE_PLATFORM` are solid until they collapse, so they can be
stood on, and they count as hazards. Moves go in the four horizontal directions.
`MovementModel` holds the limits so they can be tuned; `MovementModel.DEFAULT` is
cautious vanilla movement:

| Move | Default |
| :--- | :--- |
| Walk to the next column at the same height | always |
| Climb onto the next column | up to `jumpHeight` 1 block higher |
| Drop off an edge into the next column | at most `safeFall` 3 blocks lower |
| Jump a gap, landing level or lower | up to `maxGap` 3 columns, at most 3 lower |
| Jump a gap, landing higher | up to `maxRisingGap` 2 columns, at most 1 higher |

A climb needs air above the take-off for the rise. A jump starts only where the next
column cannot be walked onto, and needs three blocks of air (`JUMP_CLEARANCE`) from each
column's feet up to two blocks above the higher end's head, over the take-off, the gap
and the landing. Drops fall through air only. Flood-filling from the team spawn over
these moves is what makes a cell reachable, so sealed overhang hollows are standable
but never reachable.

### Crossing and the challenge measures

A side is crossable when the centre ledge can be reached from the team spawn. It must
also be crossable without relying on any crumbling block. A second search reads every
`CRUMBLE` and `CRUMBLE_PLATFORM` cell as blocked, neither solid nor air, so its route
never stands on one, walks or drops through one, or counts one as headroom or jump
clearance. A collapse only turns crumbling cells into air, and every move of that route
depends only on other cells, so the route still works whichever crumbling blocks have
collapsed, in any order or combination. Checking only "all intact" and "all collapsed"
is not enough: a crumbling block can be a floor and also part of a step or wall, and
removing just one can strand a team. The report's `crossableWithoutCrumble` holds the
result, and `NEEDS_CRUMBLE` rejects a side without it. Over 200 seeds per difficulty
this rejects 3 more sides at difficulty 4 and 7 more at difficulty 5 than the intact
check alone.

Every team's side is the same model turned about the centre, and turns map the four move
directions onto each other, so one side answers for every team. Outside a side the full
map holds only air (beyond the spawn edge and in the empty corners of four teams) or the
centre square (beyond the centre edge), so no route leaves a side except into the
centre. `MovementSolver.crossLayout(layout, team, movement)` runs the same search over
a whole `ArenaLayout`; tests compare it with the one-side answer for every team of 2 and
4.

The challenge is measured on the direct route: the shortest route by horizontal
distance, and of those the one with the fewest jumps, then the least climb, then the
fewest hazards. Its measures are what a player heading straight for the centre cannot
avoid. A least-over-every-route measure was tried first and is 0 on most seeds at every
difficulty, because a 96-block side always has a way round.

* **Forced jumps:** jumps over a gap on the direct route.
* **Climb:** blocks the direct route climbs, summed over climbs and rising jumps.
* **Hazard exposure:** lava and crumbling blocks in the 3 by 3 columns around each
  standing cell of the direct route, from the floor to the block above the head, summed
  over the route.

`CrossingReport` carries these with the spawn, crossing and enemy spawn results;
`problems(minimums)` lists why a side is rejected: `SPAWN_UNSAFE`, `NOT_CROSSABLE`,
`NEEDS_CRUMBLE`, `NO_ENEMY_SPAWNS`, `TOO_FEW_JUMPS`,
`TOO_LITTLE_CLIMB` or `TOO_FEW_HAZARDS`.

### Minimums

`ChallengeMinimums.forDifficulty(difficulty)` gives initial values, picked at or below
about the 20th percentile of what the generator gives over 300 seeds per difficulty, so
most seeds pass. Task 7.12 (#166) tunes them by play.

| Difficulty | Forced jumps | Climb | Hazard exposure |
| ---: | ---: | ---: | ---: |
| 1 | 0 | 1 | 0 |
| 2 | 0 | 2 | 0 |
| 3 | 0 | 2 | 0 |
| 4 | 0 | 2 | 1 |
| 5 | 0 | 2 | 2 |

Forced jumps stay at 0 for now: about four in ten direct routes need no jump at every
difficulty, so any minimum would reject them; #166 decides whether the generator should
force more gaps. Over the crossable sides of the same seeds, the direct route's median
climb rises from 3 at difficulty 1 to about 8 at difficulty 5, and its median hazard
exposure from 0 to about 12.

### Spawn and enemy points

`solver.points(layout)` gives `MapPoints` in the layout's map frame: block centres at
feet height with a Minecraft yaw facing the centre (0 is positive z), assuming map x and
z run along world x and z. Placement (#160) adds the map's world origin, for example
with `Point.toArenaPoint(originX, originY, originZ)`.

* **Team spawns**, team 1 first: the safe pad cell nearest the middle of the spawn
  zone, turned to each team. Safe means `SPAWN_PAD` below, two blocks of air, and no
  hazard in the 3 by 3 columns around it. `checkSpawn` says why a cell is not safe
  (`IN_LAVA`, `IN_WALL`, `NOT_ON_SPAWN_PAD`, `BESIDE_HAZARD`); a side with no safe pad
  cell is rejected.
* **Enemy spawns:** `ENEMY_SPAWNS_PER_SIDE` (3) standing cells per side, reachable from
  the team spawn, beyond the spawn zone, at least `ENEMY_SPAWN_DISTANCE` (16) blocks
  from the team spawn, not on a hazard and with none around them. Each is the candidate
  farthest from the spawn and the earlier picks. The same cells are turned to every
  team, so 2 teams get 6 and 4 teams get 12, and every team faces the same enemy layout.
* **Waiting room:** the middle block of the centre square, standing on its surface with
  yaw 0. The centre square is flat, open to the sky and has no hazards, so the point is
  always standable, and an `Arena` built from the points passes `ArenaValidator`.

### Seed search and fallback

`MapSearch.search` tries the requested seed, then up to `retryLimit` derived seeds
(default `DEFAULT_RETRY_LIMIT` 8, at most 64), and returns `Accepted` (the attempt, the
seed it used, the layout, the points and the report) or `Failed` (one report per
attempt). Attempt `n` uses `MapSearch.attemptSeed(seed, n)`: `SplitMix64.derive(seed,
1000 + n)`, the seed itself for attempt 0. An accepted map's `seed()` is the room's last
good seed: searching it again accepts it on attempt 0.

Over 200 seeds per difficulty with the defaults (the same for 2 and 4 teams, since one
side decides):

| Difficulty | First seed accepted | Accepted within the limit | Attempts per search |
| ---: | ---: | ---: | ---: |
| 1 | 100% | 100% | 1.00 |
| 2 | 95.5% | 100% | 1.05 |
| 3 | 80.5% | 100% | 1.26 |
| 4 | 61.5% | 100% | 1.62 |
| 5 | 58.0% | 100% | 1.74 |

When a search fails, `MapSearch.fallback(lastGoodSeed)` decides what the room plays:
`LAST_GOOD_SEED` with that seed if the room has one, else `HAND_BUILT`. Each carries the
`messages.yml` key that tells the room's players: `room.map-fallback-seed` (`<seed>`,
`<attempts>`, `<last>`) or `room.map-fallback-hand-built` (`<seed>`, `<attempts>`,
`<arena>`). This package provides the decision and the messages; rooms apply them, as
described under [Generated map lifecycle](#generated-map-lifecycle).

Generating and checking a default side takes about 12 ms (median over 30 seeds at
difficulty 5 after warm-up, with points for 2 or 4 teams), of which the solver is about
8 ms; a search averages under two attempts. `MapSearchTest` prints these figures and
does not assert times.

### What later tasks do

* Rooms run the search off the main thread when they need a map, remember the accepted
  seed as the room's last good seed, and on `Failed` apply `MapSearch.fallback` and send
  its message ([Generated map lifecycle](#generated-map-lifecycle)).
* Placement writes `map.layout()` within a time budget per tick, shared between the
  slot cells ([Placement](#placement)); the map's points are already turned into world
  locations.
* **#161** and **#162** may collapse any crumbling blocks, in any order, at any time:
  an accepted map keeps a route from every team spawn to the centre that never relies
  on one, so they need no path check of their own. That guarantee covers crumbling
  blocks only; a hazard that adds lava or removes any other block must be checked again.
* **#166** tunes `MovementModel` and `ChallengeMinimums` by play.

## Determinism rules

The same seed, difficulty and dimensions must give a byte-identical model on every run,
thread and JVM. Later tasks that add to the model (hazards, mirroring, path checks) keep
to the same rules:

* Draw random numbers only from `SplitMix64` and noise only from `ValueNoise`, both
  written out in this package. Never use `java.util.Random`, `ThreadLocalRandom`,
  `RandomGenerator`, `Math.random()`, the clock or anything else whose output is a JDK
  or library choice.
* Give each feature its own stream: `SplitMix64.derive(seed, STREAM_...)` with a new
  constant. Never renumber an existing one, and draw the same count of numbers per item
  whether it is placed or skipped, so one feature's changes never shift another's.
* Use only `+`, `-`, `*`, `/`, `Math.floor`, `Math.min`, `Math.max` and `Math.abs` on
  doubles. Avoid `Math.sin`, `Math.pow`, `Math.exp` and the like, which may differ
  between platforms; `StrictMath` is the fallback if one is ever needed.
* Never let the iteration order of a `HashMap` or `HashSet`, or a parallel stream,
  decide what is generated.
* Only append to `BlockKind`, never reorder or remove a constant: the digest hashes
  ordinals. Appending a kind leaves existing models unchanged until a pass uses it.
* Never reorder or remove a `TerrainTheme`. Even appending one changes existing maps:
  `themeFor` picks a theme by drawing an index below the number of themes, so a new
  theme changes which theme many existing seeds get. Treat it as an intended output
  change: update the golden theme assertions and any golden digest whose theme moved
  (the theme ordinal is in the digest header), as described under Golden digests.

## Golden digests

`TerrainGeneratorTest` pins the SHA-256 `digest()` of two fixed models and the themes of
two fixed seeds. They fail whenever the generator's output changes, which means every
seed now gives a different map. When the change is intended, run `./gradlew test --tests
'*TerrainGeneratorTest'`, copy the actual values from the failure messages into the
digest constants at the top of the test and the expected themes in
`themesForFixedSeedsAreUnchanged`, and say in the pull request that existing seeds now
generate different maps. Adding a theme is such a change even when nothing else moves.
If the digest's byte layout changes, bump `TerrainModel.DIGEST_FORMAT` as well.

## How later tasks use it

* Hazards are already part of the model, with kinds and control responsibilities above.
* Mirroring and rotation map `(x, z)` into the centre square's frame; the spawn and
  centre edges say which way each side faces.
* The movement solver checks every side from its spawn to the centre ledge and places
  the spawns, as described under Movement solver and challenge check.
* Placement runs the generator off the main thread and writes the model column by
  column, turning kinds into blocks with `ThemePalette`.

## Generated map lifecycle

`com.ninja6.ninjaarena.map` plays generated maps in the arena world. It is the only part
of the plugin that touches the world for them; this package stays pure.

### Slot cells

`SlotCells` reserves `arenas.max-active` cells of the `ArenaGrid`, one per arena slot,
farthest from the origin first (the reverse of `cellsNearestFirst()`, which `arena
create` fills). A cell that holds a hand-built arena is passed over, and the next free
one is reserved instead, with a log line. The choice depends only on the grid, the count
and the hand-built arenas' cells, so it is the same after every restart while the config
and `arenas.yml` are unchanged. A reload keeps the cells already reserved: a higher
count adds cells after them, a lower one drops the last ones, but never a cell a map
still uses, which stays reserved until it is free. `ArenaRegistry.create` skips the
reserved cells and refuses one named by hand (`arena.error.cell-reserved`).

Within its cell a map uses a fixed region: centred, as wide and long as the four-team
map of `GeneratedMaps.mapSize()` (`width + 2 * length`), and the side's height from
`GeneratedMaps.FLOOR_Y` (32). `mapSize()` is `SideDimensions.forCell` of the cell size,
or of `stability.placement.max-map-size` when that is smaller ([Size
limit](#size-limit)). A two-team map is centred in the same region. Nothing outside the
region is written or cleared.

### Lifecycle

`GeneratedMaps` holds one state per cell: free, clearing, preparing, failed, ready or
held. Everything happens on the server thread except the search.

1. **Lease.** A room on generated maps asks for an arena slot under its map's name
   (`generated:<n>`, which no hand-built name can match). The slot is granted only while
   a cell is free and no arena is in edit mode.
2. **Prepare.** `prepare(name, request, done)` takes a free cell, puts plugin chunk
   tickets on the region's chunks before any block is written, clears the region and
   meanwhile runs `MapSearch` on an async executor. A chunk unloaded since its last use
   comes back as the world save had it, so the clear is never skipped.
3. **Place.** An accepted map is handed to the `CellWriter`. Once placed it is an
   `Arena`: the cell, bounds from the layout's box at the map's origin, team spawns,
   enemy spawns and the waiting room from `MapPoints.Point.toArenaPoint`. It passes
   `ArenaValidator`, and `done` gets `Ready` with the accepted seed, which the room
   keeps as its last good seed (with its difficulty), in memory only.
4. **Fail.** A rejected search gives `Failed`; the cell stays the map's. The room
   applies `MapSearch.fallback`: its last good seed, searched again at the difficulty it
   was good at, in the same cell, else the hand-built arena a new room would get, after
   freeing the cell. Its players get `room.map-fallback-seed` or
   `room.map-fallback-hand-built`.
5. **Play.** `ArenaMaps` is the `MapSource` in front of `ArenaRegistry`: generated names
   go to `GeneratedMaps`, every other name to the registry. The match engine, death
   handling, spectating, the enemy spawner, enemy cleanup and the tools look maps up
   through it. `hold` and `release` mark the map as in use by a match; `reset` does
   nothing, since a generated map is never restored from a copy. `MatchService` reads
   the team spawns again at every round's PREP, so a map placed again between rounds can
   move them.
6. **Reset.** When the match closes, the room frees the map: `free` cancels any work left,
   clears the region and takes the tickets off, and the cell is free again. The room
   then asks for a slot again and its map is generated and placed anew for the next
   match. A room that closes, changes map or changes between 2 and 4 teams frees its map
   the same way.

Generated maps are never written to `arenas.yml` (`ArenaCodec.encode` refuses a generated
name). Every slot cell is cleared when it is reserved on startup or by a reload, and again
when the arena world reopens after a retire (`ArenaWorld.afterReopen`), so nothing a world
save kept from an earlier run survives. These sweeps clear the cell's full-size region
([Size limit](#size-limit)). A cell being cleared counts as in use. While
`arenas.yml` cannot be read, no cell is reserved or cleared, since the registry cannot
say which cells hold hand-built arenas.

While any cell is in use, `ArenaRegistry` refuses `create` and `startEdit`
(`arena.error.edit-while-generated`), as it does during a match: edit mode turns
protection off across the whole arena world, and a placed map may hold lava. The lock
works both ways, since a generated slot is not granted while an arena is in edit mode.

`RoomService.playGenerated(room, seed, difficulty)` plays a seed set by hand and
`RoomService.playSeedModes(room)` the seeds of the [seed modes](#seed-modes); a new room
uses the latter when no hand-built arena is ready. `/narena map set generated`, `map
next`, `map reroll` and `admin generate` call them (README, *Seed modes*).

### Seed modes

`SeedSettings` reads the `maps` section: `maps.default`, the `maps.slots.<n>` overrides
merged over it, and `maps.seed-sharing`. It is validated at startup (an invalid section
falls back to the defaults with a warning) and on reload (an invalid one rejects the
reload with its line). Durations go through `Durations.parse`: amounts with units `d`,
`h`, `m` and `s`, largest first, or `0`.

`SeedPolicy` is pure: it is given the settings, a clock, a source of random seeds and
the seeds active in other slots, and keeps per slot (one shared state under `shared`)
the rotation place, when it last moved, the random seed in force and any request. A slot
asks it once per generation, with `next(slot, activeElsewhere, request)`:

- `static` gives `seed`; under `distinct` a static seed that an earlier slot also has
  falls back to random (`warnings` lists those at startup and on reload), and one that
  is active elsewhere gets a random seed for that map, with a log line.
- `rotation` starts slot `n` at seed `n` of the list under `distinct` and at the first
  under `shared`, moves one step once `rotate-every` has passed (or at every generation
  for `0`) and takes the first seed from its place on that is not active elsewhere;
  with none free it gives a random seed and logs it.
- `random` keeps its seed until `reroll-every` has passed, the seed becomes active
  elsewhere, or a request asks for another.
- `Request.NEXT` moves a rotation on one step and rerolls a random slot; `REROLL` gives
  a random seed for that map, which a random slot keeps.

A random seed is drawn again while it is active elsewhere. Timers and requests are only
read by `next`, so nothing changes a placed map. `preview(slot, activeElsewhere)` tells
what `next` would give, without moving anything, when that is known: the hook for
showing the next map before it is generated. `RoomService` keeps the active seeds: the
seed each map placed or being prepared was asked for, each next round's seed, and the
seeds rooms set by hand. A seed set by hand that collides under `distinct` is refused
with `room.map-seed-in-use`.

The rotation place and random seeds are held in memory only. Nothing needs them across a
restart: every slot cell is cleared on startup, so no map from before is still played,
and a rotation simply starts again.

### Between rounds

With `regenerate: round` for its slot, a room's map is generated again in each shop
break, without touching the map being played:

1. `RoomService.onPhase` sees the match go to SHOP and asks the policy for the slot's
   next seed. `GeneratedMaps.prepareReplacement` clears, searches and places it in
   another free cell under an internal name. `RoomService` reserves one spare cell
   beyond `arenas.max-active` while any slot regenerates between rounds; with no cell
   free, the round keeps the current map.
2. Meanwhile `RoomService.mapReady`, set as `MatchService.setMapReady`, answers false,
   so the shop waits for the map up to `shop.map-wait-limit-seconds` past its time.
3. A `Ready` replacement while the match is still in SHOP is committed:
   `commitReplacement` gives it the map's name, the held state and the old map's slot
   number (`SlotCells.swap`), and clears the old cell. The next PREP reads the new team
   spawns, the enemy spawner (`MobSpawner.Following`) and the spectators' seats follow
   the new map, and `GeneratedMaps.replacementProgress` tells how far it had got.
4. A `Failed` replacement is dropped and, by `MapSearch.fallback`, the round plays the
   last good seed: the map already placed (`room.map-fallback-seed`). One that is not
   placed when the wait runs out is dropped as the match leaves SHOP
   (`shop.map-replay`). Players are never moved to a map that is not fully placed.

### The placement seam

`CellWriter` is the seam between the lifecycle and the world:

```java
Work place(GridCell cell, ArenaLayout layout, int originX, int originY, int originZ);
Work clear(GridCell cell, ArenaBounds region);
void close();
```

`place` writes every non-air block of the layout with `ThemePalette`; air is not
written, since the region is always cleared first. `clear` sets every block of the
region to air. `Work` is a `CompletableFuture<Void>` that also counts the blocks visited
(`done()`) out of the blocks in its box (`total()`). Futures complete on the server
thread; cancelling one drops the work left, and `GeneratedMaps` clears the region
afterwards. Within a cell, work runs in the order it was asked for, so a clear always
finishes before the placement asked after it; work in different cells runs side by side.
`close()` cancels every queued job.

## Placement

`BudgetedCellWriter` is the `CellWriter` on the server. It writes through a
`BlockAccess` that sets blocks without physics.

### Time budget

Every server tick with work, the writer asks `PlacementBudget` how many nanoseconds it
may use and measures its own work with `System.nanoTime` (a fake clock in tests). It
works in slices of at most 1,024 blocks, each sized from the measured time per block so
that it fits in what is left of the budget, and stops when no further block fits. The
first slice ever, before anything is measured, is 16 blocks. Every block visited counts,
written or not.

The budget follows the same tick-time guard as enemy spawning. `TickTimeGuard` holds the
guard state for both: it reads `Bukkit#getAverageTickTime` at most once per tick, with
the `stability` thresholds and their hysteresis, and both the gate and `PlacementBudget`
read it. There is one notion of a slow server, not two.

| Guard | Placement budget |
| :--- | :--- |
| `NORMAL` | `stability.placement.budget-ms` (10 ms) |
| `PAUSED` (above `pause-spawning-above-mspt`) | `slow-budget-ms` (1 ms; 0 pauses) |
| `TIER_DROPPED` (above `drop-tier-above-mspt`) | `slow-budget-ms` |
| Below `resume-below-mspt` again | Climbs back over `recovery-seconds` (10 s) |

Once the guard lets go (`NORMAL`, or `RECOVERING` after a tier drop), the budget climbs
in a straight line from the slow budget to the full one. A guard that holds back again
during the climb drops the budget to the slow one, and the climb starts over. The guard
lets go only below `resume-below-mspt`, so the budget does not flap around a threshold.

The budget is shared by every slot cell, not given to each: four slots generating at
once take no more server time per tick than one. At the defaults and the default cell, a
clear and a four-team placement visit about 7.4 million blocks; at an assumed cost of
half a microsecond per block that is about 3.7 s of server time, so about 18 s of 10 ms
ticks for one slot and about 74 s when four generate together. The real cost per block
depends on the host and is not measured here.

### Fair turns between cells

Each cell has its own queue. Cells with work take turns a slice at a time, so four slots
generating at once all move on together and finish within a tick of each other. A turn
the budget cuts short is finished first on the next tick, so the cell that happened to
be last in a tick does not fall behind.

### Size limit

`stability.placement.max-map-size` (256; 32 to 4096) is the largest grid cell a
generated map is sized for. In a larger `arena-grid.cell-size`,
`GeneratedMaps.mapSize()` is `SideDimensions.forCell(max-map-size)`: the generator is
asked for that smaller side, and the region a map is cleared and placed in is that map's
span, centred in the cell.

The limit can be lowered across a restart, and the world save keeps a map placed before
it. So the sweeps when a cell is reserved and when the arena world reopens clear
`GeneratedMaps.sweepRegion(cell)`, the region for the cell's full size whatever the
limit, which holds every map any limit could have placed. Between matches only the map's
own region is cleared, since the limit cannot change while a cell is in use. Progress
counts only the map's own clear and placement, not a sweep.

The limit caps the map's footprint rather than counting blocks per map, for three
reasons:

* The size of a map is fixed before it is generated: `SideDimensions.forCell` of the
  cell size, with the height always 64. A cap on that input bounds every clear and every
  placement up front, so there is nothing for a room to refuse or fall back from, and no
  map is generated only to be thrown away.
* The cost that matters most is the clear, which visits the whole region whatever the
  map holds. A cap on blocks written would not shrink it; a smaller footprint does.
* A size in grid-cell terms is what server owners already set with
  `arena-grid.cell-size`, so the limit reads the same way.

The default of 256 leaves the default cell unchanged (a 96 x 72 x 64 side, a 240-block
map, about 3.7 million blocks to clear and as many to place for four teams) and caps
larger cells, which without it reach a 480-block map with four times the blocks. A
shared host can set 128 (a 48 x 36 side, a 120-block map, about 0.9 million blocks each
way). The limit is restart-only, like the rest of `stability`, so a region never moves
under a map in use; `GeneratedMaps.setMaxMapSize` refuses a change while any cell is in
use.

### Progress

`GeneratedMaps.progress(name)` gives a `Progress(done, total)` for a map being prepared:
the blocks the writer has visited clearing the region and then placing the map, out of
both together. The placement's size is known before the search finishes (it is the
layout box for the map size and team count), so the total never changes while the map is
prepared, and `fraction()` only goes up. A placed map reports complete; a map with no
cell, or whose last try failed, reports nothing until it tries again. It is what the
room's boss bar (#164) is to show.
