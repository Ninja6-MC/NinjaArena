"""Bounded localhost-only Paper soak. Uses only explicitly supplied local dependencies."""
import argparse
import csv
import hashlib
import gzip
import json
import os
import platform
import re
from pathlib import Path
import shutil
import socket
import statistics
import struct
import uuid
import subprocess
import time
import zipfile

from regions import Nbt, chunks, differences, inspect

ROOT = Path(__file__).resolve().parents[2]


def plugin_config(candidate=None):
    if candidate is None:
        return (ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8")
    with zipfile.ZipFile(candidate) as jar:
        return jar.read("config.yml").decode("utf-8")


def saved_entity_counts(directory):
    counts = {}
    for file in sorted((directory / "entities").glob("*.mca")):
        for _, chunk in chunks(file):
            for entity in chunk.get("Entities", []):
                name = entity.get("id", "missing-id")
                counts[name] = counts.get(name, 0) + 1
    return counts


def arena_save(server):
    candidates = [p.parent for p in server.rglob("region")
                  if p.is_dir() and p.parent.name == "ninjaarena_arenas"]
    if len(candidates) != 1:
        raise RuntimeError("Could not locate the arena dimension save")
    return candidates[0]


def saved_world_uid(directory):
    metadata = directory / "data/paper/metadata.dat"
    if metadata.exists():
        value = Nbt(gzip.decompress(metadata.read_bytes())).root()["data"]["uuid"]
        if not isinstance(value, list) or len(value) != 4 or any(type(v) is not int for v in value):
            raise ValueError("Paper metadata must contain a four-int UUID")
        return str(uuid.UUID(bytes=struct.pack(">4i", *value)))
    legacy = directory / "uid.dat"
    return str(uuid.UUID(bytes=legacy.read_bytes()))


def lifecycle(args, server, results, launch, wait_for, owned, flags, candidate):
    setup, log = launch("lifecycle-setup")
    setup.wait(timeout=360)
    log.close()
    if setup.returncode or not (results / "lifecycle-setup-complete").exists():
        raise RuntimeError("Dirty lifecycle setup failed; see lifecycle-setup-console.log")
    world = arena_save(server)
    original_uid = saved_world_uid(world)
    setup_uid = json.loads((results / "lifecycle-setup.json").read_text(encoding="utf-8"))["uid"]
    if original_uid != setup_uid:
        raise RuntimeError("Persisted world UUID differs from setup world UUID")
    dirty_hashes = hashes(world)
    marker_positions = {"inside": (36, 64, 36), "outside": (28, 64, 40)}
    dirty = inspect(world, marker_positions)
    entities = saved_entity_counts(world)
    required = {"minecraft:zombie", "minecraft:item", "minecraft:snowball", "minecraft:arrow", "minecraft:experience_orb", "minecraft:armor_stand"}
    if not dirty_hashes or dirty["saved_markers"] != {"inside": "minecraft:gold_block", "outside": "minecraft:diamond_block"} or not required.issubset(entities):
        raise RuntimeError(f"Dirty disk fixture did not persist all required blocks/entities: {dirty['saved_markers']}, {entities}")
    copy_save(world, results / "dirty-save")
    (results / "dirty-nbt.json").write_text(json.dumps(dirty, indent=2), encoding="utf-8")
    (results / "dirty-hashes.json").write_text(json.dumps(dirty_hashes, indent=2), encoding="utf-8")
    verify, log = launch("lifecycle-verify")
    wait_for(results / "lifecycle-ready", verify, 360)
    baseline = hashes(world)
    if not baseline:
        raise RuntimeError("No settled lifecycle save baseline")
    settled_nbt = inspect(world, marker_positions)
    copy_save(world, results / "settled-baseline-save")
    (results / "settled-baseline-nbt.json").write_text(json.dumps(settled_nbt, indent=2), encoding="utf-8")
    (results / "settled-baseline-hashes.json").write_text(json.dumps(baseline, indent=2), encoding="utf-8")
    (results / "lifecycle-baseline-captured").write_text("ready", encoding="utf-8")
    botlog = open(server / "clients.log", "w", encoding="utf-8")
    bots = subprocess.Popen([args.node, "--max-old-space-size=384", str(ROOT / "scripts/soak/lifecycle-clients.cjs"),
                             str(args.modules.resolve()), str(args.port)], cwd=server,
                            stdout=botlog, stderr=subprocess.STDOUT, creationflags=flags)
    owned.append(bots)
    comparison_save = results / "settled-baseline-save"
    comparison_hashes, comparison_nbt = baseline, settled_nbt
    dirty_reopen_evidence = None
    if args.dirty_reopen:
        wait_for(results / "dirty-reopen-ready", verify, 240)
        dirty_reopen_evidence = json.loads((results / "dirty-reopen-ready.json").read_text(encoding="utf-8"))
        comparison_hashes = hashes(world)
        comparison_nbt = inspect(world, marker_positions)
        reopen_entities = saved_entity_counts(world)
        if (not dirty_reopen_evidence["dirty_reopen_world_absent_for_python_proof"]
                or saved_world_uid(world) != original_uid
                or dirty_reopen_evidence["uid"] != original_uid
                or comparison_hashes != dirty_reopen_evidence["dirty_reopen_baseline_hashes"]
                or comparison_nbt["saved_markers"] != {"inside": "minecraft:gold_block", "outside": "minecraft:diamond_block"}
                or not required.issubset(reopen_entities)):
            raise RuntimeError("Dirty reopen disk/UUID/absence proof failed")
        comparison_save = results / "dirty-reopen-baseline-save"
        copy_save(world, comparison_save)
        (results / "dirty-reopen-baseline-hashes.json").write_text(json.dumps(comparison_hashes, indent=2), encoding="utf-8")
        (results / "dirty-reopen-baseline-nbt.json").write_text(json.dumps(comparison_nbt, indent=2), encoding="utf-8")
        dirty_reopen_evidence["independent_saved_entity_counts"] = reopen_entities
        dirty_reopen_evidence["independent_saved_uid"] = saved_world_uid(world)
        dirty_reopen_evidence["independent_same_uid"] = True
        (results / "dirty-reopen-python-proof.json").write_text(json.dumps(dirty_reopen_evidence, indent=2), encoding="utf-8")
        (results / "dirty-reopen-baseline-captured").write_text("ready", encoding="utf-8")
    wait_for(results / "lifecycle-complete", verify, 300)
    verify.wait(timeout=90)
    shutdown_seconds = round(time.time() - int((results / "shutdown-requested").read_text()) / 1000, 3)
    log.close()
    if bots.poll() is not None and bots.returncode:
        raise RuntimeError("Lifecycle clients failed before completion")
    if bots.poll() is None:
        bots.terminate()
        bots.wait(timeout=15)
    botlog.close()
    if verify.returncode:
        raise RuntimeError("Lifecycle verification process failed")
    copy_save(world, results / "shutdown-save")
    after = hashes(world)
    shutdown_nbt = inspect(world, marker_positions)
    storage = storage_comparison(comparison_save, results / "shutdown-save",
                                 differences(comparison_nbt, shutdown_nbt))
    (results / "shutdown-nbt.json").write_text(json.dumps(shutdown_nbt, indent=2), encoding="utf-8")
    fixture = json.loads((results / "lifecycle-result.json").read_text(encoding="utf-8"))
    setup_evidence = json.loads((results / "lifecycle-setup.json").read_text(encoding="utf-8"))
    summary = {"fixture": fixture, "setup": setup_evidence, "dirty_entity_counts": entities,
               "plugin_sha256": hashlib.sha256(candidate.read_bytes()).hexdigest(),
               "dirty_setup_hashes": dirty_hashes, "settled_baseline_hashes": baseline, "shutdown_hashes": after,
               "startup_save_changed": dirty_hashes != baseline,
               "startup_nbt_changes": differences(dirty, settled_nbt),
               "disk_unchanged_through_match_recycle_rejoin_shutdown": baseline == after,
               "intentional_dirty_reopen": args.dirty_reopen, "dirty_reopen_proof": dirty_reopen_evidence,
               "shutdown_comparison_baseline_hashes": comparison_hashes,
               "shutdown_raw_hashes_unchanged_from_comparison_baseline": comparison_hashes == after,
               "shutdown_data_unchanged": storage["data_unchanged"], "shutdown_storage_comparison": storage,
               "shutdown_seconds": shutdown_seconds,
               "shutdown_saved_markers": inspect(world, marker_positions)["saved_markers"]}
    (results / "lifecycle-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    dirty_reopen_valid = (not args.dirty_reopen or (
        fixture.get("dirty_reopen_canonical_before_admission") is True
        and fixture.get("dirty_reopen_baseline_hashes") == comparison_hashes))
    if (baseline != fixture["settled_baseline_hashes"] or not storage["data_unchanged"]
            or fixture["uid"] != setup_evidence["uid"] or not dirty_reopen_valid):
        raise RuntimeError("Lifecycle persistence/UUID invariant failed; inspect lifecycle-summary.json")
    print(json.dumps(summary, indent=2))


def hashes(directory):
    result = {}
    for folder in ("region", "entities", "poi"):
        for file in sorted((directory / folder).glob("*.mca")):
            result[file.relative_to(directory).as_posix()] = hashlib.sha256(file.read_bytes()).hexdigest()
    return result


def copy_save(directory, destination):
    for folder in ("region", "entities", "poi"):
        for source in (directory / folder).glob("*.mca"):
            target = destination / folder / source.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)


def storage_comparison(before, after, nbt_changes):
    """Recognize only Paper's exact zero padding of an unchanged region-file prefix."""
    def inventory(directory):
        return {p.relative_to(directory).as_posix(): p for p in directory.rglob("*") if p.is_file()}

    old_files, new_files = inventory(before), inventory(after)
    failures = []
    padding = {}
    if old_files.keys() != new_files.keys():
        failures.append("File inventory changed")
    if any(nbt_changes.values()):
        failures.append("Decoded NBT changed")
    for name in sorted(old_files.keys() & new_files.keys()):
        old, new = old_files[name].read_bytes(), new_files[name].read_bytes()
        if old == new:
            continue
        boundary = ((len(old) + 4095) // 4096) * 4096
        if (re.fullmatch(r"(?:region|entities|poi)/r\.-?\d+\.-?\d+\.mca", name)
                and len(old) % 4096 and len(new) == boundary
                and new[:len(old)] == old and not any(new[len(old):])):
            padding[name] = {"before_bytes": len(old), "after_bytes": len(new),
                             "appended_zero_bytes": len(new) - len(old)}
        else:
            failures.append(f"Unrecognized storage change: {name}")
    return {"data_unchanged": not failures, "sector_padding": padding,
            "nbt_changes": nbt_changes, "failures": failures}


def measurements(results, warmup=60):
    with open(results / "matches.csv", newline="") as stream:
        rows = [r for r in csv.DictReader(stream) if int(r["second"]) > warmup]
    if not rows:
        return {"measured_match_samples": 0}
    summary = {"measured_match_samples": len(rows), "arenas": {},
               "all_overrun": all(r["phase"] == "OVERRUN" for r in rows),
               "loops_running": all(r["looping"] == "true" for r in rows)}
    for i in range(4):
        subset = [r for r in rows if r["arena"] == f"soak{i}"]
        counts = [int(r["enemies"]) for r in subset]
        levels = [float(r["intensity"]) for r in subset]
        summary["arenas"][f"soak{i}"] = {"samples": len(subset), "enemies_min": min(counts),
            "enemies_median": statistics.median(counts), "enemies_max": max(counts),
            "intensity_min": min(levels), "intensity_max": max(levels)}
    with open(results / "ticks.csv", newline="") as stream:
        ticks = [float(r["milliseconds"]) for r in csv.DictReader(stream)][warmup * 20:]
    ticks.sort()
    summary["tick_ms"] = {"count": len(ticks), "mean": statistics.mean(ticks),
        "median": statistics.median(ticks), "p95": ticks[int(.95 * (len(ticks) - 1))],
        "p99": ticks[int(.99 * (len(ticks) - 1))], "max": max(ticks),
        "over_50ms": sum(t > 50 for t in ticks)}
    totals = {}
    for row in rows:
        totals.setdefault(row["second"], 0)
        totals[row["second"]] += int(row["enemies"])
    summary["global_enemies"] = {"min": min(totals.values()),
        "median": statistics.median(totals.values()), "max": max(totals.values())}
    summary["minimum_health"] = min(float(r["minimum_health"]) for r in rows)
    summary["minimum_tps"] = min(float(r["tps"]) for r in rows)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--paper", type=Path, required=True)
    parser.add_argument("--plugin", type=Path, help="Candidate production JAR; its bundled config supplies defaults")
    parser.add_argument("--dirty-reopen", action="store_true", help="With --lifecycle, force a dirty save after clean recycle and independently prove dirty-disk reopen")
    parser.add_argument("--lifecycle", action="store_true", help="Separate dirty-disk reset/recycle fixture, not the clean stability soak")
    parser.add_argument("--large", action="store_true", help="Lifecycle bounds 128x65x128 instead of 32x13x32")
    parser.add_argument("--heap-mib", type=int, choices=(768, 1536, 2048), default=768)
    parser.add_argument("--heap-dump-on-failure", action="store_true", help="Local live heap on a later tick after failure, before shutdown")
    parser.add_argument("--via", type=Path, required=True)
    parser.add_argument("--backwards", type=Path, required=True)
    parser.add_argument("--modules", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True, help="Paper cache containing libraries/cache/versions")
    parser.add_argument("--node", default="node")
    parser.add_argument("--seconds", type=int, default=600)
    parser.add_argument("--port", type=int, default=25579)
    parser.add_argument("--name", default="soak-task29")
    args = parser.parse_args()
    if not 60 <= args.seconds <= 1800 or not 1024 <= args.port <= 65535:
        parser.error("seconds must be 60..1800 and port 1024..65535")
    if not args.name.replace("-", "").isalnum():
        parser.error("name must contain only letters, digits and hyphens")
    if args.dirty_reopen and not args.lifecycle:
        parser.error("--dirty-reopen requires --lifecycle")
    if args.large and (not args.lifecycle or args.heap_mib < 1536):
        parser.error("--large requires --lifecycle and --heap-mib 1536 or 2048")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))
    server = ROOT / "run" / args.name
    if server.exists():
        raise RuntimeError("Output directory already exists; choose a new --name")
    server.mkdir(parents=True)
    (server / "plugins").mkdir()
    shutil.copy2(args.paper, server / "paper.jar")
    shutil.copy2(args.via, server / "plugins" / "ViaVersion.jar")
    shutil.copy2(args.backwards, server / "plugins" / "ViaBackwards.jar")
    candidate = args.plugin or ROOT / "build/libs/NinjaArena-0.1.0-SNAPSHOT.jar"
    shutil.copy2(candidate, server / "plugins/NinjaArena.jar")
    shutil.copy2(ROOT / "build/libs/NinjaArena-0.1.0-SNAPSHOT-soak-probe.jar", server / "plugins/ArenaSoakProbe.jar")
    # Copy only runtime libraries, never worlds, properties, player data or other plugins.
    for directory in ("libraries", "cache", "versions"):
        if (args.cache / directory).is_dir():
            shutil.copytree(args.cache / directory, server / directory)
    # The operator supplies their existing accepted EULA; the harness does not accept it.
    eula = args.cache / "eula.txt"
    if "eula=true" not in eula.read_text():
        raise RuntimeError("Supplied cache does not contain an accepted eula.txt")
    shutil.copy2(eula, server / "eula.txt")
    (server / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\n"
        "white-list=false\nenforce-whitelist=false\nenable-query=false\nenable-rcon=false\nmax-players=8\nview-distance=2\nsimulation-distance=2\n"
        "spawn-protection=0\nlevel-type=minecraft:flat\ngenerate-structures=false\n"
        "initial-enabled-packs=vanilla\nmax-tick-time=60000\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},'
        '{"block":"minecraft:stone","height":2},{"block":"minecraft:grass_block","height":1}],'
        '"biome":"minecraft:plains"}\n', encoding="utf-8")
    plugin = server / "plugins" / "NinjaArena"
    plugin.mkdir()
    config = plugin_config(args.plugin)
    # Raised budget saturates the normal production caps; no director/gate is replaced.
    if not args.lifecycle:
        config = config.replace("base: 20.0", "base: 200.0")
    (plugin / "config.yml").write_text(config, encoding="utf-8")
    results = server / "soak-results"
    clients_process = None
    owned = []
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

    def launch(stage):
        log = open(server / f"{stage}-console.log", "w", encoding="utf-8")
        command = [args.java, "-Xms256M", f"-Xmx{args.heap_mib}M", "-XX:ActiveProcessorCount=2",
                   f"-Dsoak.large={str(args.large).lower()}",
                   f"-Dsoak.dirtyReopen={str(args.dirty_reopen).lower()}",
                   f"-Dsoak.heapDumpOnFailure={str(args.heap_dump_on_failure).lower()}",
                   f"-Dsoak.stage={stage}", f"-Dsoak.seconds={args.seconds}", "-jar", "paper.jar", "--nogui"]
        process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
        owned.append(process)
        (server / f"{stage}-pid.txt").write_text(str(process.pid))
        return process, log

    def wait_for(file, process, timeout):
        deadline = time.monotonic() + timeout
        while not file.exists():
            if (results / "failed").exists():
                raise RuntimeError((results / "failed").read_text())
            if clients_process is not None and clients_process.poll() is not None:
                raise RuntimeError("Client process exited before server checkpoint")
            if process.poll() is not None:
                raise RuntimeError(f"Server exited {process.returncode} before {file.name}")
            if time.monotonic() > deadline:
                raise TimeoutError(file.name)
            time.sleep(1)

    try:
        if args.lifecycle:
            lifecycle(args, server, results, launch, wait_for, owned, flags, candidate)
            return
        setup, log = launch("setup")
        setup.wait(timeout=240)
        log.close()
        if setup.returncode or not (results / "setup-complete").exists():
            raise RuntimeError("Setup failed; see setup-console.log")
        world = arena_save(server)
        markers = {f"soak{i}": ((-1 if i % 2 == 0 else 1) * 256 + 36, 64,
                                  (-1 if i // 2 == 0 else 1) * 256 + 36) for i in range(4)}
        copy_save(world, results / "baseline-save")
        baseline = hashes(world)
        baseline_nbt = inspect(world, markers)
        (results / "baseline-nbt.json").write_text(json.dumps(baseline_nbt, indent=2), encoding="utf-8")
        (results / "baseline-hashes.json").write_text(json.dumps(baseline, indent=2), encoding="utf-8")
        if not baseline:
            raise RuntimeError("No arena save baseline")
        copies_before = {str(p.relative_to(plugin)): hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in (plugin / "arena-copies").rglob("*") if p.is_file()}
        if not copies_before:
            raise RuntimeError("No saved arena-copy baseline")
        start_time = time.monotonic()
        run, log = launch("run")
        wait_for(results / "ready", run, 240)
        copy_save(world, results / "before-matches-save")
        before_matches = hashes(world)
        before_nbt = inspect(world, markers)
        (results / "before-matches-nbt.json").write_text(json.dumps(before_nbt, indent=2), encoding="utf-8")
        (results / "before-matches-hashes.json").write_text(json.dumps(before_matches, indent=2), encoding="utf-8")
        botlog = open(server / "clients.log", "w", encoding="utf-8")
        bots = subprocess.Popen([args.node, "--max-old-space-size=384", str(ROOT / "scripts/soak/clients.cjs"),
                                 str(args.modules.resolve()), str(args.port), str(args.seconds + 360)], cwd=server,
                                stdout=botlog, stderr=subprocess.STDOUT, creationflags=flags)
        clients_process = bots
        owned.append(bots)
        (results / "start").touch()
        wait_for(results / "active", run, 240)
        copy_save(world, results / "active-save")
        active = hashes(world)
        active_nbt = inspect(world, markers)
        (results / "active-nbt.json").write_text(json.dumps(active_nbt, indent=2), encoding="utf-8")
        (results / "active-hashes.json").write_text(json.dumps(active, indent=2), encoding="utf-8")
        wait_for(results / "complete", run, args.seconds + 240)
        run.wait(timeout=90)
        shutdown_seconds = round(time.time() - int((results / "shutdown-requested").read_text()) / 1000, 3)
        log.close()
        bots.wait(timeout=30)
        botlog.close()
        if run.returncode != 0 or bots.returncode != 0:
            raise RuntimeError(f"Run/client process exited {run.returncode}/{bots.returncode}")
        copy_save(world, results / "shutdown-save")
        after = hashes(world)
        after_nbt = inspect(world, markers)
        (results / "shutdown-nbt.json").write_text(json.dumps(after_nbt, indent=2), encoding="utf-8")
        storage = storage_comparison(results / "before-matches-save", results / "shutdown-save",
                                     differences(before_nbt, after_nbt))
        copies_after = {str(p.relative_to(plugin)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in (plugin / "arena-copies").rglob("*") if p.is_file()}
        summary = {"environment": {"os": platform.platform(), "cpu": platform.processor(),
                   "logical_processors": os.cpu_count(), "server_active_processors": 2,
                   "paper_sha256": hashlib.sha256(args.paper.read_bytes()).hexdigest(),
                   "via_sha256": hashlib.sha256(args.via.read_bytes()).hexdigest(),
                   "backwards_sha256": hashlib.sha256(args.backwards.read_bytes()).hexdigest()},
                   "duration_seconds": args.seconds, "warmup_seconds": 60,
                   "wall_seconds": round(time.monotonic() - start_time, 2), "heap_mib": args.heap_mib,
                   "baseline_hashes": baseline, "before_matches_hashes": before_matches, "active_hashes": active, "shutdown_hashes": after,
                   "active_save_unchanged": baseline == active, "shutdown_save_unchanged": baseline == after,
                   "active_matches_save_unchanged": before_matches == active,
                   "shutdown_matches_save_unchanged": before_matches == after,
                   "shutdown_matches_data_unchanged": storage["data_unchanged"],
                   "shutdown_storage_comparison": storage,
                   "arena_copies_unchanged": copies_before == copies_after,
                   "nbt_changes": {"setup_to_before_matches": differences(baseline_nbt, before_nbt),
                       "before_matches_to_active": differences(before_nbt, active_nbt),
                       "before_matches_to_shutdown": differences(before_nbt, after_nbt)},
                   "saved_markers": {"baseline": baseline_nbt["saved_markers"],
                       "before_matches": before_nbt["saved_markers"], "active": active_nbt["saved_markers"],
                       "shutdown": after_nbt["saved_markers"]}}
        summary.update(measurements(results))
        summary["shutdown_seconds"] = shutdown_seconds
        summary["environment"]["plugin_sha256"] = hashlib.sha256(candidate.read_bytes()).hexdigest()
        summary["teardown"] = json.loads((results / "teardown-chunks.json").read_text(encoding="utf-8"))
        (results / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        verify, log = launch("verify")
        verify.wait(timeout=240)
        log.close()
        if verify.returncode or not (results / "restart-markers-clean").exists():
            raise RuntimeError("Restart verification failed")
        valid = (summary["all_overrun"] and summary["loops_running"] and summary["active_matches_save_unchanged"] and summary["shutdown_matches_data_unchanged"]
                 and summary["arena_copies_unchanged"] and summary["global_enemies"]["max"] <= 80 and all(
                     a["samples"] >= args.seconds and a["intensity_min"] == 1 and a["enemies_min"] > 0
                     and a["enemies_max"] <= 25 for a in summary["arenas"].values()))
        if not valid:
            raise RuntimeError("Soak evidence did not meet acceptance; inspect summary.json")
        print(json.dumps(summary, indent=2))
    finally:
        # Let the next server tick finish optional evidence before sending stop.
        if args.heap_dump_on_failure and (results / "failed").exists():
            artifact_deadline = time.monotonic() + 60
            while (not (results / "heap-dump-complete").exists()
                   and not (results / "heap-dump-error.txt").exists()
                   and any(process.poll() is None for process in owned)
                   and time.monotonic() < artifact_deadline):
                time.sleep(0.25)
        # Only exact processes created by this invocation are eligible for cleanup.
        for process in reversed(owned):
            if process.poll() is None:
                if process.stdin:
                    try:
                        process.stdin.write(b"stop\n")
                        process.stdin.flush()
                        process.wait(timeout=30)
                    except (OSError, subprocess.TimeoutExpired):
                        process.terminate()
                else:
                    process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)
        if (results / "failed").exists() and "world" in locals() and "before_nbt" in locals():
            try:
                copy_save(world, results / "failed-shutdown-save")
                failed_nbt = inspect(world, markers)
                diagnosis = {"before_matches_to_shutdown": differences(before_nbt, failed_nbt),
                             "saved_markers": failed_nbt["saved_markers"],
                             "shutdown_hashes": hashes(world),
                             "failure": (results / "failed").read_text(),
                             "duration_seconds": args.seconds, "warmup_seconds": 60,
                             "active_matches_save_unchanged": before_matches == active if "active" in locals() else None}
                diagnosis.update(measurements(results))
                if (results / "teardown-chunks.json").exists():
                    diagnosis["teardown"] = json.loads((results / "teardown-chunks.json").read_text())
                (results / "failed-save-diagnostics.json").write_text(json.dumps(diagnosis, indent=2), encoding="utf-8")
            except Exception as diagnosis_error:
                (results / "diagnostic-error.txt").write_text(str(diagnosis_error), encoding="utf-8")


if __name__ == "__main__":
    main()
