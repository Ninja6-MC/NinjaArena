# Arena world retirement

Paper 26.3 stops its normal chunk-holder unload processing when world auto-save is
disabled. Bukkit's loaded-chunk list includes full chunks, so an empty list does not
prove that partial holders have been released. Enabling auto-save to make unloading
run would also permit match changes to be saved. Calling the internal unload queue
directly is unsafe: its unload stages can serialize protochunks, entities and POI.

The arena world therefore uses Bukkit's `unloadWorld(world, false)` when fully idle.
The five-second maintenance task attempts this only with no players, forced chunks,
plugin tickets, held arenas, edit mode, failed resets or pending entry operations.
It honours a cancelled unload event and keeps that world usable. While a load or
unload event is running, reentrant admissions and arena mutations are refused.

An unloaded world stays unloaded until needed. Diagnostics and shutdown use
`ArenaWorld.currentWorld()` without creating a replacement. The next `world()` access
reopens the same directory and verifies its UID, disables auto-save, reapplies the
latest distances and border, and restores every authoritative arena copy before
publishing the world. Missing copies are failures, never adopted from potentially
dirty world-save blocks. Load, configuration or copy failures leave the generation
unavailable; failed cleanup remains diagnosable and requires a restart after repair.
Arena-origin player snapshots also use this lazy resolver before finding a landing.

Portal entry, accepted invitations and admin teleports hold an operation lease from
before their asynchronous work through its server-thread completion. Cancelling a
request or disconnecting does not release a world while its chunk work is pending.
Old completions cannot consume a newer request. Registry tickets and structure copies
resolve the current world rather than retaining the retired generation. Per-match
enemy and tool entities are discarded by the existing match-end listeners.

Retirement preserves the world save as it stood before the cycle; arena copies remain
the authority for restored blocks. Normal shutdown and `/save-all` can still write a
world that is in use. External plugins and player respawn metadata can retain world
references independently of this plugin; integrations should resolve by world name or
UID and avoid caching retired world, chunk, entity or location objects.

This is an idle-release mitigation for #50, not a continuous-use memory bound. Full
chunks are still swept while busy, but partial holders can accumulate if a sequence
of matches, visitors or waiting players never lets the world become idle. A bounded
continuous-use policy needs a separate admission and draining design. No active
match is interrupted or forcibly recycled by this change. Startup and reopen copy
restoration run synchronously before availability; large-arena latency must still
be measured on the target server.
