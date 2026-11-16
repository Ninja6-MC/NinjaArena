# Stability soak and arena lifecycle verification

The 600-second acceptance soak passed on Paper 26.3 build 226 with production
candidate `f4556da`. Its plugin SHA-256 was
`afa795cc3e30608d39141e4fab4bc38dda3f78b5f5d4e9e7c9c2d27576a6ecda`.
Separate small and large lifecycle fixtures also passed. These results establish
idle-world retirement and the tested restoration paths, not a bound on chunk
retention while the arena world remains continuously busy. They left issue #50
open for the containment and residency work described below.

## Workload and environment

The local runs used Paper 26.3 build 226, Temurin 25.0.4+7, Node.js 24.11.1,
Mineflayer 4.39.0, ViaVersion and ViaBackwards 5.12.1, and protocol 1.21.11.
Windows 11 build 26300 reported 12 logical processors; the server was limited to
two. The acceptance server used a 256 to 768 MiB heap. Eight clients shared one
Node process with a 384 MiB old-space limit. Builds did not run alongside Paper.

Four independent 64 by 64 arenas each hosted two stationary survival clients.
Production spawning, AI, targeting, snapshots, restoration and teardown remained
active. Resistance V and saturation kept clients alive; threat base 200 filled the
unchanged caps of 25 enemies per match and 80 globally, with four spawns per tick.
Two seconds each of PREP and COMBAT preceded OVERRUN. After 60 seconds of warmup,
the harness measured 600 seconds: 2400 match samples and 12000 server ticks.
This workload does not measure player combat, ordinary survival difficulty or
remote network latency.

## Acceptance results

Every arena supplied 600 measured samples at intensity 1. All four director loops
remained active and every measured match sample was in OVERRUN.

| Arena | Live enemies: minimum / median / maximum |
| :--- | ---: |
| soak0 | 23 / 25 / 25 |
| soak1 | 22 / 25 / 25 |
| soak2 | 24 / 25 / 25 |
| soak3 | 4 / 5 / 5 |

The global population was 77 / 80 / 80. The shared cap accounts for the smaller
fourth arena. Minimum player health was 20; minimum reported one-minute TPS was
19.995.

| Tick duration, 12000 measured ticks | Milliseconds |
| :--- | ---: |
| Mean | 2.973 |
| Median | 2.662 |
| p95 | 5.267 |
| p99 | 7.644 |
| Maximum | 41.344 |

No measured tick exceeded 50 ms. The measured loaded set stayed at 1224 full chunks
and 5112 holders. This stable set for repeated work in the same four arenas is not
a general limit for new arenas or continuous world activity.

After normal match close, the arena world became absent from both Bukkit and the
manager's current-world reference. The retired world's weak reference cleared
after four bounded collection attempts. Heap usage fell from 474587512 bytes at
match close to 366155440 bytes at the teardown observation. Match close took
603 ms; retirement was observed after 49848 ms, including the idle wait, and unload
completion was observed 151 ms after the unload event. Shutdown took 2.414 seconds.
No live-world accessor was used to inspect an already retired world. Restart
verification passed and all four saved marker positions remained air.

## Save evidence and its limits

Setup shutdown and settled pre-match saves differ: 64 chunk NBT payloads changed
during bootstrap, with no semantic block or entity changes. Match activity is
compared against the settled pre-match baseline. Active-match raw hashes were
identical to that baseline, and arena structure-copy hashes stayed identical.

At shutdown, raw hash equality was **false** for `region/r.0.0.mca`. Its length grew
from 74355 to 77824 bytes: the complete original prefix was unchanged and the
3469 appended bytes were zero padding to the immediate next 4096-byte boundary.
All other files were identical; file inventories matched. Decoded comparisons
reported zero added or removed chunks and zero changes in NBT, blocks, block-state
encoding, block entities or entities. This is preserved saved gameplay content,
not byte identity or an absence of disk writes. The report retains the unequal raw
hashes and the explicit padding record.

The shutdown comparison accepts only this narrow padding shape for recognized
region, entity and POI files, with an unchanged original prefix and zero decoded
differences. Nonzero suffixes, extra sectors, partial padding, shrinking, prefix or
header changes, and added or removed files fail. Unit cases cover those failures.

## Dirty-disk lifecycle fixtures

The separate fixtures force-save a gold block plus a zombie, dropped item,
snowball, arrow and experience orb after recording the authoritative arena copy.
Python independently decodes their saved NBT before startup. An outside diamond
block and armor stand provide preservation controls. The small fixture covers
32 by 13 by 32 blocks; the large fixture covers 128 by 65 by 128 blocks and uses a
1536 MiB server heap.

Both passed startup restoration before first public world access, normal match
start and end restoration after fresh deliberate mutations, preservation of the
outside player and decoration, retirement and weak collection, same-UUID reopening,
and exact arena-origin snapshot restoration after disconnect and world retirement.
Pending snapshots cleared. Every player moved to the default world before final
guarded retirement; the arena world was absent before shutdown.

| Fixture | Startup reset | Public reopen before availability | Unload | Shutdown |
| :--- | ---: | ---: | ---: | ---: |
| Small | 1872 ms | 128.947 ms | 120.123 ms | 2.438 s |
| Large | 3163 ms | 899.593 ms | 119.976 ms | 2.505 s |

Paper's forced bootstrap save persisted the startup restoration in these runs.
Their dirty setup evidence is retained separately from the settled post-startup
baseline. Region/entity/POI hashes were byte-identical from that settled baseline
through match teardown, recycle, reconnect and shutdown. Consequently these two
reopen checks establish reopening a clean saved generation; they do not alone
prove reopening an operator-forced dirty save.

The optional `--dirty-reopen` fixture adds that distinct challenge: deliberately
save dirty state after the ordinary recycle checks, retire the world, require
independent Python NBT and persisted-UUID proof while it is absent, then require
canonical public access before admission. The focused run passed: independent saved NBT contained all five temporary entity
types plus the outside armor stand, and Paper's persisted metadata UUID matched
the live UUID. Dirty-disk public reopening returned canonical state in 126.174 ms
before admission. Subsequent reconnect, final retirement and shutdown preserved the
new deliberately dirty baseline byte-for-byte. Shutdown took 2.311 seconds; the
saved inside marker remained gold while the reopened in-memory marker was air.
The intentional save creates a new, explicitly reported comparison baseline; no
no-write claim spans that deliberate mutation.

## Scope of the original acceptance and checks

Idle retirement did not guarantee bounded loaded chunks while players, edits,
matches or other leases kept the world busy. Continued activity and visiting new
arenas needed separate residency work and acceptance evidence.

The original fixtures verified cancellation of a real Paper ignition event outside
the arena bounds. They did not certify full outside-bounds containment. At that
revision, source inspection found no piston extend/retract event handling, and
adventure-mode players could operate levers. A live regression involving a
pre-extended piston and outside blocks was still needed; broader protection unit
tests did not close that gap. The reset/entity and large-arena measurements were
verified, while the broader containment requirements remained open.

The production candidate's suite passed 626 Java tests. A fresh combined
integration build then passed 630 Java tests with zero failures, errors or skips,
including the production changes and validation regressions together. Its
production JAR SHA-256 exactly matched the runtime-tested candidate shown above.
The build and probe compilation passed, as did all 15 Python tests, both client
Node syntax checks and the standards checker. Tracked soak files use LF endings.

The harness also derives its OVERRUN limit from duration plus warmup so the
supported 1800-second duration does not expire early; that boundary is checked by
source arithmetic, not a claimed 30-minute runtime.

## Reproduction and retained evidence

Follow [the local harness instructions](../scripts/soak/README.md), pass the
production candidate explicitly with `--plugin`, and use fresh names and unused
loopback ports. The default duration is 600 measured seconds plus 60 warmup;
`--lifecycle`, `--lifecycle --large --heap-mib 1536`, and
`--lifecycle --dirty-reopen` select the separate lifecycle workloads.

The passing run names are `chunk-release-acceptance1`, `lifecycle-small3` and
`lifecycle-large1`, plus the focused `lifecycle-dirty-reopen1`. Their ignored local output retains summary JSON, raw hashes,
NBT evidence, saved snapshots, CSV data and console logs. The earlier short run
that retained 5112 holders was a failed diagnostic; it is superseded by the
candidate results above, not relabeled as a pass.

## Containment follow-up

The follow-up candidate's production JAR SHA-256 is
`590fa1cb6b58c75cf123bf376aeb7aa3291def1d71a3bdd6dc5e4769bdd40438`.
Its suite passed 642 Java tests with zero failures, errors or skips, plus all 15
Python tests. The probe build, both client syntax checks and the standards checker
passed. The [lifecycle policy](arena-world-lifecycle.md) describes the enforced
2048-block maximum and the conservative 65536-holder-coordinate ceiling. This is
a finite spatial bound, not a measured heap budget for the maximum-sized world.

The focused `containment-lifecycle9` run passed on Paper 26.3 build 226. Four
actual main-hand, empty-hand Adventure client interactions exercised normal and
pre-extended sticky pistons crossing the copied payload, head and base boundaries.
Directly removing their powered lever then challenged the earlier physics guard
without relying on redstone-event latching. Native edit-mode and other-world
controls retracted normally. A sticky retract with an immovable payload queued
while editing did not cross the edit-to-gameplay boundary.

One real client visited every source chunk in a 128-block envelope four times,
without an idle interval or world-generation replacement during traversal. The
first 128 of 256 visits warmed asynchronous work; the final 128 were measured.
Holder samples were 1142, 1178, 1178 and 1178, below the fixture's conservative
2304-coordinate ceiling. All 256 distant Bukkit teleports and 32 native
`execute in ... run teleport` destinations were suppressed. Saving stayed off and
raw gameplay save hashes matched from before traversal through its completion.

The snapshot control also passed after shrinking the configured border around an
old arena origin and respawn point: the player's state returned at a safe fallback
and the pending snapshot cleared. The ordinary reconnect control still restored
an in-bounds arena origin exactly after world retirement. Normal match start/end,
placement-time item cleanup, weak collection, canonical reopening and independent
dirty-disk/UUID proof passed. Clean reopening took 174.964 ms, dirty-disk reopening
158.233 ms and shutdown 2.299 seconds. Final raw save hashes were byte-identical to
the deliberately dirty comparison baseline, with zero decoded differences.

The boundary controls intentionally saved an edit before the gameplay baseline.
That explicit edit cycle was retired and reopened first, settling region-handle
padding before the continuous-use checks. No no-write claim spans the intentional
edit or forced dirty save. Earlier failed diagnostics and the explicitly aborted
preliminary soak remain separate retained evidence and are not acceptance passes.

The same JAR passed `containment-acceptance2`: 600 measured seconds after 60 seconds
of warmup, with 2400 full-intensity match samples and 12000 measured ticks. All
four loops stayed active in OVERRUN, global enemies ranged from 77 to 80 within
the unchanged caps, minimum player health was 20 and minimum one-minute TPS was
19.995. Full chunks and holders remained 1224 and 5112 throughout.

| Measured tick duration | Milliseconds |
| :--- | ---: |
| Mean | 3.542 |
| Median | 3.117 |
| p95 | 6.555 |
| p99 | 9.106 |
| Maximum | 83.905 |

Two measured ticks exceeded 50 ms. The workload retains the stationary-client
limits of the original soak; these measurements do not represent player combat
or the maximum supported world footprint. Active-match raw save hashes and arena
copies were unchanged. Shutdown added only the verified 3469-byte zero-sector
padding to `region/r.0.0.mca`, with no decoded NBT, block, block-entity or entity
changes. Raw hash inequality remains reported explicitly. The idle world became
unregistered and was weakly collected after four bounded collection attempts;
shutdown took 2.163 seconds and restart verification passed.

The final `containment-large3` fixture passed with 128 by 65 by 128 copied bounds
and a 1536 MiB server heap. Its piston positions were derived from those actual
bounds, so payload, head and base controls crossed the large fixture's edges too.
Startup reset completed in 3795 ms before availability; canonical public reopening
took 1263.438 ms, unloading 306.124 ms and shutdown 2.006 seconds. Continuous-use
holder samples were 1283, 1317, 1317 and 1317, below the 2916-coordinate ceiling
that includes both copied columns and the smaller player-traversal envelope.
Restoration, placement-drop cleanup, pending-player restoration, weak collection
and unchanged save checks all passed. Final raw hashes were byte-identical to the
post-control gameplay baseline, with zero decoded differences. These are local
measurements; copy restoration remains synchronous before public availability.

## Generated-map soak

Task 7.12 (#166) adds a generated-map workload to the local harness (`--generated`, see
[the harness instructions](../scripts/soak/README.md#generated-map-workload)). Run
`generated-soak4` used four rooms and `arenas.max-active: 4` on Paper 26.3 build 226,
OpenJDK 25.0.5, Node.js 24.22.3, Mineflayer 4.39.0, ViaVersion and ViaBackwards 5.12.1 and
protocol 1.21.11, on Linux with 12 logical processors; the server was limited to two and
a 256 to 1024 MiB heap. The production JAR, built from main at `5fbd9d7` with no
production changes, had SHA-256
`cec1979f38c55fbaadc892d67f2c95020658a538d9442690c0008096e531a065`.

Two clients per room opened the rooms with `/narena room create` and
`/narena room accept`, and the hosts started each match with `/narena room start`, so
every map was leased, generated and placed by the production services, at the default
difficulty 2 with random seeds. Matches used `regenerate: round`, the bundled shop phase
(45 s, 30 s map wait), bundled hazards and stability settings, the hand-built soak's
threat base of 200, and shorter rounds: 10 s prep, 45 s round, 45 s overrun limit, three
rounds and no extra rounds. First starts were 37 s apart so shop breaks spread out.

### Matches and maps

| Measure | Result |
| :--- | ---: |
| Matches started and finished | 8 and 8 (two per room) |
| Seconds until all four ran at once | 111 |
| Play and drain stages | 950 s |
| Maps placed: first, between matches, after reopening | 4, 7, 4 |
| Blocks cleared and placed per map | 5160960 |
| Placement time, first four together | 8.6 s |
| Placement time between matches, median / max | 8.7 / 9.5 s |
| Shop breaks, all with a committed replacement | 16 |
| Replacement placement, median / max | 3.7 / 5.3 s |
| Shop break length, max | 45.0 s |

No shop break waited for its map and none kept the current map. Every room saw three
rounds per match, 540 fighting samples each.

### Tick time and the stability guard

The guard's thresholds are the bundled `stability` values: spawning pauses above 40 ms
average tick time, the tier drops above 45 ms and both resume below 35 ms. The guard
stayed `NORMAL` in all 960 per-second samples of the play, drain and reopen stages.

| Tick duration, 19000 play and drain ticks | Milliseconds |
| :--- | ---: |
| Mean | 4.992 |
| Median | 2.767 |
| p95 | 13.559 |
| p99 | 16.020 |
| Maximum | 165.339 |

Three of those ticks exceeded 50 ms. The 2581 ticks with a map being placed averaged
13.2 ms (median 12.7, p95 15.8): the placement budget is 10 ms a tick. The highest
average tick time was 15.9 ms while playing and 13.5 ms while draining; minimum
one-minute TPS was 19.62.

Reopening the retired world is synchronous in the first `/narena room create`: that
command took 1373 ms, one tick of 1378 ms, and the average tick time reached 32.6 ms,
below the 35 ms resume threshold. The other three creates took under 0.2 ms; in play, the first
create took 88 ms and the others about 11 ms. All four maps were placed 11.1 s after the
reopen. Collection requests made by the probe during the release stages cost 8 ticks
over 50 ms; those are not counted above.

### Enemies, lava and collapses

Every room reached 25 live enemies, with a fighting median of 24 or 25; the global
maximum was 80, within both caps. OVERRUN intensity had a median of 1 and a minimum of
0.80. Minimum player health was 20. Up to 76 lava sources were open across the
maps at once (18 to 42 in one map); the arena world allowed 3204 lava flows, and 4766
falling blocks came from collapses. Clients stood still, so these counts reflect the
hazard schedule rather than play.

### Memory and chunks

The arena world held at most 2472 full chunks and 5688 holders. Heap use peaked at
978585048 bytes (about 933 MiB) of the 1024 MiB maximum, so the run had little
headroom; a 1024 MiB heap is a floor for four generated slots, not a recommendation.

### World release and reopen

After the last match the clients left their rooms; the world was released after 9.0 s,
and after the second cycle after 10.0 s. Slot cells were empty both times, and no
snapshot was pending at the end. The arena save held only air after shutdown: release
without saving wrote cleared chunks, so raw hashes differ from setup, but no generated
block was saved.

Cycle 2 was collected. Cycle 1 was not: the retired world stayed strongly reachable
after 45 s and four collection requests, and all eight clients' last damage came from
one of its entities. A heap dump from the earlier failed run `generated-soak1` gives the
path: the server's player list holds each `ServerPlayer`, whose `lastDamageSource` names
the zombie that last hit them, whose `level` is the retired world. Nothing clears that
field when the player leaves the arena world, so a player who fought there and then
waits in the lobby keeps the whole retired world in memory until they take other damage
or leave. Cycle 2 had no fighting, so nothing held
it. This is a real retain in the plugin's lifecycle, recorded here and not fixed by this
harness change; the run fails acceptance on it.

### Recommendation and limits

Four generated matches at once stayed inside the stability guard on a two-processor
server, so the README recommends keeping `arenas.max-active` at its default of 4. Fewer
slots were not run, since four did not break the guard. More than four were not
measured: the harness has at most eight clients, and between-round regeneration uses one
spare cell, so four slots already hold five cells.

The clients did not fight, move, buy in the shop, die or lag: they stood still on
loopback with resistance and saturation, so rounds ended at the overrun limit. Real
latency, player combat and Bedrock clients are not modelled. Rounds were shorter than
the defaults, which raises the rate of per-round regeneration compared with play.

Earlier attempts are retained but are not results: `generated-smoke1` and
`generated-soak1` stopped at the first collection check (soak1 left the heap dump used
above), `generated-soak2` completed but over-counted one placement time and then
failed the raw save-hash check that the air-only check replaced, and `generated-soak3`
was cut off by a host restart.
