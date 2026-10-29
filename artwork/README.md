# Artwork sources and exports

`shuriken-64.vectorcraft` is the authoritative editable source. Change the
named paths and layers there, then refresh the derived files. Keep the
`Team Colour Mask` neutral white; team colours are applied at runtime.

- `shuriken-64.svg`: derived interchange file for viewing and CI rendering.
- `shuriken-64.ai`: PDF-compatible Illustrator interchange copy.
- `exports/shuriken/`: neutral production PNG layers at 16, 32 and 64 pixels.
- `previews/`: coloured review images, excluded from the production bundle.
- `variants/baseline/`: preserved reference before the wider blade roots.
- `compatibility/`: format round-trip checks and export comparison files.

## Smoke bomb master

`smoke-bomb-64.vectorcraft` is the editable 64-pixel smoke-bomb master.
`smoke-bomb-64.svg` is its derived view copy. Review the named layers from
bottom to top: Body Shape, Body Shading, Clay Seal, Body Highlights, Team
Band, Band Details, Fuse, and Seal Details. Individual paths remain editable within
each layer. The team band stays neutral grayscale for team-colour tinting.

Smoke-bomb production layers are in `exports/smoke-bomb/`, with base and
accent PNGs at 16, 32 and 64 pixels. The accent includes Team Band and Band
Details, retaining neutral grayscale cloth shading. Multiply its RGB by
the selected team colour, keep its alpha, then draw it over the base.
`export-settings.json` records the split; `validation.json` records sizes,
hashes and recombination comparisons. The 16-pixel files use the accepted PhotoCraft adaptation;
32- and 64-pixel files use the VectorCraft master.

## Dash scroll master and layers

`dash-scroll-64.vectorcraft` is the editable scroll master;
`dash-scroll-64.svg` is its derived view copy. Production base and grayscale
accent PNGs are in `exports/dash-scroll/` at 16, 32 and 64 pixels. The accent
contains Team Binding, Binding Details and the visible part of Binding Back.
Clip the rear binding against the inverse body silhouette before exporting
it alone, so hidden cloth cannot show through the body during recombination.
Multiply accent RGB by the team colour, retain alpha, then draw over the base.
Export settings and validation results are stored beside the PNGs.
The 16-pixel export uses the accepted PhotoCraft adaptation.

## Production layers

Each size has `shuriken-base-<size>.png` and
`shuriken-accent-<size>.png`. Draw the base first. Multiply the accent's white
RGB by the team colour, retain its alpha, and draw it over the base. Do not
tint the base or include the coloured previews in the production pack.

The 16-pixel export adapts the centre to a clear 2-by-2-pixel square opening
and a wider accent ring. Its 64-unit geometry overrides are recorded in
`exports/shuriken/export-settings.json`; the working master is unchanged.
The 32- and 64-pixel exports use the master geometry directly.

## Interchange checks

SVG and PDF-compatible `.ai` round trips preserve the rendered artwork and
object names in VectorCraft. The legacy Illustrator 7 export preserves the
rendering and layer names but drops individual object names, so it is not
the preferred interchange copy. Adobe Illustrator opening and resaving have
not yet been verified on this machine.

When contributing from an interchange file, update and verify the editable
master before refreshing derived exports. Avoid independent edits to several
formats that leave them showing different designs.

The neutral PNG bundle contains texture assets, not a complete installable
resource pack. Item definitions, tint configuration, Bedrock mappings and pack
packaging are handled by the resource-pack implementation.

## Small-size editable sources

`adaptations/dash-scroll-16.pcraft` and
`adaptations/smoke-bomb-16.pcraft` contain eight separate SVG Smart Objects per asset, grouped into Base
Artwork and Team Colour Accent, with Base - Pixel Cleanup and Accent -
Pixel Cleanup layers above the source layers.
The accepted 16-pixel base and accent exports are in the production
folders.
On a master change, refresh the derived full SVG in VectorCraft, run
`python artwork/tools/export-svg-layers.py` from the repository root, and
use Replace Contents on each corresponding PhotoCraft Smart Object from
`linked/<asset>/`. Review the pixel cleanup, then export each artwork group
separately. All SVG layer files keep a 64-by-64 artboard and are placed at
25 percent, centred on (8, 8), in the 16-pixel documents.

The current Smart Objects embed the SVG contents; they are not automatic
filesystem links. Replace Contents preserves placement and cleanup.
The `linked/` directory holds refresh sources, with mappings in
`linked/layer-sources.json`. The rear cloth is clipped before export so
hidden binding cannot show through the body. Backups of the earlier
raster-based projects are in `variants/<asset>/before-linked-source/`.

## Portal surface source

`portal-surface-64.vectorcraft` is the editable smoky curtain concept,
with Surface Base, Smoke and Portal Glow layers.
`portal-surface-64.svg` is derived. Portal glow colour is independent of
equipment team colours. The teal preview is in `previews/`.
This surface artwork does not implement an in-game portal renderer.

## Equipment bundle

The 18 current base/accent PNGs are listed in `exports/equipment-manifest.json`,
which records dimensions and SHA-256 hashes. A texture bundle is built from
`exports/` when packing and is not committed; it holds texture assets, not an
installable Java or Bedrock pack.

## Maintenance workflow

The VectorCraft masters are the source of truth; exports and previews are
derived. Never edit an exported PNG directly.

1. Edit the master, then refresh the SVG and the named layer SVGs
   (`tools/export-svg-layers.py`).
2. In PhotoCraft, use Replace Contents on each Smart Object so the pixel
   cleanup layers stay in place, and re-check the 16-pixel result by eye.
3. Re-export the PNG layers, run `node artwork/tools/crisp-16.js` (hard-edges the
   16-pixel layers and refreshes hashes), and update `exports/equipment-manifest.json`.
4. Run `node artwork/tools/validate-exports.js`. It checks hashes, sizes,
   that every asset has base and accent layers at 16, 32 and 64, that accent
   layers are grayscale, and that 16-pixel files have no partial alpha.
5. Commit the source and exports together.

Regenerable review sheets are not committed. Kept as references, and never
packed: `previews/team-colours/`, the portal review images, and the preserved
history under `variants/`. Texture zips are not committed.
