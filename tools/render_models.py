#!/usr/bin/env python3
"""Software renderer for CoreMC skin models (standard library only).

Parses the generated Minecraft model JSON exactly as a client would see
it (elements + per-face UVs into the animated texture strips + display
transforms) and rasterises isometric previews:

  * static PNG per model (GUI display transform)
  * APNG animation per model (every texture frame -> real looping preview)
  * hat previews rendered around a translucent head box (worn look)

This is a verification tool: it proves every model has visible geometry,
that all UV/texture references resolve, and that each animation actually
moves — without needing a Minecraft client.
"""
from __future__ import annotations

import json
import math
import re
import struct
import sys
import zlib
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/itemsadder/contents/coremc/resourcepack/assets/coremc"
OUT = ROOT / "docs/skin-previews"

FACE_SHADE = {"up": 1.0, "down": 0.5, "south": 0.8, "north": 0.8, "east": 0.6, "west": 0.6}

FACE_NORMAL = {
    "up": (0.0, 1.0, 0.0),
    "down": (0.0, -1.0, 0.0),
    "south": (0.0, 0.0, 1.0),
    "north": (0.0, 0.0, -1.0),
    "east": (1.0, 0.0, 0.0),
    "west": (-1.0, 0.0, 0.0),
}

FACE_CORNERS: dict[str, list[tuple[float, float, float]]] = {
    # corner order chosen counter-clockwise seen from outside
    "north": [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)],
    "south": [(1, 0, 1), (0, 0, 1), (0, 1, 1), (1, 1, 1)],
    "west": [(0, 0, 1), (0, 0, 0), (0, 1, 0), (0, 1, 1)],
    "east": [(1, 0, 0), (1, 0, 1), (1, 1, 1), (1, 1, 0)],
    "up": [(0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0)],
    "down": [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)],
}


def read_png(path: Path) -> tuple[int, int, list[list[tuple[int, int, int, int]]]]:
    data = path.read_bytes()
    pos = 8
    ihdr = None
    compressed = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", chunk)
        elif kind == b"IDAT":
            compressed.extend(chunk)
        elif kind == b"IEND":
            break
        pos += 12 + length
    width, height, _, _, _, _, _ = ihdr
    raw = zlib.decompress(bytes(compressed))
    stride = width * 4
    rows: list[list[tuple[int, int, int, int]]] = []
    for y in range(height):
        row = raw[y * (stride + 1):(y + 1) * (stride + 1)]
        assert row[0] == 0, "unexpected filter"
        rows.append([tuple(row[1 + i * 4:5 + i * 4]) for i in range(width)])
    return width, height, rows


class TextureStrip:
    def __init__(self, path: Path) -> None:
        self.width, self.height, self.rows = read_png(path)
        self.frames = self.height // 16

    def sample(self, u: float, v: float, frame: int) -> tuple[int, int, int, int]:
        # u,v in 0..16 texture space; frame selects the 16px window
        x = max(0, min(15, int(u)))
        y = max(0, min(15, int(v)))
        row = self.rows[frame * 16 + y]
        return row[x]


def mat_mul(a, b):
    return [
        [sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)]
        for i in range(3)
    ]


def rot_x(deg: float):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [[1, 0, 0], [0, c, -s], [0, s, c]]


def rot_y(deg: float):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [[c, 0, s], [0, 1, 0], [-s, 0, c]]


def rot_z(deg: float):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def apply(m, v):
    return (
        m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
        m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
        m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
    )


def display_matrix(display: dict[str, Any] | None):
    if not display:
        return None, (0.0, 0.0, 0.0), 1.0
    rx, ry, rz = [float(v) for v in display.get("rotation", [0, 0, 0])]
    m = rot_z(rz)
    m = mat_mul(m, rot_y(ry))
    m = mat_mul(m, rot_x(rx))
    tx, ty, tz = [float(v) for v in display.get("translation", [0, 0, 0])]
    scale = display.get("scale", 1.0)
    if isinstance(scale, list):
        scale = float(scale[0])
    return m, (tx, ty, tz), scale


class Face:
    __slots__ = ("poly", "depth", "shade", "tex", "uvs", "alpha", "tint")

    def __init__(self, poly, depth, shade, tex, uvs, alpha, tint) -> None:
        self.poly = poly
        self.depth = depth
        self.shade = shade
        self.tex = tex
        self.uvs = uvs
        self.alpha = alpha
        self.tint = tint


def build_faces(model: dict[str, Any], textures: dict[str, TextureStrip], frame: int) -> list[Face]:
    faces: list[Face] = []
    disp = model.get("display", {}).get("gui")
    m, (tx, ty, tz), scale = display_matrix(disp)
    for element in model.get("elements", []):
        x1, y1, z1 = element["from"]
        x2, y2, z2 = element["to"]
        rot = element.get("rotation")
        local: Any = None
        if rot:
            origin = rot["origin"]
            axis = rot["axis"]
            angle = rot["angle"]
            rot_map = {"x": rot_x(angle), "y": rot_y(angle), "z": rot_z(angle)}
            local = rot_map[axis]
        for face_name, face_def in element["faces"].items():
            tex_ref = face_def["texture"]
            strip = textures.get(tex_ref)
            if strip is None:
                raise AssertionError(f"unresolved texture ref {tex_ref}")
            corners = FACE_CORNERS[face_name]
            pts = []
            for c in corners:
                px = x1 + c[0] * (x2 - x1)
                py = y1 + c[1] * (y2 - y1)
                pz = z1 + c[2] * (z2 - z1)
                if local is not None:
                    dx, dy, dz = px - origin[0], py - origin[1], pz - origin[2]
                    px, py, pz = apply(local, (dx, dy, dz))
                    px += origin[0]
                    py += origin[1]
                    pz += origin[2]
                # display transform: pivot (8,8,8), rotate, scale, translate
                vx, vy, vz = px - 8.0, py - 8.0, pz - 8.0
                if m is not None:
                    vx, vy, vz = apply(m, (vx, vy, vz))
                vx = vx * scale + tx
                vy = vy * scale + ty
                vz = vz * scale + tz
                pts.append((vx, vy, vz))
            # backface cull in view space (orthographic along -z view)
            ax, ay, az = pts[1][0] - pts[0][0], pts[1][1] - pts[0][1], pts[1][2] - pts[0][2]
            bx, by, bz = pts[2][0] - pts[0][0], pts[2][1] - pts[0][1], pts[2][2] - pts[0][2]
            nx, ny, nz = ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx
            if nz <= 0:  # facing away from the viewer
                continue
            depth = sum(p[2] for p in pts) / 4.0
            uv = face_def.get("uv")
            if uv is None:
                uv = [x1, y1, x2, y2]
            u1, v1, u2, v2 = uv
            # map corners to uv (corner order matches FACE_CORNERS layout)
            uv_map = {
                "north": [(u1, v2), (u2, v2), (u2, v1), (u1, v1)],
                "south": [(u1, v2), (u2, v2), (u2, v1), (u1, v1)],
                "west": [(u1, v2), (u2, v2), (u2, v1), (u1, v1)],
                "east": [(u1, v2), (u2, v2), (u2, v1), (u1, v1)],
                "up": [(u1, v1), (u2, v1), (u2, v2), (u1, v2)],
                "down": [(u1, v1), (u2, v1), (u2, v2), (u1, v2)],
            }
            faces.append(Face(
                pts, depth, FACE_SHADE[face_name], strip,
                uv_map[face_name], 255, None))
    faces.sort(key=lambda f: f.depth)  # painter's algorithm: far first
    return faces


def render(faces: list[Face], size: int = 260, frame: int = 0,
           head_hint: bool = False) -> list[list[tuple[int, int, int, int]]]:
    canvas = [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]
    if head_hint:
        # translucent head box (8 units) so hat fit is visible
        half = 4.0
        for face_name in ("north", "south", "east", "west", "up", "down"):
            corners = FACE_CORNERS[face_name]
            pts = []
            for c in corners:
                px = 8.0 + (c[0] * 2 - 1) * half
                py = 8.0 + (c[1] * 2 - 1) * half
                pz = 8.0 + (c[2] * 2 - 1) * half
                pts.append((px - 8.0, py - 8.0, pz - 8.0))
            ax, ay, az = (pts[1][i] - pts[0][i] for i in range(3))
            bx, by, bz = (pts[2][i] - pts[0][i] for i in range(3))
            nz = ay * bz - az * by
            if nz <= 0:
                continue
            poly = [(size / 2 + p[0] * (size / 22.0), size / 2 - p[1] * (size / 22.0)) for p in pts]
            _fill_poly(canvas, poly, [(210, 160, 160, 70)] * 4, size)
    for face in faces:
        scale_px = size / 22.0
        poly = [
            (size / 2 + p[0] * scale_px, size / 2 - p[1] * scale_px)
            for p in face.poly
        ]
        cols = []
        for (u, v) in face.uvs:
            r, g, b, a = face.tex.sample(u, v, frame)
            lit = face.shade
            cols.append((
                max(0, min(255, int(r * lit))),
                max(0, min(255, int(g * lit))),
                max(0, min(255, int(b * lit))),
                a,
            ))
        _fill_poly(canvas, poly, cols, size)
    return canvas


def _fill_poly(canvas, poly, cols, size: int) -> None:
    ys = range(max(0, int(min(p[1] for p in poly))), min(size - 1, int(max(p[1] for p in poly))) + 1)
    n = len(poly)
    for y in ys:
        yc = y + 0.5
        xs = []
        for i in range(n):
            x1, y1 = poly[i]
            x2, y2 = poly[(i + 1) % n]
            if y1 == y2:
                continue
            if min(y1, y2) <= yc < max(y1, y2):
                xs.append((x1 + (yc - y1) * (x2 - x1) / (y2 - y1), i))
        xs.sort()
        for (xa, ia), (xb, ib) in zip(xs[::2], xs[1::2]):
            for x in range(max(0, int(xa)), min(size, int(xb) + 1)):
                # interpolate colour across the span between edge corners
                t = 0.5 if xb == xa else max(0.0, min(1.0, (x + 0.5 - xa) / (xb - xa)))
                ca = cols[ia]
                cb = cols[(ia + 1) % n]
                r = int(ca[0] + (cb[0] - ca[0]) * t)
                g = int(ca[1] + (cb[1] - ca[1]) * t)
                b = int(ca[2] + (cb[2] - ca[2]) * t)
                a = int(ca[3] + (cb[3] - ca[3]) * t)
                if a > 0:
                    canvas[y][x] = (
                        max(0, min(255, r)),
                        max(0, min(255, g)),
                        max(0, min(255, b)),
                        max(0, min(255, a)),
                    )


# ---------------- PNG / APNG writing ----------------

def _chunk(kind: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data)) + kind + data
        + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    )


def canvas_rows(canvas) -> list[bytes]:
    return [b"".join(bytes(px) for px in row) for row in canvas]


def write_png(path: Path, canvas) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = canvas_rows(canvas)
    height = len(rows)
    width = len(rows[0]) // 4
    raw = b"".join(b"\x00" + row for row in rows)
    data = b"\x89PNG\r\n\x1a\n"
    data += _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    data += _chunk(b"IDAT", zlib.compress(raw, 9))
    data += _chunk(b"IEND", b"")
    path.write_bytes(data)


def write_apng(path: Path, frames: list, delay_num: int = 3, delay_den: int = 20) -> None:
    """Minimal APNG (default image = frame 0, fdAT for the rest)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    rows0 = canvas_rows(frames[0])
    height = len(rows0)
    width = len(rows0[0]) // 4

    def frame_bytes(canvas):
        return b"".join(b"\x00" + row for row in canvas_rows(canvas))

    seq = 0
    data = b"\x89PNG\r\n\x1a\n"
    data += _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    data += _chunk(b"acTL", struct.pack(">II", len(frames), 0))
    data += _chunk(b"fcTL", struct.pack(">IIIIIHHBB", seq, width, height, 0, 0, delay_num, delay_den, 0, 0))
    seq += 1
    data += _chunk(b"IDAT", zlib.compress(frame_bytes(frames[0]), 9))
    for canvas in frames[1:]:
        ctrl = struct.pack(">IIIIIHHBB", seq, width, height, 0, 0, delay_num, delay_den, 0, 0)
        seq += 1
        fdat = struct.pack(">I", seq) + zlib.compress(frame_bytes(canvas), 9)[2:-4]
        seq += 1
        data += _chunk(b"fcTL", ctrl)
        data += _chunk(b"fdAT", fdat)
    data += _chunk(b"IEND", b"")
    path.write_bytes(data)


# ---------------- model loading ----------------

def load_model(model_path: Path) -> tuple[dict[str, Any], dict[str, TextureStrip]]:
    model = json.loads(model_path.read_text())
    textures: dict[str, TextureStrip] = {}
    for ref, tex_path in model["textures"].items():
        if tex_path.startswith("#"):
            continue
        # coremc:item/skins/emberforge/metal -> textures/item/skins/...
        match = re.match(r"^([a-z0-9_.-]+):(.+)$", tex_path)
        ns, path = match.groups() if match else ("coremc", tex_path)
        assert ns == "coremc", f"unexpected namespace {ns}"
        png = ASSETS / (path.replace("item/", "textures/item/", 1) + ".png")
        if not png.exists():
            png = ASSETS / f"textures/{path}.png"
        textures[ref] = TextureStrip(png)
    return model, textures


def render_model(model_path: Path, out_base: Path, frames_to_render: int | None = None,
                 head_hint: bool = False) -> dict[str, Any]:
    model, textures = load_model(model_path)
    frame_counts = {t.frames for t in textures.values()}
    n_frames = max(frame_counts) if frame_counts else 1
    frames = []
    for f in range(n_frames):
        faces = build_faces(model, textures, f)
        if not faces:
            raise AssertionError(f"{model_path.name}: no visible faces at frame {f}")
        frames.append(render(faces, frame=f, head_hint=head_hint))
    write_png(out_base.with_suffix(".png"), frames[0])
    write_apng(out_base.with_suffix(".apng"), frames)
    # animation proof: count differing pixels between consecutive frames
    diffs = []
    for i in range(1, len(frames)):
        diff = sum(
            1
            for y in range(len(frames[0]))
            for x in range(len(frames[0][0]))
            if frames[i][y][x] != frames[i - 1][y][x]
        )
        diffs.append(diff)
    return {"frames": n_frames, "anim_pixels": diffs}


def main() -> None:
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    targets: list[tuple[Path, Path, bool]] = []
    tools_dir = ASSETS / "models/item/skins/tools"
    if which in ("all", "tools"):
        for col in sorted(tools_dir.iterdir()):
            for model in sorted(col.iterdir()):
                targets.append(
                    (model, OUT / "tools" / f"{col.name}_{model.stem}", False))
    hats_dir = ASSETS / "models/item/skins/hats"
    if which in ("all", "hats"):
        for model in sorted(hats_dir.iterdir()):
            targets.append((model, OUT / "hats" / model.stem, True))
    report: dict[str, Any] = {}
    for model_path, out_base, head_hint in targets:
        info = render_model(model_path, out_base, head_hint=head_hint)
        key = f"{out_base.parent.name}/{out_base.name}"
        report[key] = info
        if min(info["anim_pixels"]) == 0:
            raise AssertionError(f"{key}: frame with ZERO pixel movement (animation not visible)")
    print(json.dumps({"models": len(report), "ok": True}))


if __name__ == "__main__":
    main()
