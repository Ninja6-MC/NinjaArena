"""Read local Anvil chunk NBT for save-difference and marker diagnostics."""
import hashlib
import io
from functools import lru_cache
import json
import struct
import zlib


class Nbt:
    def __init__(self, data):
        self.stream = io.BytesIO(data)

    def read(self, size):
        value = self.stream.read(size)
        if len(value) != size:
            raise ValueError("Truncated NBT")
        return value

    def number(self, fmt):
        return struct.unpack(">" + fmt, self.read(struct.calcsize(fmt)))[0]

    def string(self):
        return self.read(self.number("H")).decode("utf-8", errors="replace")

    def payload(self, kind):
        if kind in (1, 2, 3, 4, 5, 6):
            return self.number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        if kind == 7:
            return self.read(self.number("i")).hex()
        if kind == 8:
            return self.string()
        if kind == 9:
            child = self.number("B")
            return [self.payload(child) for _ in range(self.number("i"))]
        if kind == 10:
            result = {}
            while (child := self.number("B")) != 0:
                name = self.string()
                result[name] = self.payload(child)
            return result
        if kind in (11, 12):
            fmt = "i" if kind == 11 else "q"
            return [self.number(fmt) for _ in range(self.number("i"))]
        raise ValueError(f"Unsupported NBT tag {kind}")

    def root(self):
        kind = self.number("B")
        self.string()
        return self.payload(kind)


def chunks(file):
    with open(file, "rb") as stream:
        locations = stream.read(4096)
        for index in range(1024):
            location = int.from_bytes(locations[index * 4:index * 4 + 4], "big")
            sector = location >> 8
            if not sector:
                continue
            stream.seek(sector * 4096)
            length = int.from_bytes(stream.read(4), "big")
            compression = stream.read(1)[0]
            data = stream.read(length - 1)
            if compression == 2:
                data = zlib.decompress(data)
            elif compression == 1:
                data = zlib.decompress(data, 31)
            elif compression != 3:
                raise ValueError(f"Unsupported Anvil compression {compression}")
            yield index, Nbt(data).root()


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


@lru_cache(maxsize=256)
def semantic_states(palette, data):
    if len(palette) == 1:
        return hashlib.sha256((palette[0] + "\0").encode() * 4096).hexdigest()
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    state_hash = hashlib.sha256()
    for block in range(4096):
        position = (data[block // per_long] >> ((block % per_long) * bits)) & mask
        state_hash.update((palette[position] + "\0").encode())
    return state_hash.hexdigest()


def section_blocks(section):
    states = section.get("block_states", {"palette": ["minecraft:air"]})
    palette = tuple(json.dumps(value, sort_keys=True, separators=(",", ":")) for value in states["palette"])
    return semantic_states(palette, tuple(states.get("data", [])))


def inspect(directory, markers):
    result = {"chunks": {}, "saved_markers": {name: None for name in markers}}
    for folder in ("region", "entities", "poi"):
        for file in sorted((directory / folder).glob("*.mca")):
            for index, chunk in chunks(file):
                key = f"{folder}/{file.name}:{index}"
                result["chunks"][key] = {"full": digest(chunk)}
                if folder != "region":
                    continue
                sections = chunk.get("sections", [])
                result["chunks"][key].update({
                    "blocks": digest(sorted((s["Y"], section_blocks(s)) for s in sections)),
                    "block_encoding": digest([(s["Y"], s.get("block_states")) for s in sections]),
                    "block_entities": digest(chunk.get("block_entities", [])),
                    "entities": digest(chunk.get("entities", [])),
                    "last_update": chunk.get("LastUpdate"), "status": chunk.get("Status")})
                cx, cz = chunk["xPos"], chunk["zPos"]
                for name, (x, y, z) in markers.items():
                    if x // 16 != cx or z // 16 != cz:
                        continue
                    for section in sections:
                        if section["Y"] != y // 16:
                            continue
                        states = section["block_states"]
                        palette = states["palette"]
                        if len(palette) == 1:
                            position = 0
                        else:
                            bits = max(4, (len(palette) - 1).bit_length())
                            per_long = 64 // bits
                            block = (y % 16) * 256 + (z % 16) * 16 + x % 16
                            position = (states["data"][block // per_long] >> ((block % per_long) * bits)) & ((1 << bits) - 1)
                        state = palette[position]
                        result["saved_markers"][name] = state if isinstance(state, str) else state["Name"]
    return result


def differences(before, after):
    keys = before["chunks"].keys() | after["chunks"].keys()
    summary = {"added_chunks": 0, "removed_chunks": 0, "changed_nbt": 0,
               "changed_blocks": 0, "changed_block_encoding": 0, "changed_block_entities": 0, "changed_entities": 0}
    for key in keys:
        old, new = before["chunks"].get(key), after["chunks"].get(key)
        if old is None:
            summary["added_chunks"] += 1
        elif new is None:
            summary["removed_chunks"] += 1
        else:
            summary["changed_nbt"] += old["full"] != new["full"]
            summary["changed_blocks"] += old.get("blocks") != new.get("blocks")
            summary["changed_block_encoding"] += old.get("block_encoding") != new.get("block_encoding")
            summary["changed_block_entities"] += old.get("block_entities") != new.get("block_entities")
            summary["changed_entities"] += old.get("entities") != new.get("entities")
    return summary
