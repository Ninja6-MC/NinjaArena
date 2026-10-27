# Changelog

All notable changes to **NinjaArena** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

Nothing has been released yet. The plugin targets Paper 26.3, needs the server to run on
Java 25, and does not run on Folia.

### Added
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
