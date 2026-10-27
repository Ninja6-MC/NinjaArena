#!/usr/bin/env node
// Compose every icon from one geometry master, then validate before writing.
import { execSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ASSETS = join(ROOT, 'docs', 'assets');
const PLATE = '#12161A';
const BLUE = '#38BDF8';
const BLUE_LIGHT = '#0369A1';
const AMBER = '#FFAC1C';
const AMBER_LIGHT = '#B8730A';
const SILVER = '#E8EDF2';
const SAFE_RADIUS = 460;
const THEME_INK = { dark: [BLUE, AMBER], light: [BLUE_LIGHT, AMBER_LIGHT] };

export const variants = [
  { file: 'icon', geo: 'geo', plate: PLATE, stroke: BLUE, dot: AMBER,
    theme: 'dark', sizes: [64, 128, 180, 256, 400, 512, 1024] },
  { file: 'icon-rounded', geo: 'geo', plate: PLATE, rx: 225, stroke: BLUE,
    dot: AMBER, theme: 'dark', sizes: [180, 256, 512, 1024] },
  { file: 'icon-transparent-dark', geo: 'geo', stroke: BLUE, dot: AMBER,
    theme: 'dark', sizes: [64, 128, 256, 512] },
  { file: 'icon-transparent-light', geo: 'geo', stroke: BLUE_LIGHT, dot: AMBER_LIGHT,
    theme: 'light', sizes: [64, 128, 256, 512] },
  { file: 'icon-small-dark', geo: 'geo-small', stroke: BLUE,
    theme: 'dark', sizes: [16, 20, 24, 32, 48, 64] },
  { file: 'icon-small-light', geo: 'geo-small', stroke: BLUE_LIGHT,
    theme: 'light', sizes: [16, 20, 24, 32, 48, 64] },
  { file: 'icon-mono-dark', geo: 'geo', stroke: SILVER, dot: SILVER,
    theme: 'dark', sizes: [] },
  { file: 'icon-mono-light', geo: 'geo', stroke: PLATE, dot: PLATE,
    theme: 'light', sizes: [] },
];

function requireThat(ok, message) {
  if (!ok) throw new Error(message);
}

function number(value) {
  requireThat(typeof value === 'string' && /^\d+(?:\.\d+)?$/.test(value),
    `Expected a non-negative number, got ${value}`);
  const result = Number(value);
  requireThat(Number.isFinite(result), 'Non-finite number');
  return result;
}

// Accept only the measured SVG subset. Every token and attribute is consumed;
// unsupported paint, transforms, nesting and malformed XML fail closed.
export function parseMaster(xml) {
  const groups = {};
  const stack = [];
  let group;
  let rootSeen = false;
  const tokens = xml.match(/<!--[\s\S]*?-->|<[^>]*>|[^<]+/g) ?? [];
  requireThat(tokens.join('') === xml, 'Unparsed XML');
  for (const token of tokens) {
    if (token.startsWith('<!--')) continue;
    if (!token.startsWith('<')) {
      requireThat(!token.trim(), 'Unexpected text');
      continue;
    }
    const close = token.match(/^<\/(\w+)\s*>$/);
    if (close) {
      requireThat(stack.pop() === close[1], 'Mismatched closing tag');
      if (close[1] === 'g') group = undefined;
      continue;
    }
    const open = token.match(/^<(\w+)\s*([\s\S]*?)(\/?)>$/);
    requireThat(open, 'Invalid element');
    const [, tag, raw, slash] = open;
    const attrs = {};
    let rest = raw;
    while (rest.trim()) {
      const attr = rest.match(/^\s*([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')\s*/);
      requireThat(attr, 'Invalid attribute');
      requireThat(!Object.hasOwn(attrs, attr[1]), 'Duplicate attribute');
      attrs[attr[1]] = attr[2] ?? attr[3];
      rest = rest.slice(attr[0].length);
    }
    const parent = stack.at(-1);
    let allowed;
    if (tag === 'svg') {
      requireThat(!rootSeen && !parent && !slash, 'Invalid SVG root');
      rootSeen = true;
      allowed = ['xmlns', 'viewBox', 'width', 'height'];
      requireThat(attrs.xmlns === 'http://www.w3.org/2000/svg'
        && attrs.viewBox === '0 0 1024 1024'
        && attrs.width === '1024' && attrs.height === '1024', 'Invalid canvas');
    } else if (tag === 'defs') {
      requireThat(parent === 'svg' && !slash, 'Expected defs inside SVG');
      allowed = [];
    } else if (tag === 'g') {
      requireThat(parent === 'defs' && !slash, 'Geometry groups must be flat');
      requireThat(['geo', 'geo-small'].includes(attrs.id) && !groups[attrs.id],
        'Unknown or duplicate geometry group');
      allowed = ['id'];
      group = groups[attrs.id] = [];
    } else {
      requireThat(parent === 'g' && slash, 'Shapes must be self-closing inside a group');
      if (tag === 'polyline') {
        allowed = ['points', 'fill', 'stroke-width', 'stroke-linecap', 'stroke-linejoin'];
        requireThat(attrs.fill === 'none' && attrs['stroke-linecap'] === 'square'
          && attrs['stroke-linejoin'] === 'miter', 'Invalid polyline paint');
        requireThat(number(attrs['stroke-width']) === (groups['geo-small'] === group ? 118 : 48),
          'Incorrect stroke weight');
      } else {
        requireThat(tag === 'circle' && groups.geo === group, 'Unsupported geometry');
        allowed = ['cx', 'cy', 'r', 'stroke'];
        requireThat(attrs.stroke === 'none' && number(attrs.r) > 0, 'Invalid circle');
        number(attrs.cx);
        number(attrs.cy);
      }
      group.push({ tag, attrs });
    }
    requireThat(Object.keys(attrs).every((key) => allowed.includes(key)),
      `Unsupported attribute on ${tag}`);
    if (!slash) stack.push(tag);
  }
  requireThat(rootSeen && stack.length === 0 && groups.geo?.length && groups['geo-small']?.length,
    'Incomplete master');
  for (const [id, shapes] of Object.entries(groups)) {
    const radius = worstRadius(shapes);
    requireThat(radius <= SAFE_RADIUS, `${id} breaches safe zone: ${radius.toFixed(2)}`);
  }
  return groups;
}

export function worstRadius(shapes) {
  let worst = 0;
  for (const { tag, attrs } of shapes) {
    if (tag === 'circle') {
      // Exact farthest point, including circles off the diagonal axes.
      worst = Math.max(worst, Math.hypot(number(attrs.cx) - 512,
        number(attrs.cy) - 512) + number(attrs.r));
      continue;
    }
    requireThat(tag === 'polyline', 'Unsupported geometry');
    const points = (attrs.points ?? '').trim().split(/\s+/).map((pair) => {
      const xy = pair.split(',');
      requireThat(xy.length === 2, 'Expected x,y point');
      return xy.map(number);
    });
    requireThat(points.length >= 2, 'Polyline needs two points');
    for (let i = 1; i < points.length; i++) {
      const dx = points[i][0] - points[i - 1][0];
      const dy = points[i][1] - points[i - 1][1];
      requireThat((dx === 0) !== (dy === 0), 'Expected nonzero axis-aligned segment');
    }
    // A half-width square at every vertex encloses square caps and right-angle
    // miter joins. Its corners give a conservative bound on every inked point.
    const half = number(attrs['stroke-width']) / 2;
    for (const [x, y] of points) {
      for (const a of [-half, half]) for (const b of [-half, half]) {
        worst = Math.max(worst, Math.hypot(x + a - 512, y + b - 512));
      }
    }
  }
  return worst;
}

export function compose(groups, variant) {
  const body = groups[variant.geo].map(({ tag, attrs }) =>
    `    <${tag} ${Object.entries(attrs).map(([key, value]) => `${key}="${value}"`).join(' ')} />`
  ).join('\n');
  const plate = variant.plate
    ? `  <rect width="1024" height="1024"${variant.rx ? ` rx="${variant.rx}"` : ''} fill="${variant.plate}" />\n`
    : '';
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">
  <!-- Exported from icon-master.svg by scripts/export-icons.mjs. Edit the master. -->
${plate}  <g stroke="${variant.stroke}"${variant.dot ? ` fill="${variant.dot}"` : ''}>
${body}
  </g>
</svg>
`;
}

export function renderOutputs(groups, Resvg) {
  const outputs = new Map();
  for (const variant of variants) {
    const svg = compose(groups, variant);
    const forbidden = THEME_INK[variant.theme === 'dark' ? 'light' : 'dark'];
    requireThat(forbidden.every((ink) => !svg.includes(ink)), 'Mixed theme palette');
    outputs.set(`${variant.file}.svg`, Buffer.from(svg));
    // Render the mono SVGs too, so their paint and alpha are checked.
    for (const size of new Set([...variant.sizes, 256])) {
      const image = new Resvg(svg, { fitTo: { mode: 'width', value: size },
        font: { loadSystemFonts: false } }).render();
      const pixels = image.pixels;
      const colours = new Set();
      let transparent = false;
      for (let i = 0; i < pixels.length; i += 4) {
        if (pixels[i + 3] < 255) transparent = true;
        if (pixels[i + 3] === 255) colours.add('#' + Buffer.from(pixels.slice(i, i + 3))
          .toString('hex').toUpperCase());
      }
      requireThat(variant.plate && !variant.rx ? !transparent : transparent,
        `${variant.file}: incorrect transparency`);
      if (size >= 64) {
        requireThat(colours.has(variant.stroke), `${variant.file}: stroke did not render`);
        if (variant.dot) requireThat(colours.has(variant.dot), `${variant.file}: accent did not render`);
      }
      if (variant.sizes.includes(size)) outputs.set(`${variant.file}-${size}.png`, image.asPng());
    }
  }
  return outputs;
}

async function main() {
  requireThat(process.argv.slice(2).every((arg) => arg === '--check'), 'Unknown argument');
  const groups = parseMaster(readFileSync(join(ASSETS, 'icon-master.svg'), 'utf8'));
  const tools = join(ROOT, 'build', 'icon-tools');
  const entry = join(tools, 'node_modules', '@resvg', 'resvg-js', 'index.js');
  if (!existsSync(entry)) {
    mkdirSync(tools, { recursive: true });
    writeFileSync(join(tools, 'package.json'), '{"private":true}\n');
    // npm is a shell shim on Windows. Use a literal command and cwd so neither
    // artwork nor workspace paths are interpolated into shell syntax.
    execSync('npm install --no-save --ignore-scripts @resvg/resvg-js@2.6.2',
      { cwd: tools, stdio: 'inherit' });
  }
  const { Resvg } = await import(pathToFileURL(entry).href);
  const outputs = renderOutputs(groups, Resvg);
  if (process.argv.includes('--check')) {
    const drift = [...outputs].filter(([name, bytes]) => !existsSync(join(ASSETS, name))
      || !readFileSync(join(ASSETS, name)).equals(bytes)).map(([name]) => name);
    requireThat(!drift.length, `Icon export drift: ${drift.join(', ')}`);
  } else {
    // Buffer and validate the entire export before changing committed artwork.
    for (const [name, bytes] of outputs) writeFileSync(join(ASSETS, name), bytes);
  }
  console.log(`${outputs.size} icon exports ${process.argv.includes('--check') ? 'verified' : 'written'}.`);
  for (const [id, shapes] of Object.entries(groups)) {
    console.log(`${id}: safe radius ${worstRadius(shapes).toFixed(2)} / ${SAFE_RADIUS}`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
