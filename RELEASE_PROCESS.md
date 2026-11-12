# NinjaArena release process

A NinjaArena release is one GitHub release that carries the plugin JAR together with
the Java and Bedrock resource packs and the Geyser mappings, all built from the same
commit. GitHub Releases is the only destination. Modrinth and Hangar are possible
later destinations and are not built; see [Distribution](#distribution).

Two workflows do the work. **Release Candidate** (`release-candidate.yml`) builds
every asset once, retains it, downloads it again and tests the downloaded bytes; it
publishes nothing. **Publish Release** (`release.yml`) promotes one successful
candidate run, behind the protected `release` environment, without building
anything. `scripts/release.sh` (or `scripts/release.ps1` on Windows) cuts the tag and
dispatches the publisher.

## Versioning and channels

NinjaArena is pre-1.0 and uses `0.MINOR.PATCH`. Minor versions may change settings,
storage or integration APIs; patches fix compatible behaviour or artwork. Tags are
`v0.MINOR.PATCH`, optionally suffixed `-alpha.N`, `-beta.N` or `-rc.N`, with `N`
from 1. The tag decides the channel; nothing chooses it by hand:

| Tag | GitHub release | Latest marker |
| :--- | :--- | :--- |
| `v0.5.0-alpha.1`, `v0.5.0-beta.1`, `v0.5.0-rc.1` | pre-release | never |
| `v0.5.0` | release | only while it is the highest stable version |

The plugin version is the tag without its `v`. The release build passes it to Gradle
as `-PreleaseVersion`, so `plugin.yml` and the JAR name carry it; every other build
stays `0.1.0-SNAPSHOT`. Pack versions are the tag itself, `v` included.

Bedrock needs numeric three-part versions in addition to the artifact tag.
`v0.MINOR.PATCH` maps to `[MINOR + 1, PATCH, 30000]`; pre-releases use a last
component of `N` for alpha, `10000 + N` for beta and `20000 + N` for rc. `N` must be
from 1 to 9999. This makes each later pre-release, stable release and subsequent
patch or minor increase the client version while retaining pack UUIDs. Minor must be
below 65535 and patch at most 65535; the candidate refuses a tag outside that range.
Standard and Detailed use distinct permanent header and module UUIDs recorded in
`resourcepack/bedrock/target.json`.

Artwork CI snapshots use that file's explicit `snapshot_version`, below the release
version range, and `ci-<commit SHA>` names. They are verification artifacts, kept 30
days, never release candidates and never published. Increment `snapshot_version`
before deploying changed snapshot bytes manually; never replace a hosted UUID and
version with different content. Prefer a new tagged pre-release for client testing.

## What a release contains

For tag `v0.5.0` the release carries these eleven assets, and nothing else:

| Asset | What it is |
| :--- | :--- |
| `NinjaArena-0.5.0.jar` | The plugin |
| `NinjaArena-v0.5.0-java-standard.zip`, `NinjaArena-v0.5.0-java-detailed.zip` | Java packs |
| `SHA1SUMS`, `manifest.json` | Java pack SHA-1 values for server pins, and the Java pack manifest |
| `NinjaArena-v0.5.0-bedrock-standard.mcpack`, `NinjaArena-v0.5.0-bedrock-detailed.mcpack` | Bedrock packs |
| `NinjaArena-v0.5.0-geyser-mappings.json` | Geyser item mappings |
| `BEDROCK-SHA1SUMS`, `bedrock-manifest.json` | Bedrock pack SHA-1 values, and the Bedrock manifest with SHA-256 for every Bedrock asset |
| `SHA256SUMS` | SHA-256 of every other asset |

## Cut a release

1. Merge the intended source to `main` and confirm its checks pass. Test on real Java
   26.3 and supported Bedrock clients through Geyser before declaring visual
   acceptance. For a stable release, merge its `## [0.MINOR.PATCH]` changelog section
   first; the candidate and the publisher both refuse a stable version without one. A
   pre-release may use its own section, its base version's section, or neither.
2. Run **Release Candidate** from `main` with input `tag` set to the intended tag, for
   example `gh workflow run release-candidate.yml -R Ninja6-MC/NinjaArena --ref main
   -f tag=v0.5.0-rc.1`. The tag does not exist yet. What binds the run to the tag is
   the manifest it records, and Publish Release checks that binding.
3. Record the run ID once it succeeds. Do not re-run a failed or partly failed
   candidate: the publisher accepts first attempts only, so dispatch a new one.
4. From the main checkout, run `scripts/release.sh 0.5.0-rc.1 --candidate <run-id>`.
   It refuses a linked worktree, a branch other than `main`, uncommitted changes, a
   `main` out of step with origin, an existing tag, a stable version without a
   changelog section, and a run that is not a successful first-attempt Release
   Candidate dispatch on `main` for the commit being tagged. It then asks for the tag
   to be typed before it creates and pushes an annotated tag. Pushing a tag publishes
   nothing.
5. Run `scripts/release.sh publish 0.5.0-rc.1 <candidate-run-id>`. It checks the same
   run against the tag's commit on origin, then dispatches
   `release.yml` on the tag ref with `tag`, `candidate_run_id`, `destinations=github`
   and a `nonce` that appears in the run name, and prints how to find the run.
6. Read the `plan` job summary, then approve the `release` environment in the
   browser. For a stable release that owns the latest marker, a second approval is
   requested for `shared-state` after the upload. Automation never approves or rejects
   either gate.
7. Update the server pins and the organisation profile as in
   [After publication](#after-publication).

## Release Candidate

The workflow runs only from `main` and only as attempt 1. Its `build` job, with a
read-only token and no secrets, first checks the tag grammar and, for a stable tag, its
changelog section, so no candidate is built for a tag that could never publish. It runs
the release-script tests, the artwork validators and the pack tests, then:

* builds the JAR once with `./gradlew test build -PreleaseVersion=<version>`, so the
  unit suite runs on the source before packaging;
* converts the Java layers to Bedrock packs, generates the Geyser mappings, and builds
  both Bedrock and both Java packs once with the tag as pack version;
* assembles the eleven assets into one flat directory and writes `SHA256SUMS`;
* retains them as the `release-candidate` artifact, then records
  `candidate-manifest.json` and retains it as `release-candidate-manifest`.

The manifest records the candidate ID (`<run ID>-<attempt>-<source SHA>-<tag>`), source
commit, tag, version, channel, run ID and attempt, the candidate artifact's ID, the
destination (`github`) and the SHA-256 of every asset. Both pack manifests carry the
same source, run and attempt.

The `verify` job checks out the same commit, downloads the candidate and the manifest,
and fails on any mismatch before it tests anything:

* identity, exact file list, every SHA-256 and `SHA256SUMS`;
* the JAR: a clean CRC check, no duplicate entries, `plugin.yml` byte-equal to the
  tagged source with only the version filled in, every other resource byte-equal to
  the source, the plugin main class present, exactly one compiled top-level class per
  Java source file and nothing outside the plugin's package, and Java 25 bytecode;
* both pack sets, through the pack builders' own verifiers: inventories, embedded
  versions, CRCs, SHA-1 lists, the Geyser mappings, and every entry of every ZIP and
  `.mcpack` compared with the bytes regenerated from the tagged source.

It then runs the whole unit suite again with the downloaded JAR in place of the
compiled classes and resources (`-PreleaseJar`). `PluginDescriptionTest` checks that
the plugin classes and `plugin.yml` were loaded from that JAR and that its version is
the release version. The evidence step requires a JUnit report with no failure, error
or skip and with that test present, then writes `candidate-evidence.json`, bound to the
manifest's digest, the files, the tests and the candidate run and attempt, and retains
it as `release-candidate-evidence`.

All three artifacts are kept for 90 days, the repository maximum, so a release can be
retried weeks later. What the artifact tests do not cover: no server is booted with the
JAR, and no client renders the packs. Real-client checks stay a release-note gap until
they are done by hand.

## Publish Release

`release.yml` takes `tag`, `candidate_run_id`, `destinations` (`github`, or
`shared-state` alone for recovery) and `nonce`. Every job requires `github.ref` to be
`refs/tags/<tag>` and the tag to match the grammar above. Every job checks out
`github.sha`, the tag's commit at dispatch, never the tag name: checkout resolves a bare
name as a branch before a tag, so a branch named like the tag would otherwise supply the
scripts. The scripts also require the checkout, the local tag and the tag on origin to
be the same commit, and the release targets that commit. The workflow's concurrency
group is `publish-release-<github.ref>`, keyed on the ref rather than the input,
because a workflow-level group is evaluated when the run is queued, before any step
can validate the input. `release.yml` is the only workflow that writes releases;
`PUBLISHER_WORKFLOWS` in `scripts/release_promotion.py` lists it.

**`plan`** runs first with `contents: read` and `actions: read`, no environment and no
write permission, so its failures appear before anyone is asked to approve. It
requires the candidate run to be a completed, successful, first-attempt
`workflow_dispatch` run of this repository's `release-candidate.yml` on `main`, named
Release Candidate, whose commit is the tag's commit both in the checkout and on
origin. It requires the three candidate artifacts to exist exactly once and unexpired,
and the manifest to name this tag, that commit, that run, attempt 1 and the downloaded
candidate artifact's ID. It then repeats every check of the `verify` job above and
requires the passing evidence. It checks the release notes, reads the `release`
environment and fails closed unless it requires the maintainer's review, reconciles
the GitHub release (below), and predicts whether the latest marker needs the
`shared-state` job. Its summary records the run's identity, channel, release state and
the current latest owner.

**`publish-github`** depends on `plan`, uses `environment: release` and holds
`contents: write`, as does `shared-state` below; no other job does. After approval it
downloads the same candidate again and repeats the provenance, digest and evidence
checks, preflights its token and the environment rule, and reconciles the release.
When the release is absent it writes the notes (the changelog section, or a short
pre-release line, followed by GitHub's generated change list), then, in one step:

* refuses any run attempt other than the first, because re-running one job reuses the
  outputs of the jobs it depends on;
* refuses while another Publish Release run for this tag is in progress, or when one
  finished after this run was created, since it may have written something this run
  did not see. A run created after this one does not count: it shares the
  concurrency group and cannot have started;
* rechecks the environment rule, the tag on origin, the manifest, every digest, the
  contents and the evidence, and that no release or draft for the tag has appeared;
* runs `gh release create <tag> candidate/* --verify-tag --target <source>
  --latest=false`, adding `--prerelease` for alpha, beta and rc tags.

It then downloads every asset anonymously from its public
`https://github.com/Ninja6-MC/NinjaArena/releases/download/<tag>/<name>` URL, the
way a consumer does, and fails unless the release is complete (below). It also fails
if the create left the latest marker on a tag that does not own it. No publish job
builds, packages or changes a file.

## The latest marker

The latest marker is shared state: it changes what `releases/latest` points to for
every reader. It belongs only to the highest stable release published on GitHub, by
SemVer, and stays unset until the first stable release. A pre-release never takes it,
and a stable hotfix published after a newer stable release does not take it either.

Every release is created with `--latest=false`. The marker is moved only by the
separate `shared-state` job, which runs when `plan` predicts that this tag can own the
marker and `publish-github` succeeded or was skipped. Skipped covers a `shared-state`
recovery dispatch; a failed, rejected or cancelled publish asks for no second approval.
A dispatch of `github` for an already complete release succeeds without writing, so it
also reaches `shared-state`. It uses `environment: release` (a second approval)
and the job-level concurrency group `release-shared-state`, which does not contain the
tag, so two tags published at the same time cannot both decide they own it. Inside
that group, directly before writing, it downloads and verifies the candidate again,
requires this tag's release to be complete, reads the published stable releases again
and skips with the owner's tag in the log unless this tag is the owner. Only an owner
runs `gh release edit <tag> --latest=true`, and the job reads `releases/latest` back.
The prediction in `plan` can only skip the job; it never authorizes a write.

A read-only `report-shared-state` job records in the run summary whether the job ran,
was skipped or was rejected. A pending shared-state approval holds the cross-tag group,
so approve or reject it promptly. Rejecting it, or a failure or cancellation of the
job, leaves the marker where it was; recover with a `shared-state` dispatch for the
owning tag. GitHub keeps one running and one pending job in a concurrency group, and a
third queued `shared-state` job cancels the pending one; a cancelled job has written
nothing, and the recovery is the same.

## Credentials and the release environment

There are no repository, organisation or environment secrets. Every job uses only its
own `GITHUB_TOKEN`. The candidate jobs and `plan` have read permissions only;
`publish-github` and `shared-state` add `contents: write` and wait at the `release`
environment. Every checkout sets `persist-credentials: false`.

The `release` environment requires the maintainer as reviewer and allows deployments
from `main` and `v*` tags. The workflows never change it. `plan`, the preflight and the
guard read it and stop unless its required reviewers include `BharathASL`.

The publish job's credential preflight checks, before any write, that the token is
present, can read this repository's releases and can read the environment rule. One
prerequisite can only be proven by the create itself: that `contents: write` lets
the job create the release, because a job cannot read its own token's permissions.
A failure there writes nothing and is retried by a new dispatch.

## Retry and partial publication

There is one destination, so a retry always selects `github`. Before writing,
`publish-github` reconciles the release for the tag:

* **absent**: no release and no draft for the tag; the create runs.
* **complete**: not a draft, the tag, the target commit and the pre-release flag match
  the candidate, the asset list is exactly the eleven assets with no duplicates, every
  asset is fully uploaded, and every asset downloaded anonymously matches its SHA-256 in
  the manifest. The create is skipped and nothing is written.
* anything else stops the job, and nothing is overwritten, deleted or uploaded again.

`gh release create` uploads the assets to a draft and publishes it only when all of
them are in, so an interrupted create leaves a draft rather than a partial public
release. A draft for the tag stops the publisher with its name and URL; drafts are
visible only to the write-capable token, so `plan` cannot see them and says so.

| Failure | What it means | Retry |
| :--- | :--- | :--- |
| `plan` fails on the candidate, tag, notes or environment | Nothing was approved or written | Fix the cause and dispatch again; cut a new candidate if the old one is wrong or expired |
| The guard refuses a later attempt | A job was re-run | Dispatch a new run; never re-run `publish-github` |
| The guard refuses another publisher run | A run for the tag is in progress or finished meanwhile | Let it finish, check the release, then dispatch a new run |
| Draft detected for the tag | An earlier create was interrupted | Inspect and delete the named draft on the Releases page, then dispatch `github` again; nothing is deleted automatically |
| The create fails | Nothing public was written; a draft may remain | As for a draft, then dispatch `github` again |
| Consumer verification fails after the create | The public bytes or metadata differ, or are not visible yet | Dispatch `github` again: a complete release is verified and skipped. If it still differs, stop and cut a new version; never replace public bytes |
| The create moved the latest marker to a non-owner | GitHub set the marker despite `--latest=false` | Dispatch `shared-state` for the owning tag |
| `shared-state` rejected, cancelled or failed | The release is published; the marker may be stale | Dispatch `shared-state` for the owning tag |

Retry the publisher alone, without a new tag or candidate:

```bash
scripts/release.sh publish 0.5.0 123456789
```

Repair the latest marker for a tag that is already published:

```bash
scripts/release.sh publish 0.5.0 123456789 --destinations shared-state
```

```powershell
scripts\release.ps1 publish 0.5.0 123456789 --destinations shared-state --dry-run
```

`--dry-run` shows the dispatch without making it. The concurrency group keeps one run
queued behind the running one per tag: a second dispatch replaces a run still queued
for the group, and GitHub cancels the replaced run. A run waiting at the `release` gate
holds the group.

Candidates expire 90 days after the candidate run. There is no fallback rebuild: an
expired candidate stops the publisher, nothing is rebuilt for that tag, and a release
still missing is recorded below and reached by the next version instead. Never reuse a
tag that points to different code.

### Not published

| Tag | Destination | Reason |
| :--- | :--- | :--- |
| none | | |

`v0.1.0-rc.1` was published by the pack-only process this one replaces and carries
the packs without the plugin JAR. It stays as it is.

## After publication

Once the release is verified, pin concrete release URLs and matching hashes as
described in [the pack guide](resourcepack/README.md). Icon changes change archive
hashes too. Download the published Java ZIPs, Bedrock `.mcpack` files, `SHA1SUMS` and
both manifests, and verify the downloaded bytes before using their pins. Update both
Java quality URLs and SHA-1 values together, and both Bedrock filenames and SHA-256
values together, in deployment configuration and any populated examples in
`README.md`, `resourcepack/README.md` and `src/main/resources/config.yml`. Use Java
SHA-1 values from `SHA1SUMS` and Bedrock SHA-256 values from `bedrock-manifest.json`;
never reuse hashes from an earlier release or an Actions artifact. Leave blank defaults
blank. Check that both pack lists display the icon on real clients. Do not change
existing release assets or publish a release solely to refresh these examples.

Then open a documentation PR against the organisation's
[profile README](https://github.com/Ninja6-MC/.github/blob/main/profile/README.md)
to update the published version and release stage. A pre-release stays labelled as
alpha, beta or release candidate. Do not update it for an unpublished candidate or a
release awaiting reconciliation.

## Distribution

Releases go to GitHub Releases on this repository only. The JAR ships in the same
release as the packs and the mappings, so one tag names one set of bytes.

Modrinth and Hangar are possible later destinations and nothing is built for them.
Adding either needs its own `publish-<registry>` job in `release.yml`, behind the same
`release` environment and depending on the same `plan`; its credential as an
environment secret on `release`; a credential preflight for every selected destination
in every publish job; a per-dispatch absence attestation input, because neither
registry's API proves that a version is absent; and, for Modrinth, the upload-only
publishing and token scopes of `N6-REL-04` and the description check of `N6-REL-06`.
`destinations` then becomes a list, and each destination is retried alone.

## What the tests cover

`scripts/tests/` runs on every pull request in the `Build and Test` check and again at
the start of every candidate build.

* `test_release_candidate.py`: the tag grammar and Bedrock range, the manifest, and
  refusals for a wrong tag, commit, run or attempt; a changed, missing or extra file; a
  JAR with another version, a missing or extra class, a changed or missing resource,
  extra entries or older bytecode, even after the digests are rewritten; a tampered
  pack; and evidence that is failed, mismatched, recorded by another run or attempt,
  or missing the test that proves the suite ran on the JAR.
* `test_release_promotion.py`: a candidate run from another workflow, branch,
  repository, event or attempt, or one that failed; a candidate made for another tag,
  commit, run or attempt; a moved tag; expired, missing or duplicated artifacts; a
  publish on a later attempt; a concurrent or later-finished publisher run, while runs
  that cannot have written are ignored; a release environment without the maintainer;
  draft, mismatched and conflicting releases, an untrusted download URL and consumer
  verification retries; the latest marker refused to a pre-release while a stable
  exists and to a stable hotfix below a newer stable, written and read back for the
  owner, and one concurrency group for every tag; a checkout that is not the tag's
  commit; and the changelog gate. Static checks on `release.yml` require every
  checkout to use `github.sha` and `shared-state` to need a successful or skipped
  publish.
* `test_release_wrapper.py`: `release.sh` refusals before any network call, including
  tagging without a valid `--candidate` run ID, and on
  Windows that `release.ps1` passes its arguments through intact.

These tests mock GitHub. What only a real dispatch proves: the job token's access to
the environment rule and the draft listing, `gh release create` publishing its draft
with `--latest=false` honoured, the anonymous download URLs, and the approval flow
itself.
