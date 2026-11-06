# NinjaArena pack release process

This process publishes Java resource packs only, not the plugin JAR, Bedrock
packs, or packages to external registries.

## Versioning and channels

NinjaArena is pre-1.0 and uses `0.MINOR.PATCH`. Minor versions may change settings
or integration APIs; patches fix compatible behaviour or artwork. Tags are
`v0.MINOR.PATCH`, optionally suffixed `-alpha.N`, `-beta.N`, or `-rc.N`. Suffixed
tags must be GitHub pre-releases; unsuffixed tags must be stable releases. The
publisher checks this and never edits release channels or latest status.
Pack versions follow the tag; this pack-only workflow does not rewrite the
plugin build version.

## Prepare a release

1. Start from a reviewed, green `main` commit. Run the plugin suite, standards,
   artwork validators and pack tests. Test on a real Java 26.3 client before
   declaring visual acceptance.
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
validated item definitions, models and PNGs. It retains passing evidence bound
to the candidate and manifest digest, plus verification run and attempt, in a
second artifact. These tests do not establish client rendering or negotiation;
release notes must preserve those gaps until real-client checks pass.

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

After a partial upload, rerun only the failed publisher job in the same workflow
run while its candidate and evidence are retained. Do not rebuild under the same
version. Matching assets are accepted and only absent ones uploaded. Conflicts,
expired candidates, missing evidence or uncertain uploads require manual
reconciliation: compare retained hashes and public bytes, stop on differences,
and cut a new version rather than replacing a published ZIP. After retention
expires, create a new version instead of rebuilding the old candidate silently.

Once all attachments are verified, pin concrete release URLs and matching SHA-1
values as described in [the pack guide](resourcepack/README.md). Open a separate
documentation PR to the organization profile to update the published version and
actual release stage. Do not update it while attachment publication is incomplete.
