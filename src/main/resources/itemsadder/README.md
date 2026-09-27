# CoreMC ItemsAdder source pack

This is the single CoreMC namespace source pack. There is no second plugin,
JAR, or replacement resource pack. Copy only `contents/coremc` into the
existing ItemsAdder `contents/` directory on a staging/production server.

## Contents

- `contents/coremc/configs/items.yml` — 170 ItemsAdder item definitions: exactly 120
  fishing species (24 each of Common, Uncommon, Rare, Epic, and Mythic), plus
  keys, crate/lootbox icons, progression materials, OmniTool role models, and
  cosmetic skin tokens.
- `contents/coremc/resourcepack/assets/coremc/textures/item/` — original,
  deterministic 32x32 RGBA pixel sprites. Fish use silhouette, fin/tail
  signature, markings, and rarity detail; they are not recoloured copies.
- `contents/coremc/resourcepack/assets/coremc/models/item/` — one explicit
  model per item, with stable texture paths.
- `fishing-catalog.yml` — an auditable 24-by-5 species catalog.
- `model-ids.yml` — the stable, non-overlapping custom model-data allocation.
- `skin-registry.yml` — the cosmetic-only generator/companion skin contract.

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
six OmniTool roles.

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
