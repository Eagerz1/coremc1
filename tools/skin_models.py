#!/usr/bin/env python3
"""CoreMC animated skin 3D models — original hand-authored Java Edition
element geometry (standard library only).

Six distinct tool silhouettes (pickaxe / axe / sword / rod / hoe / omni
multitool) are built per role, then each themed collection layers its own
accent geometry (floating embers, rift shards, star cubes, carved water
channels, vine wraps) and texture set on top.  Nothing here is a palette
swap of another model: every (collection, role) pair gets a separately
assembled element list.

Elements are Minecraft model JSON boxes in 0..16 space (y up, item
standing, head at the top, haft to the bottom).  Texture references are
abstract ("#metal", "#energy", "#grip", "#gem") and are resolved by the
collection.  Faces carry UVs into the animated 16x16 textures so every
skin shows a visible looping animation in hand, GUI, ground and frames.
"""
from __future__ import annotations

import copy
import math
from typing import Any


# ----------------------------------------------------------------------
#  element helpers
# ----------------------------------------------------------------------

def box(
    name: str,
    start: tuple[float, float, float],
    end: tuple[float, float, float],
    tex: str,
    uv: tuple[float, float, float, float] | None = None,
    rotation: tuple[tuple[float, float, float], str, float] | None = None,
    faces: list[str] | None = None,
) -> dict[str, Any]:
    """One model element.  ``uv`` is the shared UV window for every face
    (pattern textures tile, so one window is enough); per-face UVs derive
    from the face orientation so corners never smear."""
    if uv is None:
        uv = (start[0], start[1], end[0], end[1])
    x1, y1, z1 = start
    x2, y2, z2 = end
    face_defs: dict[str, dict[str, Any]] = {}
    wanted = faces or ["north", "south", "east", "west", "up", "down"]
    u1, v1, u2, v2 = uv
    for face in wanted:
        if face in ("north", "south"):
            face_uv = [x1, 16 - y2, x2, 16 - y1]  # x across, y flipped (v grows down)
            if u2 - u1 != x2 - x1 or v2 - v1 != y2 - y1:
                face_uv = [u1, v1, u2, v2]
        elif face in ("east", "west"):
            face_uv = [z1, 16 - y2, z2, 16 - y1]
        elif face == "up":
            face_uv = [x1, z1, x2, z2]
        else:  # down
            face_uv = [x1, 16 - z2, x2, 16 - z1]
        entry: dict[str, Any] = {"uv": [round(v, 3) for v in face_uv], "texture": tex}
        face_defs[face] = entry
    element: dict[str, Any] = {
        "name": name,
        "from": [round(v, 3) for v in (x1, y1, z1)],
        "to": [round(v, 3) for v in (x2, y2, z2)],
        "faces": face_defs,
    }
    if rotation is not None:
        origin, axis, angle = rotation
        element["rotation"] = {
            "origin": [round(v, 3) for v in origin],
            "axis": axis,
            "angle": angle,
        }
    return element


def shift(element: dict[str, Any], dx: float, dy: float, dz: float) -> dict[str, Any]:
    out = copy.deepcopy(element)
    out["from"] = [round(v + d, 3) for v, d in zip(out["from"], (dx, dy, dz))]
    out["to"] = [round(v + d, 3) for v, d in zip(out["to"], (dx, dy, dz))]
    if "rotation" in out:
        out["rotation"]["origin"] = [
            round(v + d, 3) for v, d in zip(out["rotation"]["origin"], (dx, dy, dz))
        ]
    return out


# ----------------------------------------------------------------------
#  role base geometry — six distinct silhouettes
# ----------------------------------------------------------------------

def miner_geometry() -> tuple[list[dict[str, Any]], dict[str, tuple[float, float, float]]]:
    """Pickaxe: long haft, centre block, two curved prongs."""
    els: list[dict[str, Any]] = []
    els.append(box("haft", (7.25, 0.5, 7.25), (8.75, 12.0, 8.75), "#grip", (7, 0, 9, 15)))
    els.append(box("pommel", (6.9, 0.0, 6.9), (9.1, 0.7, 9.1), "#metal", (6, 9, 10, 13)))
    els.append(box("head_block", (5.4, 11.3, 6.6), (10.6, 13.6, 9.4), "#metal", (5, 3, 11, 8)))
    els.append(box("head_band", (5.0, 12.0, 6.4), (11.0, 12.6, 9.6), "#energy", (3, 5, 13, 7)))
    # prongs: rotated so the tips sweep down like a real pick
    els.append(box(
        "prong_left", (1.2, 11.2, 7.1), (5.6, 13.4, 8.9), "#metal", (1, 4, 6, 7),
        rotation=((5.6, 12.3, 8.0), "z", -22.5)))
    els.append(box(
        "prong_left_tip", (0.6, 11.0, 7.35), (1.8, 12.6, 8.65), "#metal", (0, 4, 2, 6),
        rotation=((5.6, 12.3, 8.0), "z", -22.5)))
    els.append(box(
        "prong_right", (10.4, 11.2, 7.1), (14.8, 13.4, 8.9), "#metal", (10, 4, 15, 7),
        rotation=((10.4, 12.3, 8.0), "z", 22.5)))
    els.append(box(
        "prong_right_tip", (14.2, 11.0, 7.35), (15.4, 12.6, 8.65), "#metal", (14, 4, 16, 6),
        rotation=((10.4, 12.3, 8.0), "z", 22.5)))
    anchors = {
        "head": (8.0, 12.5, 8.0),
        "head_bounds": (1.2, 11.3, 7.1, 14.8, 13.6, 8.9),
        "head_left": (3.6, 10.4, 8.0),
        "head_right": (12.4, 10.4, 8.0),
        "tip": (8.0, 14.4, 8.0),
        "haft_mid": (8.0, 7.0, 8.0),
    }
    return els, anchors


def logger_geometry() -> tuple[list[dict[str, Any]], dict[str, tuple[float, float, float]]]:
    """Axe: haft, collar, one-sided blade with cutting edge, back poll."""
    els: list[dict[str, Any]] = []
    els.append(box("haft", (7.3, 0.5, 7.3), (8.7, 13.0, 8.7), "#grip", (7, 0, 9, 15)))
    els.append(box("pommel", (6.95, 0.0, 6.95), (9.05, 0.8, 9.05), "#metal", (6, 9, 10, 13)))
    els.append(box("collar", (6.5, 12.2, 6.5), (10.5, 13.4, 9.5), "#metal", (6, 3, 10, 8)))
    els.append(box("cheek", (8.5, 9.4, 6.7), (12.4, 12.9, 9.3), "#metal", (9, 3, 13, 8)))
    els.append(box("blade", (12.4, 8.4, 6.9), (14.6, 13.4, 9.1), "#metal", (12, 3, 15, 8)))
    els.append(box("blade_edge", (14.6, 9.0, 7.3), (15.3, 13.0, 8.7), "#energy", (12, 5, 15, 7)))
    els.append(box("poll", (5.6, 10.0, 7.0), (8.5, 12.4, 9.0), "#metal", (5, 4, 9, 8)))
    els.append(box("poll_spike", (4.9, 10.6, 7.4), (5.9, 11.9, 8.6), "#energy", (4, 5, 6, 7)))
    anchors = {
        "head": (10.4, 11.3, 8.0),
        "head_bounds": (4.9, 8.4, 6.9, 15.3, 13.4, 9.1),
        "head_left": (4.6, 11.2, 8.0),
        "head_right": (14.6, 8.4, 8.0),
        "tip": (8.0, 13.6, 8.0),
        "haft_mid": (8.0, 6.5, 8.0),
    }
    return els, anchors


def slayer_geometry() -> tuple[list[dict[str, Any]], dict[str, tuple[float, float, float]]]:
    """Sword: gem pommel, wrapped grip, guard, tapered fullered blade."""
    els: list[dict[str, Any]] = []
    els.append(box("grip", (7.35, 1.0, 7.35), (8.65, 5.0, 8.65), "#grip", (7, 10, 9, 14)))
    els.append(box("pommel_gem", (6.9, 0.0, 6.9), (9.1, 1.1, 9.1), "#gem", (5, 5, 11, 11)))
    els.append(box("guard", (5.4, 5.0, 6.9), (10.6, 6.1, 9.1), "#metal", (4, 8, 12, 12)))
    els.append(box("guard_gem_left", (5.0, 5.2, 7.3), (5.7, 5.9, 8.7), "#gem", (5, 5, 11, 11)))
    els.append(box("guard_gem_right", (10.3, 5.2, 7.3), (11.0, 5.9, 8.7), "#gem", (5, 5, 11, 11)))
    els.append(box("blade_lower", (7.2, 6.1, 7.2), (8.8, 10.4, 8.8), "#metal", (4, 3, 12, 11)))
    els.append(box("blade_mid", (7.45, 10.4, 7.45), (8.55, 13.0, 8.55), "#metal", (4, 2, 12, 8)))
    els.append(box("blade_tip", (7.65, 13.0, 7.65), (8.35, 14.6, 8.35), "#metal", (5, 1, 11, 5)))
    # fuller: animated energy channel down both flats
    els.append(box("fuller_left", (7.0, 6.6, 7.35), (7.3, 12.6, 8.65), "#energy", (4, 4, 12, 12)))
    els.append(box("fuller_right", (8.7, 6.6, 7.35), (9.0, 12.6, 8.65), "#energy", (4, 4, 12, 12)))
    anchors = {
        "head": (8.0, 9.5, 8.0),
        "head_bounds": (5.4, 6.1, 7.2, 10.6, 14.6, 8.8),
        "head_left": (5.7, 5.6, 8.0),
        "head_right": (10.3, 5.6, 8.0),
        "tip": (8.0, 14.8, 8.0),
        "haft_mid": (8.0, 3.0, 8.0),
    }
    return els, anchors


def fisher_geometry() -> tuple[list[dict[str, Any]], dict[str, tuple[float, float, float]]]:
    """Fishing rod: bending rod segments, reel, line and a bobbing float."""
    els: list[dict[str, Any]] = []
    els.append(box("rod_base", (7.4, 0.5, 7.4), (8.6, 6.0, 8.6), "#grip", (7, 10, 9, 15)))
    els.append(box("butt_cap", (7.1, 0.0, 7.1), (8.9, 0.8, 8.9), "#metal", (6, 9, 10, 13)))
    els.append(box(
        "rod_mid", (7.4, 6.0, 7.4), (8.6, 10.6, 8.6), "#grip", (7, 6, 9, 10),
        rotation=((8.0, 6.0, 8.0), "z", -7)))
    els.append(box(
        "rod_top", (7.4, 10.2, 7.4), (8.6, 13.6, 8.6), "#grip", (7, 3, 9, 7),
        rotation=((8.0, 7.0, 8.0), "z", -16)))
    els.append(box(
        "rod_tip", (7.5, 13.0, 7.5), (8.5, 15.0, 8.5), "#metal", (7, 1, 9, 3),
        rotation=((8.0, 8.0, 8.0), "z", -26)))
    els.append(box("reel_body", (8.6, 3.0, 7.0), (10.0, 5.2, 9.0), "#metal", (9, 10, 11, 12)))
    els.append(box("reel_crank", (10.0, 3.7, 7.6), (10.6, 4.5, 8.4), "#gem", (5, 5, 11, 11)))
    # line dropping from the bent tip
    els.append(box("line", (5.4, 5.0, 7.85), (5.7, 13.6, 8.15), "#energy", (7, 0, 9, 14)))
    # glass-float bobber with pulsing bioluminescent core
    els.append(box("float", (4.4, 3.0, 6.9), (6.8, 5.4, 9.3), "#gem", (4, 4, 12, 12)))
    els.append(box("hook", (5.3, 1.9, 7.7), (5.9, 3.1, 8.3), "#metal", (7, 13, 9, 15)))
    anchors = {
        "head": (8.0, 12.4, 8.0),
        "head_bounds": (5.4, 10.2, 7.4, 10.6, 15.0, 8.6),
        "head_left": (5.6, 9.6, 8.0),
        "head_right": (10.4, 10.8, 8.0),
        "tip": (6.3, 15.2, 8.0),
        "haft_mid": (8.0, 3.5, 8.0),
    }
    return els, anchors


def farmer_geometry() -> tuple[list[dict[str, Any]], dict[str, tuple[float, float, float]]]:
    """Hoe: haft, angled neck and a forward-tilted blade with a lip."""
    els: list[dict[str, Any]] = []
    els.append(box("haft", (7.3, 0.5, 7.3), (8.7, 12.6, 8.7), "#grip", (7, 0, 9, 15)))
    els.append(box("pommel", (6.95, 0.0, 6.95), (9.05, 0.8, 9.05), "#metal", (6, 9, 10, 13)))
    els.append(box("neck", (7.3, 12.4, 6.6), (8.7, 14.2, 9.4), "#metal", (7, 2, 9, 5)))
    els.append(box(
        "blade", (3.6, 12.4, 6.85), (9.1, 14.6, 9.15), "#metal", (3, 2, 9, 5),
        rotation=((8.4, 13.5, 8.0), "z", 24)))
    els.append(box(
        "blade_lip", (2.9, 12.1, 7.1), (4.1, 13.9, 8.9), "#energy", (2, 3, 5, 6),
        rotation=((8.4, 13.5, 8.0), "z", 30)))
    els.append(box(
        "blade_shoulder", (8.4, 13.6, 6.9), (10.4, 14.9, 9.1), "#metal", (8, 1, 10, 4),
        rotation=((8.4, 13.5, 8.0), "z", 24)))
    els.append(box("collar", (6.7, 11.6, 6.7), (9.3, 12.6, 9.3), "#metal", (6, 3, 10, 5)))
    anchors = {
        "head": (6.2, 13.6, 8.0),
        "head_bounds": (2.9, 12.1, 6.85, 10.4, 14.9, 9.15),
        "head_left": (3.4, 13.2, 8.0),
        "head_right": (10.0, 14.4, 8.0),
        "tip": (8.0, 15.0, 8.0),
        "haft_mid": (8.0, 6.5, 8.0),
    }
    return els, anchors


def universal_geometry() -> tuple[list[dict[str, Any]], dict[str, tuple[float, float, float]]]:
    """Universal Omni-Tool: hub block with pick prong, axe blade, sword
    point and hook — one tool for every job, instantly readable."""
    els: list[dict[str, Any]] = []
    els.append(box("haft", (7.3, 0.5, 7.3), (8.7, 10.4, 8.7), "#grip", (7, 0, 9, 13)))
    els.append(box("pommel", (6.9, 0.0, 6.9), (9.1, 0.8, 9.1), "#metal", (6, 9, 10, 13)))
    els.append(box("hub", (5.6, 10.3, 5.6), (10.4, 13.8, 10.4), "#metal", (5, 3, 11, 9)))
    els.append(box("hub_core", (7.5, 11.6, 7.5), (8.5, 12.6, 8.5), "#energy", (4, 4, 12, 12)))
    els.append(box(
        "pick_prong", (1.8, 11.6, 7.2), (5.6, 13.5, 8.8), "#metal", (1, 4, 6, 7),
        rotation=((5.6, 12.5, 8.0), "z", -20)))
    els.append(box(
        "pick_tip", (1.0, 11.4, 7.4), (2.2, 13.0, 8.6), "#energy", (0, 4, 2, 6),
        rotation=((5.6, 12.5, 8.0), "z", -20)))
    els.append(box("axe_blade", (10.4, 11.5, 6.9), (12.5, 13.5, 9.1), "#metal", (11, 4, 13, 7)))
    els.append(box("axe_edge", (12.5, 11.8, 7.3), (13.2, 13.1, 8.7), "#energy", (13, 5, 15, 7)))
    els.append(box("sword_point", (7.4, 12.4, 3.0), (8.6, 14.1, 5.6), "#metal", (7, 2, 9, 5)))
    els.append(box("sword_point_edge", (7.5, 12.9, 2.4), (8.5, 13.7, 3.2), "#energy", (7, 2, 9, 4)))
    els.append(box(
        "hook_back", (7.5, 12.0, 10.4), (8.5, 13.9, 12.6), "#metal", (7, 2, 9, 5)))
    els.append(box("top_gem", (7.2, 13.8, 7.2), (8.8, 15.2, 8.8), "#gem", (4, 4, 12, 12)))
    anchors = {
        "head": (8.0, 12.2, 8.0),
        "head_bounds": (1.0, 11.6, 3.0, 13.2, 13.8, 12.6),
        "head_left": (2.8, 11.6, 8.0),
        "head_right": (12.9, 12.3, 8.0),
        "tip": (8.0, 15.4, 8.0),
        "haft_mid": (8.0, 5.5, 8.0),
    }
    return els, anchors


ROLE_GEOMETRY = {
    "miner": miner_geometry,
    "logger": logger_geometry,
    "slayer": slayer_geometry,
    "fisher": fisher_geometry,
    "farmer": farmer_geometry,
    "universal": universal_geometry,
}


# ----------------------------------------------------------------------
#  collection accent geometry + texture sets
# ----------------------------------------------------------------------

def _cube(name: str, center: tuple[float, float, float], size: float, tex: str,
          uv: tuple[float, float, float, float]) -> dict[str, Any]:
    cx, cy, cz = center
    half = size / 2.0
    return box(name,
               (cx - half, cy - half, cz - half),
               (cx + half, cy + half, cz + half),
               tex, uv)


def emberforge_accents(anchors) -> list[dict[str, Any]]:
    """Magma core band on the head + two floating ember coals."""
    els: list[dict[str, Any]] = []
    hx, hy, hz = anchors["head"]
    # proud magma inlay straddling the head
    els.append(box("magma_inlay",
                   (hx - 3.1, hy - 0.35, hz - 1.5),
                   (hx + 3.1, hy + 0.45, hz + 1.5),
                   "#energy", (3, 6, 13, 10)))
    # floating ember coals beside the head (gap = they visibly float)
    for i, key in enumerate(("head_left", "head_right")):
        ex, ey, ez = anchors[key]
        els.append(_cube(f"ember_coal_{i}", (ex, ey, ez), 1.7, "#gem", (4, 4, 12, 12)))
    return els


def riftbound_accents(anchors) -> list[dict[str, Any]]:
    """Rift shards floating in fracture gaps + a pulsing crack band."""
    els: list[dict[str, Any]] = []
    hx, hy, hz = anchors["head"]
    # violet crack band across the head
    els.append(box("rift_crack",
                   (hx - 3.0, hy + 0.55, hz - 1.4),
                   (hx + 3.0, hy + 1.0, hz + 1.4),
                   "#energy", (3, 6, 13, 10)))
    # two crystals floating off the head — the "fractured" look
    lx, ly, lz = anchors["head_left"]
    els.append(box("rift_shard_left", (lx - 0.4, ly - 0.9, lz - 0.5),
                   (lx + 0.9, ly + 1.4, lz + 0.5), "#gem", (4, 4, 12, 12),
                   rotation=((lx + 0.25, ly + 0.25, lz), "z", 20)))
    rx, ry, rz = anchors["head_right"]
    els.append(box("rift_shard_right", (rx - 0.9, ry + 0.4, rz - 0.5),
                   (rx + 0.4, ry + 1.9, rz + 0.5), "#gem", (4, 4, 12, 12),
                   rotation=((rx - 0.25, ry + 1.15, rz), "z", -15)))
    return els


def astral_accents(anchors) -> list[dict[str, Any]]:
    """Three star cubes orbiting (fixed offsets, twinkling textures)."""
    els: list[dict[str, Any]] = []
    hx, hy, hz = anchors["head"]
    els.append(box("star_band",
                   (hx - 3.0, hy - 0.1, hz - 1.4),
                   (hx + 3.0, hy + 0.5, hz + 1.4),
                   "#energy", (3, 6, 13, 10)))
    lx, ly, lz = anchors["head_left"]
    els.append(_cube("star_cube_left", (lx, ly + 0.6, lz), 1.5, "#gem", (4, 4, 12, 12)))
    rx, ry, rz = anchors["head_right"]
    els.append(_cube("star_cube_right", (rx, ry + 0.6, rz), 1.5, "#gem", (4, 4, 12, 12)))
    tx, ty, tz = anchors["tip"]
    els.append(_cube("star_cube_tip", (tx, ty + 0.6, tz), 1.6, "#gem", (4, 4, 12, 12)))
    return els


def tidecaller_accents(anchors) -> list[dict[str, Any]]:
    """Carved water channel on the head + glass charm on a short link."""
    els: list[dict[str, Any]] = []
    hx, hy, hz = anchors["head"]
    els.append(box("water_channel",
                   (hx - 3.0, hy - 0.3, hz - 1.45),
                   (hx + 3.0, hy + 0.4, hz + 1.45),
                   "#energy", (3, 6, 13, 10)))
    lx, ly, lz = anchors["head_left"]
    els.append(_cube("glass_charm", (lx - 0.4, ly + 0.3, lz), 1.8, "#gem", (4, 4, 12, 12)))
    # bioluminescent band on the haft
    gx, gy, gz = anchors["haft_mid"]
    els.append(box("haft_lume",
                   (gx - 0.95, gy - 0.2, gz - 0.95),
                   (gx + 0.95, gy + 0.9, gz + 0.95),
                   "#energy", (6, 6, 10, 10)))
    return els


def overgrown_accents(anchors) -> list[dict[str, Any]]:
    """Moss growing on the head, vine wraps crossing the haft and a
    blooming spore flower on a stalk above the tool."""
    els: list[dict[str, Any]] = []
    bx1, by1, bz1, bx2, by2, bz2 = anchors["head_bounds"]
    # moss patches sitting ON the head (clearly outside its silhouette)
    els.append(box("moss_patch_a",
                   (bx1 - 0.15, by2 - 0.6, bz1 - 0.15),
                   (bx1 + 1.9, by2 + 0.55, bz2 + 0.15),
                   "#gem", (4, 4, 12, 12)))
    els.append(box("moss_patch_b",
                   (bx2 - 1.7, by1 - 0.1, bz1 - 0.15),
                   (bx2 + 0.3, by1 + 0.9, bz1 + 1.5),
                   "#gem", (4, 4, 12, 12)))
    # a few blades of grass off the moss
    gx0 = (bx1 + bx2) / 2.0
    els.append(box("grass_blade",
                   (gx0 - 0.1, by2 + 0.5, bz1 + 0.4),
                   (gx0 + 0.25, by2 + 1.8, bz1 + 0.75),
                   "#gem", (4, 4, 12, 12),
                   rotation=((gx0, by2, bz1 + 0.6), "z", -14)))
    gx, gy, gz = anchors["haft_mid"]
    # two angled vine strips crossing the haft (wider than it, so visible)
    els.append(box("vine_wrap_low",
                   (gx - 1.15, gy - 0.75, gz - 0.7),
                   (gx + 1.15, gy - 0.3, gz + 0.7),
                   "#gem", (2, 2, 14, 14),
                   rotation=((gx, gy, gz), "z", 28)))
    els.append(box("vine_wrap_high",
                   (gx - 1.15, gy + 0.55, gz - 0.7),
                   (gx + 1.15, gy + 1.0, gz + 0.7),
                   "#gem", (2, 2, 14, 14),
                   rotation=((gx, gy, gz), "z", -28)))
    tx, ty, tz = anchors["tip"]
    els.append(_cube("spore_bloom", (tx, ty + 0.55, tz), 1.7, "#gem", (4, 4, 12, 12)))
    return els


# ----------------------------------------------------------------------
#  display transforms
# ----------------------------------------------------------------------

TOOL_DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, 90, -50], "translation": [0, 3.5, 0.9], "scale": [0.8, 0.8, 0.8]},
    "thirdperson_lefthand": {"rotation": [0, -90, 50], "translation": [0, 3.5, 0.9], "scale": [0.8, 0.8, 0.8]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "gui": {"rotation": [30, 225, 0], "translation": [0, 1.2, 0], "scale": [0.62, 0.62, 0.62]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.38, 0.38, 0.38]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, -1, 0], "scale": [0.45, 0.45, 0.45]},
}

HAT_DISPLAY = {
    # head context: the model is worn around the head region; the crown
    # geometry is authored for a head filling x 4..12, y 8..16 (top y=12)
    "head": {"rotation": [0, 0, 0], "translation": [0, 0.2, 0], "scale": [1.05, 1.05, 1.05]},
    "thirdperson_righthand": {"rotation": [0, 90, 0], "translation": [0, 3, 0.5], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_lefthand": {"rotation": [0, -90, 0], "translation": [0, 3, 0.5], "scale": [0.5, 0.5, 0.5]},
    "firstperson_righthand": {"rotation": [0, -60, 20], "translation": [1.1, 2.6, 1.1], "scale": [0.42, 0.42, 0.42]},
    "firstperson_lefthand": {"rotation": [0, 60, -20], "translation": [1.1, 2.6, 1.1], "scale": [0.42, 0.42, 0.42]},
    "gui": {"rotation": [15, 215, 0], "translation": [0, 0.5, 0], "scale": [0.62, 0.62, 0.62]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
    "fixed": {"rotation": [-20, 0, 0], "translation": [0, 0, 0], "scale": [0.45, 0.45, 0.45]},
}


# ----------------------------------------------------------------------
#  hats — three wearable models built in head space
# ----------------------------------------------------------------------

def ember_crown_geometry() -> list[dict[str, Any]]:
    """Forged circlet with a broken-in inset band and ember cubes on the
    spike tips.  Head occupies x 4..12, y 8..16; the crown sits on top."""
    els: list[dict[str, Any]] = []
    # circlet: octagonal ring of short blocks at the brow line
    import math as _m
    r = 4.1
    for i in range(8):
        ang = _m.radians(i * 45)
        cx = 8.0 + r * _m.cos(ang)
        cz = 8.0 + r * _m.sin(ang)
        half = 0.95
        els.append(box(f"circlet_{i}",
                       (cx - half, 9.6, cz - half),
                       (cx + half, 10.8, cz + half),
                       "#metal", (3, 3, 13, 13)))
    # inner ember band around the brow
    for i in range(8):
        ang = _m.radians(i * 45 + 22.5)
        cx = 8.0 + (r - 0.1) * _m.cos(ang)
        cz = 8.0 + (r - 0.1) * _m.sin(ang)
        els.append(box(f"band_{i}",
                       (cx - 0.55, 10.15, cz - 0.55),
                       (cx + 0.55, 10.35, cz + 0.55),
                       "#energy", (3, 6, 13, 10)))
    # spikes at the outer ring, alternating tall/short
    for i in range(8):
        ang = _m.radians(i * 45)
        cx = 8.0 + (r + 0.55) * _m.cos(ang)
        cz = 8.0 + (r + 0.55) * _m.sin(ang)
        h = 2.6 if i % 2 == 0 else 1.6
        els.append(box(f"spike_{i}",
                       (cx - 0.6, 10.6, cz - 0.6),
                       (cx + 0.6, 10.6 + h, cz + 0.6),
                       "#metal", (5, 1, 11, 6)))
        if i % 2 == 0:
            els.append(_cube(f"spike_ember_{i}", (cx, 10.9 + h, cz), 1.25, "#gem", (4, 4, 12, 12)))
    return els


def rift_halo_geometry() -> list[dict[str, Any]]:
    """A slowly shifting broken ring: 8 segments with two missing, the
    fracture held apart by floating violet shards.  Floats above the head."""
    els: list[dict[str, Any]] = []
    r = 4.3
    seg = 0.92
    for i in range(10):
        ang_deg = i * 36
        # gap: segments 3 and 7 are missing (the 'broken' ring)
        if i in (3, 7):
            continue
        ang = math.radians(ang_deg)
        cx = 8.0 + r * math.cos(ang)
        cz = 8.0 + r * math.sin(ang)
        els.append(box(f"ring_{i}",
                       (cx - seg, 13.4, cz - seg),
                       (cx + seg, 14.6, cz + seg),
                       "#metal", (3, 3, 13, 13),
                       rotation=((8.0, 14.0, 8.0), "y", -ang_deg)))
    # shards drifting in the two gaps
    for ang_deg, dy in ((108.0, 0.9), (252.0, -0.9)):
        ang = math.radians(ang_deg)
        cx = 8.0 + r * math.cos(ang)
        cz = 8.0 + r * math.sin(ang)
        els.append(box(f"gap_shard_{int(ang_deg)}",
                       (cx - 0.75, 13.4 + dy, cz - 0.35),
                       (cx + 0.75, 14.9 + dy, cz + 0.35),
                       "#gem", (4, 4, 12, 12),
                       rotation=((cx, 14.1 + dy, cz), "y", -ang_deg + 90)))
    # energy lining under the ring
    for i in range(10):
        if i in (3, 7):
            continue
        ang = math.radians(i * 36)
        cx = 8.0 + r * math.cos(ang)
        cz = 8.0 + r * math.sin(ang)
        els.append(box(f"lining_{i}",
                       (cx - seg * 0.8, 13.15, cz - seg * 0.8),
                       (cx + seg * 0.8, 13.4, cz + seg * 0.8),
                       "#energy", (3, 6, 13, 10)))
    return els


def moonlit_cap_geometry() -> list[dict[str, Any]]:
    """A soft night cap: stepped dome with a brim, moonlight band and
    three small star studs on the cloth."""
    els: list[dict[str, Any]] = []
    # brim
    els.append(box("brim", (3.4, 9.4, 3.4), (12.6, 10.0, 12.6), "#metal", (2, 6, 14, 10)))
    # stepped dome
    els.append(box("dome_1", (4.0, 10.0, 4.0), (12.0, 11.6, 12.0), "#metal", (3, 2, 13, 6)))
    els.append(box("dome_2", (4.9, 11.6, 4.9), (11.1, 12.8, 11.1), "#metal", (4, 1, 12, 5)))
    els.append(box("dome_3", (5.8, 12.8, 5.8), (10.2, 13.8, 10.2), "#metal", (5, 1, 11, 4)))
    els.append(box("dome_4", (6.9, 13.8, 6.9), (9.1, 14.6, 9.1), "#metal", (6, 1, 10, 3)))
    # moonlight band sweeping around the dome base
    els.append(box("moon_band", (3.85, 11.3, 3.85), (12.15, 11.75, 12.15), "#energy", (2, 6, 14, 10)))
    # small star studs
    els.append(_cube("star_stud_front", (8.0, 12.0, 4.35), 0.95, "#gem", (4, 4, 12, 12)))
    els.append(_cube("star_stud_left", (4.35, 11.2, 8.0), 0.95, "#gem", (4, 4, 12, 12)))
    els.append(_cube("star_stud_top", (8.6, 14.3, 8.6), 1.05, "#gem", (4, 4, 12, 12)))
    # crescent moon pin at the front
    els.append(box("moon_pin", (7.3, 11.9, 3.7), (8.7, 13.3, 4.15), "#gem", (4, 4, 12, 12)))
    return els


# ----------------------------------------------------------------------
#  collections: texture sets + accent builders + names
# ----------------------------------------------------------------------

COLLECTIONS: dict[str, dict[str, Any]] = {
    "emberforge": {
        "display": "&6Emberforge",
        "lore": "Flowing magma, forged metal, pulsing heat",
        "textures": {
            "#metal": "coremc:item/skins/emberforge/metal",
            "#energy": "coremc:item/skins/emberforge/magma",
            "#grip": "coremc:item/skins/emberforge/grip",
            "#gem": "coremc:item/skins/emberforge/ember",
        },
        "accents": emberforge_accents,
        "filter_material": "ORANGE_STAINED_GLASS_PANE",
        "hat_materials": {"#metal": "emberforge/metal", "#energy": "emberforge/magma", "#gem": "emberforge/ember"},
    },
    "riftbound": {
        "display": "&5Riftbound",
        "lore": "Fractured dark metal, moving violet rift energy",
        "textures": {
            "#metal": "coremc:item/skins/riftbound/metal",
            "#energy": "coremc:item/skins/riftbound/energy",
            "#grip": "coremc:item/skins/riftbound/grip",
            "#gem": "coremc:item/skins/riftbound/shard",
        },
        "accents": riftbound_accents,
        "filter_material": "PURPLE_STAINED_GLASS_PANE",
        "hat_materials": {"#metal": "riftbound/metal", "#energy": "riftbound/energy", "#gem": "riftbound/shard"},
    },
    "astral": {
        "display": "&bAstral",
        "lore": "Star-metal, orbiting constellations, soft celestial light",
        "textures": {
            "#metal": "coremc:item/skins/astral/metal",
            "#energy": "coremc:item/skins/astral/nightglow",
            "#grip": "coremc:item/skins/astral/haft",
            "#gem": "coremc:item/skins/astral/star",
        },
        "accents": astral_accents,
        "filter_material": "LIGHT_BLUE_STAINED_GLASS_PANE",
        "hat_materials": {"#metal": "astral/metal", "#energy": "astral/nightglow", "#gem": "astral/star"},
    },
    "tidecaller": {
        "display": "&3Tidecaller",
        "lore": "Carved sea-glass, moving water and bioluminescence",
        "textures": {
            "#metal": "coremc:item/skins/tidecaller/glass",
            "#energy": "coremc:item/skins/tidecaller/biolume",
            "#grip": "coremc:item/skins/tidecaller/haft",
            "#gem": "coremc:item/skins/tidecaller/float",
        },
        "accents": tidecaller_accents,
        "filter_material": "CYAN_STAINED_GLASS_PANE",
        "hat_materials": {"#metal": "tidecaller/glass", "#energy": "tidecaller/biolume", "#gem": "tidecaller/float"},
    },
    "overgrown": {
        "display": "&2Overgrown",
        "lore": "Ancient wood and stone, growing vines and drifting spores",
        "textures": {
            "#metal": "coremc:item/skins/overgrown/stone",
            "#energy": "coremc:item/skins/overgrown/spores",
            "#grip": "coremc:item/skins/overgrown/haft",
            "#gem": "coremc:item/skins/overgrown/bloom",
        },
        "accents": overgrown_accents,
        "filter_material": "GREEN_STAINED_GLASS_PANE",
        "hat_materials": {"#metal": "overgrown/stone", "#energy": "overgrown/spores", "#gem": "overgrown/bloom"},
    },
}

ROLE_ORDER = ["miner", "logger", "fisher", "slayer", "farmer", "universal"]

ROLE_DISPLAY = {
    "miner": "&eMiner",
    "logger": "&6Logger",
    "fisher": "&bFisher",
    "slayer": "&cSlayer",
    "farmer": "&aFarmer",
    "universal": "&dUniversal",
}

HATS: dict[str, dict[str, Any]] = {
    "ember_crown": {
        "display": "&6Ember Crown",
        "lore": "Embers moving around a forged crown",
        "geometry": ember_crown_geometry,
        "textures": {
            "#metal": "coremc:item/skins/emberforge/metal",
            "#energy": "coremc:item/skins/emberforge/magma",
            "#gem": "coremc:item/skins/emberforge/ember",
        },
        "particle": "#metal",
        "collection": "emberforge",
    },
    "rift_halo": {
        "display": "&5Rift Halo",
        "lore": "A slowly shifting broken ring",
        "geometry": rift_halo_geometry,
        "textures": {
            "#metal": "coremc:item/skins/riftbound/metal",
            "#energy": "coremc:item/skins/riftbound/energy",
            "#gem": "coremc:item/skins/riftbound/shard",
        },
        "particle": "#metal",
        "collection": "riftbound",
    },
    "moonlit_cap": {
        "display": "&bMoonlit Cap",
        "lore": "Moving moonlight and small star details",
        "geometry": moonlit_cap_geometry,
        "textures": {
            "#metal": "coremc:item/skins/moonlit/cap",
            "#energy": "coremc:item/skins/moonlit/star",
            "#gem": "coremc:item/skins/moonlit/star",
        },
        "particle": "#metal",
        "collection": "astral",
    },
}


def build_tool_model(collection: str, role: str) -> dict[str, Any]:
    base, anchors = ROLE_GEOMETRY[role]()
    accents = COLLECTIONS[collection]["accents"](anchors)
    textures = dict(COLLECTIONS[collection]["textures"])
    textures["particle"] = textures["#metal"]
    return {
        "__comment": f"CoreMC {collection} {role} skin — original 3D geometry with animated textures",
        "textures": textures,
        "elements": base + accents,
        "display": copy.deepcopy(TOOL_DISPLAY),
    }


def build_hat_model(hat_id: str) -> dict[str, Any]:
    hat = HATS[hat_id]
    textures = dict(hat["textures"])
    textures["particle"] = textures["#metal"]
    return {
        "__comment": f"CoreMC {hat['display']} hat — original wearable 3D geometry",
        "textures": textures,
        "elements": hat["geometry"](),
        "display": copy.deepcopy(HAT_DISPLAY),
    }
