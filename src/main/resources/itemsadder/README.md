# CoreMC ItemsAdder source pack

This is the single CoreMC namespace source pack. There is no second plugin,
JAR, or replacement resource pack. Copy only `contents/coremc` into the
existing ItemsAdder `contents/` directory on a staging/production server.

## Contents

- `contents/coremc/configs/items.yml` — 170 ItemsAdder item definitions: exactly 120
  fishing species (24 each of Common, Uncommon, Rare, Epic, and Mythic), plus
  keys, crate/lootbox icons, progression materials, OmniTool role models, and
  cosmetic skin tokens.
- `contents/coremc/configs/skins-items.yml` — 33 animated skin items (30 tool
  skins + 3 hats), all with `generate: false` (explicit 3D geometry) and
  vanilla-material fallbacks.
- `contents/coremc/resourcepack/assets/coremc/textures/item/` — original,
  deterministic 32x32 RGBA pixel sprites. Fish use silhouette, fin/tail
  signature, markings, and rarity detail; they are not recoloured copies.
- `contents/coremc/resourcepack/assets/coremc/models/item/` — one explicit
  model per item, with stable texture paths.
- `fishing-catalog.yml` — an auditable 24-by-5 species catalog.
- `model-ids.yml` — the stable, non-overlapping custom model-data allocation.
- `skin-registry.yml` — the cosmetic-only generator/companion skin contract.
- `contents/coremc/resourcepack/assets/coremc/textures/item/skins/` — 22 original
  animated texture strips (16/32px frames, 8 frames each) with `.png.mcmeta`
  flipbook metadata. Client-side animation only: the server never swaps items
  or spawns entities to animate.
- `contents/coremc/resourcepack/assets/coremc/models/item/skins/` — 33 explicit
  3D models. The six role silhouettes are genuinely distinct geometries (pick,
  axe, rod, blade, scythe/hoe, multi-tool), not one model recoloured.
- `skins.yml` (plugin resource, next to `plugin.yml`) — the plugin-side skin
  catalogue: stable ids, model ids, unlock sources and the season-reset policy.
  Live at `plugins/CoreMC/skins.yml`; edits survive updates (merge-preserved).

The CoreMC Java service remains the source of truth for PDC identity. The
resource pack changes presentation only. All definitions retain a vanilla
material fallback, so a missing pack cannot invalidate or make an existing
player item unrecognisable.

## Deterministic repository validation

From the repository root:

```bash
python3 tools/generate_itemsadder_assets.py
python3 tools/validate_itemsadder_assets.py
```

The validator checks the namespace, 24-by-5 fish counts, unique item/model
IDs, every model and texture path, JSON mappings, all PNG signatures/CRCs and
32x32 dimensions, non-empty sprites, purple/black fallback pixels, unique fish
hashes, and compatibility aliases for the existing Java/config crate keys and
six OmniTool roles. It also validates the 33 animated skins: the exact
21600-21632 id allocation, real 3D geometry (no flat parents), coremc-namespaced
texture refs, and animation strips with valid `.png.mcmeta` flipbooks.

For the deeper skin pipeline (per-frame motion proofs, geometry distinctness,
catalogue agreement, built-pack dispatch coverage) run
`python3 tools/validate_skin_assets.py`, and `python3 tools/build_pack.py`
to produce `dist/CoreMC-ResourcePack.zip` — a ready-to-serve pack covering
1.21 through 1.21.11 (dual dispatch: legacy `CustomModelData` predicates for
1.20.x-1.21.3 and `assets/minecraft/items/*.json` range-dispatch for
1.21.4+). ItemsAdder's own `/iazip` produces the equivalent pack from the
same source files; the built zip is the CI-verifiable artifact.

A real ItemsAdder binary/server was not available in this repository session,
so the validator is deterministic repository validation, not a claim of a live
ItemsAdder build.

## Manual ItemsAdder build/reload

After building CoreMC, install the existing CoreMC plugin as usual and copy
this namespace into the existing ItemsAdder installation:

```bash
mvn -B verify
cp -R target/classes/itemsadder/contents/coremc \
  /path/to/server/plugins/ItemsAdder/contents/
# On the running Paper server, in the documented ItemsAdder order:
/iareload
/iazip
# Then let clients accept the server resource pack (or use /iazip's URL).
```

If the server keeps a source checkout instead of the Maven classes, copy
`src/main/resources/itemsadder/contents/coremc` instead. Keep the namespace
named `coremc`; do not create a second ItemsAdder namespace or plugin. Verify
in the ItemsAdder console that its generated pack reports the `coremc`
namespace and no missing model/texture warnings. Reload CoreMC only if its
configuration changed; a resource-only update needs the ItemsAdder reload and
zip steps above.
