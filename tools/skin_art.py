#!/usr/bin/env python3
"""CoreMC animated skin artwork — original pixel textures (standard library only).

Every texture here is painted from scratch as deterministic pixel art:
no image is downloaded, copied or derived from another pack.  Each painter
returns a list of 16x16 RGBA frames; the generator stacks them vertically
into a single animated strip plus a .mcmeta flipbook definition, which is
the Minecraft-native looping animation (fully client-side, no server work).

All painters are pure functions of (frame_index, frame_count) so the whole
pack rebuilds byte-identically.
"""
from __future__ import annotations

import math
import random

SIZE = 16


def _blank() -> list[list[tuple[int, int, int, int]]]:
    return [[(0, 0, 0, 0) for _ in range(SIZE)] for _ in range(SIZE)]


def _clamp(v: int) -> int:
    return max(0, min(255, v))


def _shade(base: tuple[int, int, int], factor: float) -> tuple[int, int, int]:
    return (_clamp(int(base[0] * factor)), _clamp(int(base[1] * factor)), _clamp(int(base[2] * factor)))


def _mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return (
        _clamp(int(a[0] + (b[0] - a[0]) * t)),
        _clamp(int(a[1] + (b[1] - a[1]) * t)),
        _clamp(int(a[2] + (b[2] - a[2]) * t)),
    )


def _with_alpha(rgb: tuple[int, int, int], a: int) -> tuple[int, int, int, int]:
    return (rgb[0], rgb[1], rgb[2], _clamp(a))


class Canvas:
    """One animation frame."""

    def __init__(self) -> None:
        self.px = _blank()

    def put(self, x: int, y: int, rgba: tuple[int, int, int, int]) -> None:
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.px[y][x] = rgba

    def get(self, x: int, y: int) -> tuple[int, int, int, int]:
        if 0 <= x < SIZE and 0 <= y < SIZE:
            return self.px[y][x]
        return (0, 0, 0, 0)

    def hline(self, x0: int, x1: int, y: int, rgba: tuple[int, int, int, int]) -> None:
        for x in range(x0, x1 + 1):
            self.put(x, y, rgba)

    def vline(self, x: int, y0: int, y1: int, rgba: tuple[int, int, int, int]) -> None:
        for y in range(y0, y1 + 1):
            self.put(x, y, rgba)

    def rect(self, x0: int, y0: int, x1: int, y1: int, rgba: tuple[int, int, int, int]) -> None:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, rgba)

    def frame_bytes(self) -> bytes:
        out = bytearray()
        for row in self.px:
            for px in row:
                out += bytes(px)
        return bytes(out)


# ======================================================================
#  Shared collection palettes (original, hand-picked)
# ======================================================================

EMBERFORGE = {
    "iron_dark": (54, 46, 48),
    "iron": (86, 74, 74),
    "iron_hi": (128, 110, 104),
    "rivet": (168, 148, 138),
    "magma_hot": (255, 214, 64),
    "magma": (255, 138, 34),
    "magma_deep": (198, 60, 14),
    "crust": (38, 24, 20),
    "coal": (30, 24, 26),
    "coal_hi": (58, 48, 50),
}

RIFTBOUND = {
    "void_metal": (24, 22, 32),
    "dark_metal": (38, 34, 50),
    "edge": (66, 58, 88),
    "edge_hi": (108, 92, 148),
    "rift_hot": (232, 178, 255),
    "rift": (178, 96, 255),
    "rift_deep": (104, 40, 190),
    "rift_void": (46, 16, 86),
    "shard": (150, 84, 226),
    "shard_hi": (214, 168, 255),
}

ASTRAL = {
    "star_metal": (196, 206, 226),
    "star_hi": (240, 246, 255),
    "star_lo": (138, 150, 186),
    "star_shadow": (94, 104, 142),
    "sky": (22, 26, 58),
    "sky_lo": (14, 16, 40),
    "nebula": (66, 78, 150),
    "star_hot": (255, 255, 255),
    "star": (198, 214, 255),
    "star_dim": (120, 140, 210),
    "gold": (236, 206, 128),
}

TIDECALLER = {
    "glass_deep": (16, 92, 118),
    "glass": (32, 148, 168),
    "glass_hi": (120, 226, 224),
    "glass_foam": (208, 250, 248),
    "water": (20, 108, 156),
    "water_hi": (86, 190, 226),
    "lume": (96, 255, 208),
    "lume_hi": (190, 255, 238),
    "lume_deep": (24, 158, 130),
    "sand": (196, 178, 128),
    "drift": (122, 92, 62),
    "drift_hi": (170, 136, 96),
}

OVERGROWN = {
    "stone": (116, 116, 108),
    "stone_hi": (148, 148, 138),
    "stone_lo": (84, 84, 80),
    "moss": (86, 138, 62),
    "moss_hi": (124, 178, 82),
    "moss_deep": (52, 96, 44),
    "leaf": (106, 170, 74),
    "wood": (104, 74, 48),
    "wood_hi": (150, 110, 70),
    "wood_lo": (70, 48, 32),
    "spore": (188, 232, 148),
    "spore_deep": (128, 190, 108),
    "flower": (232, 196, 92),
}

MOONLIT = {
    "cap": (168, 178, 208),
    "cap_hi": (222, 230, 248),
    "cap_lo": (118, 128, 168),
    "night": (34, 38, 74),
    "night_lo": (20, 22, 48),
    "moon": (244, 240, 214),
    "moon_lo": (198, 196, 178),
    "star_hot": (255, 255, 255),
    "star": (206, 216, 255),
}


def _frames(painter, frames: int) -> list[bytes]:
    return [painter(t, frames).frame_bytes() for t in range(frames)]


def _wave(t: int, n: int, phase: float) -> float:
    """0..1 sine wave looping over n frames."""
    return 0.5 + 0.5 * math.sin(2.0 * math.pi * (t / n + phase))


# ======================================================================
#  EMBERFORGE — flowing magma, forged metal, pulsing heat
# ======================================================================

def emberforge_metal(t: int, n: int) -> Canvas:
    c = Canvas()
    rng = random.Random(9901)
    c.rect(0, 0, 15, 15, _with_alpha(EMBERFORGE["iron"], 255))
    # forged plate bands + hammered speckle
    for y in range(16):
        band = EMBERFORGE["iron_dark"] if y % 5 == 0 else EMBERFORGE["iron"]
        c.hline(0, 15, y, _with_alpha(band, 255))
        for x in range(16):
            r = rng.random()
            if r > 0.86:
                c.put(x, y, _with_alpha(EMBERFORGE["iron_hi"], 255))
            elif r < 0.08:
                c.put(x, y, _with_alpha(EMBERFORGE["iron_dark"], 255))
    # diagonal magma crack — the heat crawls along it frame to frame
    crack = [(2, 13), (3, 12), (4, 11), (5, 10), (6, 10), (7, 9), (8, 8),
             (9, 7), (10, 6), (11, 5), (12, 4), (13, 3), (14, 3), (6, 12), (7, 11), (8, 10), (9, 9)]
    for i, (x, y) in enumerate(crack):
        heat = _wave(t, n, i / 5.0)
        col = _mix(EMBERFORGE["crust"], EMBERFORGE["magma_hot"], heat)
        c.put(x, y, _with_alpha(col, 255))
    # corner rivets
    for (x, y) in [(1, 1), (14, 1), (1, 14), (14, 14)]:
        c.put(x, y, _with_alpha(EMBERFORGE["rivet"], 255))
    return c


def emberforge_magma(t: int, n: int) -> Canvas:
    c = Canvas()
    rng = random.Random(4242)
    c.rect(0, 0, 15, 15, _with_alpha(EMBERFORGE["magma_deep"], 255))
    # molten swirl arcs drifting diagonally (loop by construction)
    for cell_y in range(4):
        for cell_x in range(4):
            heat = _wave(t, n, (cell_x + cell_y) / 4.0)
            base = _mix(EMBERFORGE["crust"], EMBERFORGE["magma"], heat)
            cx, cy = cell_x * 4, cell_y * 4
            for (dx, dy) in [(1, 1), (2, 1), (1, 2), (2, 2)]:
                c.put(cx + dx, cy + dy, _with_alpha(base, 255))
            r = rng.random()
            if r > 0.55:
                spark = _mix(base, EMBERFORGE["magma_hot"], _wave(t, n, (cell_x - cell_y) / 3.0))
                c.put(cx + (2 if rng.random() > 0.5 else 1), cy + 1, _with_alpha(spark, 255))
    # embers rising on the edges (wrap for seamless loop)
    for i in range(4):
        x = 1 + i * 4
        y = (13 - (t * 2 + i * 3) % 14) % 16
        c.put(x, y, _with_alpha(EMBERFORGE["magma_hot"], 230))
        c.put(x, (y + 15) % 16, _with_alpha(EMBERFORGE["magma"], 160))
    return c


def emberforge_grip(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(EMBERFORGE["coal"], 255))
    for y in range(16):
        for x in range(16):
            if y % 4 == 3:
                c.put(x, y, _with_alpha(EMBERFORGE["coal_hi"], 255))
    # wrap lines
    for y in range(1, 16, 4):
        c.hline(2, 13, y, _with_alpha(EMBERFORGE["iron_dark"], 255))
        c.hline(2, 13, y + 1, _with_alpha((40, 34, 36), 255))
    # travelling ember highlight — visible heat crawling along the haft
    head = (t * 2) % 16
    for x in range(16):
        d = (x - head) % 16
        if d <= 1:
            col = EMBERFORGE["magma"] if d == 0 else EMBERFORGE["magma_deep"]
            for y in range(16):
                if y % 4 != 3 and (x + y) % 2 == 0:
                    c.put(x, y, _with_alpha(col, 255))
    return c


def emberforge_ember(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(EMBERFORGE["coal"], 255))
    pulse = _wave(t, n, 0.0)
    core = _mix(EMBERFORGE["magma_deep"], EMBERFORGE["magma_hot"], pulse)
    c.rect(5, 5, 10, 10, _with_alpha(core, 255))
    c.rect(7, 7, 8, 8, _with_alpha(EMBERFORGE["magma_hot"], 255))
    # sparks orbiting the coal
    for i in range(3):
        ang = 2.0 * math.pi * (t / n + i / 3.0)
        x = int(8 + 5.4 * math.cos(ang)) % 16
        y = int(8 + 5.4 * math.sin(ang)) % 16
        c.put(x, y, _with_alpha(EMBERFORGE["magma"], 255))
        c.put((x + 1) % 16, y, _with_alpha(_shade(EMBERFORGE["magma"], 0.7), 200))
    return c


# ======================================================================
#  RIFTBOUND — fractured dark metal, moving violet rift energy
# ======================================================================

def riftbound_metal(t: int, n: int) -> Canvas:
    c = Canvas()
    rng = random.Random(7171)
    c.rect(0, 0, 15, 15, _with_alpha(RIFTBOUND["void_metal"], 255))
    for y in range(16):
        for x in range(16):
            r = rng.random()
            if r > 0.88:
                c.put(x, y, _with_alpha(RIFTBOUND["dark_metal"], 255))
            elif r < 0.06:
                c.put(x, y, _with_alpha((14, 12, 18), 255))
    # bevel
    c.hline(0, 15, 0, _with_alpha(RIFTBOUND["edge"], 255))
    c.hline(0, 15, 15, _with_alpha(RIFTBOUND["edge"], 255))
    c.vline(0, 0, 15, _with_alpha(RIFTBOUND["edge"], 255))
    c.vline(15, 0, 15, _with_alpha(RIFTBOUND["edge"], 255))
    # fracture line down the middle with violet light bleeding through
    gap_y = [3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
    bleed = _wave(t, n, 0.25)
    for i, y in enumerate(gap_y):
        x = 7 + (1 if i % 3 == 1 else 0)
        col = _mix(RIFTBOUND["edge"], RIFTBOUND["rift"], bleed * (0.4 + 0.6 * _wave(t, n, i / 6.0)))
        c.put(x, y, _with_alpha(col, 255))
        c.put(x + 1, y, _with_alpha(_shade(col, 0.6), 200))
    # glitch shimmer: one row slides out and back over the loop
    glitch_row = 5
    if 2 <= t <= 5:
        row = [c.get(x, glitch_row) for x in range(16)]
        shift = 1 if t < 4 else 0
        for x in range(16):
            c.put(x, glitch_row, row[(x + shift) % 16])
    return c


def riftbound_energy(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(RIFTBOUND["rift_void"], 255))
    # horizontal rift streaks flowing right (wrap => seamless loop)
    streak_rows = [1, 2, 5, 6, 7, 10, 11, 14]
    for i, y in enumerate(streak_rows):
        offset = (t * (1 + i % 3)) % 16
        length = 5 + (i % 3) * 2
        for k in range(length):
            x = (offset + k) % 16
            fade = 1.0 - k / length
            col = _mix(RIFTBOUND["rift_void"], RIFTBOUND["rift_hot"], fade)
            c.put(x, y, _with_alpha(col, 255))
    # nebula noise + rising particles
    rng = random.Random(313)
    for _ in range(26):
        x, y = rng.randrange(16), rng.randrange(16)
        if c.get(x, y)[3] < 200:
            c.put(x, y, _with_alpha(_mix(RIFTBOUND["rift_deep"], RIFTBOUND["rift_void"], rng.random()), 255))
    for i in range(3):
        x = (3 + i * 5) % 16
        y = (14 - (t + i * 5) % 15) % 16
        c.put(x, y, _with_alpha(RIFTBOUND["rift_hot"], 255))
    return c


def riftbound_grip(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(RIFTBOUND["void_metal"], 255))
    for y in range(0, 16, 3):
        c.hline(0, 15, y, _with_alpha(RIFTBOUND["dark_metal"], 255))
    # pulsing rune channel
    pulse = _wave(t, n, 0.0)
    for y in range(16):
        col = _mix(RIFTBOUND["rift_deep"], RIFTBOUND["rift"], pulse)
        if y % 2 == 0:
            c.put(7, y, _with_alpha(col, 255))
            c.put(8, y, _with_alpha(_shade(col, 0.75), 255))
    return c


def riftbound_shard(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(RIFTBOUND["rift_void"], 30))
    # crystal silhouette
    pts = [(8, 1), (11, 5), (10, 11), (8, 14), (6, 11), (5, 5)]
    fill = [
        (7, 2), (8, 2), (9, 3), (6, 3), (7, 3), (8, 3), (9, 4), (6, 4),
        (7, 4), (8, 4), (9, 4), (10, 4), (6, 5), (7, 5), (8, 5), (9, 5), (10, 5),
    ]
    body = pts + fill
    flick = _wave(t, n, 0.1)
    col = _mix(RIFTBOUND["shard"], RIFTBOUND["shard_hi"], flick)
    for (x, y) in body:
        c.put(x, y, _with_alpha(col, 255))
    # inner light travelling up the shard
    head = (t * 2) % 12 + 2
    for y in range(2, 14):
        if abs(y - head) <= 1:
            for x in (7, 8):
                if c.get(x, y)[3] > 0:
                    c.put(x, y, _with_alpha(RIFTBOUND["shard_hi"], 255))
    return c


# ======================================================================
#  ASTRAL — star-metal, orbiting constellations, soft celestial light
# ======================================================================

def astral_metal(t: int, n: int) -> Canvas:
    c = Canvas()
    rng = random.Random(2525)
    for y in range(16):
        for x in range(16):
            brushed = 1.0 - 0.14 * ((x + y) % 4)
            col = _shade(ASTRAL["star_metal"], brushed)
            c.put(x, y, _with_alpha(col, 255))
            if rng.random() > 0.92:
                c.put(x, y, _with_alpha(ASTRAL["star_hi"], 255))
    # bevel edges
    c.hline(0, 15, 0, _with_alpha(ASTRAL["star_hi"], 255))
    c.vline(0, 0, 15, _with_alpha(ASTRAL["star_lo"], 255))
    c.vline(15, 0, 15, _with_alpha(ASTRAL["star_shadow"], 255))
    c.hline(0, 15, 15, _with_alpha(ASTRAL["star_shadow"], 255))
    # celestial sheen sweeping diagonally across the star-metal (moves every frame)
    head = (t * 2) % 16
    for y in range(16):
        for x in range(16):
            d = ((x + y) - head * 2) % 32
            if d <= 3:
                lift = 0.22 - 0.05 * d
                base = c.get(x, y)
                if base[3] == 255:
                    rgb = _mix((base[0], base[1], base[2]), ASTRAL["star_hi"], lift)
                    c.put(x, y, _with_alpha(rgb, 255))
    # twinkling star inlays (phase-shifted = constellation shimmer)
    stars = [(3, 4), (12, 3), (4, 12), (11, 12)]
    for i, (x, y) in enumerate(stars):
        tw = _wave(t, n, i / 4.0)
        col = _mix(ASTRAL["star_dim"], ASTRAL["star_hot"], tw)
        c.put(x, y, _with_alpha(col, 255))
        if tw > 0.55:
            c.put(x + 1, y, _with_alpha(_shade(col, 0.7), 180))
            c.put(x - 1, y, _with_alpha(_shade(col, 0.7), 180))
    return c


def astral_nightglow(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(ASTRAL["sky"], 255))
    rng = random.Random(88)
    for _ in range(20):
        x, y = rng.randrange(16), rng.randrange(16)
        c.put(x, y, _with_alpha(_mix(ASTRAL["sky_lo"], ASTRAL["nebula"], rng.random()), 255))
    # three-star constellation orbiting the centre — real circular motion
    for i in range(3):
        ang = 2.0 * math.pi * (t / n + i / 3.0)
        x = int(7.5 + 5.6 * math.cos(ang))
        y = int(7.5 + 5.6 * math.sin(ang))
        bright = 0.6 + 0.4 * math.sin(ang)
        col = _mix(ASTRAL["star"], ASTRAL["star_hot"], bright)
        c.put(x, y, _with_alpha(col, 255))
        c.put(x + 1, y, _with_alpha(_shade(col, 0.75), 200))
        c.put(x, y + 1, _with_alpha(_shade(col, 0.75), 200))
    # trailing link line between two stars (constellation feel)
    ang0 = 2.0 * math.pi * (t / n)
    ang1 = 2.0 * math.pi * (t / n + 1.0 / 3.0)
    x0, y0 = int(7.5 + 5.6 * math.cos(ang0)), int(7.5 + 5.6 * math.sin(ang0))
    x1, y1 = int(7.5 + 5.6 * math.cos(ang1)), int(7.5 + 5.6 * math.sin(ang1))
    steps = 8
    for s in range(1, steps):
        xx = int(x0 + (x1 - x0) * s / steps)
        yy = int(y0 + (y1 - y0) * s / steps)
        if c.get(xx, yy) == (0, 0, 0, 0) or c.get(xx, yy)[3] < 120:
            c.put(xx, yy, _with_alpha(ASTRAL["star_dim"], 150))
    # centre heart glow pulse
    pulse = _wave(t, n, 0.5)
    c.put(7, 7, _with_alpha(_mix(ASTRAL["nebula"], ASTRAL["gold"], pulse), 255))
    c.put(8, 8, _with_alpha(_mix(ASTRAL["nebula"], ASTRAL["gold"], pulse), 200))
    return c


def astral_haft(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(ASTRAL["sky_lo"], 255))
    for y in range(16):
        if y % 5 == 2:
            c.hline(0, 15, y, _with_alpha(ASTRAL["sky"], 255))
    # star specks drifting sideways (1px per frame, wrapping => loop)
    specks = [(2, 3), (9, 6), (13, 11), (5, 13), (7, 0)]
    for i, (sx, sy) in enumerate(specks):
        x = (sx + t) % 16
        y = (sy + (t if i % 2 == 0 else 0)) % 16
        c.put(x, y, _with_alpha(ASTRAL["star"], 255))
    return c


def astral_star(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(ASTRAL["sky_lo"], 60))
    pulse = _wave(t, n, 0.0)
    col = _mix(ASTRAL["star"], ASTRAL["star_hot"], pulse)
    # four-point star
    c.rect(6, 4, 9, 11, _with_alpha(ASTRAL["star_lo"], 255))
    c.rect(4, 6, 11, 9, _with_alpha(ASTRAL["star_lo"], 255))
    c.rect(6, 6, 9, 9, _with_alpha(col, 255))
    c.rect(7, 7, 8, 8, _with_alpha(ASTRAL["star_hot"], 255))
    # sparkle cross growing and shrinking with the pulse
    reach = int(pulse * 6)
    for k in range(1, max(1, reach) + 1):
        a = int(90 + 130 * pulse)
        c.put(7, 7 - k, _with_alpha(col, a))
        c.put(8, 7 + k, _with_alpha(col, a))
        c.put(7 - k, 7, _with_alpha(col, a))
        c.put(8 + k, 8, _with_alpha(col, a))
    return c


# ======================================================================
#  TIDECALLER — carved sea-glass, moving water and bioluminescence
# ======================================================================

def tidecaller_glass(t: int, n: int) -> Canvas:
    c = Canvas()
    rng = random.Random(606)
    c.rect(0, 0, 15, 15, _with_alpha(TIDECALLER["glass"], 255))
    # carved facet lines (fixed)
    for (x0, y0, x1, y1) in [(0, 5, 5, 0), (5, 0, 10, 5), (10, 5, 15, 0), (0, 10, 5, 15), (10, 10, 15, 15)]:
        steps = max(abs(x1 - x0), abs(y1 - y0))
        for s in range(steps + 1):
            x = x0 + (x1 - x0) * s // max(1, steps)
            y = y0 + (y1 - y0) * s // max(1, steps)
            c.put(x, y, _with_alpha(TIDECALLER["glass_deep"], 255))
    # internal water band drifting downwards (carved channel)
    band = (t * 2) % 20 - 2
    for x in range(16):
        for y in range(16):
            d = (y - band) % 20
            if d <= 2:
                col = TIDECALLER["glass_hi"] if d == 1 else TIDECALLER["water_hi"]
                base = c.get(x, y)
                if base[3] == 255:
                    c.put(x, y, _with_alpha(_mix((base[0], base[1], base[2]), col, 0.8), 255))
    # foam speckle
    for _ in range(8):
        x, y = rng.randrange(16), rng.randrange(16)
        if c.get(x, y)[3] == 255 and rng.random() > 0.4:
            c.put(x, y, _with_alpha(TIDECALLER["glass_foam"], 170))
    return c


def tidecaller_biolume(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(TIDECALLER["water"], 255))
    rng = random.Random(77)
    for _ in range(18):
        x, y = rng.randrange(16), rng.randrange(16)
        c.put(x, y, _with_alpha(_shade(TIDECALLER["water"], 0.7), 255))
    # caustic light curves slowly shifting
    for i in range(3):
        base_y = (t + i * 5) % 16
        for x in range(16):
            y = (base_y + int(2 * math.sin((x + i * 4) / 2.5))) % 16
            c.put(x, y, _with_alpha(_mix(TIDECALLER["water"], TIDECALLER["water_hi"], 0.7), 255))
    # plankton dots: individual drift + pulse
    dots = [(2, 4), (6, 9), (11, 3), (13, 12), (8, 14), (4, 12)]
    for i, (dx, dy) in enumerate(dots):
        x = (dx + (t if i % 2 == 0 else -t)) % 16
        y = (dy - t // 2) % 16
        pulse = _wave(t, n, i / 6.0)
        col = _mix(TIDECALLER["lume_deep"], TIDECALLER["lume_hi"], pulse)
        c.put(x, y, _with_alpha(col, 255))
        c.put((x + 1) % 16, y, _with_alpha(_shade(col, 0.7), 190))
    return c


def tidecaller_haft(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(TIDECALLER["drift"], 255))
    rng = random.Random(1400)
    # driftwood grain
    for y in range(16):
        for x in range(16):
            r = rng.random()
            if r > 0.85:
                c.put(x, y, _with_alpha(TIDECALLER["drift_hi"], 255))
            elif r < 0.12:
                c.put(x, y, _with_alpha(_shade(TIDECALLER["drift"], 0.7), 255))
    for y in range(2, 16, 5):
        c.hline(1, 14, y, _with_alpha(_shade(TIDECALLER["drift"], 0.6), 255))
    # wet sheen band travelling along the haft
    head = (t * 2) % 16
    for y in range(16):
        d = (y - head) % 16
        if d <= 1:
            for x in range(16):
                if (x + y) % 2 == 0:
                    c.put(x, y, _with_alpha(TIDECALLER["water_hi"], 150))
    # barnacle specks
    for (x, y) in [(3, 3), (12, 8), (6, 13)]:
        c.put(x, y, _with_alpha(TIDECALLER["sand"], 255))
    return c


def tidecaller_float(t: int, n: int) -> Canvas:
    c = Canvas()
    # glass float (bobber): translucent bubble, moving highlight
    c.rect(4, 4, 11, 11, _with_alpha(TIDECALLER["glass"], 255))
    c.rect(5, 3, 10, 12, _with_alpha(TIDECALLER["glass"], 255))
    c.rect(3, 5, 12, 10, _with_alpha(TIDECALLER["glass"], 255))
    c.rect(6, 6, 9, 9, _with_alpha(TIDECALLER["water"], 255))
    # bioluminescent core pulsing
    pulse = _wave(t, n, 0.0)
    core = _mix(TIDECALLER["lume_deep"], TIDECALLER["lume_hi"], pulse)
    c.rect(7, 7, 8, 8, _with_alpha(core, 255))
    # highlight circling the sphere
    ang = 2.0 * math.pi * (t / n)
    x = int(7.5 + 4.4 * math.cos(ang))
    y = int(7.5 + 4.4 * math.sin(ang))
    c.put(x, y, _with_alpha(TIDECALLER["glass_foam"], 255))
    return c


# ======================================================================
#  OVERGROWN — ancient wood and stone, growing vines and drifting spores
# ======================================================================

def overgrown_stone(t: int, n: int) -> Canvas:
    c = Canvas()
    rng = random.Random(5150)
    c.rect(0, 0, 15, 15, _with_alpha(OVERGROWN["stone"], 255))
    for _ in range(22):
        x, y = rng.randrange(16), rng.randrange(16)
        c.put(x, y, _with_alpha(OVERGROWN["stone_hi"] if rng.random() > 0.5 else OVERGROWN["stone_lo"], 255))
    # crack
    for (x, y) in [(3, 2), (4, 3), (4, 4), (5, 5), (6, 6), (6, 7), (7, 8), (8, 9), (9, 10), (10, 11), (10, 12)]:
        c.put(x, y, _with_alpha(OVERGROWN["stone_lo"], 255))
    # vine path growing across the stone over the loop
    vine = [(1, 14), (2, 13), (3, 13), (4, 12), (5, 11), (6, 11), (7, 10), (8, 9),
            (9, 8), (10, 8), (11, 7), (12, 6), (13, 6), (13, 5), (14, 4)]
    grown = int(len(vine) * ((t + 1) / n))
    for i, (x, y) in enumerate(vine):
        if i < grown:
            col = OVERGROWN["moss_hi"] if i % 4 == 0 else OVERGROWN["moss"]
            c.put(x, y, _with_alpha(col, 255))
            if i % 4 == 3 and y > 0:
                c.put(x, y - 1, _with_alpha(OVERGROWN["leaf"], 255))
    # spore landing on stone at the end of the loop (visible growth cycle)
    if t == n - 1:
        c.put(14, 3, _with_alpha(OVERGROWN["spore"], 255))
    # damp sheen creeping across the stone every frame (keeps motion smooth)
    head = (t * 2) % 16
    for y in range(16):
        d = (y - head) % 16
        if d <= 1:
            for x in range(16):
                base = c.get(x, y)
                if base[3] == 255 and (x + y) % 2 == 0:
                    rgb = _mix((base[0], base[1], base[2]), OVERGROWN["moss_hi"], 0.35)
                    c.put(x, y, _with_alpha(rgb, 255))
    return c


def overgrown_spores(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(OVERGROWN["moss_deep"], 255))
    rng = random.Random(666)
    for _ in range(16):
        x, y = rng.randrange(16), rng.randrange(16)
        c.put(x, y, _with_alpha(_shade(OVERGROWN["moss_deep"], 0.75), 255))
    # spores drifting upward with gentle side sway, wrapping => loop
    spores = [(2, 13), (5, 9), (8, 12), (11, 6), (14, 10), (6, 3), (13, 2), (3, 6)]
    for i, (sx, sy) in enumerate(spores):
        x = (sx + int(1.4 * math.sin(2.0 * math.pi * (t / n + i / 5.0)))) % 16
        y = (sy - t) % 16
        pulse = _wave(t, n, i / 8.0)
        col = _mix(OVERGROWN["spore_deep"], OVERGROWN["spore"], pulse)
        c.put(x, y, _with_alpha(col, 255))
        c.put((x + 1) % 16, y, _with_alpha(_shade(col, 0.8), 160))
    # soft ground glow pulsing along the bottom
    glow = _wave(t, n, 0.3)
    for x in range(16):
        c.put(x, 15, _with_alpha(_mix(OVERGROWN["moss_deep"], OVERGROWN["spore"], glow * 0.6), 255))
    return c


def overgrown_haft(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(OVERGROWN["wood"], 255))
    rng = random.Random(99)
    for y in range(16):
        for x in range(16):
            r = rng.random()
            if r > 0.87:
                c.put(x, y, _with_alpha(OVERGROWN["wood_hi"], 255))
            elif r < 0.1:
                c.put(x, y, _with_alpha(OVERGROWN["wood_lo"], 255))
    # bark grain
    for y in range(1, 16, 4):
        c.hline(0, 15, y, _with_alpha(OVERGROWN["wood_lo"], 255))
    # sap pulse travelling up the living wood
    head = (15 - t * 2) % 16
    for y in range(16):
        d = (y - head) % 16
        if d <= 1:
            col = OVERGROWN["flower"] if d == 0 else OVERGROWN["moss_hi"]
            for x in range(16):
                if (x + y) % 3 == 0:
                    c.put(x, y, _with_alpha(col, 220))
    return c


def overgrown_bloom(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(OVERGROWN["wood_lo"], 255))
    # flower/spore cluster: petals open and close over the loop
    open_t = _wave(t, n, 0.0)
    reach = 1 + int(3 * open_t)
    cx, cy = 8, 8
    for k in range(reach):
        for (dx, dy) in [(k, 0), (-k, 0), (0, k), (0, -k), (k, k), (-k, -k), (k, -k), (-k, k)]:
            x, y = cx + dx, cy + dy
            if 0 <= x < 16 and 0 <= y < 16:
                col = OVERGROWN["leaf"] if k < reach - 1 else OVERGROWN["moss_hi"]
                c.put(x, y, _with_alpha(col, 255))
    c.rect(6, 6, 9, 9, _with_alpha(OVERGROWN["moss"], 255))
    c.put(7, 7, _with_alpha(OVERGROWN["flower"], 255))
    c.put(8, 8, _with_alpha(OVERGROWN["flower"], 255))
    # pollen/spores drifting off continuously (brightness follows the bloom)
    x = (2 + t) % 16
    pollen_a = int(120 + 120 * open_t)
    c.put(x, 4, _with_alpha(OVERGROWN["spore"], pollen_a))
    c.put((x + 9) % 16, 11, _with_alpha(OVERGROWN["spore"], max(90, pollen_a - 40)))
    return c


# ======================================================================
#  MOONLIT — moving moonlight and small star details (Moonlit Cap)
# ======================================================================

def moonlit_cap(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(MOONLIT["cap"], 255))
    rng = random.Random(3001)
    for _ in range(14):
        x, y = rng.randrange(16), rng.randrange(16)
        c.put(x, y, _with_alpha(MOONLIT["cap_lo"] if rng.random() > 0.5 else MOONLIT["cap_hi"], 255))
    # crescent moonlight band sweeping across the cap
    head = (t * 2) % 16
    for y in range(16):
        for x in range(16):
            d = (y - head) % 16
            if d <= 1:
                col = MOONLIT["moon"] if d == 0 else MOONLIT["moon_lo"]
                base = (c.get(x, y)[0], c.get(x, y)[1], c.get(x, y)[2])
                c.put(x, y, _with_alpha(_mix(base, col, 0.85), 255))
    return c


def moonlit_star(t: int, n: int) -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, _with_alpha(MOONLIT["night"], 255))
    rng = random.Random(2024)
    for _ in range(12):
        x, y = rng.randrange(16), rng.randrange(16)
        c.put(x, y, _with_alpha(MOONLIT["night_lo"], 255))
    # small star details twinkling in place (phase-shifted)
    stars = [(3, 3), (12, 2), (7, 7), (2, 11), (13, 12), (9, 9), (5, 14), (14, 7)]
    for i, (x, y) in enumerate(stars):
        tw = _wave(t, n, i / 8.0)
        col = _mix(MOONLIT["star"], MOONLIT["star_hot"], tw)
        c.put(x, y, _with_alpha(col, 255))
        if tw > 0.7:
            c.put(x + 1, y, _with_alpha(_shade(col, 0.8), 190))
            c.put(x, y + 1, _with_alpha(_shade(col, 0.8), 190))
    return c


# ======================================================================
#  Catalogue: texture key -> (painter, frames, frametime, description)
# ======================================================================

TEXTURES: dict[str, tuple[object, int, int, str]] = {
    # Emberforge — flowing magma, forged metal, pulsing heat
    "emberforge/metal": (emberforge_metal, 8, 3, "forged iron plate, heat crawling through the crack"),
    "emberforge/magma": (emberforge_magma, 8, 3, "molten swirl arcs drifting with rising embers"),
    "emberforge/grip": (emberforge_grip, 8, 2, "coal-dark haft with a travelling ember highlight"),
    "emberforge/ember": (emberforge_ember, 8, 3, "ember coal pulsing with orbiting sparks"),
    # Riftbound — fractured dark metal, moving violet rift energy
    "riftbound/metal": (riftbound_metal, 8, 3, "void-dark plate with pulsing fracture bleed and a glitch shimmer"),
    "riftbound/energy": (riftbound_energy, 8, 3, "violet rift streaks flowing with rising particles"),
    "riftbound/grip": (riftbound_grip, 8, 3, "blackened haft with a pulsing rune channel"),
    "riftbound/shard": (riftbound_shard, 8, 3, "rift crystal with inner light travelling upward"),
    # Astral — star-metal, orbiting constellations, celestial light
    "astral/metal": (astral_metal, 8, 3, "brushed star-metal with twinkling inlaid stars"),
    "astral/nightglow": (astral_nightglow, 8, 3, "three-star constellation orbiting a pulsing heart"),
    "astral/haft": (astral_haft, 8, 4, "night-blue haft with drifting star specks"),
    "astral/star": (astral_star, 8, 3, "four-point star pulsing with a sparkle cross"),
    # Tidecaller — carved sea-glass, moving water, bioluminescence
    "tidecaller/glass": (tidecaller_glass, 8, 3, "carved sea-glass with a water band drifting down the channels"),
    "tidecaller/biolume": (tidecaller_biolume, 8, 3, "caustic water curves with drifting pulsing plankton"),
    "tidecaller/haft": (tidecaller_haft, 8, 3, "driftwood haft with a travelling wet sheen"),
    "tidecaller/float": (tidecaller_float, 8, 3, "glass float with pulsing bioluminescent core"),
    # Overgrown — ancient wood and stone, growing vines, drifting spores
    "overgrown/stone": (overgrown_stone, 8, 3, "ancient stone with a vine that grows across it every loop"),
    "overgrown/spores": (overgrown_spores, 8, 3, "spores drifting upward with a gentle sway"),
    "overgrown/haft": (overgrown_haft, 8, 3, "living wood with a sap pulse travelling up"),
    "overgrown/bloom": (overgrown_bloom, 8, 3, "spore bloom opening and closing, shedding pollen"),
    # Moonlit (hat) — moving moonlight and small star details
    "moonlit/cap": (moonlit_cap, 8, 3, "cap cloth with a band of moonlight sweeping across"),
    "moonlit/star": (moonlit_star, 8, 3, "night field with phase-shifted twinkling stars"),
}


def paint_all() -> dict[str, list[bytes]]:
    """Returns texture key -> list of raw RGBA frame bytes."""
    return {key: _frames(painter, frames) for key, (painter, frames, _, _) in TEXTURES.items()}
