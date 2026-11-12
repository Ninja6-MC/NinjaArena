"""Refusal paths of the GitHub-only publisher (N6-REL-03 and N6-REL-05).

Covered: a candidate run from another tag, workflow, branch, repository or attempt; a
publish on a later run attempt; a concurrent or later-finished publisher run for the tag;
a latest-marker write by a tag that does not own it, both a pre-release while a stable
exists and a stable hotfix below a newer stable; draft, conflicting and mismatched
releases; and a release environment without the maintainer's review. Every network call
is mocked.
"""

import argparse
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_promotion as rp

REPO = "Ninja6-MC/NinjaArena"
TAG = "v0.5.0-rc.1"
SOURCE = "a" * 40
NOT_FOUND = subprocess.CalledProcessError(1, ["gh"], stderr="gh: Not Found (HTTP 404)")
GATE = {"protection_rules": [{"type": "required_reviewers", "reviewers": [
    {"type": "User", "reviewer": {"login": "BharathASL"}}]}, {"type": "branch_policy"}]}


def candidate_run(**fields):
    run = {"name": "Release Candidate", "path": ".github/workflows/release-candidate.yml",
           "repository": {"full_name": REPO}, "head_repository": {"full_name": REPO},
           "event": "workflow_dispatch", "head_branch": "main", "status": "completed",
           "conclusion": "success", "run_attempt": 1, "head_sha": SOURCE}
    run.update(fields)
    return run


class InTempDirectory(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        previous = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, previous)


class CandidateRunTests(InTempDirectory):
    """The publisher accepts only a successful first-attempt main run of release-candidate.yml."""

    def test_run_from_another_workflow_branch_repository_or_attempt_is_refused(self):
        for fields in ({"path": ".github/workflows/ci.yml"}, {"name": "Continuous Integration"},
                       {"head_branch": "feature"}, {"event": "push"}, {"conclusion": "failure"},
                       {"status": "in_progress"}, {"run_attempt": 2},
                       {"repository": {"full_name": "someone/NinjaArena"}},
                       {"head_repository": {"full_name": "someone/NinjaArena"}}):
            with self.subTest(fields=fields), self.assertRaisesRegex(ValueError, "Candidate run is not"):
                rp.check_candidate_run(candidate_run(**fields), REPO)
        rp.check_candidate_run(candidate_run(), REPO)

    def fetch(self, manifest, tag_commit=SOURCE, artifacts=None):
        def download(*args, **kwargs):
            if args[:3] == ("gh", "run", "download"):
                Path("candidate-manifest.json").write_text(json.dumps(manifest))
            return 0

        listed = artifacts if artifacts is not None else [
            {"name": name, "id": 9 if name == "release-candidate" else 10 + i, "expired": False}
            for i, name in enumerate(rp.ARTIFACTS)]
        responses = {f"repos/{REPO}/actions/runs/500": candidate_run(),
                     f"repos/{REPO}/actions/runs/500/artifacts?per_page=100": {"artifacts": listed}}
        with patch.object(rp, "api", side_effect=lambda path: responses[path]), \
             patch.object(rp, "tag_source", return_value=tag_commit), \
             patch.object(rp, "command", side_effect=download), \
             patch.object(rp, "verify") as verify:
            rp.fetch(argparse.Namespace(repository=REPO, run_id=500, tag=TAG))
        return verify

    def manifest(self, **fields):
        data = {"tag": TAG, "source": SOURCE, "run_id": 500, "attempt": 1, "artifact_id": 9}
        data.update(fields)
        return data

    def test_candidate_made_for_another_tag_commit_run_or_attempt_is_refused(self):
        for fields in ({"tag": "v0.5.0-rc.2"}, {"source": "b" * 40}, {"run_id": 499}, {"attempt": 2}):
            with self.subTest(fields=fields), self.assertRaisesRegex(ValueError, "another tag, commit, run or attempt"):
                self.fetch(self.manifest(**fields))

    def test_tag_on_another_commit_is_refused_before_download(self):
        with self.assertRaisesRegex(ValueError, "does not resolve to the candidate source"):
            self.fetch(self.manifest(), tag_commit="b" * 40)

    def test_expired_missing_or_duplicated_artifacts_are_refused(self):
        names = list(rp.ARTIFACTS)
        cases = ([{"name": n, "id": 1, "expired": n == names[0]} for n in names],
                 [{"name": n, "id": 1, "expired": False} for n in names[1:]],
                 [{"name": n, "id": 1, "expired": False} for n in names + names[:1]])
        for artifacts in cases:
            with self.subTest(artifacts=artifacts), self.assertRaisesRegex(ValueError, "never rebuilt"):
                self.fetch(self.manifest(), artifacts=artifacts)

    def test_artifact_id_must_match_the_manifest(self):
        with self.assertRaisesRegex(ValueError, "artifact ID"):
            self.fetch(self.manifest(artifact_id=8))

    def test_accepted_candidate_is_verified_with_its_evidence_at_attempt_one(self):
        verify = self.fetch(self.manifest())
        namespace = verify.call_args.args[0]
        self.assertEqual((TAG, SOURCE, 500, 1, "candidate-evidence.json"),
                         (namespace.tag, namespace.source, namespace.run_id, namespace.attempt, namespace.evidence))

    def test_moved_tag_is_refused(self):
        outputs = {("git", "rev-parse"): SOURCE + "\n",
                   ("git", "ls-remote"): f"{'c' * 40}\trefs/tags/{TAG}\n{'b' * 40}\trefs/tags/{TAG}^{{}}\n"}
        with patch.object(rp, "command", side_effect=lambda *args, **kwargs: outputs[args[:2]]):
            with self.assertRaisesRegex(ValueError, "moved"):
                rp.tag_source(TAG)


RUN = {"id": 500, "status": "in_progress", "head_branch": TAG,
       "created_at": "2026-11-12T12:00:00Z", "updated_at": "2026-11-12T12:00:05Z"}


def other_run(**fields):
    run = {"id": 400, "status": "completed", "head_branch": TAG,
           "created_at": "2026-11-12T11:00:00Z", "updated_at": "2026-11-12T11:30:00Z"}
    run.update(fields)
    return run


class RunGuardTests(unittest.TestCase):
    """The guard beside the create: a later attempt and other publisher runs are refused."""

    def guard(self, attempt=1, runs=()):
        args = argparse.Namespace(repository=REPO, tag=TAG, run_id=500, attempt=attempt)
        with patch.object(rp, "api", side_effect=lambda path: RUN if path.endswith("/runs/500") else GATE) as api, \
             patch.object(rp, "publisher_runs", return_value=[RUN, *runs]), \
             patch.object(rp, "recheck") as recheck, \
             patch.object(rp, "github_state", return_value="absent") as state:
            rp.guard(args)
        return api, recheck, state

    def test_later_attempt_is_refused_before_any_lookup(self):
        for attempt in (2, 3):
            with self.subTest(attempt=attempt), patch.object(rp, "api") as api, patch.object(rp, "recheck") as recheck:
                with self.assertRaisesRegex(ValueError, f"attempt {attempt}"):
                    rp.guard(argparse.Namespace(repository=REPO, tag=TAG, run_id=500, attempt=attempt))
                api.assert_not_called()
                recheck.assert_not_called()

    def test_concurrent_publisher_run_is_refused(self):
        for status in ("in_progress", "queued", "waiting", "pending"):
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, "publisher run 400"):
                self.guard(runs=[other_run(status=status)])

    def test_run_that_finished_after_this_one_was_created_is_refused(self):
        with self.assertRaisesRegex(ValueError, "publisher run 400"):
            self.guard(runs=[other_run(updated_at="2026-11-12T12:00:01Z")])

    def test_runs_that_cannot_have_written_are_ignored(self):
        _, recheck, state = self.guard(runs=[
            other_run(),  # finished before this run was created
            other_run(id=600, status="completed", created_at="2026-11-12T12:01:00Z", updated_at="2026-11-12T12:02:00Z"),
            other_run(id=601, status="queued", created_at="2026-11-12T12:01:00Z"),  # queued behind this run
            other_run(id=700, status="in_progress", head_branch="v0.4.0")])  # another tag
        recheck.assert_called_once()
        state.assert_called_once()

    def test_run_on_another_ref_is_refused(self):
        with patch.object(rp, "api", return_value=dict(RUN, head_branch="main")), self.assertRaisesRegex(ValueError, "tag ref"):
            rp.check_run(REPO, TAG, 500, 1)

    def test_release_appearing_after_reconciliation_stops_the_create(self):
        with patch.object(rp, "api", side_effect=lambda path: RUN if path.endswith("/runs/500") else GATE), \
             patch.object(rp, "publisher_runs", return_value=[RUN]), patch.object(rp, "recheck"), \
             patch.object(rp, "github_state", return_value="complete"):
            with self.assertRaisesRegex(ValueError, "appeared"):
                rp.guard(argparse.Namespace(repository=REPO, tag=TAG, run_id=500, attempt=1))

    def test_release_environment_without_the_maintainer_fails_closed(self):
        for environment in ({}, {"protection_rules": []}, {"protection_rules": [{"type": "branch_policy"}]},
                            {"protection_rules": [{"type": "required_reviewers", "reviewers": [
                                {"type": "Team", "reviewer": {"slug": "bharathasl"}}]}]}):
            with self.subTest(environment=environment), patch.object(rp, "api", return_value=environment):
                with self.assertRaisesRegex(ValueError, "maintainer"):
                    rp.require_release_gate(REPO)
        with patch.object(rp, "api", return_value=GATE):
            rp.require_release_gate(REPO)


class GitHubStateTests(InTempDirectory):
    def setUp(self):
        super().setUp()
        self.files = {"NinjaArena-0.5.0-rc.1.jar": b"jar bytes", "SHA256SUMS": b"sums"}
        Path("candidate-manifest.json").write_text(json.dumps({
            "source": SOURCE, "files": {n: hashlib.sha256(d).hexdigest() for n, d in self.files.items()}}))

    def release(self, **fields):
        release = {"tag_name": TAG, "draft": False, "prerelease": True, "target_commitish": SOURCE,
                   "assets": [{"name": n, "state": "uploaded",
                               "browser_download_url": f"https://github.com/{REPO}/releases/download/{TAG}/{n}"}
                              for n in self.files]}
        release.update(fields)
        return release

    def state(self, release, served=None, drafts=""):
        def urlopen(request, **kwargs):
            name = request.full_url.rsplit("/", 1)[1]
            return io.BytesIO((served or self.files)[name])

        with patch.object(rp, "api", side_effect=NOT_FOUND if release is None else (lambda path: release)), \
             patch.object(rp, "command", return_value=drafts) as command, \
             patch.object(rp.urllib.request, "urlopen", side_effect=urlopen) as opened:
            return rp.github_state(REPO, TAG), command, opened

    def test_matching_release_is_complete_from_anonymous_downloads(self):
        state, _, opened = self.state(self.release())
        self.assertEqual("complete", state)
        for call in opened.call_args_list:
            self.assertIsNone(call.args[0].get_header("Authorization"))

    def test_missing_release_is_absent(self):
        state, command, _ = self.state(None)
        self.assertEqual("absent", state)
        self.assertEqual(("gh", "api", "--paginate"), command.call_args.args[:3])

    def test_draft_for_the_tag_stops_without_a_second_create(self):
        draft = json.dumps({"tag_name": TAG, "html_url": "https://github.com/x/releases/42", "id": 42, "name": "Half"})
        with self.assertRaisesRegex(ValueError, "Half.*releases/42.*delete that draft manually"):
            self.state(None, drafts=draft)
        with self.assertRaisesRegex(ValueError, "draft"):
            self.state(self.release(draft=True))
        other = json.dumps({"tag_name": "v0.4.0", "id": 1})
        self.assertEqual("absent", self.state(None, drafts=other)[0])

    def test_mismatched_release_is_never_treated_as_complete(self):
        for fields, message in (({"prerelease": False}, "metadata"), ({"target_commitish": "main"}, "metadata"),
                                ({"assets": []}, "asset list"),
                                ({"assets": self.release()["assets"] * 2}, "asset list")):
            with self.subTest(fields=fields), self.assertRaisesRegex(ValueError, message):
                self.state(self.release(**fields))

    def test_conflicting_or_untrusted_asset_bytes_stop(self):
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            self.state(self.release(), served={**self.files, "SHA256SUMS": b"other"})
        release = self.release()
        release["assets"][0]["browser_download_url"] = "https://example.com/NinjaArena-0.5.0-rc.1.jar"
        with self.assertRaisesRegex(ValueError, "trusted download URL"):
            self.state(release)
        release = self.release()
        release["assets"][1]["state"] = "starter"
        with self.assertRaisesRegex(ValueError, "not fully uploaded"):
            self.state(release)

    def test_verify_published_retries_then_fails_when_consumers_never_get_the_bytes(self):
        args = argparse.Namespace(repository=REPO, tag=TAG)
        with patch.object(rp, "github_state", side_effect=["absent", ValueError("bytes differ"), "complete"]) as state, \
             patch.object(rp, "check_latest_untouched") as latest, patch.object(rp.time, "sleep"):
            rp.verify_published(args)
        self.assertEqual(3, state.call_count)
        latest.assert_called_once()
        with patch.object(rp, "github_state", side_effect=ValueError("bytes differ")), patch.object(rp.time, "sleep"):
            with self.assertRaisesRegex(ValueError, "could not be verified.*bytes differ"):
                rp.verify_published(args)


class LatestMarkerTests(unittest.TestCase):
    """Shared state follows the highest stable version; ownership is rechecked before the write."""

    def test_owner_is_the_highest_stable_and_never_a_prerelease(self):
        self.assertIsNone(rp.latest_owner([]))
        self.assertIsNone(rp.latest_owner(["v0.1.0-rc.1", "v0.5.0-rc.1"]))
        self.assertEqual("v0.5.0", rp.latest_owner(["v0.4.9", "v0.5.0", "v0.6.0-rc.1", "v0.4.10"]))
        self.assertEqual("v0.10.0", rp.latest_owner(["v0.9.0", "v0.10.0"]))

    def test_plan_prediction(self):
        self.assertFalse(rp.latest_target("v0.6.0-rc.1", ["github"], "absent", "v0.5.0"))
        self.assertFalse(rp.latest_target("v0.6.0-rc.1", ["github"], "absent", None))
        self.assertFalse(rp.latest_target("v0.5.1", ["github"], "absent", "v0.6.0"))
        self.assertTrue(rp.latest_target("v0.5.1", ["github"], "absent", "v0.5.0"))
        self.assertTrue(rp.latest_target("v0.5.0", ["github"], "absent", None))
        self.assertTrue(rp.latest_target("v0.5.0", ["shared-state"], "complete", "v0.5.0"))
        self.assertFalse(rp.latest_target("v0.5.0", ["shared-state"], "complete", "v0.6.0"))

    def shared_write(self, tag, published, state="complete", latest=None):
        args = argparse.Namespace(repository=REPO, tag=tag)
        with patch.object(rp, "recheck") as recheck, \
             patch.object(rp, "github_state", return_value=state), \
             patch.object(rp, "published_stable_tags", return_value=published) as inventory, \
             patch.object(rp, "command") as command, \
             patch.object(rp, "api", return_value={"tag_name": latest or tag}):
            rp.shared_write(args)
        recheck.assert_called_once()
        return command, inventory

    def test_prerelease_while_a_stable_exists_does_not_write(self):
        command, _ = self.shared_write("v0.6.0-rc.1", ["v0.5.0"])
        command.assert_not_called()

    def test_stable_hotfix_below_a_newer_stable_does_not_write(self):
        command, inventory = self.shared_write("v0.5.1", ["v0.5.0", "v0.5.1", "v0.6.0"])
        command.assert_not_called()
        inventory.assert_called_once()

    def test_incomplete_release_does_not_write(self):
        command, inventory = self.shared_write("v0.6.0", ["v0.5.0"], state="absent")
        command.assert_not_called()
        inventory.assert_not_called()

    def test_owner_writes_and_reads_the_marker_back(self):
        command, _ = self.shared_write("v0.6.0", ["v0.5.0", "v0.6.0"])
        command.assert_called_once_with("gh", "release", "edit", "v0.6.0", "-R", REPO, "--latest=true", capture=False)
        with self.assertRaisesRegex(ValueError, "reports v0.5.0 as latest"):
            self.shared_write("v0.6.0", ["v0.5.0", "v0.6.0"], latest="v0.5.0")

    def test_create_must_not_have_moved_the_marker_to_a_non_owner(self):
        with patch.object(rp, "current_latest", return_value="v0.5.1"), \
             patch.object(rp, "published_stable_tags", return_value=["v0.5.1", "v0.6.0"]):
            with self.assertRaisesRegex(ValueError, "dispatch shared-state for v0.6.0"):
                rp.check_latest_untouched(REPO, "v0.5.1")
        with patch.object(rp, "current_latest", return_value="v0.6.0"), \
             patch.object(rp, "published_stable_tags", return_value=["v0.5.1", "v0.6.0"]):
            rp.check_latest_untouched(REPO, "v0.5.1")

    def test_shared_state_group_is_one_for_every_tag(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/release.yml").read_text(encoding="utf-8")
        job = workflow[workflow.index("\n  shared-state:\n"):workflow.index("\n  report-shared-state:\n")]
        self.assertIn("      group: release-shared-state\n", job)
        self.assertNotIn("github.ref", job[job.index("concurrency:"):job.index("runs-on:")])
        self.assertIn("concurrency:\n  group: publish-release-${{ github.ref }}\n", workflow)


class WorkflowShapeTests(unittest.TestCase):
    """Static checks on release.yml that the script tests cannot reach at run time."""

    def setUp(self):
        self.workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/release.yml").read_text(encoding="utf-8")

    def test_every_checkout_uses_the_dispatched_tag_commit(self):
        # checkout resolves a bare name as a branch before a tag, so a branch named like the
        # tag would otherwise supply the scripts and the release target.
        refs = [line.strip() for line in self.workflow.splitlines() if line.strip().startswith("ref:")]
        self.assertEqual(3, len(refs))
        self.assertEqual({"ref: ${{ github.sha }}"}, set(refs))
        self.assertNotIn("git rev-parse HEAD", self.workflow)
        self.assertIn('--target "$GITHUB_SHA"', self.workflow)

    def test_shared_state_needs_a_successful_or_skipped_publish(self):
        job = self.workflow[self.workflow.index("\n  shared-state:\n"):self.workflow.index("\n  report-shared-state:\n")]
        condition = next(line for line in job.splitlines() if line.strip().startswith("if:"))
        self.assertIn("(needs.publish-github.result == 'success' || needs.publish-github.result == 'skipped')", condition)

    def test_checkout_that_is_not_the_tag_commit_is_refused(self):
        outputs = {("git", "rev-parse", f"refs/tags/{TAG}^{{commit}}"): SOURCE + "\n",
                   ("git", "rev-parse", "HEAD^{commit}"): "b" * 40 + "\n"}
        with patch.object(rp, "command", side_effect=lambda *args, **kwargs: outputs[args]):
            with self.assertRaisesRegex(ValueError, "not the release tag's commit"):
                rp.tag_source(TAG)


class PlanTests(InTempDirectory):
    def plan(self, destinations, state, owner_tags, tag=TAG):
        output = self.root / "outputs"
        args = argparse.Namespace(repository=REPO, tag=tag, destinations=destinations, output=str(output))
        with patch.object(rp, "recheck"), patch.object(rp, "require_release_gate"), \
             patch.object(rp, "github_state", return_value=state), \
             patch.object(rp, "published_stable_tags", return_value=owner_tags):
            rp.plan(args)
        return dict(line.split("=", 1) for line in output.read_text().splitlines())

    def test_destinations_are_validated(self):
        for text in ("", "github,github", "modrinth", "github,shared-state", "GitHub"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                rp.select_destinations(text)
        self.assertEqual(["github"], rp.select_destinations("github"))

    def test_prerelease_plan_never_needs_shared_state(self):
        outputs = self.plan("github", "absent", ["v0.4.0"])
        self.assertEqual(("true", "false", "prerelease", "v0.4.0"),
                         (outputs["github"], outputs["shared_needed"], outputs["channel"], outputs["latest_owner"]))

    def test_shared_state_recovery_requires_a_complete_release(self):
        with self.assertRaisesRegex(ValueError, "already-published"):
            self.plan("shared-state", "absent", [], tag="v0.5.0")
        outputs = self.plan("shared-state", "complete", ["v0.5.0"], tag="v0.5.0")
        self.assertEqual(("false", "true", "true"), (outputs["github"], outputs["shared_state"], outputs["shared_needed"]))


class NotesTests(InTempDirectory):
    def test_stable_needs_its_section_and_prerelease_falls_back(self):
        Path("CHANGELOG.md").write_text("# Changelog\n\n## [Unreleased]\n\n- Next\n\n## [0.4.0]\n\n- Old\n")
        with self.assertRaisesRegex(ValueError, "no CHANGELOG.md section for 0.5.0"):
            rp.notes(argparse.Namespace(tag="v0.5.0", output="notes.md"))
        rp.notes(argparse.Namespace(tag="v0.5.0-rc.1", output="notes.md"))
        self.assertEqual("NinjaArena 0.5.0-rc.1 pre-release.\n", Path("notes.md").read_text())
        rp.notes(argparse.Namespace(tag="v0.4.0", output="notes.md"))
        self.assertEqual("- Old\n", Path("notes.md").read_text())


if __name__ == "__main__":
    unittest.main()
