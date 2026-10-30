"""Bounded localhost-only Paper soak. Uses only explicitly supplied local dependencies."""
import argparse
import csv
import hashlib
import json
import os
import platform
from pathlib import Path
import shutil
import socket
import statistics
import subprocess
import time

from regions import differences, inspect

ROOT = Path(__file__).resolve().parents[2]


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
    for jar in ("NinjaArena-0.1.0-SNAPSHOT.jar", "NinjaArena-0.1.0-SNAPSHOT-soak-probe.jar"):
        shutil.copy2(ROOT / "build" / "libs" / jar, server / "plugins" / jar)
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
    config = (ROOT / "src" / "main" / "resources" / "config.yml").read_text()
    # Raised budget saturates the normal production caps; no director/gate is replaced.
    config = config.replace("base: 20.0", "base: 200.0")
    (plugin / "config.yml").write_text(config, encoding="utf-8")
    results = server / "soak-results"
    clients_process = None
    owned = []
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

    def launch(stage):
        log = open(server / f"{stage}-console.log", "w", encoding="utf-8")
        command = [args.java, "-Xms256M", "-Xmx768M", "-XX:ActiveProcessorCount=2",
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
        setup, log = launch("setup")
        setup.wait(timeout=240)
        log.close()
        if setup.returncode or not (results / "setup-complete").exists():
            raise RuntimeError("Setup failed; see setup-console.log")
        candidates = [p.parent for p in server.rglob("region")
                      if p.is_dir() and p.parent.name == "ninjaarena_arenas"]
        if len(candidates) != 1:
            raise RuntimeError("Could not locate the arena dimension save")
        world = candidates[0]
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
        log.close()
        bots.wait(timeout=30)
        botlog.close()
        if run.returncode != 0 or bots.returncode != 0:
            raise RuntimeError(f"Run/client process exited {run.returncode}/{bots.returncode}")
        copy_save(world, results / "shutdown-save")
        after = hashes(world)
        after_nbt = inspect(world, markers)
        (results / "shutdown-nbt.json").write_text(json.dumps(after_nbt, indent=2), encoding="utf-8")
        copies_after = {str(p.relative_to(plugin)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in (plugin / "arena-copies").rglob("*") if p.is_file()}
        summary = {"environment": {"os": platform.platform(), "cpu": platform.processor(),
                   "logical_processors": os.cpu_count(), "server_active_processors": 2,
                   "paper_sha256": hashlib.sha256(args.paper.read_bytes()).hexdigest(),
                   "via_sha256": hashlib.sha256(args.via.read_bytes()).hexdigest(),
                   "backwards_sha256": hashlib.sha256(args.backwards.read_bytes()).hexdigest()},
                   "duration_seconds": args.seconds, "warmup_seconds": 60,
                   "wall_seconds": round(time.monotonic() - start_time, 2), "heap_mib": 768,
                   "baseline_hashes": baseline, "before_matches_hashes": before_matches, "active_hashes": active, "shutdown_hashes": after,
                   "active_save_unchanged": baseline == active, "shutdown_save_unchanged": baseline == after,
                   "active_matches_save_unchanged": before_matches == active,
                   "shutdown_matches_save_unchanged": before_matches == after,
                   "arena_copies_unchanged": copies_before == copies_after,
                   "nbt_changes": {"setup_to_before_matches": differences(baseline_nbt, before_nbt),
                       "before_matches_to_active": differences(before_nbt, active_nbt),
                       "before_matches_to_shutdown": differences(before_nbt, after_nbt)},
                   "saved_markers": {"baseline": baseline_nbt["saved_markers"],
                       "before_matches": before_nbt["saved_markers"], "active": active_nbt["saved_markers"],
                       "shutdown": after_nbt["saved_markers"]}}
        summary.update(measurements(results))
        (results / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        verify, log = launch("verify")
        verify.wait(timeout=240)
        log.close()
        if verify.returncode or not (results / "restart-markers-clean").exists():
            raise RuntimeError("Restart verification failed")
        valid = (summary["all_overrun"] and summary["loops_running"] and summary["active_matches_save_unchanged"] and summary["shutdown_matches_save_unchanged"]
                 and summary["arena_copies_unchanged"] and summary["global_enemies"]["max"] <= 80 and all(
                     a["samples"] >= args.seconds and a["intensity_min"] == 1 and a["enemies_min"] > 0
                     and a["enemies_max"] <= 25 for a in summary["arenas"].values()))
        if not valid:
            raise RuntimeError("Soak evidence did not meet acceptance; inspect summary.json")
        print(json.dumps(summary, indent=2))
    finally:
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
