#!/usr/bin/env python3
"""Deterministic, dependency-free validation for the CoreMC ItemsAdder source.

This is intentionally not an ItemsAdder replacement: it catches repository
mistakes before an operator runs ItemsAdder. A real ItemsAdder server is still
required for the final /iareload and /iazip step.
"""
from __future__ import annotations

import hashlib
import json
import re
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "src/main/resources/itemsadder/contents/coremc"
ITEMS = PACK / "configs/items.yml"
ASSET_ROOT = PACK / "resourcepack/assets/coremc"
MODELS = ASSET_ROOT / "models/item"
TEXTURES = ASSET_ROOT / "textures/item"
MANIFEST = ROOT / "src/main/resources/itemsadder/model-ids.yml"
CATALOG = ROOT / "src/main/resources/itemsadder/fishing-catalog.yml"

RANGES = {
    "common": range(20000, 20024),
    "uncommon": range(20100, 20124),
    "rare": range(20200, 20224),
    "epic": range(20300, 20324),
    "mythic": range(20400, 20424),
}


def fail(message: str):
    raise AssertionError(message)


def png_info(path: Path):
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        fail(f"not a PNG: {path}")
    pos = 8
    ihdr = None
    compressed = bytearray()
    while pos < len(data):
        if pos + 12 > len(data):
            fail(f"truncated PNG chunks: {path}")
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        crc = struct.unpack(">I", data[pos + 8 + length:pos + 12 + length])[0]
        if (zlib.crc32(kind + chunk) & 0xffffffff) != crc:
            fail(f"bad PNG CRC in {path}")
        if kind == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", chunk)
        elif kind == b"IDAT":
            compressed.extend(chunk)
        elif kind == b"IEND":
            break
        pos += 12 + length
    if ihdr is None:
        fail(f"PNG has no IHDR: {path}")
    width, height, depth, colour, compression, filtering, interlace = ihdr
    if (width, height, depth, colour, compression, filtering, interlace) != (32, 32, 8, 6, 0, 0, 0):
        fail(f"{path}: expected 32x32 RGBA non-interlaced PNG, got {ihdr}")
    raw = zlib.decompress(bytes(compressed))
    stride = width * 4
    if len(raw) != height * (stride + 1):
        fail(f"{path}: unexpected decompressed PNG length")
    pixels = []
    for y in range(height):
        row = raw[y * (stride + 1):(y + 1) * (stride + 1)]
        if row[0] != 0:
            fail(f"{path}: unsupported non-zero PNG filter in generated asset")
        pixels.extend(tuple(row[i:i + 4]) for i in range(1, len(row), 4))
    opaque = [pixel for pixel in pixels if pixel[3] > 0]
    if not opaque:
        fail(f"{path}: fully transparent texture")
    if any(pixel[:3] == (255, 0, 255) for pixel in opaque):
        fail(f"{path}: purple fallback pixel detected")
    if any(pixel[:3] == (0, 0, 0) for pixel in opaque):
        fail(f"{path}: pure black fallback pixel detected")
    return width, height, hashlib.sha256(data).hexdigest()


def item_blocks(text: str):
    marker = text.index("items:\n")
    body = text[marker + len("items:\n"):]
    matches = list(re.finditer(r"(?m)^  ([a-z][a-z0-9_]*)\:\n", body))
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(body)
        yield match.group(1), body[match.end():end]


def parse_items():
    text = ITEMS.read_text(encoding="utf-8")
    if "namespace: coremc" not in text:
        fail("ItemsAdder namespace is not coremc")
    records = {}
    for item_id, body in item_blocks(text):
        model = re.search(r"^      model_path: \"item/([^\"]+)\"$", body, re.M)
        model_id = re.search(r"^      model_id: (\d+)$", body, re.M)
        material = re.search(r"^      material: ([A-Z0-9_]+)$", body, re.M)
        if not (model and model_id and material):
            fail(f"{item_id}: incomplete ItemsAdder resource definition")
        records[item_id] = {
            "path": model.group(1),
            "model_id": int(model_id.group(1)),
            "material": material.group(1),
        }
    return records


def check_fish(records):
    fish = {rarity: [] for rarity in RANGES}
    for item_id, record in records.items():
        match = re.fullmatch(r"fish_(common|uncommon|rare|epic|mythic)_(.+)", item_id)
        if match:
            fish[match.group(1)].append((item_id, record))
    if sum(map(len, fish.values())) != 120:
        fail(f"expected exactly 120 fishing fish, found {sum(map(len, fish.values()))}")
    ids = set()
    for rarity, entries in fish.items():
        if len(entries) != 24:
            fail(f"{rarity}: expected 24 fish, found {len(entries)}")
        expected = set(RANGES[rarity])
        actual = {record["model_id"] for _, record in entries}
        if actual != expected:
            fail(f"{rarity}: model ids are not the stable range {min(expected)}-{max(expected)}")
        for item_id, record in entries:
            if record["path"] in ids:
                fail(f"duplicate fish model path {record['path']}")
            ids.add(record["path"])
    hashes = set()
    for _item_id, record in sum(fish.values(), []):
        path = TEXTURES / (record["path"] + ".png")
        hashes.add(png_info(path)[2])
    if len(hashes) != 120:
        fail(f"fish sprites are not individually authored: only {len(hashes)} unique PNG hashes")
    return fish


def check_assets(records):
    model_ids = []
    for item_id, record in records.items():
        model_ids.append(record["model_id"])
        model = MODELS / (record["path"] + ".json")
        if not model.is_file():
            fail(f"{item_id}: missing model {model}")
        payload = json.loads(model.read_text(encoding="utf-8"))
        expected_parent = "minecraft:item/handheld" if item_id.startswith("omnitool_") else "minecraft:item/generated"
        if payload.get("parent") != expected_parent:
            fail(f"{item_id}: wrong model parent")
        expected_texture = "coremc:item/" + record["path"]
        if payload.get("textures", {}).get("layer0") != expected_texture:
            fail(f"{item_id}: model texture path mismatch")
        png_info(TEXTURES / (record["path"] + ".png"))
    if len(model_ids) != len(set(model_ids)):
        fail("duplicate custom model IDs in ItemsAdder items")
    # No orphan generated source assets: every generated model has an item entry.
    expected_models = {record["path"] + ".json" for record in records.values()}
    actual_models = {path.relative_to(MODELS).as_posix() for path in MODELS.rglob("*.json")}
    if actual_models != expected_models:
        fail(f"orphan/missing model files: expected {len(expected_models)}, found {len(actual_models)}")
    expected_textures = {record["path"] + ".png" for record in records.values()}
    actual_textures = {path.relative_to(TEXTURES).as_posix() for path in TEXTURES.rglob("*.png")}
    if actual_textures != expected_textures:
        fail(f"orphan/missing texture files: expected {len(expected_textures)}, found {len(actual_textures)}")


def check_java_references(records):
    crates = (ROOT / "src/main/resources/crates.yml").read_text(encoding="utf-8")
    key_section = crates.split("keys:\n", 1)[1].split("\ncrates:\n", 1)[0]
    key_ids = re.findall(r"^  ([a-z][a-z0-9_-]*):\n", key_section, re.M)
    # Existing crate key ids are compatibility-sensitive. The five new named
    # keys plus all old key ids must have a visual alias.
    for key_id in key_ids:
        if key_id == "ember":
            visual_id = "ember_key"
        elif key_id == "rune":
            visual_id = "rune_key"
        elif key_id == "titan":
            visual_id = "titan_key"
        elif key_id == "mythic":
            visual_id = "mythic_key"
        elif key_id == "daily":
            visual_id = "daily_key"
        else:
            visual_id = key_id + "_key"
        if visual_id not in records:
            fail(f"Java/config crate key lookup {key_id} has no ItemsAdder visual alias")
    for role in ("miner", "farmer", "fisher", "slayer", "logger", "universal"):
        item_id = "omnitool_" + role
        if item_id not in records:
            fail(f"Role {role} has no distinct OmniTool model")
    config = (ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8")
    generators = config.split("generators:\n", 1)[1].split("\n# --------------------------------------------------------------------------\n# SPAWNERS", 1)[0]
    generator_ids = re.findall(r"^  ([a-z][a-z0-9_-]*):\n", generators, re.M)
    if set(generator_ids) != {"cobble", "obsidian", "ice", "quartz"}:
        fail(f"unexpected generator catalog while validating: {generator_ids}")
    # The existing Java service still uses vanilla generator block material and
    # placeable PDC; no visual asset is allowed to masquerade as its identity.
    java = "\n".join(path.read_text(encoding="utf-8") for path in (ROOT / "src/main/java").rglob("*.java"))
    if "new NamespacedKey(plugin, \"placeable\")" not in java:
        fail("placeable PDC identity was not found; fallback safety cannot be proven")
    if "new NamespacedKey(plugin, \"crate-key\")" not in java:
        fail("crate-key PDC identity was not found; fallback safety cannot be proven")
    for model_id in (21000, 21001, 21002, 21003, 21004, 21005, 21006, 21007, 21008, 21009,
                     21400, 21401, 21402, 21403, 21404, 21405):
        if str(model_id) not in java:
            fail(f"Java visual model id {model_id} is missing from the source")


def main():
    for path in (ITEMS, MANIFEST, CATALOG):
        if not path.is_file():
            fail(f"missing required source file {path}")
    records = parse_items()
    fish = check_fish(records)
    check_assets(records)
    check_java_references(records)
    catalog_text = CATALOG.read_text(encoding="utf-8")
    for rarity in RANGES:
        if len(re.findall(rf"^  {rarity}:$", catalog_text, re.M)) != 1:
            fail(f"fishing catalog missing {rarity} section")
        if len(re.findall(rf"^      - id: fish_{rarity}_", catalog_text, re.M)) != 24:
            fail(f"fishing catalog {rarity} count mismatch")
    print(f"PASS ItemsAdder source: {len(records)} item definitions, 120 fish (24 x 5), {len(records)} models, {len(records)} textures")
    print("PASS stable model IDs: unique, range-checked, and no orphan mappings")
    print("PASS PNGs: valid 32x32 RGBA, non-empty, no purple/black fallback pixels")
    print("PASS Java/config compatibility: existing crate key lookups and role tool identities covered")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, FileNotFoundError, json.JSONDecodeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
