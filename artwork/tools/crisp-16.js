#!/usr/bin/env node
// Export step: forces every 16x16 texture to fully opaque or fully clear
// (alpha >= CUTOFF becomes 255, otherwise 0), then refreshes the hashes in
// the manifest, each asset's validation.json and export-settings.json.
// Idempotent. Run after exporting 16px layers from the PhotoCraft adaptation.
// Usage: node artwork/tools/crisp-16.js
"use strict";
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const zlib = require("zlib");

const ROOT = path.resolve(__dirname, "..");
const CUTOFF = 128;

const CRC = (() => {
  const t = [];
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
const crc32 = (b) => {
  let c = 0xffffffff;
  for (const x of b) c = CRC[(c ^ x) & 255] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}
function encodePng(w, h, px) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) px.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}
function decodePng(buf) {
  let pos = 8, w = 0, h = 0, depth = 0, colour = 0, interlace = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString("ascii", pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === "IHDR") {
      w = data.readUInt32BE(0);
      h = data.readUInt32BE(4);
      depth = data[8];
      colour = data[9];
      interlace = data[12];
    }
    if (type === "IDAT") idat.push(data);
    pos += 12 + len;
  }
  if (depth !== 8 || colour !== 6 || interlace !== 0) {
    throw new Error("expected 8-bit RGBA, non-interlaced");
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const s = w * 4, out = Buffer.alloc(s * h);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (s + 1)];
    for (let x = 0; x < s; x++) {
      const a = x >= 4 ? out[y * s + x - 4] : 0;
      const b = y ? out[(y - 1) * s + x] : 0;
      const c = x >= 4 && y ? out[(y - 1) * s + x - 4] : 0;
      let v = raw[y * (s + 1) + 1 + x];
      if (f === 1) v += a;
      else if (f === 2) v += b;
      else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      out[y * s + x] = v & 255;
    }
  }
  return { w, h, px: out };
}
const readJson = (f) => JSON.parse(fs.readFileSync(f, "utf8"));

const manifestFile = path.join(ROOT, "exports", "equipment-manifest.json");
const manifest = readJson(manifestFile);

const pending = [];
const stage = (file, content) => pending.push([file, content]);

for (const asset of Object.keys(manifest.assets)) {
  const dir = path.join(ROOT, "exports", asset);
  const hashes = {};
  for (const kind of ["base", "accent"]) {
    const name = `${asset}-${kind}-16.png`;
    const file = path.join(dir, name);
    let img;
    try {
      img = decodePng(fs.readFileSync(file));
    } catch (e) {
      throw new Error(`${name}: ${e.message}`);
    }
    if (img.w !== 16 || img.h !== 16) throw new Error(`${name}: expected 16x16, got ${img.w}x${img.h}`);
    for (let i = 3; i < img.px.length; i += 4) img.px[i] = img.px[i] >= CUTOFF ? 255 : 0;
    const png = encodePng(img.w, img.h, img.px);
    stage(file, png);
    hashes[name] = crypto.createHash("sha256").update(png).digest("hex");
    const entry = manifest.assets[asset].textures.find((t) => t.path === `${asset}/${name}`);
    if (!entry) throw new Error(`${name}: not in the manifest`);
    entry.sha256 = hashes[name];
  }

  const validationFile = path.join(dir, "validation.json");
  const validation = readJson(validationFile);
  for (const [name, sha] of Object.entries(hashes)) {
    if (validation[name]) validation[name].sha256 = sha;
  }
  stage(validationFile, JSON.stringify(validation, null, 2) + "\n");

  const settingsFile = path.join(dir, "export-settings.json");
  const settings = readJson(settingsFile);
  settings.alpha_cutoff_16 = CUTOFF;
  settings.alpha_cutoff_16_note =
    "16px layers are hard-edged: alpha >= cutoff becomes 255, otherwise 0 (tools/crisp-16.js).";
  stage(settingsFile, JSON.stringify(settings, null, 2) + "\n");
}
stage(manifestFile, JSON.stringify(manifest, null, 2) + "\n");
for (const [file, content] of pending) fs.writeFileSync(file, content);
console.log("16px exports hardened; hashes updated");
