import csv
from pathlib import Path
import tempfile
import unittest

from run import measurements


class MeasurementSummaryTest(unittest.TestCase):
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
