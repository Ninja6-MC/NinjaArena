import csv
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from generated import (MATCH_OVERRIDES, distribution, generated_acceptance, generated_config,
                       generated_measurements, saved_blocks, section_value, stagger_seconds, workload_settings)
from test_regions import tag, text

ROOT = Path(__file__).resolve().parents[2]
BUNDLED = (ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8")


class GeneratedConfigTest(unittest.TestCase):
    def test_bundled_config_takes_every_override_once(self):
        config = generated_config(BUNDLED, 3)
        settings = workload_settings(config)
        self.assertEqual(3, settings["arenas.max-active"])
        self.assertEqual("round", settings["maps.default.regenerate"])
        for key, value in MATCH_OVERRIDES.items():
            self.assertEqual(value, settings[f"match.{key}"])
        self.assertIn("    base: 200.0\n", config)
        self.assertNotIn("    base: 20.0\n", config)
        # Everything else keeps its bundled value.
        self.assertEqual(section_value(BUNDLED, "stability", "match-mob-cap"),
                         section_value(config, "stability", "match-mob-cap"))
        self.assertEqual(section_value(BUNDLED, "shop", "max-seconds"), section_value(config, "shop", "max-seconds"))

    def test_missing_or_repeated_keys_are_refused(self):
        with self.assertRaises(ValueError):
            generated_config(BUNDLED.replace("    regenerate: match\n", ""), 4)
        with self.assertRaises(ValueError):
            generated_config(BUNDLED + "\nother:\n  rounds: 5\n", 4)
        with self.assertRaises(ValueError):
            generated_config(BUNDLED, 5)

    def test_section_value_reads_only_the_named_section(self):
        text = "match:\n  # note\n  rounds: 3\n\nshop:\n  max-seconds: 45\nother:\n  rounds: 9\n"
        self.assertEqual("3", section_value(text, "match", "rounds"))
        self.assertEqual("45", section_value(text, "shop", "max-seconds"))
        with self.assertRaises(ValueError):
            section_value(text, "shop", "rounds")

    def test_stagger_spreads_shop_breaks_over_one_round_cycle(self):
        config = generated_config(BUNDLED, 4)
        cycle = 10 + 45 + 45 + 5 + int(section_value(config, "shop", "max-seconds"))
        self.assertEqual(cycle // 4, stagger_seconds(config, 4))
        self.assertEqual(cycle // 2, stagger_seconds(config, 2))
        self.assertEqual(0, stagger_seconds(config, 1))


def write(path, header, rows):
    with open(path, "w", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(header)
        writer.writerows(rows)


class GeneratedMeasurementsTest(unittest.TestCase):
    def fixture(self, folder, guard="NORMAL", committed="true", lava=3):
        output = Path(folder)
        write(output / "ticks.csv", ["tick", "milliseconds", "stage", "building"],
              [(1, 2.0, "play", 0), (2, 8.0, "play", 1), (3, 60.0, "drain", 0), (4, 500.0, "release", 0),
               (5, 4.0, "reopen", 2)])
        write(output / "generated-seconds.csv",
              ["second", "stage", "matches", "building", "guard", "average_mspt", "tps", "arena_chunks",
               "arena_holders", "lava_sources", "lava_flows", "collapses", "enemies", "minimum_health",
               "heap_used_bytes"],
              [(1, "play", 2, 1, "NORMAL", 3.0, 20.0, 500, 900, lava, 10, 2, 30, 20.0, 100),
               (2, "play", 2, 0, guard, 5.0, 19.9, 520, 950, 0, 25, 4, 41, "", 300),
               (3, "release", 0, 0, "NORMAL", 99.0, 15.0, -1, -1, 0, 25, 4, 0, "", 200)])
        write(output / "generated-matches.csv",
              ["second", "room", "arena", "phase", "round", "intensity", "enemies", "lava_sources", "pending_late"],
              [(1, 0, "generated:1", "COMBAT", 1, 0.5, 10, 3, 0), (2, 0, "generated:1", "OVERRUN", 1, 1.0, 25, 0, 1),
               (1, 1, "generated:2", "PREP", 1, 0.0, 0, 0, 0), (2, 1, "generated:2", "OVERRUN", 2, 1.0, 16, 0, 0)])
        write(output / "generation.csv", ["second", "stage", "room", "kind", "milliseconds", "blocks", "seed",
                                          "difficulty"],
              [(5, "play", 0, "initial", 9000.0, 100, 1, 2), (6, "play", 1, "initial", 11000.0, 120, 2, 2),
               (400, "reopen", 0, "reopen", 15000.0, 100, 3, 2)])
        write(output / "shops.csv", ["second", "room", "round", "shop_milliseconds", "replacement_started",
                                     "replacement_milliseconds", "committed", "blocks"],
              [(100, 0, 1, 45000.0, "true", 20000.0, committed, 100),
               (140, 1, 1, 45000.0, "false", -1.0, "false", 0)])
        return output

    def test_summary_separates_stages_and_counts_regeneration(self):
        with tempfile.TemporaryDirectory() as folder:
            summary = generated_measurements(self.fixture(folder))
        self.assertEqual(3, summary["tick_ms"]["count"])
        self.assertEqual(60.0, summary["tick_ms"]["max"])
        self.assertEqual(1, summary["tick_ms"]["over_50ms"])
        self.assertEqual(1, summary["tick_ms_while_building"]["count"])
        self.assertEqual(1, summary["tick_ms_reopen"]["count"])
        self.assertEqual(5, summary["tick_ms_all_stages"]["count"])
        seconds = summary["seconds"]
        self.assertEqual({"NORMAL": 2}, seconds["guard"])
        self.assertEqual(5.0, seconds["average_mspt_max"])
        self.assertEqual(520, seconds["arena_chunks_max"])
        self.assertEqual(41, seconds["global_enemies_max"])
        self.assertEqual(20.0, seconds["minimum_health"])
        self.assertEqual(25, seconds["lava_flow_events"])
        self.assertEqual(10, summary["rooms"]["0"]["enemies_min"])
        self.assertEqual(1.0, summary["rooms"]["1"]["overrun_intensity_min"])
        self.assertEqual(2, summary["rooms"]["1"]["rounds_seen"])
        self.assertEqual(2, summary["generation_ms"]["initial"]["count"])
        self.assertEqual(10000.0, summary["generation_ms"]["initial"]["mean"])
        self.assertEqual(1, summary["generation_ms"]["reopen"]["count"])
        self.assertEqual({"count": 2, "replacement_started": 1, "committed": 1, "kept_current_map": 1},
                         {k: summary["shop_breaks"][k] for k in ("count", "replacement_started", "committed",
                                                                 "kept_current_map")})
        self.assertEqual(20000.0, summary["shop_breaks"]["replacement_ms"]["max"])

    def result(self, **changes):
        result = {"guard": {"pause_spawning_above_mspt": 40.0, "match_mob_cap": 25, "global_mob_cap": 80},
                  "max_concurrent_matches": 2, "matches_ended": 2,
                  "releases": [{"cycle": 1, "retired_world_collected": True},
                               {"cycle": 2, "retired_world_collected": True}],
                  "reopen_all_maps_ready_ms": 1000}
        result.update(changes)
        return result

    def test_acceptance_passes_a_clean_run(self):
        with tempfile.TemporaryDirectory() as folder:
            summary = generated_measurements(self.fixture(folder))
        self.assertEqual([], generated_acceptance(summary, self.result(), 2, {}))

    def test_acceptance_names_each_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            summary = generated_measurements(self.fixture(folder, guard="PAUSED", committed="false", lava=0))
        retained = [{"cycle": 1, "retired_world_collected": False,
                     "players_last_damaged_by_retired_world_entity": 3}]
        failures = generated_acceptance(summary, self.result(max_concurrent_matches=1, releases=retained), 2,
                                        {"minecraft:lava": 1})
        text = "\n".join(failures)
        for expected in ("1 of 2 matches", "guard left NORMAL", "regenerated between rounds", "No lava",
                         "released twice", "Release 1: the retired world stayed strongly reachable; 3 client(s)",
                         "reached the arena world save"):
            self.assertIn(expected, text)

    def test_distribution_uses_nearest_rank_percentiles(self):
        result = distribution([float(v) for v in range(1, 101)])
        self.assertEqual(95.0, result["p95"])
        self.assertEqual(99.0, result["p99"])
        self.assertEqual(100.0, result["max"])
        self.assertEqual({"count": 0}, distribution([]))


class SavedBlocksTest(unittest.TestCase):
    @staticmethod
    def region(directory, names):
        palette = tag(9, "palette", bytes([8]) + struct.pack(">i", len(names)) + b"".join(text(n) for n in names))
        section = tag(10, "block_states", palette + b"\0") + b"\0"
        sections = tag(9, "sections", bytes([10]) + struct.pack(">i", 1) + section)
        data = zlib.compress(bytes([10]) + text("") + sections + b"\0")
        header = struct.pack(">I", (2 << 8) | 1) + bytes(8188)
        body = struct.pack(">I", len(data) + 1) + bytes([2]) + data
        (directory / "region").mkdir(parents=True, exist_ok=True)
        (directory / "region/r.0.0.mca").write_bytes(header + body + bytes(-len(body) % 4096))

    def test_air_only_saves_are_empty_and_blocks_are_named(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            self.region(directory, ["minecraft:air"])
            self.assertEqual({}, saved_blocks(directory))
            self.region(directory, ["minecraft:air", "minecraft:lava", "minecraft:stone"])
            self.assertEqual({"minecraft:lava": 1, "minecraft:stone": 1}, saved_blocks(directory))
        self.assertEqual({}, saved_blocks(None))


if __name__ == "__main__":
    unittest.main()
