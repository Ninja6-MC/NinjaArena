# Tool resource packs

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

Bedrock loading, held looks, team colours, Geyser negotiation and Floodgate
identities have not been verified on a real client/server. Quality preferences
belong to #80. Portal surface artwork is deliberately absent. Its rendering is deferred
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
The existing protected release publisher attaches the verified bytes and fails
closed until the required release environment exists. See
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
5. Configure Java delivery as above and enable the Bedrock hook. Restart Paper
   after mapping, file or delivery-setting changes. An invalid file, hash or
   manifest identity disables Bedrock delivery with an operator error, keeping
   Bedrock tool visuals vanilla. Invalid reload candidates change nothing.

```yaml
resource-pack:
  # Also configure the Java url, sha1 and id described above.
  enabled: true
  bedrock:
    enabled: true
    geyser-enforces-packs: false
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

## Bedrock acceptance and the operator assertion

`resource-pack.required` controls Java delivery. **Geyser's negotiation settings
control required versus optional Bedrock packs for the entire connection.**
NinjaArena never changes them. In the checked Geyser source,
`gameplay.force-resource-packs` or an active integrated pack forces acceptance
of all offered packs. The integrated pack can therefore override an otherwise
optional configuration. Operators must configure Geyser consistently with their
intended policy; NinjaArena cannot verify it through the public API.

Geyser exposes no public pack-load outcome. NinjaArena never marks a Bedrock
connection loaded, even when `geyser-enforces-packs: true`. It skips Java pack
offers and ignores Java success statuses for Bedrock players: Geyser can synthesize
those statuses without loading the corresponding Bedrock pack.

Leave the assertion **false** for optional packs or any uncertain deployment.
Bedrock players then receive usable vanilla snowball/feather tools, even when
the selected pack was offered. Set it **true** only after confirming Geyser
requires packs and the matching mappings are installed. This enables custom
held models only for connections selected by the hook; it is an operator
assertion, not evidence that the client rendered the content. Gameplay and data
tags do not depend on the assertion or selected quality.

Decline, download failure and pre-login timeout belong to Geyser's negotiation.
The checked implementation disconnects on `REFUSED`; NinjaArena cannot promise
that optional decline/failure continues on the same connection or report a
specific load outcome. `resource-pack.timeout-seconds` is the Java timer only.
The Bedrock hook does not start a second timer, retry downloads or reoffer packs.
If Detailed causes trouble, #80 must allow its saved preference to be reset to
Standard before the next negotiation.

Bedrock flight always renders as vanilla snowballs because Geyser ignores the
snowball display ItemStack. Having a Bedrock viewer also fails Java's existing
all-viewers-loaded per-throw rule, so that throw stays vanilla for everyone.
We do not replace global snowball textures. Other players' custom held stacks
may still show missing artwork to a packless viewer; there is no per-viewer
equipment rewriting. Optional players' own tools remain vanilla and functional.

## Selection seam for #80 and deployment limits

`plugin.bedrockSelections()` returns an optional `BedrockSelections` service.
Its `selector(Function<String, Quality>)` accepts a fast saved-preference lookup
by authenticated Bedrock XUID, independently of a possibly-null pre-login Java
UUID. The default, missing identity, null result, failed lookup or unavailable
Detailed pack selects Standard. Explicit Detailed selects only Detailed.
The decision is frozen for the connection; changing the selector affects later
connections. The callback runs on Geyser's event thread, so it must not call
Bukkit or block on storage. #80 must provide the menu, persistence, Floodgate
identity reconciliation and any preload/cache of preferences. None is built here.

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
