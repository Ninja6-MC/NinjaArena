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

Restoration first clears combat records whose source belongs to the arena world,
including dead-player deferrals and failed restores. Paper's last-damage-source expiry
getter does not clear its backing reference, so a narrowly validated Paper adapter clears
that field when its entity or block source belongs to the arena. The public damage-event
setter clears arena-owned events. Other-world combat records, health and damage are
unchanged. An unsupported native layout logs a warning once; successful Bukkit unload
alone must not then be treated as proof of world collection.

## Continuous-use spatial bound

The arena world's diameter is now limited to 16 through 2048 blocks. Its default
remains 2048. Player movement, teleports, portals, login and respawn destinations
enforce the configured extent, including administrators and spectators. Admin
arena teleports validate their target before asynchronous chunk work. Saved-copy
coordinates and sizes are checked before chunk access; saved snapshot destinations
outside the current extent use the existing safe fallback before any arena terrain
search. Terrain searches cannot read outside the hard extent. Ordinary entity
teleports and portals are checked too. These restrictions also apply while editing.

There are at most 128 by 128 source chunk coordinates inside the hard extent.
Paper's configured view and simulation distances are at most 32. Startup validates
that its native full-chunk dependency radius is at most 16; an unsupported layout
or radius fails closed. A conservative 64-chunk halo on each side covers those
distances, ticking propagation and generation dependencies. Consequently plugin
operations and vanilla player traversal use at most 256 by 256, or 65536, holder
coordinates in a continuously busy generation. This is a coordinate bound, not a
heap allocation budget or a promise that the largest supported layout fits any
particular server. Smaller borders and distances reduce the working set.

No active match is interrupted or forcibly recycled. Existing counted operation
leases and idle retirement remain in place. Integrations that directly request
arbitrary chunks, add arbitrary tickets, change native server state or load worlds
outside this lifecycle are outside this guarantee and must enforce the same extent
before their own requests. A cancelled teleport is not a substitute for validating
an integration's earlier asynchronous chunk request.

Existing configurations above 2048 are clamped at startup and rejected on reload.
Saved data is preserved. Arenas outside the supported extent are unavailable; move
their definitions and authoritative copies into the supported area before use.
Startup and reopen restoration remain synchronous before availability.

## Piston containment

Adventure mode permits empty-hand lever use. Cancelling piston retraction alone
does not protect outside blocks: Paper can already have changed a sticky piston
base and removed its head before dispatching the retract event, and an immovable
payload can take a path with no retract event. Gameplay therefore freezes piston
base/head/moving-block physics and redstone-current changes before motion is
queued. Extend and retract events are cancelled as an additional guard.

Before an edit finishes, loaded moving pistons must settle. Both immediate and
deferred native block-event queues are then cleared before copying the arena and
entering gameplay. This also handles an unexecuted sticky retract queued during
editing. Queued actions are cleared after authoritative copy placement and during
world preparation. The adapter only changes this arena world's block-action
queues; it never runs native chunk unloading or serialization. Unsupported native
layouts refuse the transition. Other worlds and active editing retain ordinary
piston and redstone behavior.
