# Seeded terrain generator

`com.ninja6.ninjaarena.terrain` turns a seed and a difficulty (1 to 5) into one team's
side of a generated map. It is pure logic: no world, server, scheduler, clock or
configuration, so it runs on any thread and later tasks can build a side off the main
thread and place it afterwards. It is not wired into rooms or matches yet.

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

Generating a default side takes about 2 ms per difficulty after warm-up, and about 30 ms
for the first call in a JVM, on a 6-core desktop CPU. `TerrainGeneratorTest` checks that
these measures grow with difficulty; it does not assert times.

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
* The movement solver paths from the spawn zone to the centre ledge over `SURFACE`,
  `SPAWN_PAD`, `PLATFORM`, `CRUMBLE` and `CRUMBLE_PLATFORM` tops, using
  `terrainTopY` and `at` and checking adjacent hazards.
* Placement runs the generator off the main thread and writes the model column by
  column, turning kinds into blocks with `ThemePalette`.
