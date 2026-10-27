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

## Icon artwork

The geometry master is `docs/assets/icon-master.svg`. All other SVG and PNG icons
are exported from it; edit the master and run `node scripts/export-icons.mjs`.
The exporter requires Node.js 22 or newer and installs its pinned rasterizer under
the ignored `build/icon-tools/` directory on first use.

Run `node --test scripts/export-icons.test.mjs` for the geometry and export tests,
and `node scripts/export-icons.mjs --check` to verify the committed exports.
The small cut covers 16–64 px, including a 20 px preview. Full-size, rounded,
transparent light and dark, and monochrome variants live beside the master.

## Round kits and inventory

`plugins/NinjaArena/kits.yml` defines one shared kit. Each round clears storage, armour,
offhand, cursor and the personal crafting grid before applying the kit fresh. A player
still on the death screen receives it after being placed on respawn; eliminated players
who respawn to spectate receive no new kit. Leaving or match close uses the existing
snapshot restore to return the player's own inventory.

The bundled template is empty. A missing or invalid file logs the cause and uses an empty
kit, so the plugin can still enable. A missing file also creates the empty template when
possible; a malformed file is kept for repair. Configure it before enabling the plugin:

```yaml
format: 1
items:
  - slot: 0
    material: WOODEN_SWORD
  - slot: 1
    material: ARROW
    amount: 16
```

Slots 0-8 are the hotbar, 9-35 storage, 36 boots, 37 leggings, 38 chestplate, 39 helmet and
40 offhand. Each slot may appear once. Entries use exactly one of `material` or `id`;
amount defaults to 1, must be 1-99, and cannot exceed the resolved item's stack limit.
Unknown keys and malformed entries reject the whole definition. An unresolved item ID,
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
