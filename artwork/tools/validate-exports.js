#!/usr/bin/env node
// Checks exports/equipment-manifest.json against the files on disk.
// Usage: node artwork/tools/validate-exports.js
"use strict";
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const zlib = require("zlib");

const ROOT = path.resolve(__dirname, "..");
const SIZES = [16, 32, 64];
const errors = [];
const fail = (msg) => errors.push(msg);

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

// Minimal decoder for 8-bit RGBA, non-interlaced PNGs.
function readPng(file) {
  const buf = fs.readFileSync(file);
  let pos = 8;
  let width = 0, height = 0, depth = 0, colour = 0, interlace = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString("ascii", pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      depth = data[8];
      colour = data[9];
      interlace = data[12];
    } else if (type === "IDAT") {
      idat.push(data);
    }
    pos += 12 + len;
  }
  if (depth !== 8 || colour !== 6 || interlace !== 0) {
    throw new Error("expected 8-bit RGBA, non-interlaced");
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * 4;
  const out = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let x = 0; x < stride; x++) {
      const a = x >= 4 ? out[y * stride + x - 4] : 0;
      const b = y > 0 ? out[(y - 1) * stride + x] : 0;
      const c = x >= 4 && y > 0 ? out[(y - 1) * stride + x - 4] : 0;
      let v = line[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      out[y * stride + x] = v & 255;
    }
  }
  return { width, height, pixels: out };
}

function checkTexture(asset, entry) {
  const file = path.join(ROOT, "exports", entry.path);
  if (!fs.existsSync(file)) return fail(`${asset}: missing ${entry.path}`);
  if (sha256(file) !== entry.sha256) fail(`${asset}: hash mismatch ${entry.path}`);
  let png;
  try {
    png = readPng(file);
  } catch (e) {
    return fail(`${asset}: ${entry.path}: ${e.message}`);
  }
  if (png.width !== entry.width || png.height !== entry.height) {
    fail(`${asset}: ${entry.path} is ${png.width}x${png.height}, manifest says ${entry.width}x${entry.height}`);
  }
  const isAccent = entry.path.includes("-accent-");
  for (let i = 0; i < png.pixels.length; i += 4) {
    const [r, g, b, a] = png.pixels.subarray(i, i + 4);
    if (png.width === 16 && a !== 0 && a !== 255) {
      fail(`${asset}: ${entry.path} has partial alpha (${a}) at pixel ${i / 4}`);
      break;
    }
    if (isAccent && a !== 0 && !(r === g && g === b)) {
      fail(`${asset}: ${entry.path} accent is not grayscale at pixel ${i / 4}`);
      break;
    }
  }
}

const manifest = JSON.parse(
  fs.readFileSync(path.join(ROOT, "exports", "equipment-manifest.json"), "utf8")
);

for (const [asset, info] of Object.entries(manifest.assets)) {
  const master = path.join(ROOT, info.master);
  if (!fs.existsSync(master)) fail(`${asset}: missing master ${info.master}`);
  else if (sha256(master) !== info.master_sha256) {
    fail(`${asset}: master changed since export (${info.master}); re-export and update the manifest`);
  }
  for (const size of SIZES) {
    for (const kind of ["base", "accent"]) {
      const want = `${asset}/${asset}-${kind}-${size}.png`;
      if (!info.textures.some((t) => t.path === want)) fail(`${asset}: manifest lacks ${want}`);
    }
  }
  info.textures.forEach((t) => checkTexture(asset, t));
  if (info.adaptation_16) {
    const file = path.join(ROOT, info.adaptation_16);
    if (!fs.existsSync(file)) fail(`${asset}: missing ${info.adaptation_16}`);
    else if (sha256(file) !== info.adaptation_16_sha256) {
      fail(`${asset}: 16px adaptation changed since export; re-export and update the manifest`);
    }
  }
}

if (errors.length) {
  console.error(errors.map((e) => `FAIL ${e}`).join("\n"));
  process.exit(1);
}
console.log(`OK: ${Object.keys(manifest.assets).length} assets validated`);
