import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { compose, parseMaster, renderOutputs, variants, worstRadius } from './export-icons.mjs';

const master = readFileSync(new URL('../docs/assets/icon-master.svg', import.meta.url), 'utf8');

test('both cuts survive a circular crop including their strokes', () => {
  const groups = parseMaster(master);
  assert.ok(worstRadius(groups.geo) < 460);
  assert.ok(worstRadius(groups['geo-small']) < 460);
  assert.equal(groups.geo.filter((shape) => shape.tag === 'circle').length, 1);
  assert.equal(groups['geo-small'].filter((shape) => shape.tag === 'circle').length, 0);
});

test('off-axis circle bounds use the exact farthest point', () => {
  assert.equal(worstRadius([{ tag: 'circle', attrs: { cx: '812', cy: '912', r: '10' } }]), 510);
});

test('comments, attribute order and whitespace do not change measurements', () => {
  const changed = master.replace('<g id="geo">', '<!-- </g> -->\n<g\n id = \'geo\'>');
  assert.deepEqual(parseMaster(changed), parseMaster(master));
});

for (const [name, from, to] of [
  ['group transform', '<g id="geo">', '<g id="geo" transform="scale(0.5)">'],
  ['group paint', '<g id="geo">', '<g id="geo" stroke="#FFFFFF">'],
  ['shape transform', 'fill="none"', 'transform="scale(0.5)" fill="none"'],
  ['nested group', '<g id="geo">', '<g id="geo"><g>'],
  ['unsupported shape', '<g id="geo">', '<g id="geo"><path d="M0 0" />'],
  ['shape paint', 'fill="none"', 'fill="#FFFFFF"'],
  ['stroke weight', 'stroke-width="48"', 'stroke-width="49"'],
  ['unmeasured stroke', 'stroke="none"', 'stroke="#FFFFFF"'],
  ['wrong canvas', '0 0 1024 1024', '0 0 512 512'],
  ['duplicate attributes', '<g id="geo">', '<g id="geo" id="geo">'],
  ['bad attribute syntax', '<g id="geo">', '<g id="geo" broken>'],
  ['unclosed XML', '</svg>', ''],
  ['diagonal segment', '212,272 212,212', '212,272 213,212'],
  ['zero-length segment', '212,272 212,212', '212,272 212,272'],
  ['non-finite point', '212,272 212,212', 'NaN,272 212,212'],
  ['unparsed point pair', '212,272 212,212', '212 272 212 212'],
  ['second polyline outside safe zone', '<g id="geo">',
    '<g id="geo"><polyline points="0,0 100,0" fill="none" stroke-width="48" stroke-linecap="square" stroke-linejoin="miter" />'],
]) {
  test(`rejects ${name}`, () => assert.throws(() => parseMaster(master.replace(from, to))));
}

test('light, dark, rounded and monochrome variants compose from the same geometry', () => {
  const groups = parseMaster(master);
  for (const variant of variants) {
    const svg = compose(groups, variant);
    assert.ok(svg.includes(`stroke="${variant.stroke}"`));
    assert.equal(svg.includes('<rect '), Boolean(variant.plate));
    assert.equal(svg.includes('rx="225"'), Boolean(variant.rx));
    assert.equal(svg.includes('<circle '), variant.geo === 'geo');
    assert.equal(svg.includes('fill="#FFAC1C"'), variant.dot === '#FFAC1C');
  }
});

test('small cut includes an actual 20 px preview and the complete 16–64 ladder', () => {
  for (const variant of variants.filter((v) => v.geo === 'geo-small')) {
    assert.deepEqual(variant.sizes, [16, 20, 24, 32, 48, 64]);
  }
});

test('raster validation rejects missing paint before returning any outputs', () => {
  class EmptyRasterizer {
    render() { return { pixels: new Uint8Array(256 * 256 * 4), asPng: () => Buffer.alloc(0) }; }
  }
  assert.throws(() => renderOutputs(parseMaster(master), EmptyRasterizer), /transparency/);
});
