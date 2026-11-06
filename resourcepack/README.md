# Java resource packs

Standard provides the ninja tool item definitions, flat item models and 16x16
textures. Detailed is a separate, optional 32x32 texture-only pack, using the same
paths above Standard. Detailed is never enabled automatically; quality selection
belongs to #80.

```
resourcepack/java/
  standard/pack.mcmeta
  standard/assets/ninjaarena/{items,models/item,textures/item}
  detailed/pack.mcmeta
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
produce identical bytes. Each root contains `pack.mcmeta` and `assets/`, with no
wrapper directory. The pack description embeds the artifact version. An explicit
allowlist excludes editable artwork, previews, 64x64 exports, portal assets and
unrelated files. The builder runs the existing pack validator, including texture
sync, before packaging. Never edit copied textures: refresh them from production
exports with `node artwork/tools/sync-pack-textures.js`.

Artwork CI builds both ZIPs for pull requests, merge groups, main pushes and
manual runs. Snapshot versions are `ci-<full commit SHA>`. The candidate and
separate downloaded-artifact test evidence are retained for 30 days. Release
attachments use the release tag; see [the release process](../RELEASE_PROCESS.md).

## Hosting, hashes and pinning

Use an HTTPS URL that directly returns the ZIP without login, cookies or a
landing page. GitHub release assets work once the gated attachment job completes.
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
returns false when unavailable or already active. `removeDetailed(UUID)` removes
only this plugin's overlay. Neither operation replaces other packs or clears the
whole stack. The quality menu will call this API later.

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

Bedrock packs and Geyser mapping belong to #109; quality preferences belong to
#80. Portal surface artwork is deliberately absent. Its rendering is deferred
until agreement with the player-built portal curtain, and portal colour must
not be inferred from equipment team colours.
