# Local Paper stability soak

The probe is a separate test plugin, compiled by `check` and packaged only by
`soakProbeJar`. It is never included in the production JAR. Run it in a dedicated
local server, with no existing worlds or players. The runner refuses to reuse an
output directory and binds the server and eight offline clients to loopback only.

## Prerequisites

- Java 25 and Python 3.10 or later.
- A local Paper 26.3 server JAR and its cached `libraries`, `cache`, and `versions`
  directories. The cache must contain an already accepted `eula.txt`.
- ViaVersion and ViaBackwards 5.12.1 JARs from their official releases. Both are
  needed for the older client protocol.
- Node.js and an existing installation of Mineflayer 4.39.0. `--modules` points to
  the directory containing its `mineflayer` package. No packages are installed by
  the runner.
- Enough free memory for a 768 MiB server heap, JVM overhead, and eight clients in
  one Node process with a 384 MiB old-space limit. Do not run Gradle alongside it.

```sh
./gradlew build soakProbeJar --no-daemon
./gradlew --stop
python scripts/soak/run.py --java /path/to/java --paper /path/to/paper.jar \
  --plugin /path/to/candidate-production.jar \
  --via /path/to/ViaVersion-5.12.1.jar \
  --backwards /path/to/ViaBackwards-5.12.1.jar \
  --modules /path/to/node_modules --cache /path/to/paper-cache \
  --seconds 600 --port 25579 --name soak-task29
```

Use an unused port and a fresh `--name` for each attempt. The output is beneath
ignored `run/<name>/`. The runner copies only runtime caches and supplied JARs;
existing servers, cache files, accounts, and worlds are never modified. It retains
all logs, raw CSV measurements, checkpoint files, process IDs, and `summary.json`.
Only processes launched by this invocation are eligible for cleanup. Setup and
verification have four-minute deadlines; client arrival and soak completion also
have bounded deadlines. The server has a 60-second tick watchdog.

`--plugin` selects a separately built production candidate and reads that JAR's
bundled configuration before applying the soak threat-budget override. Without it,
the runner uses this checkout's production JAR and configuration as before. Build
`soakProbeJar` in the validation checkout; do not rebuild an older production base
over the candidate. The report records the production JAR's SHA-256.

## Workload and evidence

Setup builds four independent, walled arenas, saves their structure copies through
normal edit APIs, and shuts down. The runner hashes every arena dimension region,
entity, and POI file after that shutdown. It also hashes the saved arena copies.

The run starts eight real offline clients using protocol 1.21.11 through the two
translation plugins. The client sends `tick_end` after movement packets because
this Mineflayer release does not send it itself. Players remain stationary and
receive resistance V and saturation through ordinary potion effects so deaths do
not end the load early. This measures live mob AI, targeting, spawning, chunk
loading, snapshots, restoration, and teardown; it does not model player combat,
network latency, or normal survival difficulty.

Four matches start through the normal match service with two players each. PREP
and COMBAT last two seconds each, followed by OVERRUN at requested intensity 1.
The threat base is raised from 20 to 200 to exercise the unchanged production caps:
25 enemies per match, 80 globally, and four spawns per tick. All other director,
stealth, and stability settings retain their bundled defaults. No spawn gate,
spawner, enemy AI, or director is replaced. Service references are read from the
plugin only for instrumentation; production code is unchanged.

Tick durations come from Paper's `ServerTickEndEvent`. Per-second rows include
actual director intensity, live enemies, threat, budget, loop status, loaded arena
chunks, pending chunkholders, minimum player health, server average MSPT, and TPS.
The holder count uses read-only reflection against Paper 26.3; full chunks alone
do not include inaccessible or partially generated chunks. The first sixty seconds
are warmup; the following `--seconds` seconds are the measurement window. Any
stability tier reduction remains visible and fails the full-intensity acceptance
check. Enemy populations must be positive in every measured match sample and stay
within both caps.

Gold marker blocks are placed after matches start. Thirty seconds into the run,
the runner hashes the dimension files again while those changes are live. At the
end it closes matches through the normal service, which restores players, resets
arenas, and releases tickets. Every active sample resolves the currently registered
world and its actual Paper holder manager. No strong world or holder-manager
reference is retained by the probe after close. After 45 seconds it requires the
world to be absent from Bukkit, the production manager's `currentWorld()` to be
empty, and the retired world's weak reference to clear. At most four explicit GC
attempts are made, on separate server ticks. A retained world is a failure; an
absent world is not reported as a fabricated zero-holder count. No arena may stay
held and no player snapshot may remain pending. Region/entity/POI hashes are
compared with both the setup baseline and the settled pre-match checkpoint.
Active match-phase hashes must match the pre-match checkpoint exactly. Shutdown
must preserve its saved content, allowing only the narrow sector-padding shape
described below, with raw hash inequality and padding disclosed explicitly.
Startup differences remain in the report. Saved-copy hashes must match the setup
baseline. A separate restart checks that markers are absent.

A passing result requires all runtime and process checks, at least the requested
number of full-intensity samples per arena, positive enemy populations, and
unchanged match-phase save hashes. Raw save snapshots and per-chunk NBT digests
are retained. Block comparisons decode all 4096 states per section, including
properties, so palette reordering does not count as a block change. Metadata,
block encodings, blocks, block entities, and entities are counted separately. Failure logs are retained and must not be described as a
successful soak. Raw evidence belongs in the ignored run directory; any published
report must identify workload limits and measured results without private paths.

## Generated-map workload

`--generated` selects a separate workload on generated maps instead of the four
hand-built arenas; without it the runner behaves as before. `--matches` (1 to 4,
default 4) sets both the number of rooms and `arenas.max-active`. `--heap-mib 1024` is
accepted for this workload only.

```sh
python scripts/soak/run.py <the same local dependency arguments> \
  --plugin /path/to/candidate-production.jar --generated --matches 4 \
  --heap-mib 1024 --seconds 600 --port 25591 --name generated-soak1
```

The setup boot creates the worlds only: the arena registry must be empty, so every
room plays generated maps. The run then starts two real clients per room. Each host
runs `/narena room create` and its guest `/narena room accept <host>`, so the slot
lease, seed choice, generation, placement and match start all go through the
production room, map and match services. Hosts run `/narena room start` once their
map is placed. First starts are spaced by one round cycle (prep, round, overrun limit,
round end and shop) divided by the number of rooms, so the rooms' shop breaks do not
all fall together; after the first, a room starts its next
match as soon as its next map is placed. No new match starts once `--seconds` have
passed since the rooms opened; the run waits for the matches in progress to end.

The bundled configuration is changed only where the workload needs it, and the run
fails if any of these lines is missing or repeated: `maps.default.regenerate: round`,
`arenas.max-active`, the threat base of 200 used by the hand-built soak, and shorter
match timings (10 s prep, 45 s round, 45 s overrun limit, three rounds, no extra
rounds) so that more shop breaks, and so more per-round regenerations, fall in the
measured time. The shop phase, seed modes (random, difficulty 2), hazards and every
stability setting keep their bundled values. Stationary clients receive resistance V
and saturation at every prep, so rounds end at the overrun limit as draws.

After the last match the clients leave their rooms. The arena world must retire while
idle and, from 45 seconds after the rooms close, its weak reference must clear after
at most four collection requests on separate ticks. A world that stays reachable is
recorded with the number of clients whose last damage came from one of its entities,
and the run goes on so the reopen is still measured; acceptance then fails. The rooms
are then opened again, which reopens the world and sweeps the slot cells before the
new maps are placed; all maps must be placed within five minutes. The clients leave
again, the world must retire and be collected again, and the clients leave the lobby
so no snapshot stays pending. The arena save must be unchanged from setup to
shutdown, since generated maps are never saved.

The probe writes `ticks.csv` (every tick, with its stage and the maps being built),
`generated-seconds.csv` (guard state, average tick time, TPS, arena chunks and holders,
lava sources, allowed lava flows, falling collapse blocks, enemies, health, heap),
`generated-matches.csv` (each match each second), `generation.csv` (time and blocks per
map placement), `shops.csv` (each shop break and its replacement map) and
`phases.csv`. Tick statistics cover the play and drain stages; the reopen stage is
reported separately. The run passes only if every match ran at once, every room
finished a match, the tick-time guard stayed `NORMAL` with the average below its pause
threshold, at least one map was replaced between rounds, lava flowed, every room had
live enemies within both caps, both releases and the reopen passed and the save was
unchanged. `summary.json` lists each failed condition.

The workload does not model player combat, movement, shop purchases, deaths or network
latency: clients stand still on the loopback interface. Only one replacement can be
built at a time, because one spare cell is reserved for between-round regeneration; a
shop break that finds no free cell keeps the current map, and the report counts both.

## Verification and current result

```sh
python -B -m unittest discover -s scripts/soak -p 'test_*.py'
node --check scripts/soak/clients.cjs
```

The 600-second acceptance soak passed, as did the small, large and focused
dirty-reopen lifecycle fixtures. The [stability report](../../docs/stability-soak.md)
records their workload, results and remaining limits. The earlier short diagnostic
failed chunk release; its retained evidence remains a historical failure.

## Separate dirty-disk lifecycle fixture

Use `--lifecycle` with a fresh output name and the candidate JAR. This fixture is
separate from the clean stability workload and uses the same localhost-only
dependencies. It deliberately saves a gold marker, a zombie, item, snowball, arrow and XP
orb after capturing the authoritative arena copy. Python decodes the saved NBT and
requires every entity type and the dirty marker to exist before startup testing.
An outside diamond block and armor stand must survive restoration.

Add `--dirty-reopen` for a separate forced-save challenge after the ordinary
recycle and collection checks. It deliberately saves dirty blocks and temporary
entities again, retires the world, and waits while Python independently decodes
the absent world's save and verifies its UUID. Only then may public access reopen
it; canonical state must be available immediately. The new dirty baseline is
recorded separately and governs subsequent reconnect and shutdown comparisons.
The intentional save is reported explicitly.

The next server start must expose canonical blocks and no temporary entities.
Before match testing, the clients send actual empty-hand adventure-mode lever uses
against piston fixtures crossing the copied bounds. Pre-extended sticky pistons
are also challenged by directly removing their power source. Native edit-mode and
other-world controls must move normally. A queued immovable-payload retract must
not survive the edit-to-gameplay transition.

Those controls deliberately open and finish an edit. The fixture retires and
reopens that explicit edit cycle before establishing its gameplay save baseline,
so handle-close sector padding is settled separately. One real client then visits
all 64 source chunks of a 128-block envelope four times while keeping the same
world generation busy. It attempts 256 distant Bukkit teleports and 32 native
`execute in ... run teleport` destinations, none of which may admit the player;
the first two passes warm up asynchronous chunk work, then holder counts must
plateau in both measured passes. Saving must stay off, and gameplay save hashes must
remain identical from before traversal. This is a spatial-bound regression, not a
maximum-2048-diameter heap-capacity measurement.

An additional snapshot control requires that no snapshot is taken while the
player stands in the arena world. It then captures one at the default world's
spawn, moves the player back into the arena world with their respawn point there,
and requires restoration of the player's state outside the arena world and
deletion of the pending snapshot. The border is shrunk afterwards for traversal.

Real clients exercise ordinary match start/end, snapshot restoration, and an
outside player's preservation. Before the reconnect, the fixture again requires
that a snapshot in the arena world is refused, captures one at the default world's
spawn, and disconnects the player from inside the arena world. After the world
retires, the player reconnects and must be restored at that spawn without the
restore reopening the arena world. A real Paper ignition event is dispatched to the
installed protection listener; this is event-level integration evidence, not a
claim that a client sent a fire-use packet. The fixture retires the world through
the production public API, requires unregistering and eventual weak collection,
then reopens it. The same UUID and canonical arena must be available immediately
when public world access returns. Region, entity and POI hashes must remain equal
across match teardown, unload, reopen, reconnect and shutdown against a settled
post-control gameplay baseline. Paper can force a bootstrap save after startup restoration;
the dirty setup NBT and hashes are retained separately, and the summary reports
startup hash and NBT changes explicitly. All players return to the default world
before the final guarded retirement, so shutdown observes an absent arena world.

Raw file hashes and byte-identity flags remain in both reports. Shutdown content
comparison permits only Paper's sector padding: an unaligned recognized region,
entity or POI file may gain zero bytes up to its immediate next 4096-byte boundary,
with its entire original prefix unchanged. File inventories must match and every
decoded NBT, block and entity difference must be zero. The report lists each padded
file and appended byte count; every other storage change fails acceptance. Active
workload and in-process lifecycle comparisons still require exact raw hashes.

```sh
python scripts/soak/run.py <the same local dependency arguments> \
  --plugin /path/to/candidate-production.jar --lifecycle \
  --port 25580 --name lifecycle-small
python scripts/soak/run.py <the same local dependency arguments> \
  --plugin /path/to/candidate-production.jar --lifecycle --large --heap-mib 1536 \
  --port 25581 --name lifecycle-large
```

Small bounds are 32 by 13 by 32. `--large` uses 128 by 65 by 128, requires an
explicit 1536 or 2048 MiB heap, and retains the server watchdog and bounded stage
deadlines. Only one server is run at a time. Evidence includes copy/reset/reopen
time, heap observations, unload duration, shutdown time, raw NBT and strict save
hashes. Neither fixture substitutes for continuous-load admission bounds or a
ten-minute stability acceptance run.
