# Changelog

All notable changes to **NinjaArena** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

Nothing has been released yet. The plugin targets Paper 26.3, needs the server to run on
Java 25, and does not run on Folia.

### Added
- A shared `kits.yml` kit, cleared and rebuilt every round and on a delayed respawn into
  the current round. Dead and eliminated players are skipped. Missing or invalid files
  log the reason and use a safe empty kit; item-provider failures also use an empty kit.
- Named kit item providers via `NinjaArenaPlugin#kitItems()` for custom item IDs, alongside
  vanilla material entries. Providers are resolved when a kit is applied and their item
  data is preserved on independent stacks.
- Match-only inventory lockdown for item and arrow pickup, item drops, external container
  inventories and direct container interactions. Personal inventory arrangement remains
  available; builders outside a match are unaffected. Snapshot restoration returns the
  player's own storage, armour and offhand inventory after leaving or match close.
- Match deaths retain inventory and levels without item or XP drops. Eliminated players
  spectate living teammates inside their arena, limited to 16 blocks from their team, or
  wait near their own base if nobody remains. Enemy and mob cameras are blocked.
- At the next round, spectators return to Adventure mode at their team base. Players
  still on the death screen are readied and placed when they respawn. Leaving while
  spectating uses the existing snapshot restore, including the saved game mode.
- Team scoring: 5 points for a round win and 2 for an opposing player kill, configurable
  under `scoring`. Teamkills, suicides, environmental deaths, leaving and drawn rounds
  award nothing. Total points decide match standings and extra rounds, and the final
  points table replaces the rounds-won summary before players return home.
- `NinjaArenaPlugin#scores()` exposes the per-match team scores and `TeamScores#addPoints`
  accepts awards from other sources, including future enemy kills. Scores are frozen in
  the match result and each new match starts at zero.
- Match players use Adventure mode and cannot harm teammates with direct attacks,
  projectiles, harmful splash potions or area-effect clouds unless `match.friendly-fire`
  is enabled. Leaving restores the saved game mode with the rest of the snapshot.
- Explosions keep their player damage but cannot destroy blocks in the arena world;
  entity block changes are cancelled there too. All environmental protection is suspended
  while any arena is in edit mode and resumes when the last edit finishes.
- Initial project scaffolding with Gradle Kotlin DSL, the Gradle 9.8.1 wrapper and a
  Java 25 toolchain, compiling against `paper-api` 26.3.
- `NinjaArenaPlugin`, which only logs on enable and disable.
- `plugin.yml` with `api-version: '26.3'` and `folia-supported: false`, the
  `/ninjaarena` command with `/narena` as an alias, and the permission nodes
  `ninjaarena.play` (default true), `ninjaarena.host` and `ninjaarena.admin` (both op),
  where admin includes host and host includes play. The command has no executor yet and
  answers with its usage line.
- JUnit test that parses the built `plugin.yml` with paper-api's own
  `PluginDescriptionFile` and checks the main class, API version, version expansion,
  command, alias and permission tree, and reads `folia-supported: false` from the raw
  YAML.
- CI workflow running `Build and Test` on Java 25: a CRLF guard, Gradle wrapper
  validation, `./gradlew test` and `./gradlew build`.
- DCO and Standards workflows calling the org's reusable workflows, plus Dependabot,
  `.editorconfig`, `.gitattributes` and an empty `.github/standards-exceptions.yml`.
- OpenSSF Scorecard workflow, run weekly, on every push to `main` and when branch
  protection changes.
- Player snapshots taken before arena entry: location, inventory, armour, offhand,
  experience, health, food, saturation and exhaustion, potion effects, game mode, fire
  ticks and fall distance. Each is written to `plugins/NinjaArena/snapshots/<uuid>.yml`
  through a temporary file and an atomic move, so a crash leaves the previous file or the
  new one. A capture reports whether it was saved, refuses to overwrite a snapshot that
  has not been restored yet, and snapshots on disk can be loaded, listed and deleted.
- The `ninjaarena_arenas` world, created on first start and loaded on every start after
  that: a void generator with one biome and no structures or mobs, time and weather
  frozen at a clear noon, natural spawning off, a world border and a low view and
  simulation distance, all tunable under `arena-world` in `config.yml`. Auto-save is off;
  `ArenaWorld#setSavingEnabled` turns it on for edit mode and saves once when it is
  turned off again.
- Restoring a player from their snapshot: location, inventory, experience, health, food,
  effects, game mode, fire ticks and fall distance, with velocity and gliding cleared. The
  snapshot is deleted only once the restore has succeeded. An unsafe saved spot (anything
  solid, lava or fire in the player's space, judged by collision box so slabs, carpet and
  snow layers count as ground, or lava, fire or the void below) moves the player to the
  nearest safe spot within 8 blocks, then their respawn point, then the world spawn. A
  player who left mid-arena is restored on their next join, or on respawn if they join
  dead, and snapshots left by a crash are marked on startup so their players are restored
  when they join.
- The arena registry in `plugins/NinjaArena/arenas.yml`, written through a temporary file
  and an atomic move. Each arena owns one cell of a fixed grid in the arena world
  (`arena-grid.cell-size` in `config.yml`, 256 blocks by default) and has bounds inside
  that cell, a waiting room, 2 to 4 team spawns and at least one enemy spawn point, all
  inside the bounds. An arena that breaks any of these is unavailable for matches, and a
  file that cannot be read is left untouched and blocks every change until it is fixed.
- `/ninjaarena arena` admin subcommands, gated on `ninjaarena.admin` for both running and
  tab completion: `create`, `delete`, `list`, `info`, `edit <on|off>`, `tp`, `setbounds`,
  `setwaitingroom`, `setteamspawn`, `removeteamspawn`, `addenemyspawn` and
  `removeenemyspawn`. Positions default to where the player stands and can be given as
  coordinates, so everything except `tp` also runs from the console.
- Edit mode: arenas change only while in edit mode, which turns arena world saving on and
  takes the arena out of matches; finishing the last edit turns saving off, saving the
  world, and lists what still stops the arena from hosting a match.
- `ArenaRegistry#hold` and `#release` for the match engine, adding and removing plugin
  chunk tickets on every chunk of an arena's bounds for the length of a match. Saving is
  one switch for the whole world, so no arena can be held while any arena is in edit
  mode, no edit can start while any arena is held, and a held arena cannot be deleted.
- Portals: a box of blocks in any world except the arena world, bound to an arena by
  name and stored in `plugins/NinjaArena/portals.yml` through a temporary file and an
  atomic move. Portals cannot overlap and are at most 128 blocks along each axis. A file
  that cannot be read, including one with a single bad entry, fails the load naming the
  portal, is left untouched and blocks every change until it is fixed.
- `/ninjaarena portal` admin subcommands, gated on `ninjaarena.admin` for both running and
  tab completion: `setcorner <1|2>` from where the player stands or from coordinates in
  their world, `create <name> <arena>`, `delete`, `list` and `info`.
- Walking into a portal sends a player with `ninjaarena.play` to the arena's waiting room
  once the arena is available and their snapshot is saved. Only moves that cross a block
  boundary are checked, through an index of the chunks each portal touches. A refused
  player is put back where they came from and told why, at most every two seconds.
  Teleporting into a portal does not enter it, and vanilla portal travel is cancelled
  inside one.
- Waiting rooms: a player who arrives through a portal joins the smallest of the arena's
  teams, and when a departure leaves one team two behind another, the last player to join
  the larger team moves over. A start countdown begins once `waiting-room.min-players`
  are waiting, runs for `waiting-room.countdown-seconds` and stops if the count drops
  below the minimum; when it finishes, the players are handed to the match engine as a
  team roster. Players in a waiting room take no damage and keep their inventory if they
  die.
- Leaving a waiting room restores the player from their snapshot: walking or falling more
  than `waiting-room.radius` blocks from it, being teleported out of it, dying, or
  `/ninjaarena leave`, which needs `ninjaarena.play`. A player who disconnects is restored
  on their next join, and disabling the plugin sends every waiting player home. Putting an
  arena in edit mode or deleting it sends everyone in its waiting room home, so a room never
  outlives the definition it opened with.
- Saved arena copies: finishing an edit copies the arena's blocks, block entities
  included, to `plugins/NinjaArena/arena-copies/<arena>/` as structure files, one per
  chunk column. Every arena is reset from its copy on startup, so what a match left
  behind is undone whether the server stopped cleanly or crashed: auto-save being off
  keeps the arena world from saving while the server runs, but the shutdown save and
  `/save-all` still write it. An arena with no copy is unavailable; one built before
  copies existed is copied as it is on the first start. `ArenaRegistry#reset` resets one
  arena, for the match engine at match start and end.
- The round loop. A match holds its arena from start to finish and resets it from the
  saved copy before the players arrive and again when it ends; if the arena cannot be held
  or reset at the start, the players are told and sent home. Each round is a prep phase
  at the team spawns with no damage taken (`match.prep-seconds`, 15 by default), combat
  for `match.round-seconds` (240), then Overrun, at maximum intensity, until one team is
  left or `match.overrun-limit-seconds` (120, 0 for none) runs out as a draw. Every player
  is healed and teleported to their team spawn at the start of each round.
- A round is won by the last team with a player alive and drawn when the last players of
  every team go down in the same tick. A death takes the player out of the round, and they
  respawn at their team spawn; leaving takes them out of the round and the match. After
  `match.rounds` rounds (5) the team with the most round wins takes the match, and a tied
  lead plays up to `match.extra-rounds` extra rounds (1) before the leaders share the win.
  A match where fewer than two teams still have players ends as a forfeit. The result is
  shown for `match.match-end-seconds` (10), then every player is sent home.
- Leaving a match: `/ninjaarena leave` and being teleported out of the arena world send
  the player home, a disconnect restores them on their next join, and disabling the plugin
  stops every match and sends everyone home.
- Match events for other features to build on: phase changes, round start once the teams
  are placed, round end, player eliminated, player left and match end, plus a read API for
  the match a player is in, its phase, their team and who is alive, and a replaceable team
  ranking for scoring.
