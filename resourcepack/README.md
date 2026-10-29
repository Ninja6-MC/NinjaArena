# Java resource pack sources

Sources for the ninja tool item models. Pack delivery, `pack.mcmeta`, the
pack format and hashes, and the Bedrock packs belong to the pack build and are
not here yet.

```
resourcepack/java/
  standard/assets/ninjaarena/{items,models/item,textures/item}   16x16 base pack
  detailed/assets/ninjaarena/textures/item                      32x32 overlay
```

The Detailed overlay holds textures only, at the same paths and with the same
identifiers, so it is loaded above the base to switch quality. Models and item
definitions live once, in the Standard pack.

## Items

| Tool | Item definition | Textures |
|---|---|---|
| Shuriken | `ninjaarena:shuriken` | `shuriken`, `shuriken_accent` |
| Smoke bomb | `ninjaarena:smoke_bomb` | `smoke_bomb`, `smoke_bomb_accent` |
| Dash | `ninjaarena:dash` | `dash`, `dash_accent` |

Each model is a flat `item/generated` model with the fixed artwork on
`layer0` and the grayscale accent on `layer1`. The item definition tints only
`layer1`, from the first colour of the item's `custom_model_data` colours, so
the plugin sets the team colour there; `layer0` is never tinted. A tool is
still recognised only by its `ninjaarena:tool` data tag, never by model.

## Held and thrown

Item models are not shown to be enough for projectiles. The held and thrown
looks of the shuriken and smoke bomb must each be checked in game before this
is final; see #65.

## Not verified yet

These files have not been loaded in a client, and the pack format for the
target Minecraft version is not set. The textures are copied from
`artwork/exports` by `artwork/tools/sync-pack-textures.js`; do not edit them
here.

## Checks

```
node artwork/tools/sync-pack-textures.js   # refresh textures from the exports
node artwork/tools/validate-pack.js        # references and texture sync
```
