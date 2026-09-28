#!/usr/bin/env python3
"""Generate the CoreMC animated tool-skin & hat ItemsAdder assets.

Adds to the EXISTING `coremc` ItemsAdder namespace (never a second plugin,
JAR or pack): 30 animated 3D tool skins (5 collections x 6 OmniTool roles)
and 3 animated wearable 3D hats, all with original artwork.

Outputs (all inside src/main/resources/itemsadder/):
  contents/coremc/configs/skins-items.yml    — ItemsAdder item definitions
  contents/coremc/resourcepack/assets/coremc/models/item/skins/...   — 3D models
  contents/coremc/resourcepack/assets/coremc/textures/item/skins/... — animated
      texture strips + .mcmeta flipbook metadata (client-side loops)
  ../../skins.yml (plugin data folder bundle) — the plugin-side catalog:
      stable ids, model ids, materials, unlock sources, season policy

Also keeps model-ids.yml (the stable CMD allocation manifest) and
skin-registry.yml (the cosmetic contract) up to date without ever
renumbering or deleting anything another generator owns.

Run from the repository root:  python3 tools/generate_skin_assets.py
"""
from __future__ import annotations

import json
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import skin_art  # noqa: E402
import skin_models  # noqa: E402

PACK = ROOT / "src/main/resources/itemsadder"
CONTENTS = PACK / "contents/coremc"
ASSETS = CONTENTS / "resourcepack/assets/coremc"
MODEL_DIR = ASSETS / "models/item/skins"
TEXTURE_DIR = ASSETS / "textures/item/skins"
ITEMS_FILE = CONTENTS / "configs/skins-items.yml"
MANIFEST = PACK / "model-ids.yml"
SKIN_REGISTRY = PACK / "skin-registry.yml"
PLUGIN_SKINS = ROOT / "src/main/resources/skins.yml"
README = PACK / "README.md"

# ---- stable model-id allocation (see model-ids.yml) --------------------
TOOL_SKIN_IDS = {  # (collection, role) -> CMD id
    (col, role): 21600 + i
    for i, (col, role) in enumerate(
        (col, role)
        for col in ("emberforge", "riftbound", "astral", "tidecaller", "overgrown")
        for role in skin_models.ROLE_ORDER
    )
}
HAT_SKIN_IDS = {"ember_crown": 21630, "rift_halo": 21631, "moonlit_cap": 21632}

TOOL_MATERIAL = "NETHERITE_PICKAXE"  # the OmniTool base material
HAT_MATERIAL = "CARVED_PUMPKIN"      # vanilla fallback: pumpkin-on-head

COLLECTION_IDS = {
    "emberforge": "emberforge",
    "riftbound": "riftbound",
    "astral": "astral",
    "tidecaller": "tidecaller",
    "overgrown": "overgrown",
}

# Default unlock sources (config-editable; drives the season-reset policy).
DEFAULT_SOURCES = {
    "emberforge": "crate:ember",
    "riftbound": "crate:rune",
    "astral": "store",
    "tidecaller": "crate:daily",
    "overgrown": "event",
}


# ----------------------------------------------------------------------
#  PNG writers (standard library only, same style as the fish generator)
# ----------------------------------------------------------------------

def _chunk(kind: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + kind
        + data
        + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    )


def write_png(path: Path, width: int, height: int, rgba_rows: list[bytes]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = b"".join(b"\x00" + row for row in rgba_rows)
    data = b"\x89PNG\r\n\x1a\n"
    data += _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    data += _chunk(b"IDAT", zlib.compress(raw, 9))
    data += _chunk(b"IEND", b"")
    path.write_bytes(data)


def write_texture_strip(key: str) -> dict[str, object]:
    """Stacks the animation frames vertically and writes strip + .mcmeta."""
    painter, frames, frametime, _desc = skin_art.TEXTURES[key]
    frame_rows = [painter(t, frames).frame_bytes() for t in range(frames)]
    width = skin_art.SIZE
    rows: list[bytes] = []
    for frame in frame_rows:
        for y in range(skin_art.SIZE):
            rows.append(frame[y * width * 4:(y + 1) * width * 4])
    out = TEXTURE_DIR / f"{key}.png"
    write_png(out, width, len(rows), rows)
    mcmeta = {
        "animation": {
            "frametime": frametime,
            "frames": list(range(frames)),
        }
    }
    (TEXTURE_DIR / f"{key}.png.mcmeta").write_text(json.dumps(mcmeta, indent=2) + "\n")
    return {"path": f"item/skins/{key}", "frames": frames, "frametime": frametime}


# ----------------------------------------------------------------------
#  model + item writers
# ----------------------------------------------------------------------

def write_tool_model(collection: str, role: str) -> str:
    model = skin_models.build_tool_model(collection, role)
    rel = f"skins/tools/{collection}/{role}"
    path = MODEL_DIR / f"tools/{collection}/{role}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(model, indent=2) + "\n")
    return rel


def write_hat_model(hat_id: str) -> str:
    model = skin_models.build_hat_model(hat_id)
    rel = f"skins/hats/{hat_id}"
    path = MODEL_DIR / f"hats/{hat_id}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(model, indent=2) + "\n")
    return rel


def display_name(collection: str, role: str) -> str:
    col = skin_models.COLLECTIONS[collection]["display"]
    return f"{col} {skin_models.ROLE_DISPLAY[role]} Tool Skin".replace("&6", "").replace("&5", "").replace(
        "&b", "").replace("&3", "").replace("&2", "").replace("&e", "").replace("&c", "").replace(
        "&a", "").replace("&d", "").replace("&6", "")


def plain(text: str) -> str:
    for code in ("&0", "&1", "&2", "&3", "&4", "&5", "&6", "&7", "&8", "&9",
                 "&a", "&b", "&c", "&d", "&e", "&f", "&l", "&o", "&n", "&r"):
        text = text.replace(code, "")
    return text


def write_items_yml() -> None:
    lines = [
        "# CoreMC animated skin items (ItemsAdder source, namespace coremc).",
        "# Generated by tools/generate_skin_assets.py — stable model ids from",
        "# itemsadder/model-ids.yml.  Every entry keeps a vanilla material",
        "# fallback so a missing pack never invalidates a held item.",
        "info:",
        "  namespace: coremc",
        "items:",
    ]
    for (collection, role), model_id in TOOL_SKIN_IDS.items():
        col = skin_models.COLLECTIONS[collection]
        lines += [
            f"  tool_skin_{collection}_{role}:",
            f'    display_name: "{plain(col["display"])} {plain(skin_models.ROLE_DISPLAY[role])} Tool Skin"',
            "    lore:",
            f'      - "&8CoreMC animated tool skin"',
            f'      - "&7{col["lore"]}"',
            f'      - "&7Role: {plain(skin_models.ROLE_DISPLAY[role])}"',
            "    resource:",
            f"      material: {TOOL_MATERIAL}",
            "      generate: false",
            f"      model_path: \"item/skins/tools/{collection}/{role}\"",
            f"      model_id: {model_id}",
            "",
        ]
    for hat_id, model_id in HAT_SKIN_IDS.items():
        hat = skin_models.HATS[hat_id]
        lines += [
            f"  hat_skin_{hat_id}:",
            f'    display_name: "{plain(hat["display"])}"',
            "    lore:",
            '      - "&8CoreMC animated hat"',
            f'      - "&7{hat["lore"]}"',
            "    resource:",
            f"      material: {HAT_MATERIAL}",
            "      generate: false",
            f"      model_path: \"item/skins/hats/{hat_id}\"",
            f"      model_id: {model_id}",
            "",
        ]
    ITEMS_FILE.parent.mkdir(parents=True, exist_ok=True)
    ITEMS_FILE.write_text("\n".join(lines))


# ----------------------------------------------------------------------
#  manifest merge (model-ids.yml) — additive only
# ----------------------------------------------------------------------

def merge_manifest() -> None:
    text = MANIFEST.read_text()
    if "tool_skin:" not in text:
        text = text.replace(
            "  skin:\n    start: 21500\n    end: 21512\n",
            "  skin:\n    start: 21500\n    end: 21512\n"
            "  tool_skin:\n    start: 21600\n    end: 21629\n"
            "  hat_skin:\n    start: 21630\n    end: 21632\n",
        )
    # drop any previously generated per-item block, then append fresh
    marker = "  # --- generated: animated skin items (tools/generate_skin_assets.py) ---"
    if marker in text:
        text = text[: text.index(marker)]
    text = text.rstrip("\n") + "\n"
    text += marker + "\n"
    for (collection, role), model_id in TOOL_SKIN_IDS.items():
        text += (
            f"  tool_skin_{collection}_{role}:\n"
            f"    model_id: {model_id}\n"
            f"    model_path: skins/tools/{collection}/{role}\n"
            f"    fallback_material: {TOOL_MATERIAL}\n"
            f"    category: tool_skin\n"
        )
    for hat_id, model_id in HAT_SKIN_IDS.items():
        text += (
            f"  hat_skin_{hat_id}:\n"
            f"    model_id: {model_id}\n"
            f"    model_path: skins/hats/{hat_id}\n"
            f"    fallback_material: {HAT_MATERIAL}\n"
            f"    category: hat_skin\n"
        )
    MANIFEST.write_text(text)


def merge_skin_registry() -> None:
    marker = "# ==== animated tool skins + hats (tools/generate_skin_assets.py) ===="
    text = SKIN_REGISTRY.read_text()
    if marker in text:
        text = text[: text.index(marker)]
    text = text.rstrip("\n") + "\n\n" + marker + "\n"
    text += (
        "tool_skin_contract:\n"
        "  pdc_key: coremc:tool_skin\n"
        "  cosmetic_only: true\n"
        "  stat_effect: none\n"
        "  preserves: [damage, enchants, upgrades, levels, role, pdc_identity]\n"
        "  selection_rule: custom model layer over the soulbound OmniTool only;\n"
        "    role must match the tool binding, ownership lives in the profile\n"
        "  fallback: vanilla NETHERITE_PICKAXE with role lore when pack missing\n"
        "hat_contract:\n"
        "  mechanism: item_display passenger overlay (HEAD transform)\n"
        "  preserves: real helmet slot item and all armour attributes\n"
        "  fallback: vanilla CARVED_PUMPKIN appearance when pack missing\n"
        "tool_skins:\n"
    )
    for (collection, role), model_id in TOOL_SKIN_IDS.items():
        text += (
            f"  {collection}_{role}:\n"
            f"    item_id: tool_skin_{collection}_{role}\n"
            f"    model_id: {model_id}\n"
            f"    applies_to: omnitool:{role}\n"
            f"    cosmetic_only: true\n"
        )
    text += "hat_skins:\n"
    for hat_id, model_id in HAT_SKIN_IDS.items():
        text += (
            f"  {hat_id}:\n"
            f"    item_id: hat_skin_{hat_id}\n"
            f"    model_id: {model_id}\n"
            f"    applies_to: hat\n"
            f"    cosmetic_only: true\n"
        )
    SKIN_REGISTRY.write_text(text)


# ----------------------------------------------------------------------
#  plugin-side catalog: skins.yml (bundled into the plugin jar)
# ----------------------------------------------------------------------

def write_plugin_catalog(texture_meta: dict[str, dict[str, object]]) -> None:
    lines = [
        "# ======================================================",
        "#  CoreMC — animated skins catalogue",
        "#  Generated by tools/generate_skin_assets.py; edit freely,",
        "#  values survive plugin updates (merge-preserved on disk).",
        "#  Stable ids live here AND in player profiles — never rename.",
        "# ======================================================",
        "",
        "skins:",
        "  # season reset policy: which unlock sources survive a season",
        "  # reset.  'all' keeps everything (default — purchased skins are",
        "  # never wiped), a list keeps only those sources.",
        "  season-reset-keep-sources: all",
        "  # never wipe purchased ownership: sources listed here are always",
        "  # kept regardless of the policy above.",
        "  always-keep-sources: [store, crate]",
        "  hats:",
        "    # worn via a client-visible overlay entity; translation/scale",
        "    # are per-hat and tunable in blocks (16 = one block).",
        "    overlay-translation: [0, 0, 0]",
        "    overlay-scale: 1.0",
        "",
        "  collections:",
    ]
    for collection in COLLECTION_IDS:
        col = skin_models.COLLECTIONS[collection]
        metal_key = col["textures"]["#metal"].replace("coremc:item/skins/", "")
        lines += [
            f"    {collection}:",
            f'      display: "{col["display"]}"',
            f'      description: "&7{col["lore"]}"',
            f'      filter-icon: {col["filter_material"]}',
            f'      source: "{DEFAULT_SOURCES[collection]}"',
            "      tool-skins:",
        ]
        for role in skin_models.ROLE_ORDER:
            model_id = TOOL_SKIN_IDS[(collection, role)]
            lines += [
                f"        {collection}_{role}:",
                f'          display: "{col["display"]} {skin_models.ROLE_DISPLAY[role]}"',
                f"          role: {role}",
                f"          model-id: {model_id}",
                f"          material: {TOOL_MATERIAL}",
                f'          model-path: "coremc:item/skins/tools/{collection}/{role}"',
                f'          texture: "coremc:item/skins/{metal_key}"',
                f'          animation-frames: {texture_meta[metal_key]["frames"]}',
                "",
            ]
    lines += [
        "  hat-skins:",
    ]
    for hat_id, model_id in HAT_SKIN_IDS.items():
        hat = skin_models.HATS[hat_id]
        lines += [
            f"    {hat_id}:",
            f'      display: "{hat["display"]}"',
            f'      description: "&7{hat["lore"]}"',
            f"      model-id: {model_id}",
            f"      material: {HAT_MATERIAL}",
            f'      model-path: "coremc:item/skins/hats/{hat_id}"',
            f'      source: "{DEFAULT_SOURCES[hat["collection"]] if hat["collection"] in DEFAULT_SOURCES else "event"}"',
            f'      overlay-translation: [0, 0.02, 0]',
            f"      overlay-scale: 1.0",
            "",
        ]
    PLUGIN_SKINS.write_text("\n".join(lines) + "\n")


def main() -> None:
    texture_meta: dict[str, dict[str, object]] = {}
    for key in skin_art.TEXTURES:
        texture_meta[key] = write_texture_strip(key)
    model_paths = []
    for (collection, role) in TOOL_SKIN_IDS:
        model_paths.append(write_tool_model(collection, role))
    for hat_id in HAT_SKIN_IDS:
        model_paths.append(write_hat_model(hat_id))
    write_items_yml()
    merge_manifest()
    merge_skin_registry()
    write_plugin_catalog(texture_meta)
    print(
        f"skin assets: {len(skin_art.TEXTURES)} animated textures, "
        f"{len(model_paths)} models ({len(TOOL_SKIN_IDS)} tool skins, {len(HAT_SKIN_IDS)} hats), "
        f"ids {min(TOOL_SKIN_IDS.values())}-{max(HAT_SKIN_IDS.values())}"
    )


if __name__ == "__main__":
    main()
