<p align="center">
  <img src="docs/assets/icon-256.png" width="128" height="128" alt="NinjaArena">
</p>

# NinjaArena

Team vs Team vs Enemies arena plugin for PaperMC: walk through a portal, outplay rival
teams and the horde, and return to where you left.

NinjaArena targets Paper 26.3 and does not run on Folia.

## Building

Requires JDK 25, which paper-api 26.3 is built for. Gradle finds a matching local JDK
through its toolchain support; the wrapper downloads Gradle itself.

```sh
./gradlew build
```

On Windows, use `gradlew.bat build`. The plugin JAR is written to `build/libs/`, and
`./gradlew test` runs the unit tests on their own.

## Setting up an arena

Admins need `ninjaarena.admin`. `/narena` on its own lists the `arena` and `portal`
command groups. A first arena, in the order you need it:

1. `/narena arena create dojo` registers the arena in the next free cell of the arena
   world and puts it in edit mode. The reply shows the cell's coordinates.
2. Switch to creative, then `/narena arena tp dojo` takes you to the middle of the cell.
   Build the arena inside the cell.
3. `/narena arena setbounds dojo <x1> <y1> <z1> <x2> <y2> <z2>` marks the playable box.
   It must fit inside the cell, and everything below must be inside it. `~` and `~n` are
   relative to where you stand.
4. Stand at each team base and run `/narena arena setteamspawn dojo 1`, then `2`, up to
   four teams. Two are required.
5. Stand where players wait before the match and run `/narena arena setwaitingroom dojo`.
6. Stand where enemies should appear and run `/narena arena addenemyspawn dojo`. At least
   one is required; enemies spawn there during rounds.
7. `/narena arena edit dojo off` saves the arena and leaves edit mode. If anything is
   still missing it is listed; `/narena arena edit dojo on` lets you fix it.
   `/narena arena info dojo` shows the current setup and problems at any time.
8. Back in the main world, build a doorway frame for the portal. Stand on the floor
   inside it and run `/narena portal setcorner 1`, then `/narena portal setcorner 2 ~ ~2 ~`
   so the portal is a doorway rather than a flat pad. Move sideways first for a wider
   door. `/narena portal create dojo-door dojo` binds it to the arena.

## Icon artwork

The geometry master is `docs/assets/icon-master.svg`. All other SVG and PNG icons
are exported from it; edit the master and run `node scripts/export-icons.mjs`.
The exporter requires Node.js 22 or newer and installs its pinned rasterizer under
the ignored `build/icon-tools/` directory on first use.

Run `node --test scripts/export-icons.test.mjs` for the geometry and export tests,
and `node scripts/export-icons.mjs --check` to verify the committed exports.
The small cut covers 16–64 px, including a 20 px preview. Full-size, rounded,
transparent light and dark, and monochrome variants live beside the master.

## Building an arena

Arenas are built in the void world `ninjaarena_arenas`. `/narena arena create <name>` puts
the new arena in edit mode in an empty grid cell; `/narena arena tp <name>` takes you there
and reports the cell and its bounds. Switch to creative or turn on flight before building:
until a waiting room is set, `tp` lands you over the cell centre with nothing below, and
turns flight on for survival and adventure players once the teleport is done (after
plugins such as EssentialsX have reset flight for the world change). The arena world
keeps inventories on death, so a fall while building loses no items; match deaths are
handled by the match as described below.

## Round kits and inventory

Players arrive in the waiting room with an empty inventory. Once their snapshot is saved
and they are in the room, their storage, armour, offhand, cursor and crafting grid are
cleared, their potion effects and fire are removed, and they are put in adventure mode.
The waiting room is locked like a match: no item drops or pickups, no containers or ender
chests (including ones opened by a command), and no armour stands. A player whose
inventory cannot be emptied is sent home through the restore instead. Leaving returns
exactly what they came in with, game mode and effects included.

`plugins/NinjaArena/kits.yml` defines one shared kit. Each round clears storage, armour,
offhand, cursor and the personal crafting grid before applying the kit fresh. A player
still on the death screen receives it after being placed on respawn; eliminated players
who respawn to spectate receive no new kit. Leaving or match close uses the existing
snapshot restore to return the player's own inventory.

The bundled template holds the ninja kit tools (see below). On a fresh install, the plugin
writes that template before loading it. If creation fails or the file is still missing, a
clear message explains the empty-kit fallback. Malformed files also use an empty kit and
remain available for repair. Add weapons and armour before enabling the plugin:

```yaml
format: 1
items:
  - slot: 0
    material: WOODEN_SWORD
  - slot: 1
    id: ninjaarena:shuriken
    amount: 8
  - slot: 2
    id: ninjaarena:smoke-bomb
    amount: 2
  - slot: 3
    id: ninjaarena:dash
```

Slots 0-8 are the hotbar, 9-35 storage, 36 boots, 37 leggings, 38 chestplate, 39 helmet and
40 offhand. Each slot may appear once. Entries use exactly one of `material` or `id`;
amount defaults to 1, must be 1-99, and cannot exceed the resolved item's stack limit.
Unknown keys and malformed entries reject the whole definition. Known materials that
cannot be items (such as WATER, FIRE or NETHER_PORTAL) and air entries are skipped at load
with a warning naming the file and entry, while valid entries remain. An unresolved item ID,
an empty provider result or a provider failure logs the cause and applies an empty kit
instead of a partial one.

Custom tools can register a named provider on the server thread before the first round:

```java
plugin.kitItems().register("ninjaarena:smoke-bomb", player -> createSmokeBomb(player));
```

Then a kit entry can use `id: ninjaarena:smoke-bomb` instead of `material`. IDs are
namespaced and lowercase. Providers are resolved when applying a kit, and each returned
stack is cloned before its amount is set, preserving custom item data. The provider seam
does not implement the tool itself.

Players in a match cannot drop items, pick up items or arrows, open external inventories
(including ender chests), or transfer kit items to container blocks or entities. They can
arrange their personal inventory. Lockdown uses match membership, so admins building in
edit mode and other nonparticipants retain normal inventory access.
Every match exit clears kit storage, armour, offhand, cursor and crafting contents before
the snapshot restore, including deferred teleport exits, match close and plugin disable.
Quitting clears the kit before player data is saved. A failed restore therefore leaves an
empty inventory rather than allowing arena items to be stashed before the snapshot retry;
the snapshot still holds the player's real inventory.

## Ninja kit tools

Three tools ship with the plugin, each a vanilla item recognised by a data tag, so
Bedrock players see the base item. The kit decides who gets them: `amount` is how many
each player receives per round, and leaving an entry out turns that tool off for the kit.

| Kit ID | Item | Default kit | Effect |
| :--- | :--- | :--- | :--- |
| `ninjaarena:smoke-bomb` | Snowball | 2 | Thrown. Where it lands, a 3-block cloud lasts 4 seconds and blinds rival players inside it. |
| `ninjaarena:shuriken` | Snowball | 8 | Thrown. A hit deals 2 damage and makes a rival glow for 3 seconds. |
| `ninjaarena:dash` | Feather | 1 | Right-click for a short forward burst. 8-second cooldown, shown on the item. Not consumed. |

Tools work only for players alive in the round during COMBAT and OVERRUN. During PREP,
between rounds, or once eliminated, a throw or dash is cancelled and the item kept. Smoke
never blinds the thrower, teammates, eliminated players or anyone outside the match,
whatever `match.friendly-fire` says. Shuriken damage goes through the normal damage
rules, including friendly fire. A dash replaces the player's velocity instead of adding
to it, is shortened so it cannot carry them past the arena bounds, and is refused while
gliding, flying, riptiding or riding. Flying tools, clouds, cooldowns and glow marks are
cleared at the end of each round and match, when a player leaves or is eliminated, and on
plugin disable. Durations, radius, damage, cooldown and dash speed are under `tools` in
`config.yml`.

`plugin.tools().isMarked(uuid)` reports whether a player is glowing from a shuriken hit,
and `plugin.tools().inSmoke(location)` whether a place is inside a smoke cloud, for enemy
targeting.

### Stealth and enemy aggro

Enemies pick their targets by weight, and stealth and the tools steer that weight:

- A player who crouches where no enemy can see them, or crouches in low light (level 7 or
  below), counts as hidden and weighs a quarter as much.
- A player marked by a shuriken weighs twice as much while the glow lasts. The hidden cut,
  the mark and the leading-team bias multiply together.
- An enemy that has not seen the player it chases for 3 seconds loses track of them and
  goes for another player.
- An enemy inside a smoke cloud drops its target and takes no other while inside.

Each enemy type has an `ignores-stealth` setting, off by default, that skips the hidden cut
and the lose-track timer; smoke and marks still apply. Every weight and timing is under
`director.stealth` in `config.yml`.

## Deaths and spectating

Players in a match drop no items or XP on death and keep their inventory and levels.
After respawning, eliminated players spectate living teammates until the next round.
Spectator cameras can target only their own team; movement and teleports stay inside the
arena and within 16 blocks of a living teammate. If nobody on the team remains alive and
inside the arena, spectators wait near their own team base.
Spectators can detach the camera to move within that limit or choose another teammate;
the tether reattaches only after an invalid target or movement outside the allowed area.

The next round returns spectators to Adventure mode at their team base. A player still
on the death screen at round start is placed and readied when they respawn. Use
`/narena leave` to leave while spectating: the existing restore returns the saved
belongings, game mode and location. Quitting leaves the restore to the next join.
Every exit first clears spectator mode, so an unsuccessful restore leaves the player in
Adventure mode while their snapshot remains available for a retry.

## Match scoring

Teams earn 5 points for a round win and 2 for killing an opposing player in the same
match. Configure these awards with `scoring.round-win-points` and
`scoring.player-kill-points`; zero disables an award and negative values become zero.
Teamkills, suicides, environmental deaths, leaving and drawn rounds award nothing.
Points decide the match standings and whether an extra round is needed. The final points
table is shown to the match's online players before they return home.

Other features can award team points on the server thread through
`plugin.scores().addPoints(match, team, points)`, using the live `MatchView` from
`plugin.matches()`. Awards are accepted from PREP through ROUND_END for teams that started
with players. Totals stop at `Integer.MAX_VALUE`. Once the match ends its result keeps the
final scores, further awards are refused, and a new match starts at zero.

## Licence

GPL-3.0. See [`LICENSE`](LICENSE).
