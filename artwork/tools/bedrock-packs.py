#!/usr/bin/env python3
"""Convert flat Java tool layers, generate Geyser mappings and retain Bedrock candidates."""

import argparse
import binascii
import importlib.util
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import uuid
import zipfile
import zlib

SPEC = importlib.util.spec_from_file_location("java_packs", Path(__file__).with_name("java-packs.py"))
java = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(java)
ROOT = java.ROOT
COLOURS = {"white": (255, 255, 255), "red": (255, 85, 85), "blue": (85, 85, 255),
           "green": (85, 255, 85), "yellow": (255, 255, 85)}
MANIFEST = "bedrock-manifest.json"
SUMS = "BEDROCK-SHA1SUMS"


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def target(root):
    value = java.read_json(root / "resourcepack/bedrock/target.json")
    for key in ("min_engine_version", "snapshot_version"):
        if not isinstance(value.get(key), list) or len(value[key]) != 3 or any(
                type(n) is not int or not 0 <= n <= 65535 for n in value[key]):
            raise ValueError(f"Bedrock {key} must contain three unsigned 16-bit integers")
    identities = [value[v][key] for v in java.VARIANTS for key in ("header_uuid", "module_uuid")]
    if len(set(identities)) != 4 or any(str(uuid.UUID(identity)) != identity for identity in identities):
        raise ValueError("Bedrock header and module UUIDs must be distinct canonical UUIDs")
    return value


def pack_version(root, version):
    if java.version_channel(version) == "snapshot":
        return target(root)["snapshot_version"]
    match = re.fullmatch(r"v0\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?", version)
    minor, patch = int(match[1]), int(match[2])
    phase = 30000 if match[3] is None else {"alpha": 0, "beta": 10000, "rc": 20000}[match[3]] + int(match[4])
    if minor >= 65535 or patch > 65535 or match[4] and int(match[4]) > 9999:
        raise ValueError("release exceeds the Bedrock numeric version range")
    return [minor + 1, patch, phase]


def read_png(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("invalid PNG signature")
    pos, compressed, dimensions = 8, bytearray(), None
    while pos < len(data):
        size = struct.unpack_from(">I", data, pos)[0]
        kind, contents = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + size]
        checksum = struct.unpack_from(">I", data, pos + 8 + size)[0]
        if binascii.crc32(kind + contents) & 0xffffffff != checksum:
            raise ValueError("PNG CRC mismatch")
        if kind == b"IHDR":
            width, height, depth, colour, compression, filtering, interlace = struct.unpack(">IIBBBBB", contents)
            if (depth, colour, compression, filtering, interlace) != (8, 6, 0, 0, 0):
                raise ValueError("expected non-interlaced 8-bit RGBA PNG")
            dimensions = (width, height)
        elif kind == b"IDAT":
            compressed.extend(contents)
        pos += size + 12
    if dimensions is None:
        raise ValueError("missing PNG dimensions")
    width, height = dimensions
    stride = width * 4
    raw = zlib.decompress(compressed)
    if len(raw) != (stride + 1) * height:
        raise ValueError("PNG pixel length mismatch")
    pixels = bytearray(stride * height)
    for y in range(height):
        filtering = raw[y * (stride + 1)]
        if filtering > 4:
            raise ValueError("unsupported PNG filter")
        for x in range(stride):
            index = y * stride + x
            a = pixels[index - 4] if x >= 4 else 0
            b = pixels[index - stride] if y else 0
            c = pixels[index - stride - 4] if y and x >= 4 else 0
            prediction = a + b - c
            distance = [abs(prediction - n) for n in (a, b, c)]
            paeth = (a, b, c)[distance.index(min(distance))]
            predictor = (0, a, b, (a + b) // 2, paeth)[filtering]
            pixels[index] = (raw[y * (stride + 1) + x + 1] + predictor) & 255
    return width, height, pixels


def png_bytes(width, height, pixels):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xffffffff)
    scanlines = b"".join(b"\0" + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
    # Stored deflate blocks make PNG bytes independent of zlib compression heuristics.
    compressed = zlib.compress(scanlines, level=0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + \
        chunk(b"IDAT", compressed) + chunk(b"IEND", b"")


def composite(base, accent, colour):
    output = bytearray(base)
    for i in range(0, len(base), 4):
        red, green, blue, alpha = accent[i:i + 4]
        if alpha == 0:
            continue
        if red != green or green != blue:
            raise ValueError("accent must be grayscale")
        denominator = alpha * 255 + base[i + 3] * (255 - alpha)
        for channel in range(3):
            tinted = (accent[i + channel] * colour[channel] + 127) // 255
            numerator = tinted * alpha * 255 + base[i + channel] * base[i + 3] * (255 - alpha)
            output[i + channel] = (numerator + denominator // 2) // denominator
        output[i + 3] = (denominator + 127) // 255
    return output


def source_files(root, variant, version):
    config = target(root)
    # Reuse the Java source inventory/target checks before converting its layers.
    java_files = java.source_files(root, variant, version)
    size = 16 if variant == "standard" else 32
    numeric = pack_version(root, version)
    metadata = {"format_version": 2,
                "header": {"name": f"NinjaArena {variant.title()}", "description": f"Ninja tool visuals — {version}",
                           "uuid": config[variant]["header_uuid"], "version": numeric,
                           "min_engine_version": config["min_engine_version"]},
                "modules": [{"type": "resources", "uuid": config[variant]["module_uuid"], "version": numeric}]}
    textures = {}
    files = {"manifest.json": json_bytes(metadata), "pack_icon.png": java_files["pack.png"]}
    folder = root / f"resourcepack/java/{variant}/assets/ninjaarena/textures/item"
    for tool in java.TOOLS:
        width, height, base = read_png((folder / f"{tool}.png").read_bytes())
        aw, ah, accent = read_png((folder / f"{tool}_accent.png").read_bytes())
        if (width, height, aw, ah) != (size, size, size, size):
            raise ValueError(f"{variant} {tool} texture dimensions mismatch")
        for colour, rgb in COLOURS.items():
            key = f"ninjaarena.{tool}_{colour}"
            texture = f"textures/items/ninjaarena/{tool}_{colour}"
            textures[key] = {"textures": texture}
            files[f"{texture}.png"] = png_bytes(size, size, composite(base, accent, rgb))
    files["textures/item_texture.json"] = json_bytes({"resource_pack_name": "ninjaarena", "texture_name": "atlas.items",
                                                      "texture_data": textures})
    return files


DASH_COOLDOWN_SECONDS = 8.0


def mappings():
    items = {"minecraft:snowball": [], "minecraft:feather": []}
    for tool in java.TOOLS:
        for colour in COLOURS:
            definition = {"type": "definition", "model": f"ninjaarena:{tool}",
                          "bedrock_identifier": f"ninjaarena:{tool}_{colour}",
                          "predicate": {"type": "match", "property": "custom_model_data", "index": 0, "value": colour},
                          "bedrock_options": {"icon": f"ninjaarena.{tool}_{colour}"}}
            if tool == "dash":
                # Geyser names the Bedrock cooldown category after this group; the server's
                # cooldown packet for a feather uses the same group, so the hotbar overlay matches.
                definition["components"] = {"minecraft:max_stack_size": 1,
                                            "minecraft:use_cooldown": {"seconds": DASH_COOLDOWN_SECONDS,
                                                                       "cooldown_group": "minecraft:feather"}}
            items["minecraft:feather" if tool == "dash" else "minecraft:snowball"].append(definition)
    return json_bytes({"format_version": 2, "items": items})


def require_empty(folder):
    folder.mkdir(parents=True, exist_ok=True)
    if any(folder.iterdir()):
        raise ValueError("output directory must be empty; never replace a retained candidate")


def convert(root, folder, version):
    require_empty(folder)
    for variant in java.VARIANTS:
        for name, contents in source_files(root, variant, version).items():
            file = folder / variant / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(contents)


def check_conversion(root, folder, version):
    expected = {f"{v}/{name}": data for v in java.VARIANTS for name, data in source_files(root, v, version).items()}
    actual = {p.relative_to(folder).as_posix(): p for p in folder.rglob("*") if p.is_file()}
    if actual.keys() != expected.keys() or any(p.is_symlink() or p.read_bytes() != expected[n] for n, p in actual.items()):
        raise ValueError("converted Bedrock files differ from repository sources")


def build(root, output, version, source, run_id="local", attempt="1", converted=None, mapping_file=None):
    channel = java.version_channel(version)
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ValueError("source must be a full commit SHA")
    if channel == "stable" and f"## [{version[1:]}]" not in (root / "CHANGELOG.md").read_text(encoding="utf-8"):
        raise ValueError("stable release requires a CHANGELOG.md section")
    if converted is None or mapping_file is None:
        raise ValueError("run explicit convert and mappings steps before building")
    check_conversion(root, converted, version)
    if mapping_file.read_bytes() != mappings():
        raise ValueError("generated Geyser mappings differ from repository specification")
    require_empty(output)
    files = {}
    for variant in java.VARIANTS:
        filename = f"NinjaArena-{version}-bedrock-{variant}.mcpack"
        with zipfile.ZipFile(output / filename, "w", compression=zipfile.ZIP_STORED) as archive:
            for name, contents in sorted(source_files(root, variant, version).items()):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                archive.writestr(info, contents)
        files[filename] = {algorithm: java.digest((output / filename).read_bytes(), algorithm) for algorithm in ("sha1", "sha256")}
    mapping_name = f"NinjaArena-{version}-geyser-mappings.json"
    (output / mapping_name).write_bytes(mapping_file.read_bytes())
    files[mapping_name] = {"sha256": java.digest(mapping_file.read_bytes())}
    hashes = "".join(f'{data["sha1"]}  {name}\n' for name, data in sorted(files.items()) if "sha1" in data)
    (output / SUMS).write_bytes(hashes.encode())
    files[SUMS] = {"sha256": java.digest(hashes.encode())}
    manifest = {"candidate": f"{source}-{run_id}-{attempt}", "source": source, "version": version, "channel": channel,
                "run_id": str(run_id), "attempt": str(attempt), "bedrock": target(root),
                "destinations": [] if channel == "snapshot" else [f"github:{java.REPOSITORY}:{version}"], "files": files}
    java.write_json(output / MANIFEST, manifest)
    return manifest


def verify(root, folder, version, source, run_id, attempt):
    manifest = java.read_json(folder / MANIFEST)
    channel = java.version_channel(version)
    expected = {"source": source, "version": version, "channel": channel, "run_id": str(run_id), "attempt": str(attempt),
                "candidate": f"{source}-{run_id}-{attempt}", "bedrock": target(root),
                "destinations": [] if channel == "snapshot" else [f"github:{java.REPOSITORY}:{version}"]}
    for key, value in expected.items():
        if manifest.get(key) != value:
            raise ValueError(f"candidate {key} mismatch")
    packs = {f"NinjaArena-{version}-bedrock-{v}.mcpack" for v in java.VARIANTS}
    mapping_name = f"NinjaArena-{version}-geyser-mappings.json"
    names = packs | {mapping_name, SUMS}
    if set(manifest["files"]) != names or {p.name for p in folder.iterdir()} != names | {MANIFEST}:
        raise ValueError("candidate file inventory mismatch")
    for name, checksums in manifest["files"].items():
        required = {"sha1", "sha256"} if name in packs else {"sha256"}
        if set(checksums) != required or any(java.digest((folder / name).read_bytes(), a) != h for a, h in checksums.items()):
            raise ValueError(f"candidate digest mismatch: {name}")
    expected_hashes = "".join(f'{manifest["files"][name]["sha1"]}  {name}\n' for name in sorted(packs))
    if (folder / SUMS).read_bytes() != expected_hashes.encode() or (folder / mapping_name).read_bytes() != mappings():
        raise ValueError("candidate mappings or hash list mismatch")
    for variant in java.VARIANTS:
        with zipfile.ZipFile(folder / f"NinjaArena-{version}-bedrock-{variant}.mcpack") as archive:
            expected_files = source_files(root, variant, version)
            if len(archive.namelist()) != len(expected_files) or set(archive.namelist()) != expected_files.keys():
                raise ValueError("Bedrock archive inventory mismatch")
            if archive.testzip() is not None or any(archive.read(n) != d for n, d in expected_files.items()):
                raise ValueError("Bedrock archive content mismatch")
    return {"candidate": manifest["candidate"], "manifest_sha256": java.digest((folder / MANIFEST).read_bytes()),
            "run_id": str(run_id), "attempt": str(attempt), "result": "passed"}


def publish(root, folder, evidence, version, source, run_id, attempt):
    java.publish(root, folder, evidence, version, source, run_id, attempt, verifier=verify, manifest_name=MANIFEST)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("convert", "mappings", "build", "verify", "publish"))
    parser.add_argument("--output", type=Path, default=ROOT / "build/bedrock-resourcepacks")
    parser.add_argument("--converted", type=Path, default=ROOT / "build/bedrock-converted")
    parser.add_argument("--mapping-file", type=Path, default=ROOT / "build/bedrock-mappings.json")
    parser.add_argument("--version", default=os.environ.get("PACK_VERSION"), required="PACK_VERSION" not in os.environ)
    parser.add_argument("--source", default=os.environ.get("GITHUB_SHA"))
    parser.add_argument("--run-id", default=os.environ.get("GITHUB_RUN_ID", "local"))
    parser.add_argument("--attempt", default=os.environ.get("GITHUB_RUN_ATTEMPT", "1"))
    parser.add_argument("--evidence", type=Path, default=ROOT / "build/bedrock-pack-verification.json")
    args = parser.parse_args()
    if args.command == "convert":
        subprocess.run(["node", str(ROOT / "artwork/tools/validate-pack.js")], check=True)
        convert(ROOT, args.converted, args.version)
    elif args.command == "mappings":
        if args.mapping_file.exists():
            raise ValueError("mapping output already exists")
        args.mapping_file.parent.mkdir(parents=True, exist_ok=True)
        args.mapping_file.write_bytes(mappings())
    else:
        source = args.source or subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        if args.command == "build":
            build(ROOT, args.output, args.version, source, args.run_id, args.attempt, args.converted, args.mapping_file)
        elif args.command == "verify":
            evidence = verify(ROOT, args.output, args.version, source, args.run_id, args.attempt)
            evidence.update(verification_run_id=os.environ.get("GITHUB_RUN_ID", "local"),
                            verification_attempt=os.environ.get("GITHUB_RUN_ATTEMPT", "1"))
            args.evidence.parent.mkdir(parents=True, exist_ok=True)
            java.write_json(args.evidence, evidence)
        else:
            publish(ROOT, args.output, args.evidence, args.version, source, args.run_id, args.attempt)


if __name__ == "__main__":
    main()
