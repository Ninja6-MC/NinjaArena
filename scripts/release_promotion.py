#!/usr/bin/env python3
"""Fetch, plan and publish one previously verified release candidate to GitHub Releases.

Publish Release runs one `plan` job with no environment and a read-only token, then the
`publish-github` job behind the protected `release` environment, then a separate
`shared-state` job that moves the GitHub "latest" marker only for the tag that owns it.
Each subcommand here is one step of that sequence; RELEASE_PROCESS.md describes the whole.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

from release_candidate import DESTINATIONS, TAG, details, digest, read_json, verify

SELECTABLE = (*DESTINATIONS, "shared-state")
CANDIDATE_WORKFLOW = ".github/workflows/release-candidate.yml"
CANDIDATE_NAME = "Release Candidate"
ARTIFACTS = {"release-candidate": "candidate", "release-candidate-manifest": ".", "release-candidate-evidence": "."}
# Every workflow able to write releases. Each shares the publisher's concurrency group,
# and each counts when deciding whether another run may have written for this tag.
PUBLISHER_WORKFLOWS = ("release.yml",)
MAINTAINER = "bharathasl"
VERIFY_TRIES = 3
VERIFY_DELAY = 10


def command(*args, capture=True):
    if capture:
        return subprocess.run(args, check=True, text=True, capture_output=True).stdout
    return subprocess.run(args, check=True).returncode


def api(path):
    return json.loads(command("gh", "api", path))


def write_outputs(path, values):
    with Path(path).open("a", encoding="utf-8") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")


def flag(value):
    return value is True or value == "true"


def tag_source(tag):
    """The tag's commit, once the checkout, the local tag and origin all agree on it."""
    source = command("git", "rev-parse", f"refs/tags/{tag}^{{commit}}").strip()
    if command("git", "rev-parse", "HEAD^{commit}").strip() != source:
        raise ValueError("The checkout is not the release tag's commit")
    remote_refs = command("git", "ls-remote", "origin", f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}").splitlines()
    remote_source = remote_refs[-1].split()[0] if remote_refs else ""
    if source != remote_source:
        raise ValueError("Release tag moved on origin")
    return source


def check_candidate_run(run, repository):
    if any((run.get("name") != CANDIDATE_NAME,
            run.get("path") != CANDIDATE_WORKFLOW,
            (run.get("repository") or {}).get("full_name") != repository,
            (run.get("head_repository") or {}).get("full_name") != repository,
            run.get("event") != "workflow_dispatch", run.get("head_branch") != "main",
            run.get("status") != "completed", run.get("conclusion") != "success",
            run.get("run_attempt") != 1)):
        raise ValueError("Candidate run is not a successful first-attempt main run of this repository's candidate workflow")


def manifest_check(manifest, tag, source, run_id):
    # The candidate workflow runs on main before the tag exists, so its tag input is bound
    # to this dispatch only through the manifest it recorded. Pin attempt 1.
    if (manifest.get("tag") != tag or manifest.get("source") != source
            or manifest.get("run_id") != run_id or manifest.get("attempt") != 1):
        raise ValueError("Candidate run was made for another tag, commit, run or attempt")


def fetch(args):
    run = api(f"repos/{args.repository}/actions/runs/{args.run_id}")
    check_candidate_run(run, args.repository)
    source = run["head_sha"]
    if tag_source(args.tag) != source:
        raise ValueError("Tag does not resolve to the candidate source on origin")
    artifacts = api(f"repos/{args.repository}/actions/runs/{args.run_id}/artifacts?per_page=100")["artifacts"]
    named = [item for item in artifacts if item["name"] in ARTIFACTS]
    found = {item["name"]: item for item in named if not item["expired"]}
    if len(found) != len(ARTIFACTS) or len(named) != len(ARTIFACTS):
        raise ValueError("Candidate artifacts are missing, expired or ambiguous; an expired candidate is never rebuilt")
    for name, target in ARTIFACTS.items():
        command("gh", "run", "download", str(args.run_id), "-R", args.repository,
                "--name", name, "--dir", target, capture=False)
    manifest = read_json("candidate-manifest.json")
    manifest_check(manifest, args.tag, source, args.run_id)
    if manifest.get("artifact_id") != found["release-candidate"]["id"]:
        raise ValueError("Downloaded candidate artifact ID differs from the manifest")
    verify(argparse.Namespace(manifest="candidate-manifest.json", directory="candidate", tag=args.tag,
                              source=source, run_id=args.run_id, attempt=1, evidence="candidate-evidence.json"))


def recheck(args):
    """Tag, manifest, digests, contents and evidence again, immediately before a write."""
    manifest = read_json("candidate-manifest.json")
    source = tag_source(args.tag)
    manifest_check(manifest, args.tag, source, manifest.get("run_id"))
    verify(argparse.Namespace(manifest="candidate-manifest.json", directory="candidate", tag=args.tag,
                              source=source, run_id=manifest["run_id"], attempt=1, evidence="candidate-evidence.json"))


def select_destinations(text):
    names = [part.strip() for part in (text or "").split(",")]
    if not names or any(name not in SELECTABLE for name in names) or len(set(names)) != len(names):
        raise ValueError(f"Destinations must be a comma-separated subset of {','.join(SELECTABLE)}")
    if "shared-state" in names and len(names) != 1:
        raise ValueError("shared-state must be selected alone")
    return [name for name in SELECTABLE if name in names]


def require_release_gate(repository):
    """Fail closed unless the `release` environment requires the maintainer's review."""
    environment = api(f"repos/{repository}/environments/release")
    reviewers = [reviewer.get("reviewer", {}).get("login", "").casefold()
                 for rule in environment.get("protection_rules", []) if rule.get("type") == "required_reviewers"
                 for reviewer in rule.get("reviewers", []) if reviewer.get("type") == "User"]
    if MAINTAINER not in reviewers:
        raise ValueError("The release environment must require the maintainer's review before publishing")


def consumer_digest(url, repository, tag, name):
    """SHA-256 of an asset as an anonymous consumer downloads it."""
    expected = f"https://github.com/{repository}/releases/download/{tag}/{name}"
    if url != expected:
        raise ValueError(f"Release asset {name} has no trusted download URL")
    request = urllib.request.Request(url, headers={"User-Agent": "NinjaArena-release-check"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return hashlib.sha256(response.read()).hexdigest()
    except (urllib.error.URLError, OSError):
        raise ValueError(f"Release asset {name} could not be downloaded") from None


def refuse_draft(release, tag):
    identity = release.get("html_url") or f"release ID {release.get('id')}"
    raise ValueError(f"GitHub draft {release.get('name') or tag} ({identity}) exists for {tag}; "
                     "open the repository's Releases page, inspect and delete that draft manually, "
                     "then dispatch again. No second release will be created")


def github_state(repository, tag):
    """`complete` when the published release matches the candidate, `absent` when there is none."""
    manifest = read_json("candidate-manifest.json")
    channel = details(tag)[1]
    try:
        release = api(f"repos/{repository}/releases/tags/{tag}")
    except subprocess.CalledProcessError as error:
        if "HTTP 404" not in (error.stderr or ""):
            raise
        # Only a token with write access sees drafts; plan's read-only token sees none.
        lines = command("gh", "api", "--paginate", f"repos/{repository}/releases?per_page=100",
                        "--jq", ".[] | select(.draft) | {tag_name, html_url, id, name}")
        for draft in (json.loads(line) for line in lines.splitlines() if line.strip()):
            if draft.get("tag_name") == tag:
                refuse_draft(draft, tag)
        return "absent"
    if release.get("draft"):
        refuse_draft(release, tag)
    if (release.get("tag_name") != tag or release.get("target_commitish") != manifest["source"]
            or release.get("prerelease") is not (channel == "prerelease")):
        raise ValueError("Existing GitHub release metadata cannot be reconciled with the candidate")
    assets = release.get("assets", [])
    names = [asset.get("name") for asset in assets]
    if len(names) != len(set(names)) or set(names) != set(manifest["files"]):
        raise ValueError("Existing GitHub release asset list differs from the candidate")
    for asset in assets:
        if asset.get("state") != "uploaded":
            raise ValueError(f"Existing GitHub release asset {asset['name']} is not fully uploaded")
        actual = consumer_digest(asset.get("browser_download_url"), repository, tag, asset["name"])
        if actual != manifest["files"][asset["name"]]:
            raise ValueError(f"Existing GitHub release asset {asset['name']} bytes differ from the candidate")
    return "complete"


def publisher_runs(repository):
    runs = []
    for workflow in PUBLISHER_WORKFLOWS:
        lines = command("gh", "api", "--paginate", f"repos/{repository}/actions/workflows/{workflow}/runs?per_page=100",
                        "--jq", ".workflow_runs[] | {id, status, head_branch, created_at, updated_at}")
        runs.extend(json.loads(line) for line in lines.splitlines() if line.strip())
    return runs


def conflicting_runs(runs, current, tag):
    """Publisher runs for this tag that may have written something this run did not see."""
    conflicts = []
    for run in runs:
        if run.get("id") == current["id"] or run.get("head_branch") != tag:
            continue
        # A run created after this one shares its concurrency group, so it cannot start
        # while this run holds the group and cannot have written anything. That holds
        # whatever its status: one cancelled while queued is completed with a fresh updated_at.
        if run.get("created_at", "") > current["created_at"]:
            continue
        if run.get("status") != "completed":
            conflicts.append(run)
        # updated_at is never earlier than completion, so this errs towards refusing.
        elif run.get("updated_at", "") > current["created_at"]:
            conflicts.append(run)
    return conflicts


def check_run(repository, tag, run_id, attempt):
    """Refuse a later attempt, or a write while another publisher run for this tag may have written."""
    if attempt != 1:
        raise ValueError(f"Publishing is refused on run attempt {attempt}: a re-run reuses the outputs of "
                         "the jobs it depends on. Dispatch a new run")
    current = api(f"repos/{repository}/actions/runs/{run_id}")
    if current.get("head_branch") != tag:
        raise ValueError("Publisher run is not on the release tag ref")
    conflicts = conflicting_runs(publisher_runs(repository), current, tag)
    if conflicts:
        ids = ", ".join(str(run["id"]) for run in conflicts)
        raise ValueError(f"Publishing is refused: publisher run {ids} for {tag} is in progress or finished "
                         "after this run was created. Check the release, then dispatch a new run")


def guard(args):
    """Directly before the release is created: attempt, concurrent runs, gate, then the candidate."""
    check_run(args.repository, args.tag, args.run_id, args.attempt)
    require_release_gate(args.repository)
    recheck(args)
    if github_state(args.repository, args.tag) != "absent":
        raise ValueError("A GitHub release for this tag appeared after reconciliation; dispatch again to verify it")
    print(f"Guard passed for run {args.run_id} attempt {args.attempt}")


SEMVER = re.compile(r"^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
                    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
                    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$")


def version_key(tag):
    match = SEMVER.fullmatch(tag)
    if not match:
        raise ValueError(f"Not a SemVer version: {tag}")
    identifiers = match.group(4).split(".") if match.group(4) else []
    if any(part.isdigit() and len(part) > 1 and part.startswith("0") for part in identifiers):
        raise ValueError(f"Not a SemVer version: {tag}")
    prerelease = tuple((0, int(part)) if part.isdigit() else (1, part) for part in identifiers)
    return tuple(int(match.group(i)) for i in (1, 2, 3)) + (not identifiers, prerelease)


def latest_owner(tags):
    """The highest stable tag. GitHub never marks a pre-release latest, so none owns it."""
    stable = [tag for tag in tags if version_key(tag)[3]]
    return max(stable, key=version_key) if stable else None


def published_stable_tags(repository):
    lines = command("gh", "api", "--paginate", f"repos/{repository}/releases?per_page=100",
                    "--jq", ".[] | select(.draft | not) | select(.prerelease | not) | .tag_name")
    return [line.strip() for line in lines.splitlines() if line.strip()]


def latest_target(tag, selected, state, owner):
    """Plan's prediction only; it can skip the shared-state job but never authorizes a write."""
    if not version_key(tag)[3]:
        return False
    recovery = selected == ["shared-state"]
    if recovery:
        return state == "complete" and owner == tag
    # A selected upload may make this tag the owner after plan has finished.
    return latest_owner(([owner] if owner else []) + [tag]) == tag


def plan(args):
    selected = select_destinations(args.destinations)
    recheck(args)
    require_release_gate(args.repository)
    channel = details(args.tag)[1]
    state = github_state(args.repository, args.tag)
    if selected == ["shared-state"] and state != "complete":
        raise ValueError("shared-state requires an already-published, complete GitHub release for this tag")
    owner = latest_owner(published_stable_tags(args.repository))
    shared = latest_target(args.tag, selected, state, owner)
    write_outputs(args.output, {
        "github": str("github" in selected).lower(), "shared_state": str(selected == ["shared-state"]).lower(),
        "github_public": state, "shared_needed": str(shared).lower(), "latest_owner": owner or "none",
        "destinations": ",".join(selected), "channel": channel})


def shared_write(args):
    """Called only in the cross-tag serialized job, directly before its public write."""
    recheck(args)
    if github_state(args.repository, args.tag) != "complete":
        print(f"Skip the latest marker: {args.tag} has no complete published release")
        return
    if not version_key(args.tag)[3]:
        print(f"Skip the latest marker for {args.tag}: a pre-release never owns it")
        return
    # Inventory is read last, after byte checks, never reused from plan.
    owner = latest_owner(published_stable_tags(args.repository))
    if owner != args.tag:
        print(f"Skip the latest marker for {args.tag}: the owner is {owner or 'none'}")
        return
    command("gh", "release", "edit", args.tag, "-R", args.repository, "--latest=true", capture=False)
    latest = api(f"repos/{args.repository}/releases/latest")
    if latest.get("tag_name") != args.tag:
        raise ValueError(f"GitHub reports {latest.get('tag_name')} as latest after marking {args.tag}")
    print(f"Marked {args.tag} as the latest release")


def preflight(args):
    """Credential checks that write nothing, for every destination selected in this dispatch."""
    select_destinations(args.destinations)
    if not os.environ.get("GH_TOKEN"):
        raise ValueError("GH_TOKEN is required")
    api(f"repos/{args.repository}/releases?per_page=1")
    require_release_gate(args.repository)
    print(f"Credential preflight passed for {args.destinations}")


def reconcile(args):
    recheck(args)
    state = github_state(args.repository, args.tag)
    write_outputs(args.output, {"state": state})
    print(f"GitHub: {state}")


def current_latest(repository):
    try:
        return api(f"repos/{repository}/releases/latest").get("tag_name")
    except subprocess.CalledProcessError as error:
        if "HTTP 404" in (error.stderr or ""):
            return None
        raise


def check_latest_untouched(repository, tag):
    """The release is created with --latest=false; only the shared-state job may move the marker."""
    latest = current_latest(repository)
    owner = latest_owner(published_stable_tags(repository))
    if latest == tag and owner != tag:
        raise ValueError(f"GitHub marks {tag} as latest although {owner or 'no stable release'} owns it; "
                         f"dispatch shared-state for {owner} to restore the marker")


def verify_published(args):
    """After the create, require the release to be complete as anonymous consumers download it."""
    problem = "release not visible yet"
    for attempt in range(1, VERIFY_TRIES + 1):
        try:
            complete = github_state(args.repository, args.tag) == "complete"
            problem = "release not visible yet"
        except (ValueError, subprocess.CalledProcessError) as error:
            complete, problem = False, str(error)
        if complete:
            print("GitHub serves the candidate bytes")
            check_latest_untouched(args.repository, args.tag)
            return
        if attempt < VERIFY_TRIES:
            print(f"GitHub not verified yet ({problem}); retrying")
            time.sleep(VERIFY_DELAY)
    raise ValueError(f"GitHub release could not be verified from consumer downloads: {problem}")


def notes(args):
    version, channel = details(args.tag)
    lines = Path("CHANGELOG.md").read_text(encoding="utf-8").splitlines()
    base = version.split("-")[0]
    for heading in (version, base):
        start = next((i + 1 for i, line in enumerate(lines) if line.startswith(f"## [{heading}]")), None)
        if start is not None:
            end = next((i for i in range(start, len(lines)) if lines[i].startswith("## ")), len(lines))
            text = "\n".join(lines[start:end]).strip()
            if text:
                Path(args.output).write_text(text + "\n", encoding="utf-8", newline="\n")
                return
    if channel == "stable":
        raise ValueError(f"Stable release has no CHANGELOG.md section for {version}")
    Path(args.output).write_text(f"NinjaArena {version} pre-release.\n", encoding="utf-8", newline="\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("fetch", "plan", "preflight", "reconcile", "guard", "verify-published", "notes", "prerelease",
                 "shared-write"):
        item = sub.add_parser(name)
        item.add_argument("--tag", required=True)
        if name not in ("notes", "prerelease"):
            item.add_argument("--repository", required=True)
        if name in ("fetch", "guard"):
            item.add_argument("--run-id", type=int, required=True)
        if name == "guard":
            item.add_argument("--attempt", type=int, required=True)
        if name in ("plan", "reconcile", "notes"):
            item.add_argument("--output", required=True)
        if name in ("plan", "preflight"):
            item.add_argument("--destinations", required=True)
    args = parser.parse_args()
    handlers = {"fetch": fetch, "plan": plan, "preflight": preflight, "reconcile": reconcile, "guard": guard,
                "verify-published": verify_published, "notes": notes, "shared-write": shared_write}
    try:
        if not TAG.fullmatch(args.tag):
            raise ValueError(f"Invalid release tag: {args.tag!r}")
        if args.action in handlers:
            handlers[args.action](args)
        else:
            print("--prerelease" if details(args.tag)[1] == "prerelease" else "")
    except (ValueError, OSError, KeyError, TypeError, subprocess.CalledProcessError) as exc:
        parser.exit(1, f"Release promotion stopped: {exc}\n")


if __name__ == "__main__":
    main()
