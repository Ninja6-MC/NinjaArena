# Tool resource packs

Standard provides the ninja tool item definitions, flat item models and 16x16
textures. Detailed is a separate, optional 32x32 texture-only pack, using the same
paths above Standard. Detailed is never enabled automatically; players choose it in
`/ninjaarena textures` (see [Player texture quality](#player-texture-quality)).

```
resourcepack/java/
  standard/{pack.mcmeta,pack.png}
  standard/assets/ninjaarena/{items,models/item,textures/item}
  detailed/{pack.mcmeta,pack.png}
  detailed/assets/ninjaarena/textures/item
```

## Supported version

These packs target **Minecraft Java 26.3**, matching the plugin's Paper
dependency. The resource format is **97.1**, confirmed by the official
[26.3 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)
and the server's `version.json` (`resource_major: 97`, `resource_minor: 1`).
Both `pack.mcmeta` files use `min_format` and `max_format` of `[97, 1]`, the
major/minor metadata introduced in
[25w31a](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w31a).
The optional legacy `pack_format` integer cannot express the minor version and
is omitted. `target.json` records the checked version and source; validation
rejects a target that differs from the Paper dependency or pack metadata.
Verify the corresponding Minecraft release before changing these on an upgrade.

## Build and checks

Use Node.js 22 or later and Python 3.10 or later; no Python packages are needed.

```sh
node artwork/tools/validate-exports.js
node artwork/tools/validate-pack.js
python -m unittest discover -s artwork/tools -p test_java_packs.py -v
python artwork/tools/java-packs.py build --version v0.1.0-rc.1
python artwork/tools/java-packs.py verify --version v0.1.0-rc.1
```

The default output, `build/resourcepacks`, must be empty before building. Retained
candidates are never overwritten. Each build produces:

- `NinjaArena-<version>-java-standard.zip`
- `NinjaArena-<version>-java-detailed.zip`
- `SHA1SUMS`, with the exact SHA-1 values for client delivery
- `manifest.json`, with source SHA, version, channel, target, candidate identity,
  workflow run/attempt, destinations and SHA-256 digests

ZIP entries are sorted with fixed timestamps, so identical sources and versions
produce identical bytes. Each root contains `pack.mcmeta`, `pack.png` and
`assets/`, with no wrapper directory. The pack description embeds the artifact version. An explicit
allowlist excludes editable artwork, previews, 64x64 exports, portal assets and
unrelated files. The builder runs the existing pack validator, including texture
sync, before packaging. Never edit copied textures: refresh them from production
exports with `node artwork/tools/sync-pack-textures.js`.

Both qualities share the plugin icon, a copy of `docs/assets/icon-256.png`
(generated from `docs/assets/icon-master.svg`). After re-exporting the plugin
icon, refresh the pack copies with `python artwork/tools/sync-pack-icons.py`; the
pack validator rejects missing or stale icons. Bedrock conversion reuses these exact bytes as root `pack_icon.png`.
Build and downloaded-candidate verification require the icons and compare their
bytes with the source, including for releases.

Artwork CI builds both ZIPs for pull requests, merge groups, main pushes and
manual runs. Snapshot versions are `ci-<full commit SHA>`. The candidate and
separate downloaded-artifact test evidence are retained for 30 days. Release packs
are built from the release tag by Release Candidate and published with the plugin
JAR by Publish Release; see [the release process](../RELEASE_PROCESS.md).

## Hosting, hashes and pinning

Use an HTTPS URL that directly returns the ZIP without login, cookies or a
landing page. With `required: true`, delivery is only as reliable as the pack host:
use a host you control or a CDN. GitHub release URLs are a convenience for small or
test setups, available once the gated release is published. If a client cannot
reach the pack host, reconnecting usually works.
For example, replace this version with a published release containing the packs:

```
https://github.com/Ninja6-MC/NinjaArena/releases/download/v0.1.0-rc.1/NinjaArena-v0.1.0-rc.1-java-standard.zip
```

Copy the matching 40-character SHA-1 from that release's `SHA1SUMS`. Check locally
with `sha1sum <pack.zip>` or PowerShell's
`Get-FileHash <pack.zip> -Algorithm SHA1`. Hash the actual ZIP bytes, not the source
folder, Actions artifact wrapper or a recompressed copy. The manifest's SHA-256
is a separate artifact integrity check.

Pin a concrete release URL and matching hash, never a `latest` redirect. Do not
replace a published ZIP in place. Update URL and SHA-1 together for new bytes.
Adding or refreshing an icon changes the archive hashes. At the next release,
update both Java quality URL/SHA-1 examples and Bedrock filename/SHA-256 examples
from the verified release assets; see the release checklist. Existing published
release bytes and their pins must remain unchanged.
Clients can reuse a cached ZIP whose hash matches, but each offer still waits for
a successful-load status. A new hash must negotiate again; an older cached version
does not grant loaded status to the update. Actions downloads expire and wrap the
files, so host live delivery on release assets or a dedicated HTTPS server.

## Settings and delivery

```yaml
resource-pack:
  enabled: false
  required: true
  thrown-visuals: true
  java-login-gate: false
  timeout-seconds: 120
  url: ''
  sha1: ''
  id: ''
  detailed:
    url: ''
    sha1: ''
    id: ''
```

The `id` fields are UUID pack identities, independent of content hashes. Choose
distinct identities for Standard and Detailed and do not reuse another plugin's
identity. Keep identities stable across ordinary content updates; change URL and
hash together. Detailed can remain unconfigured until an integration needs it;
a configured overlay needs its own URL, SHA-1 and distinct UUID.

Configure Standard's URL, hash and UUID before enabling delivery. Missing or
invalid enabled configuration gives an operator error before delivery starts,
disables delivery and leaves vanilla tools until fixed and restarted.
Strict reload validation rejects invalid candidates and preserves previous
effective settings. All `resource-pack` settings require a restart except
`thrown-visuals`, which applies to subsequent throws after a reload.
Offers start on subsequent joins; enabling the plugin does not prompt players
who were already online.

Standard is offered on join. Acceptance starts the wait; only successful loading
activates custom tool items. Decline, failed download, failed reload and timeout
are explicit terminal outcomes. Required-pack failures kick the player once with
a reason and do not retry prompts during that connection. With `required: false`,
players continue with vanilla items and unchanged gameplay. Detailed is optional
even when Standard is required.

For integrations, `plugin.resourcePacks()` exposes `ResourcePacks` on the server
thread. `loaded(UUID)` reports successful Standard loading.
`addDetailed(UUID)` adds this plugin's overlay only after Standard loads and
returns false when unavailable or when this connection already used its one
overlay request. `removeDetailed(UUID)` removes only this plugin's overlay.
Neither operation replaces other packs or clears the whole stack. The quality
menu calls this API. `overlay(UUID)` reports `NONE`, `PENDING`, `LOADED`,
`DECLINED`, `FAILED` or `CANCELLED` for the current connection.

## Models, accents and flight

| Tool | Item definition | Textures |
|---|---|---|
| Shuriken | `ninjaarena:shuriken` | `shuriken`, `shuriken_accent` |
| Smoke bomb | `ninjaarena:smoke_bomb` | `smoke_bomb`, `smoke_bomb_accent` |
| Dash | `ninjaarena:dash` | `dash`, `dash_accent` |

Each flat `item/generated` model puts fixed artwork on `layer0` and grayscale
accent on `layer1`. Only the accent reads the first `custom_model_data` colour.
Teams 1–4 use `#FF5555`, `#5555FF`, `#55FF55`, `#FFFF55`; unassigned items use white.
Recognition uses the `ninjaarena:tool` data tag alone, never model, name or colour.

Only tool items given to a player with loaded Standard get a custom `item_model`.
With optional packs, another player lacking the pack may see that held item as
vanilla or a missing model. This is an accepted limitation of `required: false`;
there is no per-viewer item metadata.
Items already handed out are not continually rewritten after a later optional
pack loss; the next kit issue uses the current loaded status.

Shuriken and smoke bombs keep snowball movement and collision. At throw time,
the projectile's display stack gets a custom model and accent only when all
match participants, that match's spectators, and everyone else in the arena world
have loaded Standard. Anyone pending, declined or failed makes that throw a
plain snowball. The decision is made once and is not revised for late viewers or
status changes during flight. `thrown-visuals: false` forces vanilla flight.
There are no following display entities, packets or per-viewer metadata.

Paper 26.3 exposes the projectile's display ItemStack; local inspection confirmed
component copying into synchronized entity data and network registration of
`item_model`. This supports custom flight but is not visual acceptance.

## Scope and unverified behaviour

A real Java client loading these ZIPs, held/thrown looks, both resolutions, team
accents and the optional-pack missing-model case have not been verified.
Coexistence with other plugins' actual packs also needs an in-game check; this
API isolates its own pack identities by construction.

Bedrock loading, held looks, team colours, Geyser negotiation and Floodgate
identities have not been verified on a real client/server. Portal surface artwork is deliberately absent. Its rendering is deferred
until agreement with the player-built portal curtain, and portal colour must
not be inferred from equipment team colours.

## Bedrock builds and mappings

Bedrock Standard and Detailed are separate complete packs at 16x16 and 32x32.
Neither depends on the other. Their permanent header/module identities and
minimum engine target are recorded in `bedrock/target.json`. The minimum target
describes the manifest and atlas features used, not a tested client matrix.
Geyser supplies item definitions from the generated mappings; a behaviour pack
is not shipped or required. Inventory and held items use flat atlas textures.
No attachables, geometry or custom projectile entities are supplied.

The build consumes the Java packs' validated base/accent PNGs, never editable
artwork or review previews. For each tool it composites white, red, blue, green
and yellow variants. Only the grayscale accent is multiplied by the team RGB;
the base is unchanged. Both qualities use the same fifteen Bedrock identifiers,
`ninjaarena:<tool>_<colour>`, and identical mappings and gameplay components.
Java item models remain `ninjaarena:shuriken`, `ninjaarena:smoke_bomb` and
`ninjaarena:dash`. Their first custom-model-data string selects the Bedrock
colour; Java continues using the first custom-model-data colour for its accent.
The string is presentation only. Recognition still reads only `ninjaarena:tool`.
Kits compute the current team when giving tools for each round; already-issued
items are not continually rewritten.

Run these explicit steps in an empty staging/output location:

```sh
python -m unittest discover -s artwork/tools -p 'test_*packs.py' -v
python artwork/tools/bedrock-packs.py convert --version v0.1.0-rc.1
python artwork/tools/bedrock-packs.py mappings --version v0.1.0-rc.1
python artwork/tools/bedrock-packs.py build --version v0.1.0-rc.1
python artwork/tools/bedrock-packs.py verify --version v0.1.0-rc.1
```

Outputs under `build/bedrock-resourcepacks` are both versioned `.mcpack` files,
`NinjaArena-<version>-geyser-mappings.json`, `BEDROCK-SHA1SUMS`, and
`bedrock-manifest.json`. The manifest pins SHA-256 for every output and SHA-1 for
the packs. CI performs conversion and mapping generation before packaging,
then downloads and verifies the retained candidate against repository sources.
The gated release publisher uploads those verified bytes unchanged, in the same
release as the plugin JAR. See
[the release process](../RELEASE_PROCESS.md) for numeric Bedrock version bumps,
snapshot limitations and immutable hosting. Never replace published pack bytes
under an existing UUID/version.

## Same-server Geyser and Floodgate setup

The supported topology is **Geyser-Spigot on the same Paper server as NinjaArena**,
with optional Floodgate. Both are soft dependencies. Without enabled Geyser,
NinjaArena runs normally and does not load Geyser API classes. All platform API
references are isolated in `resourcepack.geyser`; no Geyser implementation classes
are used. The hook registers after Geyser is enabled and unregisters on disable.
A separate extension JAR is not needed for this topology.

The API compile target is Geyser `2.12.1-SNAPSHOT`. Source behaviour was checked
at [bce177a3e68b](https://github.com/GeyserMC/Geyser/tree/bce177a3e68b87f0d38430d41fd0a1ae1a73ce37).
Use Geyser v2 custom item mappings, introduced in API 2.9.3/build 1062; that
minimum for mappings alone does not establish compatibility of this hook with
older Geyser builds. Verify the deployment with the targeted API before enabling
models. Geyser does not convert Java packs or generate mappings at runtime.

1. Install compatible Geyser-Spigot and, if wanted, Floodgate on Paper. Follow
   [Geyser's Floodgate setup](https://geysermc.org/wiki/floodgate/setup/) for
   `auth-type: floodgate`. NinjaArena does not require Floodgate for Java-account
   authentication through Geyser and does not change authentication settings.
2. Pin a concrete release and download its mappings, packs and manifest from
   that release's assets. Verify SHA-256 against `bedrock-manifest.json`.
3. Put the mapping JSON in `plugins/Geyser-Spigot/custom_mappings/` and ensure
   Geyser's `gameplay.enable-custom-content` is enabled. Install one copy only;
   do not register the same definitions again through another integration.
4. Copy the `.mcpack` files into `plugins/NinjaArena/`. Keep these two files
   **outside Geyser's automatic `packs/` folder**, which otherwise offers all
   packs to everyone. Configure their exact plain filenames and SHA-256 below.
   Standard is required to enable this hook; Detailed may remain unconfigured.
5. Configure Java delivery as above and configure the Bedrock files. Restart Paper
   after mapping, file or delivery-setting changes. An invalid file, hash or
   manifest identity disables Bedrock delivery with an operator error, keeping
   Bedrock tool visuals vanilla. Invalid reload candidates change nothing.

```yaml
resource-pack:
  # Also configure the Java url, sha1 and id described above.
  enabled: true
  required: true
  bedrock:
    mappings-installed: false
    standard:
      file: 'NinjaArena-v0.1.0-rc.1-bedrock-standard.mcpack'
      sha256: '<64 hexadecimal characters from bedrock-manifest.json>'
    detailed:
      file: ''
      sha256: ''
```

The hook checks pinned local files and registers exactly one complete pack in
`SessionLoadResourcePacksEvent`, Standard by default. It removes only the two
known NinjaArena quality UUIDs from that connection's offer before registering
the selected one, preserving unrelated packs. Files must remain immutable while
the server is running. There is no automatic download, live quality swap,
forced reconnect or repeated offer.

## Pack policy and status

| Setting | Java | Bedrock |
| --- | --- | --- |
| `resource-pack.enabled` | Enables pack delivery | Enables delivery with enabled same-server Geyser and configured Standard files |
| `resource-pack.required` | Kicks on decline or failure | Allows custom held models only with verified Geyser enforcement; false keeps vanilla tools without an enforcement warning |
| `resource-pack.url` / `sha1` / `id` | Required Standard HTTPS pack identity and pin | No effect |
| `resource-pack.detailed.url` / `sha1` / `id` | Optional overlay identity and pin | No effect |
| `resource-pack.java-login-gate` | Default `false`. Declares that a login plugin gates Java accounts, so choices are saved by account UUID (restart required; see Player texture quality) | No effect |
| `resource-pack.timeout-seconds` | Time limit for loading a pack | No second timer; Geyser owns negotiation |
| `resource-pack.thrown-visuals` | Enables custom flight when every viewer loaded the pack | Flight remains vanilla |
| `resource-pack.bedrock.mappings-installed` | No effect | Confirms matching mappings were installed; false keeps vanilla tools |
| `resource-pack.bedrock.standard.file` / `sha256` | No effect | Required file pin for delivery |
| `resource-pack.bedrock.detailed.file` / `sha256` | No effect | Optional quality file pin |

`resource-pack.bedrock.enabled` and `geyser-enforces-packs` were removed.
Reload rejects either key and names its replacement. Use the shared enabled and
required switches, and keep only the mapping-installation assertion. All pack
settings except `thrown-visuals` remain restart-only.

Geyser's public API exposes definitions during its initialization event, but
NinjaArena starts after Geyser and cannot query its loaded mapping registry.
Checking for a JSON file would not prove Geyser loaded the matching definitions.
Set `mappings-installed: true` only after installing matching mappings and enabling
Geyser custom content. This option asserts installation only, never enforcement.

When enabled and required with configured Bedrock files, NinjaArena reads
Geyser-Spigot's own `config.yml` through its enabled Bukkit plugin's data folder.
Custom held models require boolean `gameplay.force-resource-packs: true`, the
mapping assertion and a connection selected by the hook. False, missing,
unreadable, malformed or oversized config keeps vanilla tools and logs one warning
per evaluation: `set gameplay.force-resource-packs: true in Geyser`.
Optional policy does not read the file or warn. NinjaArena cannot enforce Bedrock
acceptance and never changes Geyser's settings.

The bounded, strict YAML check runs on enable and successful `/narena admin reload`.
Rejected reloads preserve the gate. Apply changes in Geyser too: reading the file
does not prove its live configuration matches. Already issued items are not
rewritten; the next kit issue uses the reevaluated gate.

Startup and `/narena pack status` show Java state, Bedrock state and the reason
for vanilla Bedrock tools in one line. The command requires `ninjaarena.admin`
and reads cached state without reading files or changing settings.

Geyser exposes no public pack-load outcome. The status reports eligibility, never
successful rendering or loading. NinjaArena skips Java pack offers and ignores
Java success statuses for Bedrock connections. Gameplay and data tags do not
depend on this gate. Automated policy and adapter tests do not establish real
Bedrock client behavior.

Decline, download failure and pre-login timeout belong to Geyser's negotiation.
The checked implementation disconnects on `REFUSED`; NinjaArena cannot promise
that optional decline/failure continues on the same connection or report a
specific load outcome. `resource-pack.timeout-seconds` is the Java timer only.
The Bedrock hook does not start a second timer, retry downloads or reoffer packs.
If Detailed causes trouble, an administrator resets the saved preference to
Standard before the next negotiation with `/narena admin textures reset`.

Bedrock flight always renders as vanilla snowballs because Geyser ignores the
snowball display ItemStack. Having a Bedrock viewer also fails Java's existing
all-viewers-loaded per-throw rule, so that throw stays vanilla for everyone.
We do not replace global snowball textures. Other players' custom held stacks
may still show missing artwork to a packless viewer; there is no per-viewer
equipment rewriting. Optional players' own tools remain vanilla and functional.

## Selection seam and deployment limits

`plugin.bedrockSelections()` returns an optional `BedrockSelections` service.
Its `selector(Function<String, Quality>)` accepts a fast saved-preference lookup
by authenticated Bedrock XUID, independently of a possibly-null pre-login Java
UUID. The default, missing identity, null result, failed lookup or unavailable
Detailed pack selects Standard. Explicit Detailed selects only Detailed.
The decision is frozen for the connection; changing the selector affects later
connections. The callback runs on Geyser's event thread, so it must not call
Bukkit or block on storage. The texture preference store installs a selector that
reads only its in-memory snapshot, once that snapshot has loaded.

Proxy and standalone Geyser deployments are **not covered**. They would need a
Geyser-side extension or proxy integration and a shared identity/preference
bridge. No such extension is provided. Backend Floodgate detection alone cannot
select packs before a separate Geyser instance negotiates. Native Bedrock pack
swapping after a backend switch is also outside this implementation.

Automated checks cover pack inventories, manifests, composition, identifiers,
determinism, release guards, selection defaults/freezing, vanilla fallback,
synthetic-status rejection and strict configuration rollback. A real Bedrock
client loading either pack, held rendering, team colours, live Geyser negotiation
and Floodgate identities remain unverified. Vanilla flight is the accepted
fallback, not a claim of custom projectile rendering.

## Player texture quality

Players choose Standard (16x16) or Detailed (32x32) with `/ninjaarena textures`
(alias `/narena textures`). The command needs `ninjaarena.play`, is player-only,
appears in help and completion only for players who hold the node, and opens
**Arena Texture Quality**. `resource-pack.texture-menu` (restart required) picks
the screen: `dialog` (default) is a native Paper dialog with the status text and
Standard, Detailed and Close buttons, which Geyser 2.8.2 or newer turns into a
form for Bedrock players; `chest` is the chest menu described below. An unknown
value falls back to `dialog` with an operator warning. If the dialog does not show
or misbehaves, for example with an older Geyser or a proxy setup, set `chest`; the
two behave the same. If the dialog cannot be built or sent, that opening uses the
chest menu and the failure is logged once. `/narena pack status` reports the menu
in use. Both menus use plain text, so they work without either pack, and neither
uses clickable chat or a second pack popup. Standard is the default; Detailed is
used only after an explicit choice. Both qualities have the same identifiers, team
colours, tool behaviour and gameplay.

| Control | Meaning |
| --- | --- |
| Standard 16x16 | Saves Standard. |
| Detailed 32x32 | Saves Detailed. Shown as unavailable when no Detailed pack is configured for the player's edition. |
| Status | Shows the saved choice and, separately, what this connection is doing now. |
| Close | Closes the menu. |

The chest's selected choice is marked `[SELECTED]` in text as well as by colour and glint.
An unsaved default reads `Standard (default, not saved yet)` in both menus.
A saved Detailed choice that is no longer configured stays visibly Detailed and
says that Standard is used instead. The menu shows `Saving...` until the write
has committed; a failed write is reported as failed and never shown as saved.
Every transfer, drag, shift-click, number-key swap, drop and collect action that
touches the menu is cancelled. The menu is identified by its holder and a
per-open token, and each choice rechecks ownership and permission, so an old
window cannot change anything. A match's inventory lockdown may refuse to open
the window; leave the match first.

The dialog buttons call the same service as the chest, so saving, choices for the
current connection only, the "not saved" wording and the unauthenticated wording
are identical. A dialog has no close event, so the player's latest dialog is the
current one; a press from an older dialog is refused with the same out-of-date
message as a stale chest, and every press rechecks ownership and `ninjaarena.play`.
A choice re-shows the dialog with `Saving...` and then the updated choice and short
status. The full result is sent in chat. The dialog uses three short lines: choice,
current pack status and when changes apply. Option names and sizes stay on the
buttons; full diagnostics remain in `/narena pack status`. The dialog is blocked
exactly like the chest: it is refused
while the player is in a match, waiting for one or spectating, with the same
"cannot be opened right now" message. A button pressed in a dialog that was already
open when the player entered a match is refused too, so no quality changes mid-match.

### Storage and identity

Choices are stored through the existing storage settings (`stats.storage`,
`stats.sqlite.file`, `stats.mysql.*`, `stats.table-prefix`) in two tables of their
own, `<prefix>texture_preferences` and `<prefix>texture_schema`. The schema
version is separate from the stats schema. There is no separate preferences file
or setting. The store works when `stats.enabled` is `false` and is unaffected by
seasons, archived seasons or clearing seasonal records. It starts only when
`resource-pack.enabled` is true. A database written by a newer build is left
untouched and the feature reports itself unsupported. Changing the backend (for
example SQLite to MySQL) starts with no preferences, so everyone is Standard;
there is no migration tool.

Only explicit choices are stored: identity, quality and revision/update times.
No record means Standard. Keys never merge editions and are never guessed from a
name:

- native Java: `java:<authenticated account UUID>`, used only when Java accounts
  count as authenticated: `online-mode`, a proxy that reports online mode, or
  `resource-pack.java-login-gate: true`;
- Geyser/Floodgate: `bedrock:<authenticated XUID>` from Geyser's connection;
- a missing, blank or non-numeric XUID gives no identity: the Bedrock player is
  Standard and nothing can be saved.

Startup and `/narena pack status` report which of these applies:
`authenticated (online mode)`, `authenticated (online proxy)`,
`authenticated (operator-declared login gate)` or
`not authenticated: choices last one connection`.

#### Offline-mode servers

Both settings below apply to Java players only; Bedrock is unchanged.

- `resource-pack.java-login-gate: true` (default `false`, restart required) is the
  operator's statement that a login plugin blocks commands until a Java player has
  logged in. Java accounts then count as authenticated and the same `java:<uuid>`
  preference is saved, read and applied as on an online-mode server; there is no
  schema change. The trade-off is plain: on an offline-mode server, whoever holds a
  player name can use that name's account UUID, so whoever holds the name can change
  that name's cosmetic quality. Enable it only where a login plugin (AuthMe by
  default) blocks commands before login.
- With the setting off and Java accounts not authenticated, a player may still pick
  a quality for the current connection only. The choice is kept in memory against
  that connection, never written to storage, and dropped when the player leaves, so
  it does not reach a later connection or another player. Detailed loads through
  the usual overlay request (after Standard has loaded, once per connection, never
  required). The menu and the command reply say the choice is not saved and resets
  when they leave.

Database work runs on one thread of its own and writes are serialized. A write
publishes its committed value to a thread-safe snapshot before success is
reported. While storage is unreachable the feature is degraded: everyone is
Standard, saving fails, and stored records are not overwritten. It reconnects in
the background. Loading happens at startup; Java players are also read on join.
Servers sharing one MySQL database see another server's Java changes on the next
join, but a Bedrock preference changed elsewhere reaches this server's snapshot
only after a restart.

### Java sequence

`ResourcePacks` stays the authority for every request.

1. On join, Standard is offered once with the existing `required` policy.
2. A saved Detailed choice waits for Standard's `SUCCESSFULLY_LOADED`; acceptance
   or download alone is not enough. Preference load and base load may finish in
   either order, and completions from an earlier connection are ignored.
3. Detailed is then requested with `required=false`, even when Standard is required.
4. Detailed is active only after its own successful-load status.
5. A decline, failed download or reload, timeout or send failure removes only the
   overlay. The accepted base and the saved choice are kept, the player is told,
   and there is no disconnect or automatic reprompt.
6. Choosing Standard cancels pending Detailed intent, then removes only this
   plugin's overlay identity. Later overlay statuses cannot reactivate it.
7. On reconnect the same sequence runs; a matching cached hash still waits for a
   successful-load response.

Each connection gets one overlay request. After a cancellation, decline or
failure, another Detailed choice is saved for the next connection. Standard ->
Detailed -> Standard within a connection is immediate. Status separates the saved
choice from the overlay state (pending, loaded, declined, failed or removed).

### Bedrock

The choice is saved by XUID and applies on the next connection only. The current
connection keeps the quality frozen at negotiation, nothing is swapped live and no
reconnect is forced. The message is: `Saved: Detailed. Applies on your next
connection. Pack loading cannot be observed.` NinjaArena never reports a Bedrock
pack as loaded; the menu shows only the saved choice, the quality selected for
this connection, unavailable files and the existing enforcement status.

The Geyser selector reads only the in-memory snapshot, with no Bukkit call or
database access, and is installed after the snapshot loads. Until then, and while
storage is degraded, Standard is selected. Exactly one complete pack is selected.
Only same-server Geyser-Spigot is supported; proxy and standalone Geyser are not.

### Recovery

If Detailed cannot load, an administrator (or the console) runs:

```
/narena admin textures reset <java|bedrock> <uuid|xuid>
```

It requires `ninjaarena.admin`, works for offline identities and saves Standard
durably before the next negotiation. A connected Java player also has their
overlay removed.

### Tests and what is not verified

Automated tests cover defaults and explicit choice, menu and permission handling,
the Java sequence, failure and cache cases, both `required` values, the Bedrock
selector against the committed XUID snapshot, identity isolation, persistence in a
real SQLite file across close and reopen, schema versioning, offline reset, and
unchanged gameplay at both qualities. The MySQL statements are exercised only in
H2's MySQL compatibility mode, which is not a MySQL server.

Not verified: menu and dialog usability on real Java and Bedrock clients, the dialog
as a Geyser form, real pack loading,
caching and coexistence with other plugins' packs, live Geyser and Floodgate
timing, a real MySQL or MariaDB server, and per-client Bedrock outcomes.
