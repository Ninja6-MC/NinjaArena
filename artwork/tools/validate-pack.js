#!/usr/bin/env node
// Checks the Java resource pack tree: every item definition points at an
// existing model, every model texture exists in the Standard pack and the
// Detailed overlay, and pack textures match artwork/exports.
// Usage: node artwork/tools/validate-pack.js
"use strict";
const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const REPO = path.resolve(__dirname, "..", "..");
const PACK = path.join(REPO, "resourcepack", "java");
const NAMESPACE = "ninjaarena";
const errors = [];

const read = (f) => JSON.parse(fs.readFileSync(f, "utf8"));
const assets = (variant) => path.join(PACK, variant, "assets", NAMESPACE);

function resolve(variant, id, kind, ext) {
  const [ns, p] = id.includes(":") ? id.split(":") : ["minecraft", id];
  return ns === NAMESPACE ? path.join(assets(variant), kind, `${p}.${ext}`) : null;
}

const itemDir = path.join(assets("standard"), "items");
for (const file of fs.readdirSync(itemDir)) {
  const def = read(path.join(itemDir, file));
  const model = def.model && def.model.model;
  if (!model) { errors.push(`items/${file}: no model`); continue; }
  const modelFile = resolve("standard", model, "models", "json");
  if (modelFile && !fs.existsSync(modelFile)) { errors.push(`items/${file}: missing model ${model}`); continue; }
  const textures = Object.values(read(modelFile).textures || {});
  for (const tex of textures) {
    for (const variant of ["standard", "detailed"]) {
      const f = resolve(variant, tex, "textures", "png");
      if (f && !fs.existsSync(f)) errors.push(`${variant}: ${file} needs texture ${tex}`);
    }
  }
}

const sync = spawnSync(process.execPath, [path.join(__dirname, "sync-pack-textures.js"), "--check"], { encoding: "utf8" });
if (sync.status !== 0) errors.push((sync.stderr || sync.stdout).trim());

if (errors.length) {
  console.error(errors.map((e) => `FAIL ${e}`).join("\n"));
  process.exit(1);
}
console.log("OK: resource pack tree is consistent");
