#!/usr/bin/env python3
"""Assemble, record and verify one NinjaArena release candidate, and bind its test evidence.

Release Candidate builds the plugin JAR, the Java and Bedrock packs and the Geyser mappings
once, retains them with a manifest of their SHA-256 digests, then downloads them again and
runs every check here on the downloaded bytes. Publish Release rechecks the same candidate
with `verify` before anything is written. RELEASE_PROCESS.md describes the whole sequence.
"""

import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import tempfile
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TAG = re.compile(r"^v0\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\.([1-9][0-9]*))?$")
SHA = re.compile(r"^[0-9a-f]{40}$")
DESTINATIONS = ["github"]
REPOSITORY = "Ninja6-MC/NinjaArena"
PACKAGE = "com/ninja6/ninjaarena/"
MAIN_CLASS = PACKAGE + "NinjaArenaPlugin.class"
# Class file major version for Java 25, the build's release target.
CLASS_MAJOR = 69
SUMS = "SHA256SUMS"
TESTS = ["candidate-inspection", "java-pack-verification", "bedrock-pack-verification", "unit-suite-on-release-jar"]
RELEASE_TEST = ("com.ninja6.ninjaarena.PluginDescriptionTest", "releaseRunLoadsTheCandidateJarAndItsVersion()")


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / "artwork/tools" / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


java_packs = load("java_packs", "java-packs.py")
bedrock_packs = load("bedrock_packs", "bedrock-packs.py")


def details(tag):
    """The plugin version and GitHub channel for a release tag, or ValueError."""
    match = TAG.fullmatch(tag or "")
    if not match:
        raise ValueError(f"Invalid release tag: {tag!r}; expected v0.MINOR.PATCH with an optional -alpha.N, -beta.N or -rc.N")
    # The Bedrock pack version is numeric and bounded; refuse a tag it cannot represent.
    bedrock_packs.pack_version(ROOT, tag)
    return tag[1:], "prerelease" if match.group(3) else "stable"


def java_files(tag):
    return {f"NinjaArena-{tag}-java-{variant}.zip" for variant in java_packs.VARIANTS} | {"SHA1SUMS", "manifest.json"}


def bedrock_files(tag):
    return ({f"NinjaArena-{tag}-bedrock-{variant}.mcpack" for variant in java_packs.VARIANTS}
            | {f"NinjaArena-{tag}-geyser-mappings.json", bedrock_packs.SUMS, bedrock_packs.MANIFEST})


def jar_name(tag):
    return f"NinjaArena-{details(tag)[0]}.jar"


def expected_files(tag):
    """Every release asset, in the flat layout the GitHub release carries."""
    return {jar_name(tag), SUMS} | java_files(tag) | bedrock_files(tag)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_json(path, data):
    Path(path).write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def sums_text(directory, names):
    return "".join(f"{digest(Path(directory) / name)}  {name}\n" for name in sorted(names))


def inventory(directory):
    entries = list(Path(directory).iterdir())
    if any(entry.is_symlink() or not entry.is_file() for entry in entries):
        raise ValueError("Candidate directory may hold only regular files")
    return {entry.name for entry in entries}


def assemble(args):
    """Copy the built JAR and both pack outputs into one flat candidate directory."""
    details(args.tag)
    directory = Path(args.directory)
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise ValueError("Candidate directory must be empty; never replace a retained candidate")
    jar = Path(args.jar)
    if jar.name != jar_name(args.tag) or not jar.is_file():
        raise ValueError(f"Expected the built JAR {jar_name(args.tag)}")
    for folder, names in ((Path(args.java), java_files(args.tag)), (Path(args.bedrock), bedrock_files(args.tag))):
        if inventory(folder) != names:
            raise ValueError(f"{folder} does not hold exactly the expected pack files")
        for name in sorted(names):
            shutil.copyfile(folder / name, directory / name)
    shutil.copyfile(jar, directory / jar.name)
    names = expected_files(args.tag) - {SUMS}
    (directory / SUMS).write_text(sums_text(directory, names), encoding="utf-8", newline="\n")


def candidate_id(run_id, attempt, source, tag):
    return f"{run_id}-{attempt}-{source}-{tag}"


def create(args):
    version, channel = details(args.tag)
    if not SHA.fullmatch(args.source):
        raise ValueError("Source must be a full commit SHA")
    if args.artifact_id < 1 or args.run_id < 1 or args.attempt < 1:
        raise ValueError("Invalid run, attempt or artifact identity")
    directory = Path(args.directory)
    names = expected_files(args.tag)
    if inventory(directory) != names:
        raise ValueError("Candidate directory does not hold exactly the release assets")
    if (directory / SUMS).read_text(encoding="utf-8") != sums_text(directory, names - {SUMS}):
        raise ValueError(f"{SUMS} differs from the candidate files")
    write_json(args.output, {
        "schema": 1,
        "candidate_id": candidate_id(args.run_id, args.attempt, args.source, args.tag),
        "run_id": args.run_id,
        "attempt": args.attempt,
        "artifact_id": args.artifact_id,
        "source": args.source,
        "tag": args.tag,
        "version": version,
        "channel": channel,
        "destinations": DESTINATIONS,
        "files": {name: digest(directory / name) for name in sorted(names)},
    })


def source_resources(root):
    folder = root / "src/main/resources"
    return {path.relative_to(folder).as_posix(): path.read_bytes() for path in folder.rglob("*") if path.is_file()}


def inspect_jar(root, jar, version):
    """The JAR's descriptor, resources and class inventory against the tagged source."""
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Candidate JAR has duplicate entries")
        if archive.testzip() is not None:
            raise ValueError("Candidate JAR fails its CRC check")
        resources = source_resources(root)
        template = resources["plugin.yml"]
        if template.count(b"'${version}'") != 1:
            raise ValueError("plugin.yml source no longer carries one version placeholder")
        resources["plugin.yml"] = template.replace(b"${version}", version.encode())
        files = {name for name in names if not name.endswith("/")}
        classes = {name for name in files if name.endswith(".class")}
        unexpected = files - classes - set(resources) - {"META-INF/MANIFEST.MF"}
        if unexpected or "META-INF/MANIFEST.MF" not in files:
            raise ValueError(f"Candidate JAR has unexpected or missing entries: {sorted(unexpected)}")
        for name, contents in resources.items():
            if name not in files or archive.read(name) != contents:
                raise ValueError(f"Candidate JAR resource differs from the tagged source: {name}")
        if MAIN_CLASS not in classes:
            raise ValueError("Candidate JAR lacks the plugin main class")
        sources = {path.relative_to(root / "src/main/java").as_posix()[:-5]
                   for path in (root / "src/main/java").rglob("*.java")}
        compiled = {name[:-6].split("$")[0] for name in classes}
        if any(not name.startswith(PACKAGE) for name in classes) or compiled != sources:
            raise ValueError("Candidate JAR classes do not match the tagged source files")
        for name in classes:
            header = archive.read(name)[:8]
            if header[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(header[6:8], "big") != CLASS_MAJOR:
                raise ValueError(f"Candidate JAR class is not Java 25 bytecode: {name}")


def verify(args):
    data = read_json(args.manifest)
    version, channel = details(args.tag)
    if not SHA.fullmatch(args.source or ""):
        raise ValueError("Source must be a full commit SHA")
    expected = {"schema": 1, "candidate_id": candidate_id(args.run_id, args.attempt, args.source, args.tag),
                "run_id": args.run_id, "attempt": args.attempt, "source": args.source, "tag": args.tag,
                "version": version, "channel": channel, "destinations": DESTINATIONS}
    for key, value in expected.items():
        if data.get(key) != value:
            raise ValueError(f"Candidate {key} differs from the intended release")
    if type(data.get("artifact_id")) is not int or data["artifact_id"] < 1:
        raise ValueError("Missing artifact ID")
    names = expected_files(args.tag)
    files = data.get("files")
    if not isinstance(files, dict) or set(files) != names:
        raise ValueError("Candidate manifest does not name exactly the release assets")
    directory = Path(args.directory)
    if inventory(directory) != names:
        raise ValueError("Candidate file inventory differs from the manifest")
    for name, expected_hash in files.items():
        if digest(directory / name) != expected_hash:
            raise ValueError(f"Candidate {name} SHA-256 differs from the manifest")
    if (directory / SUMS).read_text(encoding="utf-8") != sums_text(directory, names - {SUMS}):
        raise ValueError(f"{SUMS} differs from the candidate files")
    inspect_jar(ROOT, directory / jar_name(args.tag), version)
    # The pack verifiers rebuild the expected contents from this checkout, which is the
    # candidate's source commit, and compare every entry of every downloaded pack.
    with tempfile.TemporaryDirectory() as temp:
        for module, subset in ((java_packs, java_files(args.tag)), (bedrock_packs, bedrock_files(args.tag))):
            folder = Path(temp) / module.__name__
            folder.mkdir()
            for name in subset:
                shutil.copyfile(directory / name, folder / name)
            module.verify(ROOT, folder, args.tag, args.source, str(args.run_id), str(args.attempt))
    if args.evidence:
        if read_json(args.evidence) != evidence_record(data, args.manifest):
            raise ValueError("Missing or mismatched passing test evidence")
    print(f"Verified {data['candidate_id']}: {len(files)} files")


def evidence_record(data, manifest):
    return {"schema": 1, "candidate_id": data["candidate_id"], "manifest_sha256": digest(manifest),
            "artifact_id": data["artifact_id"], "files": data["files"], "tests": TESTS, "result": "passed",
            "verification_run_id": data["run_id"], "verification_attempt": data["attempt"]}


def check_test_results(folder):
    """The unit suite ran on the release JAR: no failure, error or skip, and the JAR test passed."""
    reports = sorted(Path(folder).glob("TEST-*.xml"))
    cases = []
    for report in reports:
        suite = ElementTree.parse(report).getroot()
        for case in suite.iter("testcase"):
            outcome = [child.tag for child in case if child.tag in ("failure", "error", "skipped")]
            cases.append((case.get("classname"), case.get("name"), outcome))
    if not cases:
        raise ValueError("No unit test results were recorded for the release JAR")
    bad = [f"{name} ({', '.join(outcome)})" for _, name, outcome in cases if outcome]
    if bad:
        raise ValueError(f"Unit tests on the release JAR did not all pass: {', '.join(bad[:5])}")
    if not any((classname, name) == RELEASE_TEST for classname, name, _ in cases):
        raise ValueError("The release JAR test did not run; the suite may have used the compiled classes")
    return len(cases)


def evidence(args):
    data = read_json(args.manifest)
    if data.get("run_id") != args.run_id or data.get("attempt") != args.attempt:
        raise ValueError("Evidence is recorded only by the run and attempt that built the candidate")
    verify(argparse.Namespace(manifest=args.manifest, directory=args.directory, tag=data.get("tag"),
                              source=data.get("source"), run_id=args.run_id, attempt=args.attempt, evidence=None))
    count = check_test_results(args.test_results)
    write_json(args.output, evidence_record(data, args.manifest))
    print(f"Recorded passing evidence for {data['candidate_id']} ({count} unit tests on the JAR)")


def tag_details(args):
    version, channel = details(args.tag)
    with Path(args.output).open("a", encoding="utf-8") as stream:
        stream.write(f"version={version}\nchannel={channel}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("details", "assemble", "create", "verify", "evidence"):
        command = sub.add_parser(name)
        command.add_argument("--directory", default="candidate")
        command.add_argument("--manifest", default="candidate-manifest.json")
        if name != "evidence":
            command.add_argument("--tag", required=True)
        if name in ("details", "create", "evidence"):
            command.add_argument("--output", required=True)
        if name == "assemble":
            command.add_argument("--jar", required=True)
            command.add_argument("--java", required=True)
            command.add_argument("--bedrock", required=True)
        if name in ("create", "verify"):
            command.add_argument("--source", required=True)
        if name in ("create", "verify", "evidence"):
            command.add_argument("--run-id", type=int, required=True)
            command.add_argument("--attempt", type=int, required=True)
        if name == "create":
            command.add_argument("--artifact-id", type=int, required=True)
        if name == "verify":
            command.add_argument("--evidence")
        if name == "evidence":
            command.add_argument("--test-results", required=True)
    args = parser.parse_args()
    handlers = {"details": tag_details, "assemble": assemble, "create": create, "verify": verify, "evidence": evidence}
    try:
        handlers[args.command](args)
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile, ElementTree.ParseError) as exc:
        parser.exit(1, f"Release candidate rejected: {exc}\n")


if __name__ == "__main__":
    main()
