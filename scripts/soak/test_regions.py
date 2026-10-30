import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from regions import Nbt, chunks, differences, inspect, section_blocks


def text(value):
    data = value.encode()
    return struct.pack(">H", len(data)) + data


def tag(kind, name, value):
    return bytes([kind]) + text(name) + value


def packed(values, bits=4):
    per_long = 64 // bits
    data = []
    for start in range(0, len(values), per_long):
        value = sum(v << (i * bits) for i, v in enumerate(values[start:start + per_long]))
        data.append(value if value < 1 << 63 else value - (1 << 64))
    return data


class RegionEvidenceTest(unittest.TestCase):
    def test_palette_order_padding_and_unused_entries_do_not_change_blocks(self):
        values = [0] * 4096
        values[15], values[16], values[-1] = 1, 1, 1
        original = {"block_states": {"palette": ["minecraft:air", "minecraft:gold_block"],
                                      "data": packed(values)}}
        reordered = {"block_states": {"palette": ["minecraft:gold_block", "minecraft:air", "minecraft:stone"],
                                       "data": packed([1 - v for v in values])}}
        self.assertEqual(section_blocks(original), section_blocks(reordered))
        changed = {"block_states": {"palette": ["minecraft:air", "minecraft:stone"], "data": packed(values)}}
        self.assertNotEqual(section_blocks(original), section_blocks(changed))

    def test_nbt_compound_signed_coordinates_and_string_palette_from_anvil(self):
        # A minimal 26.3 chunk at negative coordinates, all gold in the Y=4 section.
        palette = tag(9, "palette", bytes([8]) + struct.pack(">i", 1) + text("minecraft:gold_block"))
        states = tag(10, "block_states", palette + b"\0")
        section = tag(1, "Y", struct.pack(">b", 4)) + states + b"\0"
        sections = tag(9, "sections", bytes([10]) + struct.pack(">i", 1) + section)
        root = bytes([10]) + text("") + tag(3, "xPos", struct.pack(">i", -14))
        root += tag(3, "zPos", struct.pack(">i", -14)) + sections + b"\0"
        decoded = Nbt(root).root()
        self.assertEqual(-14, decoded["xPos"])
        compressed = zlib.compress(root)
        payload = struct.pack(">i", len(compressed) + 1) + bytes([2]) + compressed
        header = bytearray(8192)
        index = (18 + 18 * 32) * 4
        header[index:index + 4] = (2 << 8 | 1).to_bytes(4, "big")
        with tempfile.TemporaryDirectory() as folder:
            region = Path(folder) / "region"
            region.mkdir()
            file = region / "r.-1.-1.mca"
            file.write_bytes(header + payload + bytes(4096 - len(payload)))
            self.assertEqual(decoded, list(chunks(file))[0][1])
            result = inspect(Path(folder), {"marker": (-220, 64, -220)})
            self.assertEqual("minecraft:gold_block", result["saved_markers"]["marker"])
            changed = json.loads(json.dumps(result))
            entry = next(iter(changed["chunks"].values()))
            entry["full"] = "changed-metadata"
            self.assertEqual(1, differences(result, changed)["changed_nbt"])
            self.assertEqual(0, differences(result, changed)["changed_blocks"])

    def test_entity_region_detects_spawn_position_and_content_changes(self):
        def entity(position, health):
            return (tag(8, "id", text("minecraft:zombie"))
                    + tag(9, "Pos", bytes([6]) + struct.pack(">i3d", 3, *position))
                    + tag(5, "Health", struct.pack(">f", health)) + b"\0")

        def write_entities(directory, entries):
            root = bytes([10]) + text("")
            root += tag(9, "Entities", bytes([10]) + struct.pack(">i", len(entries)) + b"".join(entries))
            root += b"\0"
            compressed = zlib.compress(root)
            payload = struct.pack(">i", len(compressed) + 1) + bytes([2]) + compressed
            header = bytearray(8192)
            header[:4] = (2 << 8 | 1).to_bytes(4, "big")
            folder = directory / "entities"
            folder.mkdir(exist_ok=True)
            (folder / "r.0.0.mca").write_bytes(header + payload + bytes(4096 - len(payload)))
            return inspect(directory, {})

        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            empty = write_entities(directory, [])
            spawned = write_entities(directory, [entity((1.5, 64, 2.5), 20)])
            moved = write_entities(directory, [entity((2.5, 64, 2.5), 20)])
            damaged = write_entities(directory, [entity((2.5, 64, 2.5), 10)])
            for before, after in ((empty, spawned), (spawned, moved), (moved, damaged), (damaged, empty)):
                with self.subTest(before=before, after=after):
                    delta = differences(before, after)
                    self.assertEqual(1, delta["changed_nbt"])
                    self.assertEqual(1, delta["changed_entities"])
                    self.assertEqual(0, delta["changed_blocks"])
            (directory / "entities" / "r.0.0.mca").unlink()
            absent = inspect(directory, {})
            self.assertEqual(1, differences(absent, spawned)["added_chunks"])
            self.assertEqual(1, differences(absent, spawned)["changed_entities"])
            self.assertEqual(1, differences(spawned, absent)["removed_chunks"])
            self.assertEqual(1, differences(spawned, absent)["changed_entities"])
            self.assertEqual(0, differences(absent, empty)["changed_entities"])

    def test_truncated_nbt_fails_instead_of_certifying_empty_data(self):
        with self.assertRaises(ValueError):
            Nbt(bytes([10, 0])).root()


if __name__ == "__main__":
    unittest.main()
