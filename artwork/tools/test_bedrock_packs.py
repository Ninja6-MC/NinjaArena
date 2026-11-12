"""Checks converted Bedrock files and item mappings."""

import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("bedrock_packs", Path(__file__).with_name("bedrock-packs.py"))
packs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packs)
SOURCE = "a" * 40
VERSION = "v0.1.0-rc.1"


class BedrockPacksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.converted = self.folder / "converted"
        self.mapping = self.folder / "mappings.json"
        packs.convert(packs.ROOT, self.converted, VERSION)
        self.mapping.write_bytes(packs.mappings())
        self.output = self.folder / "candidate"
        self.manifest = self.build(self.output)

    def build(self, output):
        return packs.build(packs.ROOT, output, VERSION, SOURCE, "42", "1", self.converted, self.mapping)

    def verify(self):
        return packs.verify(packs.ROOT, self.output, VERSION, SOURCE, "42", "1")

    def rehash(self):
        manifest = packs.java.read_json(self.output / packs.MANIFEST)
        for name, checksums in manifest["files"].items():
            for algorithm in checksums:
                checksums[algorithm] = packs.java.digest((self.output / name).read_bytes(), algorithm)
        sums = "".join(f'{v["sha1"]}  {n}\n' for n, v in sorted(manifest["files"].items()) if "sha1" in v)
        (self.output / packs.SUMS).write_bytes(sums.encode())
        manifest["files"][packs.SUMS]["sha256"] = packs.java.digest(sums.encode())
        packs.java.write_json(self.output / packs.MANIFEST, manifest)

    def test_both_packs_are_complete_with_stable_distinct_manifest_identities(self):
        identities = set()
        for variant, size in (("standard", 16), ("detailed", 32)):
            with zipfile.ZipFile(self.output / f"NinjaArena-{VERSION}-bedrock-{variant}.mcpack") as archive:
                self.assertEqual(18, len(archive.namelist()))
                self.assertEqual(None, archive.testzip())
                icon = archive.read("pack_icon.png")
                self.assertEqual((256, 256), packs.read_png(icon)[:2])
                self.assertEqual((packs.ROOT / f"resourcepack/java/{variant}/pack.png").read_bytes(), icon)
                metadata = json.loads(archive.read("manifest.json"))
                self.assertEqual(2, metadata["format_version"])
                self.assertEqual("resources", metadata["modules"][0]["type"])
                self.assertEqual([2, 0, 20001], metadata["header"]["version"])
                self.assertEqual(metadata["header"]["version"], metadata["modules"][0]["version"])
                self.assertEqual([1, 21, 0], metadata["header"]["min_engine_version"])
                identities.update((metadata["header"]["uuid"], metadata["modules"][0]["uuid"]))
                self.assertFalse("dependencies" in metadata)
                self.assertIn(VERSION, metadata["header"]["description"])
                atlas = json.loads(archive.read("textures/item_texture.json"))
                self.assertEqual("atlas.items", atlas["texture_name"])
                self.assertEqual(15, len(atlas["texture_data"]))
                for entry in atlas["texture_data"].values():
                    width, height, _ = packs.read_png(archive.read(entry["textures"] + ".png"))
                    self.assertEqual((size, size), (width, height))
                self.assertFalse(any(any(bad in name for bad in ("artwork", "portal", ".svg", ".vectorcraft", ".pcraft"))
                                     for name in archive.namelist()))
        self.assertEqual(4, len(identities))
        self.assertEqual("passed", self.verify()["result"])

    def test_mapping_has_fifteen_appearance_only_variants_and_unchanged_bases(self):
        mapping = json.loads(packs.mappings())
        self.assertEqual(2, mapping["format_version"])
        self.assertEqual({"minecraft:snowball", "minecraft:feather"}, set(mapping["items"]))
        self.assertEqual(10, len(mapping["items"]["minecraft:snowball"]))
        self.assertEqual(5, len(mapping["items"]["minecraft:feather"]))
        identifiers = set()
        for base, definitions in mapping["items"].items():
            for definition in definitions:
                self.assertEqual("definition", definition["type"])
                tool = definition["model"].split(":")[1]
                marker = definition["predicate"]
                self.assertEqual({"type": "match", "property": "custom_model_data", "index": 0, "value": marker["value"]}, marker)
                self.assertIn(marker["value"], packs.COLOURS)
                identifier = f"ninjaarena:{tool}_{marker['value']}"
                self.assertEqual(identifier, definition["bedrock_identifier"])
                self.assertEqual(identifier.replace(":", "."), definition["bedrock_options"]["icon"])
                identifiers.add(identifier)
                if tool == "dash":
                    self.assertEqual("minecraft:feather", base)
                    self.assertEqual({"minecraft:max_stack_size": 1,
                                      "minecraft:use_cooldown": {"seconds": 8.0, "cooldown_group": "minecraft:feather"}},
                                     definition["components"])
                else:
                    self.assertEqual("minecraft:snowball", base)
                    self.assertNotIn("components", definition)
        self.assertEqual(15, len(identifiers))
        for variant in packs.java.VARIANTS:
            atlas = json.loads(packs.source_files(packs.ROOT, variant, VERSION)["textures/item_texture.json"])
            self.assertEqual({i.replace(":", ".") for i in identifiers}, atlas["texture_data"].keys())

    def test_accent_composition_never_tints_base_pixels(self):
        for variant in packs.java.VARIANTS:
            folder = packs.ROOT / f"resourcepack/java/{variant}/assets/ninjaarena/textures/item"
            for tool in packs.java.TOOLS:
                _, _, base = packs.read_png((folder / f"{tool}.png").read_bytes())
                _, _, accent = packs.read_png((folder / f"{tool}_accent.png").read_bytes())
                files = packs.source_files(packs.ROOT, variant, VERSION)
                for colour in packs.COLOURS:
                    _, _, actual = packs.read_png(files[f"textures/items/ninjaarena/{tool}_{colour}.png"])
                    for i in range(0, len(base), 4):
                        if accent[i + 3] == 0:
                            self.assertEqual(base[i:i + 4], actual[i:i + 4])
        self.assertEqual(bytearray([128, 43, 43, 255]), packs.composite(
            bytearray([10, 20, 30, 255]), bytearray([128, 128, 128, 255]), packs.COLOURS["red"]))

    def test_composition_respects_partial_alpha_and_rejects_coloured_masks(self):
        result = packs.composite(bytearray([100, 100, 100, 255]), bytearray([255, 255, 255, 128]), (255, 0, 0))
        self.assertEqual(bytearray([178, 50, 50, 255]), result)
        with self.assertRaisesRegex(ValueError, "grayscale"):
            packs.composite(bytearray([1, 2, 3, 255]), bytearray([1, 2, 3, 255]), (255, 255, 255))

    def test_reproducible_bytes_and_refusal_to_replace_staging_or_candidate(self):
        other = self.folder / "other"
        self.build(other)
        for file in self.output.iterdir():
            self.assertEqual(file.read_bytes(), (other / file.name).read_bytes())
        with self.assertRaisesRegex(ValueError, "must be empty"):
            self.build(self.output)
        with self.assertRaisesRegex(ValueError, "must be empty"):
            packs.convert(packs.ROOT, self.converted, VERSION)

    def test_explicit_conversion_and_mapping_steps_are_required(self):
        with self.assertRaisesRegex(ValueError, "explicit convert"):
            packs.build(packs.ROOT, self.folder / "other", VERSION, SOURCE)
        self.mapping.write_text("{}")
        with self.assertRaisesRegex(ValueError, "mappings differ"):
            self.build(self.folder / "other")
        self.mapping.write_bytes(packs.mappings())
        (self.converted / "standard/unwanted.svg").write_text("editable source")
        with self.assertRaisesRegex(ValueError, "differ from repository"):
            self.build(self.folder / "other")

    def test_release_numeric_versions_increase_across_prerelease_and_release(self):
        versions = ("v0.1.0-alpha.1", "v0.1.0-alpha.2", "v0.1.0-beta.1", "v0.1.0-rc.1", "v0.1.0", "v0.1.1-alpha.1", "v0.2.0-alpha.1")
        numeric = [packs.pack_version(packs.ROOT, v) for v in versions]
        self.assertEqual(numeric, sorted(numeric))
        self.assertEqual(len(numeric), len({tuple(v) for v in numeric}))
        self.assertEqual([0, 0, 1], packs.pack_version(packs.ROOT, "ci-" + SOURCE))
        for invalid in ("v0.65535.0", "v0.1.65536", "v0.1.0-rc.10000"):
            with self.assertRaisesRegex(ValueError, "numeric version range"):
                packs.pack_version(packs.ROOT, invalid)

    def test_invalid_targets_and_duplicate_uuids_are_rejected(self):
        root = self.folder / "root"
        shutil.copytree(packs.ROOT / "resourcepack/bedrock", root / "resourcepack/bedrock")
        config = packs.target(root)
        config["detailed"]["header_uuid"] = config["standard"]["header_uuid"]
        packs.java.write_json(root / "resourcepack/bedrock/target.json", config)
        with self.assertRaisesRegex(ValueError, "distinct canonical UUIDs"):
            packs.target(root)
        config["min_engine_version"] = [1, -1, 0]
        packs.java.write_json(root / "resourcepack/bedrock/target.json", config)
        with self.assertRaisesRegex(ValueError, "unsigned 16-bit"):
            packs.target(root)

    def test_modified_or_extra_candidate_files_fail(self):
        name = f"NinjaArena-{VERSION}-bedrock-standard.mcpack"
        file = self.output / name
        original = file.read_bytes()
        file.write_bytes(original + b"tampered")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            self.verify()
        file.write_bytes(original)
        (self.output / "editable.vectorcraft").write_text("not runtime")
        with self.assertRaisesRegex(ValueError, "file inventory"):
            self.verify()

    def test_rehashed_mapping_tampering_still_fails_source_verification(self):
        (self.output / f"NinjaArena-{VERSION}-geyser-mappings.json").write_text("{}")
        self.rehash()
        with self.assertRaisesRegex(ValueError, "mappings or hash list"):
            self.verify()

    def test_rehashed_pack_tampering_still_fails_source_verification(self):
        with zipfile.ZipFile(self.output / f"NinjaArena-{VERSION}-bedrock-standard.mcpack", "a") as archive:
            archive.writestr("editable.svg", "not runtime")
        self.rehash()
        with self.assertRaisesRegex(ValueError, "archive inventory"):
            self.verify()

    def test_missing_or_changed_icons_fail_even_with_updated_digests(self):
        for variant in packs.java.VARIANTS:
            file = self.output / f"NinjaArena-{VERSION}-bedrock-{variant}.mcpack"
            original = file.read_bytes()
            for replacement in (None, b"broken icon"):
                with zipfile.ZipFile(file) as archive:
                    entries = {n: archive.read(n) for n in archive.namelist() if n != "pack_icon.png"}
                if replacement is not None:
                    entries["pack_icon.png"] = replacement
                with zipfile.ZipFile(file, "w") as archive:
                    for name, data in entries.items():
                        archive.writestr(name, data)
                self.rehash()
                with self.assertRaisesRegex(ValueError, "archive (inventory|content) mismatch"):
                    self.verify()
                file.write_bytes(original)
                self.rehash()

    def test_conversion_without_icon_cannot_build(self):
        for variant in packs.java.VARIANTS:
            icon = self.converted / variant / "pack_icon.png"
            original = icon.read_bytes()
            icon.unlink()
            with self.assertRaisesRegex(ValueError, "differ from repository"):
                self.build(self.folder / "other")
            icon.write_bytes(original)

    def test_wrong_source_and_attempt_fail(self):
        with self.assertRaisesRegex(ValueError, "source mismatch"):
            packs.verify(packs.ROOT, self.output, VERSION, "b" * 40, "42", "1")
        with self.assertRaisesRegex(ValueError, "attempt mismatch"):
            packs.verify(packs.ROOT, self.output, VERSION, SOURCE, "42", "2")


if __name__ == "__main__":
    unittest.main()
