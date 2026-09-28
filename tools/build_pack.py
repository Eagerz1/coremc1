#!/usr/bin/env python3
"""Build the CoreMC resource pack from the ItemsAdder namespace source.

This mirrors what ItemsAdder's own pack build (/iazip) produces for the
`coremc` namespace, so the pack can be verified, hosted and offered to
clients even before the ItemsAdder plugin performs its own build:

  * copies `contents/*/resourcepack/**` verbatim (models, textures, mcmeta)
  * generates the 1.21.4+ item model definitions
    `assets/minecraft/items/<material>.json` — a range_dispatch on
    custom_model_data floats[0] covering every coremc model id for that
    material with a vanilla fallback (exactly the legacy-CMD compatibility
    layer ItemsAdder generates for 1.21.4+)
  * generates the pre-1.21.4 legacy override models
    `assets/minecraft/models/item/<material>.json` (custom_model_data
    predicates) so 1.21-1.21.3 clients keep working from the same zip
  * writes a multi-version pack.mcmeta (loads on 1.21 through 1.21.11)

Integer CustomModelData is used everywhere on purpose: with the pack the
custom models render; without the pack every item keeps its vanilla
appearance (no missing-model fallback risk).

Usage:  python3 tools/build_pack.py [output.zip]
"""
from __future__ import annotations

import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "src/main/resources/itemsadder"
CONTENTS = PACK / "contents"
MANIFEST = PACK / "model-ids.yml"
DEFAULT_OUT = ROOT / "dist/CoreMC-ResourcePack.zip"

# 1.21 = 34, 1.21.2-3 = 42, 1.21.4 = 46, 1.21.5 = 55, 1.21.6 = 63,
# 1.21.7-8 = 64, 1.21.9-10 = 69, 1.21.11 = 75
MIN_FORMAT = 34
MAX_FORMAT = 75
PACK_FORMAT = 75
DESCRIPTION = "§bCoreMC §7— custom items & animated skins §8[coremc namespace]"


def parse_manifest() -> list[dict[str, str]]:
    """Parses the flat `  item:` / `    key: value` structure of
    model-ids.yml (generated, deterministic — a tiny reader is enough)."""
    items: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    for raw in MANIFEST.read_text().splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if raw.startswith("  ") and not raw.startswith("    ") and raw.rstrip().endswith(":"):
            current = {"id": raw.strip()[:-1]}
            items.append(current)
        elif raw.startswith("    ") and current is not None:
            key, _, value = raw.strip().partition(":")
            current[key.strip()] = value.strip()
    return [i for i in items if "model_id" in i and "model_path" in i]


def build_dispatches(items: list[dict[str, str]]) -> dict[Path, str]:
    """One 1.21.4+ item definition + one legacy override per material."""
    by_material: dict[str, list[dict[str, str]]] = {}
    for item in items:
        by_material.setdefault(item["fallback_material"].lower(), []).append(item)
    files: dict[Path, str] = {}
    for material, entries in sorted(by_material.items()):
        entries = sorted(entries, key=lambda e: int(e["model_id"]))
        # --- 1.21.4+ item model definition (range_dispatch on floats[0])
        cases = []
        for entry in entries:
            cases.append({
                "threshold": float(int(entry["model_id"])),
                "model": {
                    "type": "minecraft:model",
                    "model": f"coremc:item/{entry['model_path'].removeprefix('item/')}",
                },
            })
        definition = {
            "model": {
                "type": "minecraft:range_dispatch",
                "property": "minecraft:custom_model_data",
                "index": 0,
                "scale": 1.0,
                "entries": cases,
                "fallback": {
                    "type": "minecraft:model",
                    "model": f"minecraft:item/{material}",
                },
            },
        }
        files[Path(f"assets/minecraft/items/{material}.json")] = json.dumps(definition, indent=2) + "\n"
        # --- pre-1.21.4 legacy override model
        handheld = material in {
            "netherite_pickaxe", "diamond_pickaxe", "iron_pickaxe", "golden_pickaxe",
            "wooden_sword", "stone_sword", "iron_sword", "diamond_sword", "netherite_sword",
            "golden_sword", "iron_sword", "iron_axe", "diamond_axe", "netherite_axe",
            "wooden_hoe", "stone_hoe", "iron_hoe", "diamond_hoe", "netherite_hoe",
            "fishing_rod", "shears", "trident",
        }
        legacy = {
            "parent": "minecraft:item/handheld" if handheld else "minecraft:item/generated",
            "textures": {"layer0": f"minecraft:item/{material}"},
            "overrides": [
                {
                    "predicate": {"custom_model_data": int(entry["model_id"])},
                    "model": f"coremc:item/{entry['model_path'].removeprefix('item/')}",
                }
                for entry in entries
            ],
        }
        files[Path(f"assets/minecraft/models/item/{material}.json")] = json.dumps(legacy, indent=2) + "\n"
    return files


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    items = parse_manifest()
    if not items:
        raise SystemExit("no items parsed from model-ids.yml")
    dispatches = build_dispatches(items)

    pack_mcmeta = {
        "pack": {
            "pack_format": PACK_FORMAT,
            "supported_formats": {"min_inclusive": MIN_FORMAT, "max_inclusive": MAX_FORMAT},
            "min_format": MIN_FORMAT,
            "max_format": MAX_FORMAT,
            "description": DESCRIPTION,
        }
    }

    out.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        zf.writestr("pack.mcmeta", json.dumps(pack_mcmeta, indent=2) + "\n")
        for namespace_dir in sorted(CONTENTS.iterdir()):
            rp = namespace_dir / "resourcepack"
            if not rp.is_dir():
                continue
            for path in sorted(rp.rglob("*")):
                if path.is_file():
                    rel = path.relative_to(rp)
                    zf.write(path, str(rel))
                    count += 1
        for rel, content in sorted(dispatches.items()):
            zf.writestr(str(rel), content)
            count += 1
    materials = len({i["fallback_material"].lower() for i in items})
    print(
        f"pack built: {out} ({out.stat().st_size} bytes, {count} files, "
        f"{len(items)} model ids across {materials} materials, "
        f"formats {MIN_FORMAT}-{MAX_FORMAT})"
    )


if __name__ == "__main__":
    main()
