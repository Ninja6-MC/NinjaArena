#!/usr/bin/env python3
"""Refresh square pack icons from the existing neutral shuriken production layers."""

import argparse
import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("bedrock_packs", Path(__file__).with_name("bedrock-packs.py"))
packs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packs)


def icon_bytes(root):
    folder = root / "artwork/exports/shuriken"
    width, height, base = packs.read_png((folder / "shuriken-base-64.png").read_bytes())
    aw, ah, accent = packs.read_png((folder / "shuriken-accent-64.png").read_bytes())
    if (width, height, aw, ah) != (64, 64, 64, 64):
        raise ValueError("pack icon requires the 64x64 shuriken layers")
    pixels = packs.composite(base, accent, (255, 255, 255))
    # Integer nearest-neighbour enlargement preserves the production artwork.
    enlarged = bytearray()
    for y in range(256):
        for x in range(256):
            index = ((y // 4) * 64 + x // 4) * 4
            enlarged.extend(pixels[index:index + 4])
    return packs.png_bytes(256, 256, enlarged)


def sync(root, check=False):
    contents = icon_bytes(root)
    stale = []
    for variant in packs.java.VARIANTS:
        file = root / f"resourcepack/java/{variant}/pack.png"
        if check:
            if not file.is_file() or file.read_bytes() != contents:
                stale.append(str(file.relative_to(root)))
        else:
            file.write_bytes(contents)
    if stale:
        raise ValueError("pack icons differ from production artwork; run sync-pack-icons.py: " + ", ".join(stale))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    sync(packs.ROOT, parser.parse_args().check)
    print("OK: pack icons match production artwork")
