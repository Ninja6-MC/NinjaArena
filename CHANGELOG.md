# Changelog

All notable changes to **NinjaArena** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

Nothing has been released yet. The plugin targets Paper 26.3, needs the server to run on
Java 25, and does not run on Folia.

### Added
- Tick-budgeted placement of generated maps. Slot cells are cleared and maps placed
  within a time budget per tick (`stability.placement.budget-ms`, 10 ms) shared fairly
  between every slot generating at once, measured rather than counted in blocks. The
  budget drops to `slow-budget-ms` (1 ms; 0 pauses) while the tick-time guard holds
  enemy spawning back, and climbs back over `recovery-seconds` (10) once it lets go;
  spawning and placement share one tick-time reading per tick. `max-map-size` (256) caps
  the cell size generated maps are sized for, so a large `arena-grid.cell-size` no
  longer means larger maps on a shared host. All four keys are restart-only. Internal:
  `GeneratedMaps#progress` reports blocks done out of total for a map being prepared,
  for the room's boss bar; `CellWriter` takes the slot cell and returns `Work` with its
  progress; `TickCellWriter` is replaced by `BudgetedCellWriter`.
- Slot cells for generated maps. `arenas.max-active` cells of the arena grid, farthest
  from the origin first, are reserved for generated maps; `arena create` skips them and
  refuses one named by hand (`arena.error.cell-reserved`), and a hand-built arena already
  in one keeps it while the next free cell is reserved, with a log line. A reload never
  moves a reserved cell or takes one a map still uses. Slot cells are cleared on startup,
  when the arena world reopens after a retire and before every map is placed, and arena
  edits are refused while one is in use (`arena.error.edit-while-generated`).
- Internal: the generated map lifecycle. Rooms can play a generated map from a seed and
  a difficulty (`RoomService#playGenerated`; commands come later): the map is searched
  off the server thread, placed in a slot cell over several ticks, played like any
  arena, and cleared and generated again after each match. When no map comes from the
  seed, the room plays its last good seed, else a hand-built arena, and tells its players
  which. Generated maps are held in memory only, under names no hand-built arena can
  have, and never reach `arenas.yml`. Matches find maps through a new `MapSource`, and
  read team spawns again at every round. New `room.map-generating` and `room.map-ready`
  messages.
- Rejoining a match after a disconnect. A player who disconnects before the match ends
  is held in it for `match.rejoin-grace-seconds` (60; 0 to 600, 0 keeps a disconnect a
  leave): still on their team and in their room, out of the round, with their points,
  pending shop purchases and snapshot kept, and their team does not forfeit meanwhile.
  Back in time, they play at once during prep, return to the shop area during the shop,
  and otherwise spectate their team until the next round. When the grace runs out they
  leave as before; a match that ends first lets them go to the lobby on their next join.
  The grace applies to new disconnects on reload. New `PlayerRejoinMatchEvent`, a
  `MatchUpdate.Cause.DISCONNECTED` elimination cause, and `MatchView#isDisconnected`.
- Internal: a movement solver that checks each generated map can be crossed from every
  team spawn to the centre whichever crumbling blocks collapse, measures its challenge
  against per-difficulty minimums, places team spawns, enemy spawns and the waiting
  room, and retries derived seeds, with a fallback decision for rooms to use later.
- Internal: seeded lava falls, low-ground rivers, hanging lava, gaps and collapsing
  ground and platform rims in the terrain model, increasing with difficulty and kept
  outside spawn, the centre ledge and their blends. Placement and activation come later.
- Internal: a full-map view with mirrored sides for 2 teams, quarter-turns for 4 teams,
  a flat centre and frame helpers for later path checks and placement.
- Internal: a seeded terrain generator that builds one team's side of a generated map
  from a seed and a difficulty, identical on every run. Not used by matches yet; see
  [the terrain generator notes](docs/terrain-generator.md).
- Configurable personal sound cues for rooms, ready, match starts, purchases, tool
  cooldown completions, enemy spawns and round results, plus a future hazard warning.
  Each cue can be disabled and reload applies immediately to running matches.
- Room hosts with `ninjaarena.map.change` can choose a hand-built map with
  `/narena map set <arena>`; room members can browse `/narena map list`. Admins mark
  arenas with `/narena arena pickable <arena> <on|off>`, stored as optional
  `pickable: true` in `arenas.yml` format 1. Map changes move the room's slot lease,
  adjust its team count and tell every member who changed it and its queue position.
- A shop phase between rounds. After each round that another round follows, extra
  rounds included, players, eliminated players and spectators go to the lobby world's
  shop area, stay in the match with their inventory, snapshot and points, and come back
  at the next prep. Buying from `shop.yml` is open only then. It lasts
  `shop.max-seconds` (45); once everyone has run the new `/narena shop ready`, it ends
  after `shop.quick-start-seconds` (5). It never ends before the next map is ready, and
  after `shop.map-wait-limit-seconds` (30) past its time limit the round is played on the
  current arena, reset first. No shop follows the last round, the final extra round or a
  forfeit, and a team leaving during the shop forfeits at once. A room keeps its slot
  lease through the shop. `shop.enabled: false` keeps the direct round-to-round flow.
  Shop settings apply to the next match on reload; the boss bar shows the shop countdown
  and the ready count, and `MatchPhaseChangeEvent` reports `MatchPhase.SHOP`.
- First-time lobby help covering tools, the shop and arena hazards, remembered across
  restarts. `/narena intro` shows it again in the lobby; servers using chest menus get
  the same help in chat.
- Host-started rooms. `/narena room create` opens a room in the lobby; its host sets the
  required player count, teams, team size and privacy with `/narena room settings`,
  invites with a clickable join button, removes players with a button or
  `/narena room kick`, and starts the match with `/narena room start` once enough have
  joined. Nothing starts a room's match by itself; an admin may also press start. When
  the host leaves, the longest-waiting player with the new `ninjaarena.room.create`
  permission takes over, or the room closes. That permission defaults to everyone and is
  granted by `ninjaarena.play`, so servers without ops can play.
- Arena slot leases. A room leases a slot for its hand-built arena when it is created and
  keeps it through every round to the end of the match; while every slot is leased, more
  rooms queue and see their place.
- A validated `shop.yml` catalogue with personal-point purchases, per-round buy limits,
  armour, extra thrown-tool uses, potion effects and consumables. Purchases apply after
  round kits; undelivered purchases are refunded while the ledger exists. The new
  `ninjaarena.shop.use` permission defaults to true and is a child of `ninjaarena.play`.
  Buying is open during the shop phase between rounds.

- A prebuilt obsidian lobby, placed in `ninjaarena_lobby` the first time the plugin
  finds that world without it. Restarts and arena-world retirements leave the lobby
  as it is. `/narena admin lobby edit` lets an admin change it, `done` saves it and
  ends edit mode, and `reset` puts the bundled build back. These need the new
  `ninjaarena.admin.lobby` permission, granted by `ninjaarena.admin`. The build is
  generated from a readable spec with `./gradlew generateLobbyBuild`, and the spec
  marks the spawn, room area, shop area and board positions.
- Lobby membership, a player state like a waiting room, for rooms to use: members'
  inventories are locked, the join restore and trophy delivery treat them as busy,
  and members who leave the lobby area return to its spawn. Nothing takes damage in
  the lobby world, a fall into its void returns the player to the lobby spawn, and
  no death there counts in a match. A claimed trophy is never delivered while the
  player is in the arena or lobby world.

- A second void world, `ninjaarena_lobby`, created or loaded at startup beside the
  arena world. It is saved normally and never unloaded, keeps inventories, has no
  mobs, weather or day cycle, and blocks environmental block changes at all times.
  Rooms bring players there. Any other teleport into it takes a match player out of the
  match. Portals cannot be created in it. World-manager plugins must leave both worlds
  alone (see the plugin compatibility audit).

- Each release is one GitHub release carrying the plugin JAR with the Java and Bedrock
  resource packs, the Geyser mappings and a `SHA256SUMS` file, all built once from the
  tagged commit. The release candidate tests the downloaded JAR and packs before a
  gated publisher, approved by the maintainer, uploads those same bytes. Alpha, beta
  and rc tags are published as pre-releases, and only the highest stable release is
  marked latest. `RELEASE_PROCESS.md` describes the procedure and how to retry it.

- Per-player shuriken and smoke bomb cooldown and per-round limit hints in the match
  action bar on Java and Bedrock. Used-up hints take priority over cooldown seconds;
  ready tools leave the existing status unchanged. Hint text and separators are
  configurable in `messages.yml` without changing `hud.status`.

- Separate shuriken and smoke bomb cooldown groups and configurable per-round limits.
  Both tools default to unlimited, non-consuming throws. Each has `cooldown-seconds`
  (0-60, default 0) and `uses-per-round` (0-64, default 0) under `tools`; kit amounts
  that disagree warn once per load. Reload keeps running cooldown deadlines.

- The texture chest menu now opens during a match while inventory lockdown still
  prevents kit transfers through it.

- Personal points for player and enemy kills, round survival and shared round wins,
  configurable under `points` and shown per player in the match action bar. Points carry
  across rounds and reset on leaving or match end; team scoring is unchanged.

- Players choose Standard (16x16) or Detailed (32x32) tool textures in the
  "Arena Texture Quality" menu, opened with `/ninjaarena textures` (permission
  `ninjaarena.play`). Standard is the default; Detailed is used only after an
  explicit choice. On Java, a saved Detailed choice requests the optional overlay
  once Standard has loaded; declining or failing it keeps the base and the choice.
  On Bedrock the choice applies on the next connection through the existing Geyser
  selector. Choices are stored by authenticated Java UUID or Bedrock XUID in their
  own tables of the existing storage (SQLite or MySQL), independent of statistics and
  seasons. `/narena admin textures reset <java|bedrock> <uuid|xuid>` restores
  Standard. Real-client menu, pack loading, live Geyser timing and real MySQL are
  unverified; see `resourcepack/README.md`.

- `resource-pack.java-login-gate` (default `false`, restart required) declares that a
  login plugin gates Java accounts on an offline-mode server. When true, Java accounts
  count as authenticated and the existing `java:<uuid>` preference is saved and applied
  as on an online-mode server. Whoever holds a player name can then change that name's
  cosmetic quality, so enable it only where a login plugin blocks commands before login
  (AuthMe by default). With it off, Java players on such a server can still pick a
  quality for the current connection only; it is not saved and resets when they leave.
  `/narena pack status` and the startup line now report whether Java accounts are
  treated as authenticated and why.

- `resource-pack.texture-menu` (`dialog` or `chest`, default `dialog`, restart required)
  chooses the screen for `/ninjaarena textures`. The dialog is a native Paper dialog with
  three short body lines and Standard, Detailed and Close buttons, and Geyser 2.8.2 or newer
  shows it to Bedrock players as a form. Its buttons use the same service as the chest
  menu, with the same saving, session-only and out-of-date behaviour. If the dialog cannot
  be sent, that opening uses the chest menu and the failure is logged once. An unknown
  value uses `dialog` with a warning. Set `chest` if the dialog does not show. `/narena
  pack status` reports the menu in use.

- `messages.yml` holds the player-facing text in MiniMessage format with named
  placeholders such as `<arena>` and `<player>`, so server owners can reword or translate
  the plugin. It is saved on first start and reloaded by `/narena admin reload`. Typed
  input is inserted as plain text. A missing key, or one MiniMessage cannot read, uses
  the bundled default with one warning per key. Customised `events.messages` texts in
  `config.yml` are moved into it on first start; those keys are now deprecated. Command
  argument syntax, errors from the system itself and the reasons a reload gives for
  rejecting `config.yml` or `kits.yml` stay in English.

### Changed
- Arena waiting-room points are optional: a ready arena only needs bounds, two to four
  team spawns and an enemy spawn. `setwaitingroom` still sets where `arena tp` lands.
- No match starts by itself: waiting rooms, their countdown and the shared hub are
  replaced by rooms, and every match is a room's, started by its host or an admin, and
  holds an arena slot lease. A room that closes while its match runs keeps the lease
  until the match ends. Existing `config.yml` and `portals.yml` files still load, with a
  startup warning per old setting that points to the new README section "Moving from
  waiting rooms to rooms".
- Play portals lead to the lobby, saving the snapshot there, and put the player in the
  open room with the most players that admits them: on the portal's arena for an old
  dedicated portal, any room for an old hub portal or a player-built portal. With no
  such room the player stays in the lobby and sees the room list. Arena use or edit mode
  no longer turns portal players away. Spectator portals still lead to the live match.
- `/narena host <arena>` opens an invite-only room on that arena (it need not be
  pickable); `/narena invite` and `/narena accept` are the room's invite and accept.
  Nothing is reserved without a room any more.
- `/narena spectate <arena>` works from the lobby, for a player in no room, instead of
  from a waiting room.
- `/narena room` outside a room lists the open rooms with join buttons. During a
  scheduled event it names the featured map and marks rooms on it; a new room plays on
  that map when it is free, hub and player-built portals prefer rooms on it, and a room's
  match there earns the event bonus. Events still never start a match.
- `waiting-room.min-players` and `team-size` are what a new room starts from, applied to
  the next room on reload. An old hub point no longer limits `arena setbounds`.
- `stability.max-concurrent-matches` is now `arenas.max-active` (default 4), at most the
  cells of the arena grid. It counts room leases, applies on reload, and a lower value
  never ends a lease already granted. The old name is still read, with a warning, when
  the new one is not set.
- The end of a match, and `/narena leave` from a room, a match or spectating, now lead to
  the lobby with the snapshot kept rather than home; `/narena leave` from the lobby goes
  home. A player who quits from the lobby, a room, a match or spectating rejoins in the
  lobby while the server keeps running. A teleport out of the plugin's worlds still sends
  players home.

- No snapshot is taken in the arena or lobby world, and a restore never lands in either:
  entering an arena from them is refused, and a saved spot or respawn point there is
  skipped for the next fallback without reopening the arena world.

- Each Java connection gets one Detailed overlay request through `ResourcePacks`;
  `overlay(UUID)` reports its state.

### Deprecated
- `/narena portal lobby` still sets a portal's choice, which now only picks which open
  rooms its players join, and says it is deprecated. `/narena portal sethub` checks its
  arguments, saves nothing and says the lobby took the hub's place; `portal removehub`
  clears an old hub point.
- `waiting-room.lobby` is still read as the default for portals with no choice of their
  own, with a warning. `waiting-room.max-players`, `countdown-seconds` and `radius` are
  ignored with a warning; a reload accepts all four and names them in its reply.
- The `lobby:` value of a portal and `hub:` in `portals.yml` are still read, with a
  startup warning.

### Fixed
- Servers sharing one SQLite file no longer lose matches when they create the file
  together: the plugin creates the file before the bundled SQLite driver opens it, since
  that driver deletes a missing file it has just created to test write access. Stats
  and season writes take the write lock before they read, so a server waits for the
  other's commit instead of reporting the database as unusable, and the switch to WAL
  waits for its lock (#181).
- A join restore that another plugin cancelled, such as a login plugin holding the
  player at a spawn, is retried after 20, 100, 200, 400 and 600 ticks. The snapshot
  stays until a restore succeeds, a retry replaces the inventory instead of adding
  to it, and the player is told if the last attempt fails. Any other plugin's
  teleport within 100 ticks after the restore (a plot placement, but also a spawn or
  hub command) is undone once so the player ends at the restored spot; NinjaArena's
  own teleports and players in a match, waiting room or hub are never undone. A player
  who joins dead is restored on respawn, without retries or this window. Simulated in tests; not yet checked
  against AuthMe or SpiralGenesis on a test server. Refs #105, #106.
- Standard and Detailed Java and Bedrock packs show the NinjaArena plugin icon in the pack list.
  Build and downloaded-pack checks reject missing or changed icons (#127).
- The Geyser mappings now give the Bedrock dash item a cooldown (`minecraft:use_cooldown`,
  group `minecraft:feather`, 8 seconds), so Geyser's cooldown packet matches the item and
  the hotbar overlay can show after a dash. Reinstall the mappings from the next release.
  Not verified on a real Bedrock client (#128).
- Startup rejects removed Bedrock pack switches with the same replacement guidance
  as reload, logs an operator error and keeps Bedrock delivery off until they are
  removed. Java delivery and the rest of the plugin continue running.
- Resource pack delivery now uses shared `enabled` and `required` switches for Java
  and Bedrock. Bedrock custom held models require Geyser's force-pack file check
  and the narrowly scoped `bedrock.mappings-installed` assertion. Optional Bedrock
  policy keeps vanilla tools without an enforcement warning. Removed Bedrock
  switches are rejected on reload with replacements; file and SHA-256 keys stay.
- Startup and admin-only `/narena pack status` report both platforms and the reason
  for vanilla Bedrock tools. Status reads cached policy, not client load outcomes.
- Idle arena worlds now unload without saving through the world lifecycle, releasing
  Paper's partial chunk holders as well as full chunks. A later use reopens the same world
  and restores authoritative arena copies before admission. Pending entry and teleport
  operations, players, tickets, edits, held arenas and failed resets prevent retirement.
  This does not bound partial holders in a world that remains continuously busy.
- Leaving an arena clears arena-sourced player combat references before restoration,
  including deferred and failed restores, so removed enemies do not retain retired
  worlds through Paper's last damage source. Other-world damage records are preserved.
- Director enemies remain protected from daylight ignition when Paper ignores their
  daylight-burning flag. Fire, lava and attacks can still ignite them.

### Changed
- Confirmed the restore-position decision for #107: keep the saved in-gate position,
  including existing pending snapshots, subject to the usual safe-landing checks.
  The existing portal-region crossing guard prevents re-entry while stationary or moving
  within the same gate; leaving and walking back in enters normally. No timed cooldown
  or restore-position migration is needed. Added regression coverage for the guard across
  logout, death, restart and teleport transitions using simulated player state.

### Added
- Complete Standard and Detailed Bedrock tool packs with deterministic builds, pinned
  artifacts and fifteen accent-colour mappings through Geyser. A same-server Paper hook
  selects one quality by XUID, defaulting to Standard. Bedrock held models require an
  explicit operator assertion that Geyser enforces packs; client load outcomes remain
  unobservable, and flight keeps the vanilla snowball appearance.
- Opt-in Java resource-pack delivery with required or optional loading, bounded failures,
  versioned Standard and Detailed ZIPs and SHA-1 hashes. Tool models and team accents are
  applied only for loaded recipients. Thrown tools use custom artwork only when all match
  participants, spectators and arena-world players have loaded the base at throw time;
  `resource-pack.thrown-visuals: false` keeps vanilla flight. Detailed stays API-only.
  Portal artwork and Bedrock delivery remain deferred.
- Weekly arena events with configurable announcements, featured-arena matchmaking,
  optional Discord delivery and capped bonus rewards. Admins can list, start or cancel
  event occurrences with `/narena event`.
- Configurable milestone and season placing rewards with cached title and chat tag
  placeholders, pending cosmetic trophies, and opt-in Vault payouts with a daily cap.
- Player-built portals. A crying obsidian frame shaped like a nether portal (opening 2x3
  up to 21x21, either axis, corners optional) and lit with flint and steel opens a portal
  with a coloured particle curtain that needs no resource pack. It follows
  `waiting-room.lobby`: the shared hub, or a filling waiting room, else a free arena, else
  "All arenas are busy". Entry uses the admin portals' checks, snapshot and empty
  inventory. `player-portals` in `config.yml` sets the allowed worlds (never the arena
  world), frame block, per-player limit, minimum distance and colour, and reloads safely.
  Breaking any frame block removes the portal; the frame is also checked while players are
  near. Portals persist in `portals.yml`, which earlier versions refuse once it holds one.
- Shared or dedicated lobbies. A shared hub, set with `/narena portal sethub`, gathers
  players from every shared portal and sends the first arrivals to a free arena's teams
  once enough are there; the rest wait for the next free arena. Each portal chooses with
  `/narena portal lobby`, and portals that do not choose, player-built ones included, use
  `waiting-room.lobby`. The hub keeps the waiting-room inventory, lockdown and restore
  rules, holds while arenas are edited, and sends its players home when moved, removed or
  on shutdown.
- Player caps: `waiting-room.max-players` and `waiting-room.team-size` limit one match in
  both modes. A full dedicated room turns arrivals away with a message before their
  snapshot is saved; spectators never count.
- View all-time or current-season top tens with `/narena leaderboard`. PlaceholderAPI
  can show personal stats, ranked scores and names, and the current season.
- Player stats and seasons: wins, rounds won, player kills, enemy kills, deaths, best
  Overrun survival and matches played, all-time and per season, written in batches off
  the server thread when a match ends. SQLite by default; MySQL or MariaDB lets several
  servers share one set of tables. Changing `stats.season` archives the current season
  without deleting it. Unreachable databases are retried, and the queue is flushed on
  shutdown. `plugin.stats()` reads a player's stats, top lists and seasons.
- Waiting players and spectator portal entrants can watch live matches within the arena
  bounds. Viewers use the existing snapshot and restore flow, with separate admission
  limits and no effect on team rosters or enemy pressure.
- The Shadow Stalker signature enemy: a wither skeleton changed only through attributes,
  equipment, particles and Paper Mob Goals, so Bedrock players see it without a resource
  pack. It ignores stealth by default (`ignores-stealth.shadow-stalker`), hunts the leading
  team, and when its target is out of reach blinks behind them after a particle tell and
  makes them glow for 3 seconds. The director spends its threat cost, caps it at one per
  match by default, and removes it with the other enemies. Health, damage, speed, size,
  blink timing and range and the glow are under `director.shadow-stalker`.
- Unit coverage for composed director pressure, repeated binary snapshots and safe-landing
  search boundaries. A separate local Paper stability probe records tick durations, decoded
  save comparisons and pending chunkholders. The short diagnostic reproduces retained
  holders after matches; the full acceptance soak remains incomplete.
- Safe `/narena admin reload` validates both configuration and kits with file and line
  diagnostics before applying changes. Waiting-room, tool and world-distance settings
  apply immediately; running countdowns, matches and round kits retain their values at
  the documented boundaries. Empty registries can resize the grid and border; occupied
  ones refuse those changes. Replies separate changed, postponed and refused settings,
  while arenas, portals and snapshots are never reloaded.
- Hosted matches with plain-command invitations and explicit acceptance. Opening and
  inviting move nobody; accepted entry reuses portal snapshot and teleport safeguards.
  Reservations end on host exit, room closure, match start, arena changes and shutdown.
  Host and accepted invitees stay together within balanced team capacity and reunite when
  later arrivals allow it. Failed entries retain invitations for retry; leave cancels an
  accepted entry still loading.
- Permission-filtered command help and completion for player, host and admin routes.
  Existing leave, arena, portal and director paths remain, and every
  permission tier declares its children explicitly.
- Round feedback: a round timer bossbar that turns red for Overrun, team points and living
  enemy counts in the actionbar, round start and end titles, and standard sound cues for
  successful enemy spawns and round ends. Eliminated players retain the HUD while
  spectating; leaving, match end and plugin disable clear it.
- Enemy spawn diagnostics. When no enemy spawn point qualifies, one log line names the check
  that refused each point (too close, in view, chunk not loaded, blocked space), and a
  spawn the world cancels is logged too; each reason at most once per round per match, at
  FINE, or at INFO with `director.debug` on. `/narena director [arena]` (admins) shows the
  round budget, intensity, enemies in play with their threat and the last refused or
  cancelled spawn for the match the admin is in or the one running in the named arena.
  Budget and spawn-gate limits also explain why spawning is held back, once per reason per
  round at the same log level, and appear in the director status.
- Enemy lifecycle and arena teardown: enemies are non-persistent, cannot pick up items,
  and drop neither items nor experience. Enemies stuck within a quarter block or outside
  their arena for 10 seconds are removed, returning their threat cost through the director.
  Player kills award the enemy's configured cost to the killer's team. Match end and plugin
  disable remove tracked enemies, including escapees, and clear mobs, projectiles, items
  and experience orbs inside the arena before the existing reset and chunk-ticket release.
- A per-match enemy director. Once a second during COMBAT and OVERRUN it works out a target
  intensity and spawns zombies, skeletons and spiders through the spawn-position selection
  until the cost of those alive reaches intensity x the round's threat budget; an enemy
  that dies or is removed returns its cost. Zombies and skeletons do not burn under the
  arena world's fixed noon. Combat cycles through build-up, peak and a
  short relax, each round peaking higher; combat time ramps it up, while teams knocked out
  and recent damage taken ease it. OVERRUN runs at full intensity. The round budget grows
  each round and with the players alive when the fight starts. Enemies are pointed at a
  player drawn by weight, and players on the team with the most points weigh more. All
  values are under `director.intensity` in `config.yml`. The loop starts with each fight
  and is cancelled when the round or match ends, when the last player leaves and on plugin
  disable. `NinjaArenaPlugin#directors()` exposes each match's director and its enemies, a
  spawn gate for caps and tick-time limits, and a targeting-weight hook.
- Server stability limits under `stability` in `config.yml`: at most 25 enemies alive per
  match and 80 across every match, and at most 4 enemy spawns per server tick. Spawning
  pauses while the average tick time is above 40 ms, and the intensity also drops one tier
  (0.25) above 45 ms. Spawning resumes once it falls below 35 ms, and after a tier drop the
  intensity stays a tier lower for another 30 s. At most 4 matches run at once; a waiting
  room whose countdown ends while every slot is in use holds at its last second, and the
  room held longest starts first when a slot frees up.
- Stealth and aggro steering through the director's targeting-weight hook. A player who
  crouches where no enemy can see them, or crouches in low light, weighs a quarter as
  much; a shuriken-marked player weighs twice as much while marked, and the leading-team
  bias still applies on top. An enemy that has not seen the player it chases for 3 seconds
  loses track and is drawn another player, and an enemy inside a smoke bomb cloud drops
  its target and takes no other while inside. Each enemy type has an `ignores-stealth`
  setting, off by default, that skips the hidden cut and the lose-track timer. Values are
  under `director.stealth` in `config.yml`. `NinjaTools#inSmoke` reports whether a place
  is inside a smoke cloud.
- Enemy spawn-position selection rotates through registered points, skipping points within
  8 blocks of any player, visible to any player, or obstructed by block collision shapes
  for the requested mob dimensions. If no point is valid, selection returns empty.
- Pure threat-budget arithmetic: base x round multiplier x player factor, with a
  configurable base under `director.threat-budget` and enemy costs under
  `director.enemy-costs` (zombie 1, skeleton 2, spider 2, Shadow Stalker 8).
- A shared `kits.yml` kit, cleared and rebuilt every round and on a delayed respawn into
  the current round. Dead and eliminated players are skipped. Missing or invalid files
  log the reason and use a safe empty kit; item-provider failures also use an empty kit.
- Ninja kit tools as kit item IDs: `ninjaarena:smoke-bomb` (a cloud that blinds only
  rival players alive in the round), `ninjaarena:shuriken` (low damage through the normal
  damage rules; a hit rival glows and is marked, exposed as `NinjaTools#isMarked`) and
  `ninjaarena:dash` (a forward burst capped to the arena bounds, with a vanilla item
  cooldown). Vanilla items tagged with persistent data. The bundled kit gives 8
  shuriken, 2 smoke bombs and a dash each round; the `tools` section of `config.yml`
  tunes durations, radius, damage, cooldown and dash speed. Tools work only in COMBAT and
  OVERRUN for players alive in the round, and their projectiles, clouds, cooldowns and
  marks are cleared at round end, match end, leave, elimination and plugin disable.
- Fresh installs write the default kit template before loading. Non-item material entries
  are skipped with a warning at load while valid entries remain. All exits discard kit
  items before restoration or saving quit-player data, so a failed restore cannot leak a kit.
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
  entity block changes are cancelled there too, as are fire, fluid flow, growth, spread,
  forming, fading and leaf decay, so a match cannot change a block outside the arena
  bounds that the saved copy does not cover. All environmental protection is suspended
  while any arena is in edit mode and resumes when the last edit finishes.
- Arena setup help: `/narena` with no or an unknown group lists the `arena`, `portal` and
  `leave` commands with one-line descriptions. `arena edit <name> off` says the arena left
  edit mode and how to return when problems remain, the enemy spawn problem explains what
  the spawns are for, and `portal setcorner` warns about a one-block-tall selection and
  suggests `~ ~2 ~` for a doorway. The README has a first-arena walkthrough.
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
  frozen at a clear noon, natural spawning off, inventories kept on death, a world border
  and a low view and simulation distance, the last three tunable under `arena-world` in
  `config.yml`. Auto-save is off; `ArenaWorld#setSavingEnabled` turns it on for edit mode
  and saves once when it is turned off again.
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
  player is put back where they came from and told why, at most every two seconds. A
  failed teleport to the arena is logged, and its message shares that limit. Entry runs
  on a later tick, never inside the move that crossed into the portal. Teleporting into a
  portal does not enter it, and vanilla portal travel is cancelled inside one.
- Waiting rooms: a player who arrives through a portal joins the smallest of the arena's
  teams, and when a departure leaves one team two behind another, the last player to join
  the larger team moves over. A start countdown begins once `waiting-room.min-players`
  are waiting, runs for `waiting-room.countdown-seconds` and stops if the count drops
  below the minimum; when it finishes, the players are handed to the match engine as a
  team roster. Players in a waiting room take no damage and keep their inventory if they
  die.
- Players arrive in the waiting room with an empty inventory, no potion effects or fire,
  and in adventure mode, once their snapshot is saved. The cursor and crafting grid are
  put back into the inventory before the snapshot. The room is locked like a match: no
  drops, pickups, containers or ender chests. A player who cannot be emptied is sent
  home, and leaving restores exactly what they came in with.
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
  arena, for the match engine at match start and end. A reset loads each chunk's
  entities before clearing them, and the startup reset logs how long it took.
- Arena world chunks that nothing holds (no player near, no arena held by a match) are
  swept every five seconds without being saved, up to 64 full chunks at a time. This
  sweep does not enumerate Paper's partial holders; idle world retirement releases those.
  Nothing is
  unloaded this way while an arena is in edit mode. Plugin disable logs how many chunks
  were unloaded and how many were still loaded. Since the world save can hold what a
  match left behind, starting an edit first resets the arena from its copy, and an edit
  whose copy cannot be read is refused rather than copying that damage.
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

### Changed
- An enemy spawn point now counts as hidden from a player who has no line of sight to it
  or who looks more than 100 degrees away from it (`director.spawn.view-angle`; 180 keeps
  cover as the only way to hide a point), so an arena with a single enemy spawn gets
  enemies whenever nobody faces it. Only the match's players alive in the round count;
  spectators, eliminated players and anyone outside the match no longer refuse a point.
  Points within 8 blocks of such a player, in unloaded chunks or without room for the
  enemy are still skipped, and nothing spawns when no point qualifies.
