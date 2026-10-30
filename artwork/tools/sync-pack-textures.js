#!/usr/bin/env node
// Copies the production PNGs into the resource pack tree: 16px into the
// Standard base pack, 32px into the Detailed overlay (same resource paths).
// Usage: node artwork/tools/sync-pack-textures.js [--check]
"use strict";
const fs = require("fs");
const path = require("path");

const REPO = path.resolve(__dirname, "..", "..");
const EXPORTS = path.join(REPO, "artwork", "exports");
const CHECK = process.argv.includes("--check");

// Export asset -> pack texture name; the pack uses underscores.
const ASSETS = { shuriken: "shuriken", "smoke-bomb": "smoke_bomb", "dash-scroll": "dash" };
const TARGETS = [
  { size: 16, dir: "resourcepack/java/standard/assets/ninjaarena/textures/item" },
  { size: 32, dir: "resourcepack/java/detailed/assets/ninjaarena/textures/item" },
];

const stale = [];
for (const [asset, name] of Object.entries(ASSETS)) {
  for (const { size, dir } of TARGETS) {
    for (const [kind, suffix] of [["base", ""], ["accent", "_accent"]]) {
      const from = path.join(EXPORTS, asset, `${asset}-${kind}-${size}.png`);
      const to = path.join(REPO, dir, `${name}${suffix}.png`);
      const src = fs.readFileSync(from);
      if (CHECK) {
        if (!fs.existsSync(to) || !fs.readFileSync(to).equals(src)) stale.push(path.relative(REPO, to));
      } else {
        fs.mkdirSync(path.dirname(to), { recursive: true });
        fs.writeFileSync(to, src);
      }
    }
  }
}
if (stale.length) {
  console.error(`Out of date with artwork/exports (run sync-pack-textures.js):\n${stale.join("\n")}`);
  process.exit(1);
}
console.log(CHECK ? "OK: pack textures match exports" : "Pack textures synced");
