"""Checks the actual downloadable ZIPs and checksums."""

import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("java_packs", Path(__file__).with_name("java-packs.py"))
packs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packs)
SOURCE = "a" * 40
VERSION = "v0.1.0-rc.1"


class JavaPacksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "candidate"
        self.manifest = packs.build(packs.ROOT, self.output, VERSION, SOURCE, "42", "1")

    def verify(self):
        return packs.verify(packs.ROOT, self.output, VERSION, SOURCE, "42", "1")

    def test_complete_standard_and_texture_only_detailed_have_root_metadata(self):
        for variant, count in (("standard", 14), ("detailed", 8)):
            with zipfile.ZipFile(self.output / f"NinjaArena-{VERSION}-java-{variant}.zip") as archive:
                self.assertEqual(count, len(archive.namelist()))
                self.assertEqual(None, archive.testzip())
                self.assertEqual((packs.ROOT / f"resourcepack/java/{variant}/pack.png").read_bytes(), archive.read("pack.png"))
                metadata = json.loads(archive.read("pack.mcmeta"))["pack"]
                self.assertEqual([97, 1], metadata["min_format"])
                self.assertEqual([97, 1], metadata["max_format"])
                self.assertIn(VERSION, metadata["description"])
                self.assertFalse(any("portal" in name or "artwork" in name for name in archive.namelist()))
        self.assertEqual("passed", self.verify()["result"])

    def test_build_is_reproducible_and_refuses_to_replace_candidate(self):
        other = Path(self.temp.name) / "other"
        packs.build(packs.ROOT, other, VERSION, SOURCE, "42", "1")
        for file in self.output.iterdir():
            self.assertEqual(file.read_bytes(), (other / file.name).read_bytes())
        with self.assertRaisesRegex(ValueError, "must be empty"):
            packs.build(packs.ROOT, self.output, VERSION, SOURCE)

    def test_modified_zip_or_hash_file_fails_verification(self):
        for name in (f"NinjaArena-{VERSION}-java-standard.zip", "SHA1SUMS"):
            file = self.output / name
            old = file.read_bytes()
            file.write_bytes(old + b"tampered")
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                self.verify()
            file.write_bytes(old)

    def test_wrong_candidate_identity_and_unexpected_files_fail(self):
        with self.assertRaisesRegex(ValueError, "source mismatch"):
            packs.verify(packs.ROOT, self.output, VERSION, "b" * 40, "42", "1")
        with self.assertRaisesRegex(ValueError, "attempt mismatch"):
            packs.verify(packs.ROOT, self.output, VERSION, SOURCE, "42", "2")
        (self.output / "source.svg").write_text("editable artwork")
        with self.assertRaisesRegex(ValueError, "unexpected files"):
            self.verify()

    def test_unapproved_sources_and_mismatched_target_are_rejected(self):
        root = Path(self.temp.name) / "root"
        shutil.copytree(packs.ROOT / "resourcepack", root / "resourcepack")
        shutil.copyfile(packs.ROOT / "build.gradle.kts", root / "build.gradle.kts")
        unwanted = root / "resourcepack/java/standard/source.vectorcraft"
        unwanted.write_text("editable source")
        with self.assertRaisesRegex(ValueError, "unexpected pack files"):
            packs.source_files(root, "standard", VERSION)
        unwanted.unlink()
        target = root / "resourcepack/target.json"
        value = packs.read_json(target)
        value["minecraft"] = "26.2"
        packs.write_json(target, value)
        with self.assertRaisesRegex(ValueError, "does not match the Paper dependency"):
            packs.source_files(root, "standard", VERSION)

    def test_content_and_embedded_version_are_checked_even_with_updated_digests(self):
        name = f"NinjaArena-{VERSION}-java-detailed.zip"
        file = self.output / name
        with zipfile.ZipFile(file, "a") as archive:
            archive.writestr("unexpected.txt", b"not production")
        manifest = packs.read_json(self.output / "manifest.json")
        manifest["files"][name] = {algorithm: packs.digest(file.read_bytes(), algorithm) for algorithm in ("sha1", "sha256")}
        sums = self.output / "SHA1SUMS"
        sums.write_text("".join(f'{v["sha1"]}  {k}\n' for k, v in sorted(manifest["files"].items()) if k.endswith(".zip")))
        manifest["files"]["SHA1SUMS"]["sha256"] = packs.digest(sums.read_bytes())
        packs.write_json(self.output / "manifest.json", manifest)
        with self.assertRaisesRegex(ValueError, "archive inventory mismatch"):
            self.verify()

    def test_missing_or_changed_icons_fail_even_with_updated_digests(self):
        for variant in packs.VARIANTS:
            file = self.output / f"NinjaArena-{VERSION}-java-{variant}.zip"
            original = file.read_bytes()
            for replacement in (None, b"broken icon"):
                with zipfile.ZipFile(file) as archive:
                    entries = {n: archive.read(n) for n in archive.namelist() if n != "pack.png"}
                if replacement is not None:
                    entries["pack.png"] = replacement
                with zipfile.ZipFile(file, "w") as archive:
                    for name, data in entries.items():
                        archive.writestr(name, data)
                manifest = packs.read_json(self.output / "manifest.json")
                for name, checksums in manifest["files"].items():
                    if name.endswith(".zip"):
                        manifest["files"][name] = {a: packs.digest((self.output / name).read_bytes(), a) for a in ("sha1", "sha256")}
                sums = "".join(f'{v["sha1"]}  {n}\n' for n, v in sorted(manifest["files"].items()) if n.endswith(".zip"))
                (self.output / "SHA1SUMS").write_bytes(sums.encode())
                manifest["files"]["SHA1SUMS"]["sha256"] = packs.digest(sums.encode())
                packs.write_json(self.output / "manifest.json", manifest)
                with self.assertRaisesRegex(ValueError, "archive (inventory|content) mismatch"):
                    self.verify()
                file.write_bytes(original)

    def test_missing_and_non_square_source_icons_fail(self):
        root = Path(self.temp.name) / "icons-root"
        shutil.copytree(packs.ROOT / "resourcepack", root / "resourcepack")
        shutil.copyfile(packs.ROOT / "build.gradle.kts", root / "build.gradle.kts")
        for variant in packs.VARIANTS:
            icon = root / f"resourcepack/java/{variant}/pack.png"
            original = icon.read_bytes()
            icon.unlink()
            with self.assertRaisesRegex(ValueError, "missing or unexpected pack files"):
                packs.source_files(root, variant, VERSION)
            icon.write_bytes(original[:20] + (128).to_bytes(4, "big") + original[24:])
            with self.assertRaisesRegex(ValueError, "256x256 PNG"):
                packs.source_files(root, variant, VERSION)
            icon.write_bytes(original)

    def test_versions_and_channels_cannot_be_paths_or_stable_prereleases(self):
        self.assertEqual("stable", packs.version_channel("v0.1.0"))
        self.assertEqual("prerelease", packs.version_channel(VERSION))
        self.assertEqual("snapshot", packs.version_channel("ci-" + SOURCE))
        for invalid in ("../evil", "v0.1.0/evil", "latest", "v0.1.0-alpha.0", "v0.01.0"):
            with self.assertRaises(ValueError):
                packs.version_channel(invalid)


if __name__ == "__main__":
    unittest.main()
