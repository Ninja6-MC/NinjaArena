#!/usr/bin/env python3
"""Build, verify and promote the same Java resource pack candidate bytes."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VARIANTS = ("standard", "detailed")
TOOLS = ("shuriken", "smoke_bomb", "dash")
REPOSITORY = "Ninja6-MC/NinjaArena"


def digest(data, algorithm="sha256"):
    return hashlib.new(algorithm, data).hexdigest()


def read_json(file):
    return json.loads(file.read_text(encoding="utf-8"))


def write_json(file, value):
    file.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8", newline="\n")


def version_channel(version):
    if re.fullmatch(r"ci-[0-9a-f]{40}", version):
        return "snapshot"
    if not re.fullmatch(r"v0\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)\.[1-9][0-9]*)?", version):
        raise ValueError("version must be ci-<full commit SHA> or v0.MINOR.PATCH[-alpha.N|-beta.N|-rc.N]")
    return "prerelease" if "-" in version else "stable"


def inventory(variant):
    names = {"pack.mcmeta"}
    prefix = "assets/ninjaarena/"
    for tool in TOOLS:
        if variant == "standard":
            names.update((f"{prefix}items/{tool}.json", f"{prefix}models/item/{tool}.json"))
        names.update(f"{prefix}textures/item/{tool}{suffix}.png" for suffix in ("", "_accent"))
    return names


def source_files(root, variant, version):
    target = read_json(root / "resourcepack/target.json")
    gradle = (root / "build.gradle.kts").read_text(encoding="utf-8")
    if f'paper-api:{target["minecraft"]}.build.' not in gradle:
        raise ValueError("resourcepack/target.json Minecraft version does not match the Paper dependency")
    folder = root / "resourcepack/java" / variant
    files = {}
    for file in folder.rglob("*"):
        if file.is_symlink():
            raise ValueError(f"pack sources must not contain symlinks: {file}")
        if file.is_file():
            files[file.relative_to(folder).as_posix()] = file.read_bytes()
    if files.keys() != inventory(variant):
        raise ValueError(f"{variant} has missing or unexpected pack files: {set(files) ^ inventory(variant)}")
    metadata = json.loads(files["pack.mcmeta"])
    pack = metadata["pack"]
    if pack.get("min_format") != target["resource_format"] or pack.get("max_format") != target["resource_format"]:
        raise ValueError(f"{variant} pack.mcmeta does not match the target resource format")
    pack["description"] += f" — {version}"
    files["pack.mcmeta"] = (json.dumps(metadata, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    return files


def build(root, output, version, source, run_id="local", attempt="1"):
    channel = version_channel(version)
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ValueError("source must be a full commit SHA")
    if channel == "stable" and f"## [{version[1:]}]" not in (root / "CHANGELOG.md").read_text(encoding="utf-8"):
        raise ValueError(f"stable release requires a CHANGELOG.md section for {version[1:]}")
    subprocess.run(["node", str(root / "artwork/tools/validate-pack.js")], check=True)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("output directory must be empty; never replace a retained candidate")
    files = {}
    for variant in VARIANTS:
        filename = f"NinjaArena-{version}-java-{variant}.zip"
        with zipfile.ZipFile(output / filename, "w", compression=zipfile.ZIP_STORED) as archive:
            for name, data in sorted(source_files(root, variant, version).items()):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data)
        data = (output / filename).read_bytes()
        files[filename] = {"sha1": digest(data, "sha1"), "sha256": digest(data)}
    hashes = "".join(f'{values["sha1"]}  {name}\n' for name, values in sorted(files.items()))
    (output / "SHA1SUMS").write_text(hashes, encoding="utf-8", newline="\n")
    files["SHA1SUMS"] = {"sha256": digest(hashes.encode())}
    candidate = f"{source}-{run_id}-{attempt}"
    manifest = {"candidate": candidate, "source": source, "version": version, "channel": channel,
                "run_id": str(run_id), "attempt": str(attempt), "minecraft": read_json(root / "resourcepack/target.json"),
                "destinations": [] if channel == "snapshot" else [f"github:{REPOSITORY}:{version}"], "files": files}
    write_json(output / "manifest.json", manifest)
    return manifest


def verify(root, folder, version, source, run_id, attempt):
    manifest = read_json(folder / "manifest.json")
    channel = version_channel(version)
    expected = {"source": source, "version": version, "channel": channel, "run_id": str(run_id),
                "attempt": str(attempt), "candidate": f"{source}-{run_id}-{attempt}",
                "minecraft": read_json(root / "resourcepack/target.json"),
                "destinations": [] if channel == "snapshot" else [f"github:{REPOSITORY}:{version}"]}
    for key, value in expected.items():
        if manifest.get(key) != value:
            raise ValueError(f"candidate {key} mismatch")
    names = {f"NinjaArena-{version}-java-{v}.zip" for v in VARIANTS} | {"SHA1SUMS"}
    if set(manifest["files"]) != names:
        raise ValueError("candidate manifest file inventory mismatch")
    if {p.name for p in folder.iterdir()} != names | {"manifest.json"}:
        raise ValueError("retained candidate contains missing or unexpected files")
    for name, checksums in manifest["files"].items():
        data = (folder / name).read_bytes()
        for algorithm, value in checksums.items():
            if algorithm not in ("sha1", "sha256") or digest(data, algorithm) != value:
                raise ValueError(f"candidate digest mismatch: {name}")
        if "sha256" not in checksums or (name.endswith(".zip") and "sha1" not in checksums):
            raise ValueError(f"candidate missing digest: {name}")
    expected_hashes = "".join(f'{manifest["files"][name]["sha1"]}  {name}\n' for name in sorted(names) if name.endswith(".zip"))
    if (folder / "SHA1SUMS").read_text(encoding="utf-8") != expected_hashes:
        raise ValueError("SHA1SUMS does not match pack hashes")
    for variant in VARIANTS:
        filename = f"NinjaArena-{version}-java-{variant}.zip"
        with zipfile.ZipFile(folder / filename) as archive:
            expected_files = source_files(root, variant, version)
            if len(archive.namelist()) != len(expected_files) or set(archive.namelist()) != expected_files.keys():
                raise ValueError(f"{variant} archive inventory mismatch")
            if archive.testzip() is not None:
                raise ValueError(f"{variant} archive CRC failure")
            for name, contents in expected_files.items():
                if archive.read(name) != contents:
                    raise ValueError(f"{variant} archive content mismatch: {name}")
    return {"candidate": manifest["candidate"], "manifest_sha256": digest((folder / "manifest.json").read_bytes()),
            "run_id": str(run_id), "attempt": str(attempt), "result": "passed"}


def publish(root, folder, evidence_file, version, source, run_id, attempt):
    evidence = read_json(evidence_file)
    expected = verify(root, folder, version, source, run_id, attempt)
    if any(evidence.get(key) != value for key, value in expected.items()) or not evidence.get("verification_run_id") or not evidence.get("verification_attempt"):
        raise ValueError("passing verification evidence is missing or does not identify this candidate")
    if version_channel(version) == "snapshot":
        raise ValueError("CI snapshots cannot be published as releases")
    environment = json.loads(subprocess.check_output(
        ["gh", "api", f"repos/{REPOSITORY}/environments/release"], text=True))
    if not any(rule.get("type") == "required_reviewers" and any(
            reviewer.get("type") == "User" and reviewer.get("reviewer", {}).get("login", "").casefold() == "bharathasl"
            for reviewer in rule.get("reviewers", [])) for rule in environment.get("protection_rules", [])):
        raise ValueError("release environment must require maintainer review before publishing")
    refs = subprocess.check_output(["git", "ls-remote", "origin", f"refs/tags/{version}", f"refs/tags/{version}^{{}}"], text=True)
    commits = dict(line.split()[::-1] for line in refs.splitlines())
    if commits.get(f"refs/tags/{version}^{{}}", commits.get(f"refs/tags/{version}")) != source:
        raise ValueError("release tag no longer points to the verified source")
    release = json.loads(subprocess.check_output(["gh", "release", "view", version, "--repo", REPOSITORY,
                                                 "--json", "tagName,isPrerelease,isDraft,assets"], text=True))
    if release["isDraft"] or release["tagName"] != version or release["isPrerelease"] != (version_channel(version) == "prerelease"):
        raise ValueError("published release tag/channel mismatch")
    manifest = read_json(folder / "manifest.json")
    intended = set(manifest["files"]) | {"manifest.json"}
    existing = {asset["name"] for asset in release["assets"]} & intended
    with tempfile.TemporaryDirectory() as temp:
        for name in sorted(existing):
            subprocess.run(["gh", "release", "download", version, "--repo", REPOSITORY, "--pattern", name, "--dir", temp], check=True)
            if (Path(temp) / name).read_bytes() != (folder / name).read_bytes():
                raise ValueError(f"public asset differs; reconcile manually, never overwrite: {name}")
        missing = sorted(intended - existing)
        if missing:
            subprocess.run(["gh", "release", "upload", version, "--repo", REPOSITORY, *[str(folder / name) for name in missing]], check=True)
        # Read back the public bytes, including assets already present on a retry.
        for name in sorted(intended):
            destination = Path(temp) / name
            if destination.exists():
                destination.unlink()
            subprocess.run(["gh", "release", "download", version, "--repo", REPOSITORY, "--pattern", name, "--dir", temp], check=True)
            if destination.read_bytes() != (folder / name).read_bytes():
                raise ValueError(f"published asset verification failed: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("build", "verify", "publish"))
    parser.add_argument("--output", type=Path, default=ROOT / "build/resourcepacks")
    parser.add_argument("--version", default=os.environ.get("PACK_VERSION"), required="PACK_VERSION" not in os.environ)
    parser.add_argument("--source", default=os.environ.get("GITHUB_SHA"))
    parser.add_argument("--run-id", default=os.environ.get("GITHUB_RUN_ID", "local"))
    parser.add_argument("--attempt", default=os.environ.get("GITHUB_RUN_ATTEMPT", "1"))
    parser.add_argument("--evidence", type=Path, default=ROOT / "build/pack-verification.json")
    args = parser.parse_args()
    source = args.source or subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if args.command == "build":
        build(ROOT, args.output, args.version, source, args.run_id, args.attempt)
    elif args.command == "verify":
        evidence = verify(ROOT, args.output, args.version, source, args.run_id, args.attempt)
        evidence.update(verification_run_id=os.environ.get("GITHUB_RUN_ID", "local"),
                        verification_attempt=os.environ.get("GITHUB_RUN_ATTEMPT", "1"))
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        write_json(args.evidence, evidence)
    else:
        publish(ROOT, args.output, args.evidence, args.version, source, args.run_id, args.attempt)


if __name__ == "__main__":
    main()
