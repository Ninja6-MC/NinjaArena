import csv
import gzip
import struct
from pathlib import Path
import tempfile
import unittest
import zipfile

from run import measurements, plugin_config, saved_world_uid, storage_comparison


class SavedWorldUidTest(unittest.TestCase):
    def test_paper_26_metadata_signed_int_array_uuid(self):
        # Real Paper 26.3 metadata shape, including negative signed UUID components.
        def text(value):
            data = value.encode()
            return struct.pack(">H", len(data)) + data
        values = (-1408465482, -1619705063, -1360446329, 951354506)
        root = b"\x0a" + text("") + b"\x03" + text("DataVersion") + struct.pack(">i", 5023)
        root += b"\x0a" + text("data") + b"\x0b" + text("uuid")
        root += struct.pack(">i4i", 4, *values) + b"\x00\x00"
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            metadata = directory / "data/paper/metadata.dat"
            metadata.parent.mkdir(parents=True)
            metadata.write_bytes(gzip.compress(root))
            self.assertEqual("ac0c85b6-9f75-4319-aee9-3c8738b4848a", saved_world_uid(directory))
            # A legacy file must not override the target Paper metadata.
            (directory / "uid.dat").write_bytes(bytes(16))
            self.assertEqual("ac0c85b6-9f75-4319-aee9-3c8738b4848a", saved_world_uid(directory))
            metadata.write_bytes(gzip.compress(root.replace(struct.pack(">i4i", 4, *values),
                                                         struct.pack(">i3i", 3, *values[:3]))))
            with self.assertRaises(ValueError):
                saved_world_uid(directory)

    def test_legacy_uid_and_missing_uuid(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            with self.assertRaises(FileNotFoundError):
                saved_world_uid(directory)
            (directory / "uid.dat").write_bytes(bytes.fromhex("ac0c85b69f754319aee93c8738b4848a"))
            self.assertEqual("ac0c85b6-9f75-4319-aee9-3c8738b4848a", saved_world_uid(directory))


class StorageComparisonTest(unittest.TestCase):
    def compare(self, old, new, changes=None, name="region/r.0.-1.mca", extra=None):
        with tempfile.TemporaryDirectory() as folder:
            before, after = Path(folder) / "before", Path(folder) / "after"
            before.mkdir()
            after.mkdir()
            for root, value in ((before, old), (after, new)):
                if value is not None:
                    target = root / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(value)
            if extra:
                (after / "extra.mca").write_bytes(b"extra")
            return storage_comparison(before, after, changes or {"changed_nbt": 0})

    def test_identical_bytes(self):
        result = self.compare(b"abc", b"abc")
        self.assertTrue(result["data_unchanged"])
        self.assertEqual({}, result["sector_padding"])

    def test_exact_next_sector_zero_padding(self):
        old = b"header" + bytes(range(256)) * 290
        new = old + bytes((-len(old)) % 4096)
        result = self.compare(old, new)
        self.assertTrue(result["data_unchanged"])
        self.assertEqual(len(new) - len(old), result["sector_padding"]["region/r.0.-1.mca"]["appended_zero_bytes"])

    def test_storage_mutations_rejected(self):
        old = b"header" + b"x" * 100
        padded = old + bytes(4096 - len(old))
        cases = {"nonzero_append": old + b"x" + bytes(4095 - len(old)),
                 "overpadding": padded + bytes(4096), "partial_padding": padded[:-1],
                 "shrink": old[:-1], "header_change": b"H" + padded[1:],
                 "prefix_change": padded[:50] + b"y" + padded[51:],
                 "same_size_change": old[:-1] + b"y"}
        for name, new in cases.items():
            with self.subTest(name=name):
                self.assertFalse(self.compare(old, new)["data_unchanged"])
        self.assertFalse(self.compare(bytes(4096), bytes(8192))["data_unchanged"])
        self.assertFalse(self.compare(b"", bytes(4096))["data_unchanged"])

    def test_inventory_changes_rejected(self):
        self.assertFalse(self.compare(b"abc", None)["data_unchanged"])
        self.assertFalse(self.compare(None, b"abc")["data_unchanged"])
        self.assertFalse(self.compare(b"abc", b"abc", extra=True)["data_unchanged"])

    def test_only_recognized_region_files_allow_padding(self):
        for name in ("region/other.mca", "other/r.0.0.mca", "region/r.0.0.dat"):
            with self.subTest(name=name):
                self.assertFalse(self.compare(b"abc", b"abc" + bytes(4093), name=name)["data_unchanged"])
        for directory in ("region", "entities", "poi"):
            self.assertTrue(self.compare(b"abc", b"abc" + bytes(4093),
                                        name=f"{directory}/r.-1.2.mca")["data_unchanged"])

    def test_nbt_changes_rejected_even_with_identical_bytes(self):
        for field in ("added_chunks", "removed_chunks", "changed_nbt", "changed_blocks",
                      "changed_block_encoding", "changed_block_entities", "changed_entities"):
            with self.subTest(field=field):
                self.assertFalse(self.compare(b"abc", b"abc", {field: 1})["data_unchanged"])
                self.assertFalse(self.compare(b"abc", b"abc" + bytes(4093), {field: 1})["data_unchanged"])


class MeasurementSummaryTest(unittest.TestCase):
    def test_candidate_bundled_config_supplies_defaults(self):
        with tempfile.TemporaryDirectory() as folder:
            candidate = Path(folder) / "candidate.jar"
            with zipfile.ZipFile(candidate, "w") as jar:
                jar.writestr("config.yml", "director:\n  base: 73.5\nlabel: caf\u00e9\n")
            self.assertEqual("director:\n  base: 73.5\nlabel: caf\u00e9\n", plugin_config(candidate))

    def test_candidate_without_config_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            candidate = Path(folder) / "candidate.jar"
            with zipfile.ZipFile(candidate, "w") as jar:
                jar.writestr("plugin.yml", "name: Example")
            with self.assertRaises(KeyError):
                plugin_config(candidate)

    def test_warmup_is_excluded_and_concurrent_populations_are_combined(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            fields = ["second", "arena", "phase", "intensity", "enemies", "looping", "minimum_health", "tps"]
            with open(output / "matches.csv", "w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=fields)
                writer.writeheader()
                for second in (1, 61):
                    for arena, enemies in enumerate((25, 25, 25, 5)):
                        writer.writerow({"second": second, "arena": f"soak{arena}", "phase": "OVERRUN",
                            "intensity": 0.75 if second == 1 else 1, "enemies": enemies,
                            "looping": "true", "minimum_health": 20, "tps": 19.99})
            with open(output / "ticks.csv", "w", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(["tick", "milliseconds"])
                writer.writerows((tick, 100) for tick in range(1200))
                writer.writerow([1200, 4])
            result = measurements(output)
            self.assertEqual(4, result["measured_match_samples"])
            self.assertEqual(1, result["arenas"]["soak0"]["intensity_min"])
            self.assertEqual({"min": 80, "median": 80, "max": 80}, result["global_enemies"])
            self.assertEqual(4, result["tick_ms"]["mean"])
            self.assertEqual(1, result["tick_ms"]["count"])
            self.assertTrue(result["all_overrun"])
            self.assertTrue(result["loops_running"])


if __name__ == "__main__":
    unittest.main()
