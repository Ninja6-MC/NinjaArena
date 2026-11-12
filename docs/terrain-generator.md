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
`SURFACE`, `CLIFF`, `PLATFORM` or `SPAWN_PAD`. The model carries a `TerrainTheme` picked
by the seed, and `ThemePalette` turns a theme and a kind into a `Material` when the side
is placed. `ThemePalette` is the only class in the package that names a Bukkit type, so
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
* Only append to `BlockKind` and `TerrainTheme`: the digest hashes ordinals, and the
  seed picks a theme by index.

## Golden digests

`TerrainGeneratorTest` pins the SHA-256 `digest()` of two fixed models and the themes of
two fixed seeds. They fail whenever the generator's output changes, which means every
seed now gives a different map. When the change is intended, run `./gradlew test --tests
'*TerrainGeneratorTest'`, copy the actual values from the failure messages into the
constants at the top of the test, and say in the pull request that existing seeds now
generate different maps. If the digest's byte layout changes, bump
`TerrainModel.DIGEST_FORMAT` as well.

## How later tasks use it

* Hazards (lava falls, rivers, gaps, collapsing ledges) are added as new `BlockKind`s
  from their own seed stream, as part of the model.
* Mirroring and rotation map `(x, z)` into the centre square's frame; the spawn and
  centre edges say which way each side faces.
* The movement solver paths from the spawn zone to the centre ledge over `SURFACE`,
  `SPAWN_PAD` and `PLATFORM` tops, using `terrainTopY` and `at`.
* Placement runs the generator off the main thread and writes the model column by
  column, turning kinds into blocks with `ThemePalette`.
