"""Configuration, measurements and acceptance for the generated-map soak workload."""
import csv
import re
import statistics

from regions import chunks

# Shorter rounds than the defaults give more shop breaks, and so more per-round map
# regenerations, in the same measured time. The shop keeps its bundled timings.
MATCH_OVERRIDES = {"prep-seconds": 10, "round-seconds": 45, "overrun-limit-seconds": 45,
                   "round-end-seconds": 5, "rounds": 3, "extra-rounds": 0}
FIGHTING = ("COMBAT", "OVERRUN")


def _replace_once(text, pattern, replacement, label):
    updated, count = re.subn(pattern, replacement, text, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"Expected exactly one {label} line in the bundled config, found {count}")
    return updated


def section_value(text, section, key):
    """A top-level section's direct child value, as text."""
    match = re.search(rf"^{re.escape(section)}:\n((?:[ #].*\n|\n)*)", text, flags=re.MULTILINE)
    if not match:
        raise ValueError(f"No {section} section in the bundled config")
    found = re.search(rf"^  {re.escape(key)}: *(\S+)", match.group(1), flags=re.MULTILINE)
    if not found:
        raise ValueError(f"No {section}.{key} in the bundled config")
    return found.group(1)


def generated_config(text, max_active):
    """The bundled config with the generated workload's overrides; every override must apply once."""
    if not 1 <= max_active <= 4:
        raise ValueError("max_active must be 1 to 4")
    text = _replace_once(text, r"^    base: 20\.0$", "    base: 200.0", "threat-budget base")
    text = _replace_once(text, r"^  max-active: \d+$", f"  max-active: {max_active}", "arenas.max-active")
    text = _replace_once(text, r"^    regenerate: match$", "    regenerate: round", "maps.default.regenerate")
    for key, value in MATCH_OVERRIDES.items():
        text = _replace_once(text, rf"^  {re.escape(key)}: \d+$", f"  {key}: {value}", f"match.{key}")
    return text


def stagger_seconds(text, rooms):
    """Seconds between first match starts, so the rooms' shop breaks spread over one round cycle."""
    if rooms <= 1:
        return 0
    cycle = sum(int(section_value(text, "match", key)) for key in
                ("prep-seconds", "round-seconds", "overrun-limit-seconds", "round-end-seconds"))
    cycle += int(section_value(text, "shop", "max-seconds"))
    return cycle // rooms


def workload_settings(text):
    """The match, shop and map settings a generated run played with, for the report."""
    settings = {f"match.{key}": int(section_value(text, "match", key)) for key in MATCH_OVERRIDES}
    for key in ("max-seconds", "quick-start-seconds", "map-wait-limit-seconds"):
        settings[f"shop.{key}"] = int(section_value(text, "shop", key))
    settings["arenas.max-active"] = int(section_value(text, "arenas", "max-active"))
    for key in ("regenerate", "difficulty", "mode"):
        found = re.search(rf"^    {key}: *(\S+)", text, flags=re.MULTILINE)
        settings[f"maps.default.{key}"] = found.group(1) if found else None
    return settings


def saved_blocks(directory):
    """Counts of every non-air block state named in the saved region palettes."""
    found = {}
    for file in sorted((directory / "region").glob("*.mca")) if directory else ():
        for _, chunk in chunks(file):
            for section in chunk.get("sections", []):
                for entry in section.get("block_states", {}).get("palette", []):
                    name = entry["Name"] if isinstance(entry, dict) else entry
                    if name not in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"):
                        found[name] = found.get(name, 0) + 1
    return found


def _rows(path):
    with open(path, newline="") as stream:
        return list(csv.DictReader(stream))


def distribution(values):
    """Mean, median, nearest-rank p95/p99 and maximum, as the hand-built report computes them."""
    values = sorted(values)
    if not values:
        return {"count": 0}
    return {"count": len(values), "mean": statistics.mean(values), "median": statistics.median(values),
            "p95": values[int(.95 * (len(values) - 1))], "p99": values[int(.99 * (len(values) - 1))],
            "max": values[-1], "over_50ms": sum(v > 50 for v in values)}


def _timings(values):
    values = sorted(values)
    if not values:
        return {"count": 0}
    return {"count": len(values), "mean": statistics.mean(values), "median": statistics.median(values),
            "max": values[-1], "min": values[0]}


def generated_measurements(results):
    """Summarises the probe's CSV files for one generated run."""
    ticks = _rows(results / "ticks.csv")
    seconds = _rows(results / "generated-seconds.csv")
    matches = _rows(results / "generated-matches.csv")
    built = _rows(results / "generation.csv")
    shops = _rows(results / "shops.csv")
    play = ("play", "drain")
    summary = {"tick_ms": distribution([float(r["milliseconds"]) for r in ticks if r["stage"] in play]),
               "tick_ms_while_building": distribution([float(r["milliseconds"]) for r in ticks
                                                       if r["stage"] in play and int(r["building"]) > 0]),
               "tick_ms_reopen": distribution([float(r["milliseconds"]) for r in ticks if r["stage"] == "reopen"]),
               "tick_ms_all_stages": distribution([float(r["milliseconds"]) for r in ticks])}
    active = [r for r in seconds if r["stage"] in play + ("reopen",)]
    guard = {}
    for row in active:
        guard[row["guard"]] = guard.get(row["guard"], 0) + 1
    loaded = [r for r in seconds if int(r["arena_chunks"]) >= 0]
    summary["seconds"] = {"samples": len(active), "guard": guard,
        "average_mspt_max": max((float(r["average_mspt"]) for r in active), default=None),
        "tps_min": min((float(r["tps"]) for r in active if r["stage"] in play), default=None),
        "arena_chunks_max": max((int(r["arena_chunks"]) for r in loaded), default=None),
        "arena_holders_max": max((int(r["arena_holders"]) for r in loaded), default=None),
        "heap_used_max_bytes": max((int(r["heap_used_bytes"]) for r in seconds), default=None),
        "lava_sources_max": max((int(r["lava_sources"]) for r in seconds), default=0),
        "lava_flow_events": max((int(r["lava_flows"]) for r in seconds), default=0),
        "collapse_falling_blocks": max((int(r["collapses"]) for r in seconds), default=0),
        "global_enemies_max": max((int(r["enemies"]) for r in active), default=0),
        "matches_max": max((int(r["matches"]) for r in active), default=0),
        "minimum_health": min((float(r["minimum_health"]) for r in active if r["minimum_health"]), default=None)}
    rooms = {}
    for row in matches:
        room = rooms.setdefault(row["room"], {"samples": 0, "fighting_samples": 0, "enemies": [],
                                              "overrun_intensity": [], "lava_sources_max": 0, "rounds": set()})
        room["samples"] += 1
        room["rounds"].add(int(row["round"]))
        room["lava_sources_max"] = max(room["lava_sources_max"], int(row["lava_sources"]))
        if row["phase"] in FIGHTING:
            room["fighting_samples"] += 1
            room["enemies"].append(int(row["enemies"]))
        if row["phase"] == "OVERRUN":
            room["overrun_intensity"].append(float(row["intensity"]))
    summary["rooms"] = {}
    for name, room in sorted(rooms.items()):
        enemies = room["enemies"]
        summary["rooms"][name] = {"samples": room["samples"], "fighting_samples": room["fighting_samples"],
            "enemies_min": min(enemies, default=0), "enemies_median": statistics.median(enemies) if enemies else 0,
            "enemies_max": max(enemies, default=0),
            "overrun_intensity_min": min(room["overrun_intensity"], default=None),
            "lava_sources_max": room["lava_sources_max"], "rounds_seen": len(room["rounds"])}
    summary["generation_ms"] = {}
    for kind in sorted({r["kind"] for r in built}):
        rows = [r for r in built if r["kind"] == kind]
        summary["generation_ms"][kind] = _timings([float(r["milliseconds"]) for r in rows])
        summary["generation_ms"][kind]["blocks_max"] = max(int(r["blocks"]) for r in rows)
    summary["difficulties"] = sorted({r["difficulty"] for r in built if r["difficulty"]})
    started = [r for r in shops if r["replacement_started"] == "true"]
    finished = [float(r["replacement_milliseconds"]) for r in started if float(r["replacement_milliseconds"]) >= 0]
    summary["shop_breaks"] = {"count": len(shops), "replacement_started": len(started),
        "committed": sum(r["committed"] == "true" for r in shops),
        "kept_current_map": sum(r["committed"] != "true" for r in shops),
        "replacement_ms": _timings(finished),
        "shop_ms": _timings([float(r["shop_milliseconds"]) for r in shops])}
    return summary


def generated_acceptance(summary, result, rooms, saved):
    """Every reason the run does not pass; empty when it passes."""
    failures = []
    guard = result["guard"]
    seconds = summary["seconds"]
    if result["max_concurrent_matches"] != rooms:
        failures.append(f"Only {result['max_concurrent_matches']} of {rooms} matches ran at once")
    if result["matches_ended"] < rooms:
        failures.append("Not every room finished a match")
    if set(seconds["guard"]) - {"NORMAL"}:
        failures.append(f"Tick-time guard left NORMAL: {seconds['guard']}")
    if seconds["average_mspt_max"] is None or seconds["average_mspt_max"] >= guard["pause_spawning_above_mspt"]:
        failures.append("Average tick time reached the guard's pause threshold")
    if summary["shop_breaks"]["committed"] < 1:
        failures.append("No map was regenerated between rounds")
    if seconds["lava_sources_max"] < 1 or seconds["lava_flow_events"] < 1:
        failures.append("No lava flowed")
    if len(summary["rooms"]) != rooms:
        failures.append("Not every room was sampled")
    for name, room in summary["rooms"].items():
        if room["enemies_max"] < 1:
            failures.append(f"Room {name} never had a live enemy")
        if room["enemies_max"] > guard["match_mob_cap"]:
            failures.append(f"Room {name} exceeded the match cap")
    if seconds["global_enemies_max"] > guard["global_mob_cap"]:
        failures.append("Enemies exceeded the global cap")
    releases = result["releases"]
    if len(releases) != 2:
        failures.append("The idle arena world was not released twice")
    for release in releases:
        if not release["retired_world_collected"]:
            failures.append(f"Release {release['cycle']}: the retired world stayed strongly reachable; "
                            f"{release.get('players_last_damaged_by_retired_world_entity', 0)} client(s) "
                            "were last damaged by one of its entities")
    if "reopen_all_maps_ready_ms" not in result:
        failures.append("Maps were not placed after the arena world reopened")
    if saved:
        failures.append(f"Generated blocks reached the arena world save: {saved}")
    return failures
