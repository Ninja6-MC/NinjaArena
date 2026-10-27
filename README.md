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
