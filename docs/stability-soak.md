# Stability soak and arena lifecycle verification

The 600-second acceptance soak passed on Paper 26.3 build 226 with production
candidate `f4556da`. Its plugin SHA-256 was
`afa795cc3e30608d39141e4fab4bc38dda3f78b5f5d4e9e7c9c2d27576a6ecda`.
Separate small and large lifecycle fixtures also passed. These results establish
idle-world retirement and the tested restoration paths, not a bound on chunk
retention while the arena world remains continuously busy. Issue #50 remains open
for the broader containment and residency work described below.

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

## Remaining scope and checks

Idle retirement does not guarantee bounded loaded chunks while players, edits,
matches or other leases keep the world busy. Continued activity and visiting new
arenas need separate residency work and acceptance evidence.

The fixtures verify cancellation of a real Paper ignition event outside the arena
bounds. They do not certify full outside-bounds containment. Source inspection
still finds no piston extend/retract event handling, and adventure-mode players
can operate levers. A live regression involving a pre-extended piston and outside
blocks remains needed; broader existing protection unit tests do not close this
gap. Issue #50's reset/entity and large-arena measurements are verified here, but
its broader containment requirements remain open.

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
