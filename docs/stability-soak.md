# Stability soak: chunk release failure

Issue #29 remains incomplete. A real local Paper diagnostic reproduced retained
chunkholders after all four matches ended. The 600-second acceptance soak has not
run. No production world, director, or storage code changes are included.

## Test coverage

The existing suite already covers director scalar boundaries, snapshot round trips,
and safe-landing geometry. The additions cover composed pressure over all 35 rounds
and a 3600-second timer, repeated round trips of every byte value and quoted Unicode
world names, and negative-coordinate plus diagonal/vertical search boundaries.
The full Java suite passes 591 tests. Four Python tests verify the save evidence
reader and warmup/population aggregation.

## Local workload, 2026-10-30

- Paper 26.3 build 226, API compile target 26.3 build 223.
- Temurin 25.0.4+7, Node.js 24.11.1, Mineflayer 4.39.0.
- ViaVersion and ViaBackwards 5.12.1, clients using protocol 1.21.11.
- Windows 11 build 26300; AMD64, 12 logical processors; server limited to two.
- Server heap 256â€“768 MiB; eight clients in one Node process with a 384 MiB
  old-space limit; no simultaneous Gradle or other test workload.
- Four independent 64 by 64 arenas, two stationary survival clients each, normal
  production spawning, AI, targeting, snapshots, restoration, and match teardown.
- Resistance V and saturation kept clients alive. The threat base was 200 to fill
  the unchanged caps: 25 per match, 80 globally, four spawns per tick.
- Two seconds each of PREP and COMBAT, followed by OVERRUN; 60 seconds of warmup
  followed by 60 measured seconds. This is a diagnostic, not a ten-minute soak.

This workload does not measure player combat, ordinary survival difficulty, or
remote network latency. Startup average tick time reached 50.7 ms, causing the
normal stability tier drop. Spawning resumed at 23.9 ms with the configured
30-second intensity recovery. Those readings remain in the raw data; they fall
within the documented warmup.

## Measured results

Each arena provided 60 measured samples at actual director intensity 1. All four
normal director loops remained active.

| Arena | Live enemies: minimum / median / maximum |
| :--- | ---: |
| soak0 | 24 / 25 / 25 |
| soak1 | 24 / 25 / 25 |
| soak2 | 25 / 25 / 25 |
| soak3 | 4 / 5 / 5 |

The global population was 79 / 80 / 80. The shared cap explains the smaller last
arena. Minimum player health was 20 and minimum reported one-minute TPS was 19.995.

| Tick duration, 1200 measured ticks | Milliseconds |
| :--- | ---: |
| Mean | 3.491 |
| Median | 3.134 |
| p95 | 5.769 |
| p99 | 9.293 |
| Maximum | 24.742 |

No measured tick exceeded 50 ms. These short-window values do not establish
long-term stability.

## Release and save evidence

After normal match close and a further 45 seconds, the strict probe recorded:

```json
{"full_chunks":0,"holders":5112,"plugin_ticket_chunks":0}
```

It failed with `Arena chunks/holders remain: 0/5112`. All plugin chunk tickets were
released; the earlier diagnostic also verified that no arena remained held and no
snapshots remained pending. The
strict run stopped before its later snapshot/restart assertions, so those are not
claimed as completed in this run. The runner and all owned server/client processes
exited, and the loopback listener closed.

Save comparisons distinguish startup writes from match activity:

| Checkpoint comparison | Added chunks | Changed NBT | Changed blocks | Changed block entities | Changed entities |
| :--- | ---: | ---: | ---: | ---: | ---: |
| Setup shutdown to settled pre-match | 0 | 56 | 0 | 0 | 0 |
| Pre-match to active matches at 30 s | 0 | 0 | 0 | 0 | 0 |
| Pre-match to failed-run shutdown | 2408 | 2688 | 0 | 0 | 0 |

Raw region hashes were identical between settled pre-match and active matches.
All four marker positions were gold blocks in the live world and air in saved NBT.
At shutdown, four block-state encodings changed, but decoding all 4096 states per
section found no semantic block changes. Saved markers were still air and no
entities were present in the dimension's saved chunk records. Arena structure-copy
hashes were unchanged in the preceding diagnostic. The failed strict run does not
certify overall no-write behavior: startup metadata and shutdown-generated chunks
changed raw hashes, and 5112 chunk records remained on disk. Paper reported saving
5096 block chunks and 1160 entity chunks during shutdown; an entity chunk count does
not mean mobs were persisted.

An earlier 60-second diagnostic restarted successfully and found all four markers
absent. The strict final diagnostic intentionally did not restart after its holder
failure. The raw save snapshots, per-chunk digests, CSV files, and shutdown logs are
retained under the ignored run output.

## Observed cause and scope

In Paper build 226, `CraftWorld.getLoadedChunks()` enumerates the full-chunk map.
It does not enumerate every pending or partially generated holder. Cached server
bytecode also shows `ChunkMap.tick` skipping unload processing while the world's
`noSave` flag is set. The current arena sweep examines only full chunks with load
level `INACCESSIBLE`; it reported zero unloads while the holder manager retained
5112 entries. A zero full-chunk count is therefore insufficient release evidence.

The public API exposes `setAutoSave(boolean)` but no separate per-world automatic
save interval setter. Allowing normal automatic saving merely to enable unloading
would also allow held-match data to be saved. `ChunkUnloadEvent.setSaveChunk(false)`
does not by itself suppress periodic saves of chunks still held by matches. No
production workaround or internal-server mutation is included. The chunk lifecycle
failure must be resolved before issue #29 can satisfy its acceptance conditions.

## Reproduction

Follow [the local harness instructions](../scripts/soak/README.md). Use a fresh name
and an unused loopback port. The diagnostic invocation differs from the default
acceptance command only in duration:

```sh
python -B scripts/soak/run.py --java /path/to/java --paper /path/to/paper.jar \
  --via /path/to/ViaVersion-5.12.1.jar \
  --backwards /path/to/ViaBackwards-5.12.1.jar \
  --modules /path/to/node_modules --cache /path/to/paper-cache \
  --seconds 60 --port 25579 --name stability-diagnostic
```

A nonzero result with `teardown-chunks.json` and `failed-save-diagnostics.json` is
the reproduced failure. Do not describe it as a passing stability soak. The default
600-second measurement remains reserved for acceptance after chunk release works.
