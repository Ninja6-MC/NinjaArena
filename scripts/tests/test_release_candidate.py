"""The candidate manifest, the downloaded-file inspection and the evidence binding.

The packs are built by the real pack builders from this checkout. The JAR is synthetic: it
carries the real resources and one class file per source file, which is what the
inspection checks, without needing a Gradle build inside a unit test.
"""

import argparse
import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_candidate as rc

TAG = "v0.5.0-rc.1"
VERSION = "0.5.0-rc.1"
SOURCE = "a" * 40
RUN = 777
CLASS = b"\xca\xfe\xba\xbe\x00\x00\x00\x45" + b"\x00" * 8


def write_jar(path, version=VERSION, drop=(), extra=None, class_header=CLASS, resource_override=None):
    resources = rc.source_resources(rc.ROOT)
    resources["plugin.yml"] = resources["plugin.yml"].replace(b"${version}", version.encode())
    resources.update(resource_override or {})
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/", b"")
        archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n\r\n")
        for name, data in sorted(resources.items()):
            if name not in drop:
                archive.writestr(name, data)
        for source in sorted((rc.ROOT / "src/main/java").rglob("*.java")):
            name = source.relative_to(rc.ROOT / "src/main/java").as_posix()[:-5] + ".class"
            if name not in drop:
                archive.writestr(name, class_header)
        for name, data in (extra or {}).items():
            archive.writestr(name, data)


def junit(folder, cases):
    folder.mkdir(parents=True, exist_ok=True)
    body = "".join(f'<testcase classname="{c}" name="{n}">{o}</testcase>' for c, n, o in cases)
    (folder / "TEST-x.xml").write_text(f'<testsuite name="x">{body}</testsuite>', encoding="utf-8")


class CandidateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.built = tempfile.TemporaryDirectory()
        base = Path(cls.built.name)
        rc.java_packs.build(rc.ROOT, base / "java", TAG, SOURCE, str(RUN), "1")
        rc.bedrock_packs.convert(rc.ROOT, base / "converted", TAG)
        (base / "mappings.json").write_bytes(rc.bedrock_packs.mappings())
        rc.bedrock_packs.build(rc.ROOT, base / "bedrock", TAG, SOURCE, str(RUN), "1",
                               base / "converted", base / "mappings.json")
        write_jar(base / f"NinjaArena-{VERSION}.jar")
        cls.base = base

    @classmethod
    def tearDownClass(cls):
        cls.built.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.candidate = self.root / "candidate"
        self.manifest = self.root / "candidate-manifest.json"
        self.assemble()
        self.create()

    def assemble(self, jar=None, directory=None):
        rc.assemble(argparse.Namespace(tag=TAG, jar=str(jar or self.base / f"NinjaArena-{VERSION}.jar"),
                                       java=str(self.base / "java"), bedrock=str(self.base / "bedrock"),
                                       directory=str(directory or self.candidate)))

    def create(self, **fields):
        values = dict(tag=TAG, source=SOURCE, run_id=RUN, attempt=1, artifact_id=9,
                      directory=str(self.candidate), output=str(self.manifest))
        values.update(fields)
        rc.create(argparse.Namespace(**values))

    def verify(self, evidence=None, **fields):
        values = dict(tag=TAG, source=SOURCE, run_id=RUN, attempt=1, manifest=str(self.manifest),
                      directory=str(self.candidate), evidence=evidence)
        values.update(fields)
        rc.verify(argparse.Namespace(**values))

    def evidence(self, cases=None):
        results = self.root / "results"
        junit(results, cases if cases is not None else [(*rc.RELEASE_TEST, ""), ("a.B", "other()", "")])
        output = self.root / "candidate-evidence.json"
        rc.evidence(argparse.Namespace(run_id=RUN, attempt=1, manifest=str(self.manifest),
                                       directory=str(self.candidate), test_results=str(results), output=str(output)))
        return output

    def rehash(self):
        """Rewrite SHA256SUMS and the manifest so only the content checks can catch a change."""
        names = rc.expected_files(TAG)
        (self.candidate / rc.SUMS).write_text(rc.sums_text(self.candidate, names - {rc.SUMS}), encoding="utf-8")
        self.create()

    def replace_jar(self, **kwargs):
        write_jar(self.candidate / f"NinjaArena-{VERSION}.jar", **kwargs)
        self.rehash()

    def test_tag_grammar_and_channels(self):
        self.assertEqual(("0.5.0", "stable"), rc.details("v0.5.0"))
        self.assertEqual(("0.5.0-alpha.2", "prerelease"), rc.details("v0.5.0-alpha.2"))
        for tag in ("0.5.0", "v1.0.0", "v0.5", "v0.05.0", "v0.5.0-rc.0", "v0.5.0-dev.1", "v0.5.0-rc.1/x",
                    "v0.65535.0", "v0.5.0-rc.10000", None):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                rc.details(tag)

    def test_manifest_names_every_asset_with_its_digest(self):
        data = rc.read_json(self.manifest)
        self.assertEqual(rc.expected_files(TAG), set(data["files"]))
        self.assertIn(f"NinjaArena-{VERSION}.jar", data["files"])
        self.assertIn(f"NinjaArena-{TAG}-geyser-mappings.json", data["files"])
        self.assertEqual(["github"], data["destinations"])
        self.assertEqual(("prerelease", RUN, 1, SOURCE, TAG), (data["channel"], data["run_id"], data["attempt"],
                                                               data["source"], data["tag"]))
        self.verify()

    def test_assemble_refuses_to_replace_a_candidate_or_take_a_wrong_jar(self):
        with self.assertRaisesRegex(ValueError, "must be empty"):
            self.assemble()
        other = self.root / "NinjaArena-0.5.0.jar"
        shutil.copyfile(self.base / f"NinjaArena-{VERSION}.jar", other)
        with self.assertRaisesRegex(ValueError, "Expected the built JAR"):
            self.assemble(jar=other, directory=self.root / "other")

    def test_identity_mismatches_fail(self):
        for field, value, message in (("tag", "v0.5.0-rc.2", "tag|candidate_id"), ("source", "b" * 40, "candidate_id"),
                                      ("run_id", 778, "candidate_id"), ("attempt", 2, "candidate_id")):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, message):
                self.verify(**{field: value})

    def test_changed_missing_or_extra_files_fail(self):
        jar = self.candidate / f"NinjaArena-{VERSION}.jar"
        original = jar.read_bytes()
        jar.write_bytes(original + b"x")
        with self.assertRaisesRegex(ValueError, "SHA-256 differs"):
            self.verify()
        jar.write_bytes(original)
        (self.candidate / "extra.txt").write_text("x")
        with self.assertRaisesRegex(ValueError, "inventory differs"):
            self.verify()
        (self.candidate / "extra.txt").unlink()
        (self.candidate / "SHA1SUMS").unlink()
        with self.assertRaisesRegex(ValueError, "inventory differs"):
            self.verify()

    def test_jar_with_another_version_fails_even_when_rehashed(self):
        self.replace_jar(version="0.5.0")
        with self.assertRaisesRegex(ValueError, "plugin.yml"):
            self.verify()

    def test_jar_missing_a_class_or_carrying_extra_entries_fails(self):
        for kwargs, message in ((dict(drop={rc.MAIN_CLASS}), "main class"),
                                (dict(drop={"com/ninja6/ninjaarena/config/ConfigReload.class"}), "classes do not match"),
                                (dict(extra={"com/evil/Payload.class": CLASS}), "classes do not match"),
                                (dict(extra={"notes.txt": b"x"}), "unexpected"),
                                (dict(drop={"messages.yml"}), "messages.yml"),
                                (dict(resource_override={"config.yml": b"changed: true\n"}), "config.yml"),
                                (dict(class_header=b"\xca\xfe\xba\xbe\x00\x00\x00\x41"), "Java 25")):
            with self.subTest(kwargs=kwargs):
                self.replace_jar(**kwargs)
                with self.assertRaisesRegex(ValueError, message):
                    self.verify()

    def test_tampered_pack_fails_source_verification_even_when_rehashed(self):
        mappings = self.candidate / f"NinjaArena-{TAG}-geyser-mappings.json"
        mappings.write_bytes(mappings.read_bytes().replace(b"snowball", b"egg", 1))
        self.rehash()
        with self.assertRaisesRegex(ValueError, "digest mismatch|mismatch"):
            self.verify()

    def test_evidence_binds_to_manifest_and_requires_the_release_jar_test(self):
        evidence = self.evidence()
        self.verify(evidence=str(evidence))
        data = json.loads(evidence.read_text())
        data["result"] = "failed"
        evidence.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "evidence"):
            self.verify(evidence=str(evidence))
        evidence = self.evidence()
        self.create(artifact_id=10)
        with self.assertRaisesRegex(ValueError, "evidence"):
            self.verify(evidence=str(evidence))

    def test_evidence_refuses_failed_skipped_or_missing_jar_tests(self):
        for cases, message in (([], "No unit test results"),
                               ([("a.B", "other()", "")], "did not run"),
                               ([(*rc.RELEASE_TEST, "<skipped/>")], "did not all pass"),
                               ([(*rc.RELEASE_TEST, ""), ("a.B", "other()", '<failure message="x"/>')], "did not all pass")):
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                self.evidence(cases)

    def test_evidence_is_recorded_only_by_the_candidate_run_and_attempt(self):
        results = self.root / "results"
        junit(results, [(*rc.RELEASE_TEST, "")])
        for run_id, attempt in ((RUN, 2), (RUN + 1, 1)):
            with self.subTest(run_id=run_id, attempt=attempt), self.assertRaisesRegex(ValueError, "run and attempt"):
                rc.evidence(argparse.Namespace(run_id=run_id, attempt=attempt, manifest=str(self.manifest),
                                               directory=str(self.candidate), test_results=str(results),
                                               output=str(self.root / "e.json")))


if __name__ == "__main__":
    unittest.main()
