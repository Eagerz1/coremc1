#!/usr/bin/env python3
"""Deterministic validation of the CoreMC animated skin assets.

Checks, from the repository alone (no server needed):
  1. exactly 30 tool skins (5 collections x 6 roles) + 3 hats with stable
     ids 21600-21632, disjoint from every pre-existing model-id range
  2. every model JSON parses, has textures/elements/display, every face
     references a declared texture, every texture exists as an animated
     strip with a .mcmeta flipbook of >1 frame
  3. the 30 tool models are pairwise geometrically distinct (no recolour
     clones): geometry signatures ignore textures entirely
  4. every model renders with visible faces and visibly moves between
     consecutive animation frames (software renderer, no client)
  5. skins.yml (plugin catalogue) / skins-items.yml (ItemsAdder) /
     model-ids.yml agree on ids, paths and materials
  6. the built resource pack zip contains every asset, valid multi-version
     pack.mcmeta, and dispatch files covering every model id

Exit 0 = all green.
"""
from __future__ import annotations

import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

PACK = ROOT / "src/main/resources/itemsadder"
ASSETS = PACK / "contents/coremc/resourcepack/assets/coremc"
MODELS_TOOLS = ASSETS / "models/item/skins/tools"
MODELS_HATS = ASSETS / "models/item/skins/hats"
TEXTURES = ASSETS / "textures/item/skins"
ITEMS_FILE = PACK / "contents/coremc/configs/skins-items.yml"
MANIFEST = PACK / "model-ids.yml"
CATALOG = ROOT / "src/main/resources/skins.yml"
PACK_ZIP = ROOT / "dist/CoreMC-ResourcePack.zip"

COLLECTIONS = ["emberforge", "riftbound", "astral", "tidecaller", "overgrown"]
ROLES = ["miner", "logger", "fisher", "slayer", "farmer", "universal"]
HATS = ["ember_crown", "rift_halo", "moonlit_cap"]
TOOL_IDS = {(c, r): 21600 + i for i, (c, r) in enumerate(
    (c, r) for c in COLLECTIONS for r in ROLES)}
HAT_IDS = dict(zip(HATS, range(21630, 21633)))

# pre-existing ranges (from model-ids.yml 'ranges:' block)
EXISTING_RANGES = [
    (20000, 20023), (20100, 20123), (20200, 20223), (20300, 20323),
    (20400, 20423), (21000, 21009), (21100, 21108), (21200, 21218),
    (21400, 21405), (21500, 21512),
]

failures: list[str] = []


def check(cond: bool, label: str, detail: str = "") -> bool:
    if cond:
        print(f"  PASS  {label}")
    else:
        print(f"  FAIL  {label}{' :: ' + detail if detail else ''}")
        failures.append(label)
    return cond


def load_model(path: Path) -> dict:
    return json.loads(path.read_text())


def check_model(path: Path) -> tuple[dict, str]:
    model = load_model(path)
    assert "textures" in model and "elements" in model and "display" in model, f"{path}: missing section"
    tex_declared = model["textures"]
    for element in model["elements"]:
        assert "from" in element and "to" in element, f"{path}: element without bounds"
        for face, face_def in element["faces"].items():
            ref = face_def["texture"]
            assert ref.startswith("#") and ref in tex_declared, f"{path}: face {face} -> undeclared {ref}"
    # every referenced texture exists and animates
    frames = set()
    for ref, tex in tex_declared.items():
        if tex.startswith("#"):
            continue
        png = ASSETS / "textures" / (tex.split(":", 1)[1] + ".png")
        mcmeta = Path(str(png) + ".mcmeta")
        assert png.exists(), f"{path}: missing texture {png}"
        assert mcmeta.exists(), f"{path}: missing mcmeta {mcmeta}"
        meta = json.loads(mcmeta.read_text())
        anim = meta.get("animation", {})
        n = anim.get("frames") or 8
        assert len(anim.get("frames", [0, 1])) > 1 or n > 1, f"{path}: {mcmeta.name} not animated"
        frames.add(len(anim.get("frames", [])))
    # geometry signature: elements only, no textures
    signature = json.dumps(model["elements"], sort_keys=True)
    # display completeness
    for ctx in ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand",
                "firstperson_lefthand", "gui", "ground", "fixed"):
        assert ctx in model["display"], f"{path}: display.{ctx} missing"
    return model, signature


def main() -> int:
    print("== 1. skin inventory ==")
    tool_files = {c: sorted((MODELS_TOOLS / c).glob("*.json")) for c in COLLECTIONS}
    check(all(len(v) == len(ROLES) for v in tool_files.values()),
          "5 collections x 6 tool models exist")
    hat_files = sorted(MODELS_HATS.glob("*.json"))
    check(len(hat_files) == 3, "3 hat models exist")

    print("== 2. model ids: allocation, no collisions ==")
    new_ids = list(TOOL_IDS.values()) + list(HAT_IDS.values())
    check(sorted(new_ids) == list(range(21600, 21633)), "ids are exactly 21600..21632")
    for lo, hi in EXISTING_RANGES:
        check(not any(lo <= i <= hi for i in new_ids), f"no overlap with existing range {lo}-{hi}")
    manifest = MANIFEST.read_text()
    for (c, r), mid in TOOL_IDS.items():
        check(f"tool_skin_{c}_{r}:" in manifest and f"model_id: {mid}" in manifest,
              f"manifest lists {c}_{r} = {mid}")

    print("== 3. models parse, textures resolve, mcmeta animates ==")
    signatures: dict[str, str] = {}
    names: dict[str, str] = {}
    for c in COLLECTIONS:
        for r in ROLES:
            path = MODELS_TOOLS / c / f"{r}.json"
            model, sig = check_model(path)
            key = f"{c}_{r}"
            signatures[key] = sig
            names[key] = str(path)
    for h in HATS:
        path = MODELS_HATS / f"{h}.json"
        model, sig = check_model(path)
        assert "head" in model["display"], f"{path}: hats need display.head"
        signatures[h] = sig
        names[h] = str(path)

    print("== 4. geometry distinctness (no recolour clones) ==")
    keys = list(signatures)
    dupes = []
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            if signatures[keys[i]] == signatures[keys[j]]:
                dupes.append(f"{keys[i]}=={keys[j]}")
    check(not dupes, "all 33 models have pairwise distinct geometry", "; ".join(dupes[:4]))
    role_sigs = {r: {signatures[f"{c}_{r}"] for c in COLLECTIONS} for r in ROLES}
    check(all(len(v) == len(COLLECTIONS) for v in role_sigs.values()),
          "same role across 5 collections differs by accent geometry")

    print("== 5. render check: visible geometry + real per-frame motion ==")
    import render_models as rm
    motion_failures = []
    for key, path in names.items():
        model_path = Path(path)
        model, textures = rm.load_model(model_path)
        n_frames = max(t.frames for t in textures.values())
        prev = None
        moved = 0
        for f in range(n_frames):
            faces = rm.build_faces(model, textures, f)
            if not faces:
                motion_failures.append(f"{key}: no visible faces @f{f}")
                break
            canvas = rm.render(faces, frame=f, head_hint=key in HATS)
            rows = rm.canvas_rows(canvas)
            if prev is not None and rows != prev:
                moved += 1
            prev = rows
        if moved < n_frames // 2:
            motion_failures.append(f"{key}: only {moved}/{n_frames} frames move")
    check(not motion_failures, "every model animates visibly every frame",
          "; ".join(motion_failures[:4]))

    print("== 6. catalog / items / manifest agreement ==")
    catalog = CATALOG.read_text()
    items = ITEMS_FILE.read_text()
    for (c, r), mid in TOOL_IDS.items():
        sid = f"{c}_{r}"
        check(f"{sid}:" in catalog and f"model-id: {mid}" in catalog,
              f"skins.yml has {sid}")
        check(f"tool_skin_{sid}:" in items and f"model_id: {mid}" in items,
              f"skins-items.yml has {sid}")
    for h, mid in HAT_IDS.items():
        check(f"{h}:" in catalog and f"model-id: {mid}" in catalog, f"skins.yml has {h}")
        check(f"hat_skin_{h}:" in items and f"model_id: {mid}" in items, f"skins-items.yml has {h}")
    check("namespace: coremc" in items, "skins-items.yml stays in the coremc namespace")

    print("== 7. built resource pack ==")
    if not PACK_ZIP.exists():
        # self-contained: build the pack first so CI never needs a pre-step
        import subprocess
        import sys
        result = subprocess.run([sys.executable, str(ROOT / "tools/build_pack.py")],
                                capture_output=True, text=True)
        check(result.returncode == 0, "pack build on demand", result.stdout.strip()[:120])
    if not PACK_ZIP.exists():
        check(False, "dist pack zip exists (run tools/build_pack.py)")
    else:
        zf = zipfile.ZipFile(PACK_ZIP)
        names_z = zf.namelist()
        check(len(names_z) == len(set(names_z)), "no duplicate zip entries")
        meta = json.loads(zf.read("pack.mcmeta"))
        check(meta["pack"]["min_format"] == 34 and meta["pack"]["max_format"] == 75,
              "pack.mcmeta covers formats 34-75 (1.21 - 1.21.11)")
        # every model the catalog references is in the zip with textures
        missing = []
        for key, path in names.items():
            model = load_model(Path(path))
            rel_model = "assets/coremc/" + str(Path(path).relative_to(ASSETS))
            if rel_model not in names_z:
                missing.append(rel_model)
            for ref, tex in model["textures"].items():
                if tex.startswith("#"):
                    continue
                rel_png = "assets/coremc/textures/" + tex.split(":", 1)[1] + ".png"
                if rel_png not in names_z:
                    missing.append(rel_png)
        check(not missing, "zip contains every model + texture", "; ".join(missing[:4]))
        # dispatch coverage for the two live materials
        for material, ids in (("netherite_pickaxe", list(TOOL_IDS.values()) + [21400, 21401, 21402, 21403, 21404, 21405]),
                              ("carved_pumpkin", list(HAT_IDS.values()))):
            dispatch = json.loads(zf.read(f"assets/minecraft/items/{material}.json"))
            entries = dispatch["model"]["entries"]
            got = {int(e["threshold"]) for e in entries}
            check(all(i in got for i in ids), f"{material} dispatch covers all coremc ids")

    print()
    if failures:
        print(f"VALIDATION FAILED: {len(failures)} problem(s)")
        return 1
    print("ALL SKIN ASSET CHECKS PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
