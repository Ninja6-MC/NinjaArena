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
arenas, and releases tickets. After 45 seconds, the probe requires no loaded arena
chunks or pending chunkholders, no held arenas, and no pending snapshots, then shuts down without
reloading any arena chunks. Region/entity/POI hashes are compared with both the setup baseline and the
pre-match checkpoint after startup settles. Match-phase and shutdown hashes must
match that pre-match checkpoint; startup differences remain in the report.
Saved-copy hashes must match the setup baseline. A separate restart checks that markers are absent.

A passing result requires all runtime and process checks, at least the requested
number of full-intensity samples per arena, positive enemy populations, and
unchanged match-phase save hashes. Raw save snapshots and per-chunk NBT digests
are retained. Block comparisons decode all 4096 states per section, including
properties, so palette reordering does not count as a block change. Metadata,
block encodings, blocks, block entities, and entities are counted separately. Failure logs are retained and must not be described as a
successful soak. Raw evidence belongs in the ignored run directory; any published
report must identify workload limits and measured results without private paths.

## Verification and current result

```sh
python -B -m unittest discover -s scripts/soak -p 'test_*.py'
node --check scripts/soak/clients.cjs
```

The local run recorded in [the stability report](../../docs/stability-soak.md)
failed chunk release. The 600-second acceptance soak has not run. The strict probe
retains this failure; it does not discard pending holders or change production
world, director, or storage behavior.
