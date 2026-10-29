"""Export named SVG layers after refreshing the full SVGs from the vector masters."""
from pathlib import Path
import copy
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", NS)
ASSETS = {
    "dash-scroll": {"Team Binding", "Binding Details", "Binding Back"},
    "smoke-bomb": {"Team Band", "Band Details"},
}


def main():
    manifest = {}
    for asset, accent_names in ASSETS.items():
        original = ET.parse(ROOT / f"{asset}-64.svg").getroot()
        body = next((e for e in original.iter()
                     if e.get("id") == "Rolled_Scroll_Body"), None)
        folder = ROOT / "linked" / asset
        folder.mkdir(parents=True, exist_ok=True)
        manifest[asset] = []
        for group in original:
            if group.tag != f"{{{NS}}}g" or not len(group):
                continue
            name = group.get("data-name", group.get("id"))
            output = ET.Element(original.tag, original.attrib)
            for child in original:
                if child.tag == f"{{{NS}}}defs":
                    output.append(copy.deepcopy(child))
            layer = copy.deepcopy(group)
            output.append(layer)
            if name == "Binding Back":
                if body is None:
                    raise ValueError("Missing scroll body for rear binding occlusion")
                defs = next(e for e in output if e.tag == f"{{{NS}}}defs")
                clip = ET.SubElement(defs, f"{{{NS}}}clipPath", {
                    "id": "visible-rear-cloth", "clipPathUnits": "userSpaceOnUse"})
                ET.SubElement(clip, f"{{{NS}}}path", {
                    "d": "M-64 -64 H128 V128 H-64 Z " + body.get("d"),
                    "fill-rule": "evenodd", "clip-rule": "evenodd"})
                layer.set("clip-path", "url(#visible-rear-cloth)")
            slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
            path = folder / f"{slug}-64.svg"
            ET.ElementTree(output).write(path, encoding="utf-8", xml_declaration=True)
            manifest[asset].append({
                "name": name,
                "role": "accent" if name in accent_names else "base",
                "path": path.relative_to(ROOT).as_posix(),
            })
    (ROOT / "linked" / "layer-sources.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Refreshed separate SVG layer sources for both assets.")


if __name__ == "__main__":
    main()
