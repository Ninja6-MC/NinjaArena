# NinjaArena pack release process

This process publishes Java and Bedrock resource packs and Geyser mappings, not
the plugin JAR or packages to external registries.

## Versioning and channels

NinjaArena is pre-1.0 and uses `0.MINOR.PATCH`. Minor versions may change settings
or integration APIs; patches fix compatible behaviour or artwork. Tags are
`v0.MINOR.PATCH`, optionally suffixed `-alpha.N`, `-beta.N`, or `-rc.N`. Suffixed
tags must be GitHub pre-releases; unsuffixed tags must be stable releases. The
publisher checks this and never edits release channels or latest status.
Pack versions follow the tag; this pack-only workflow does not rewrite the
plugin build version.

Bedrock needs numeric three-part versions in addition to the artifact tag.
`v0.MINOR.PATCH` maps to `[MINOR + 1, PATCH, 30000]`; prereleases use a last
component of `N` for alpha, `10000 + N` for beta and `20000 + N` for rc. `N`
must be from 1 to 9999. This makes each later prerelease, stable release and
subsequent patch/minor increase the client version while retaining pack UUIDs.
Minor must be below 65535 and patch at most 65535. Standard and Detailed use
distinct permanent header and module UUIDs recorded in
`resourcepack/bedrock/target.json`.

CI snapshots use that file's explicit `snapshot_version`, below the release
version range. They are verification artifacts, not production deployment
pins. Increment `snapshot_version` before deploying changed snapshot bytes
manually; never replace a hosted UUID/version with different content. Prefer
a new tagged prerelease for client testing, especially after testing a release.

## Prepare a release

1. Start from a reviewed, green `main` commit. Run the plugin suite, standards,
   artwork validators and pack tests. Test on real Java 26.3 and supported
   Bedrock clients through Geyser before declaring visual acceptance.
2. Stable versions need a `## [0.MINOR.PATCH]` changelog section; the build rejects
   a stable candidate without one. Pre-releases may use `[Unreleased]`.
3. Create and push the tag at that commit, then manually publish its GitHub
   release with the correct pre-release flag and changelog notes.
4. The release's `published` event starts Artwork CI. A published release page
   alone does not mean its packs are ready to download.

## Candidate, tests and approval

The read-only build job creates both ZIPs once, with the tag in their filenames
and descriptions. It retains them, `SHA1SUMS` and `manifest.json` in an immutable
Actions artifact `java-packs-<run ID>-<attempt>` for 30 days. The manifest records
the unique source-SHA/run-ID/attempt candidate identity, Minecraft format, intended
version/channel/destinations and every file's SHA-256. ZIPs also record client
SHA-1 hashes.

A separate verifier downloads the candidate and checks the exact file list,
identity, source, version, channel and all hashes. It opens each ZIP, tests CRCs,
checks root metadata, embedded version and expected inventory, and compares every
entry with the tagged production source bytes. This covers the previously
validated item definitions, models, textures and root `pack.png` icons. It retains
passing evidence bound to the candidate and manifest digest, plus verification run and attempt, in a
second artifact. These tests do not establish client rendering or negotiation;
release notes must preserve those gaps until real-client checks pass.

Artwork CI explicitly converts the Java base/accent layers into two complete
Bedrock packs, explicitly generates v2 Geyser mappings, and only then packages
them. It retains a second candidate artifact with the `-bedrock` suffix,
containing both `.mcpack` files, versioned Geyser mappings,
`BEDROCK-SHA1SUMS` and `bedrock-manifest.json`. The manifest records SHA-256
digests of every asset and SHA-1 digests of the downloadable packs. Verification
downloads that candidate, regenerates expected conversion/mapping bytes from
the checked-out source and validates pack manifests, textures, atlas and file
inventory, including a root `pack_icon.png` in each quality. The same evidence
artifact includes `bedrock-pack-verification.json`, bound to this candidate's manifest digest.

The publisher waits on the **`release` environment**. Before using it, configure
that environment with a required maintainer reviewer and verify the setting
independently; the environment name in YAML does not create an approval rule.
Only this gated job has `contents: write`. The build and verifier are read-only.
No extra publishing secrets are needed, only the publisher's job-scoped
`GITHUB_TOKEN`. Automation must never approve the environment gate.
The publisher additionally reads the environment through GitHub's API and refuses
to upload unless its required-reviewers rule includes the maintainer
(`BharathASL`). A missing, unreadable or unprotected environment fails closed.
During implementation this repository's environment endpoint returned 404;
protection is therefore a prerequisite still to be configured or made visible
before the first release. This workflow does not change environment settings.

## Promotion and recovery

After approval, the publisher downloads the same candidate and evidence,
rechecks contents, digests, identity and passing evidence, resolves the current
remote tag to the recorded source SHA, and checks the existing release tag and
channel. A moved tag or conflicting identity stops publication. This job does
not build or repackage anything.

The only destination is the existing GitHub release. The publisher downloads
already attached intended assets and compares them byte-for-byte, uploads only
missing ones, then downloads and checks all public assets. It never overwrites
conflicting bytes or uses `--clobber`; unrelated release assets are untouched.
Attachments are both ZIPs, `SHA1SUMS`, and `manifest.json`.
The same protected publisher also attaches both `.mcpack` files,
`NinjaArena-<version>-geyser-mappings.json`, `BEDROCK-SHA1SUMS`, and
`bedrock-manifest.json`. It uses the same environment, tag, evidence, channel
and no-overwrite checks. A partial publication of either candidate is recovered
by downloading and reconciling the retained bytes, never by rebuilding them.

After a partial upload, rerun only the failed publisher job in the same workflow
run while its candidate and evidence are retained. Do not rebuild under the same
version. Matching assets are accepted and only absent ones uploaded. Conflicts,
expired candidates, missing evidence or uncertain uploads require manual
reconciliation: compare retained hashes and public bytes, stop on differences,
and cut a new version rather than replacing a published ZIP. After retention
expires, create a new version instead of rebuilding the old candidate silently.

Once all attachments are verified, pin concrete release URLs and matching SHA-1
values as described in [the pack guide](resourcepack/README.md). Icon changes
change archive hashes too. Download the published Java ZIPs, Bedrock `.mcpack`
files, `SHA1SUMS` and both manifests; verify the downloaded bytes before using
their pins. Update both Java quality URLs and SHA-1 values together, and both
Bedrock filenames and SHA-256 values together, in deployment configuration and
any populated examples in `README.md`, `resourcepack/README.md` and
`src/main/resources/config.yml`. Use Java SHA-1 values from `SHA1SUMS` and
Bedrock SHA-256 values from `bedrock-manifest.json`; never reuse hashes from an
earlier release or an Actions wrapper. Leave blank defaults blank. Check that
both pack lists display the icon on real clients. Do not change existing release
assets or publish a release solely to refresh these examples. Open a separate
documentation PR to the organization profile to update the published version and
actual release stage. Do not update it while attachment publication is incomplete.
