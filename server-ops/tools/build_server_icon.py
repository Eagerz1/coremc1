#!/usr/bin/env python3
"""Generate an original 64x64 CoreMC server-icon.png using only stdlib.

Simple pixel-art C/M lettering, dark palette, cyan/gold accents, no imported assets.
PNG is 8-bit RGBA. Run: python3 server-ops/tools/build_server_icon.py
"""
from __future__ import annotations
import argparse
from pathlib import Path
import struct
import zlib


W = H = 64
BACKGROUND = (12, 24, 36, 255)
INK = (6, 18, 26, 255)
CYAN = (39, 184, 208, 255)
PALE = (245, 244, 238, 255)
GOLD = (231, 180, 83, 255)

C = ["01111", "11000", "10000", "10000", "10000", "11000", "01111"]
M = ["10001", "11011", "10101", "10101", "10001", "10001", "10001"]


def build_pixels():
    p = [[BACKGROUND for _ in range(W)] for _ in range(H)]
    def rect(x1, y1, x2, y2, color):
        for yy in range(max(0, y1), min(H, y2)):
            for xx in range(max(0, x1), min(W, x2)):
                p[yy][xx] = color
    rect(4, 4, 60, 60, INK)
    rect(4, 4, 60, 7, CYAN)
    rect(4, 57, 60, 60, CYAN)
    rect(4, 4, 7, 60, CYAN)
    rect(57, 4, 60, 60, CYAN)
    rect(11, 47, 53, 49, GOLD)
    for glyph, start_x, color in [(C, 12, CYAN), (M, 35, PALE)]:
        for ry, row in enumerate(glyph):
            for rx, value in enumerate(row):
                if value == "1":
                    rect(start_x + rx * 4, 14 + ry * 4, start_x + rx * 4 + 4, 14 + ry * 4 + 4, color)
    rect(26, 20, 29, 41, GOLD)
    return p


def png_bytes(pixels):
    def chunk(tag, data):
        return (struct.pack("!I", len(data)) + tag + data +
                struct.pack("!I", zlib.crc32(tag + data) & 0xffffffff))
    rows = bytearray()
    for row in pixels:
        rows.append(0)  # PNG scanline filter = none
        for rgba in row:
            rows.extend(rgba)
    header = struct.pack("!IIBBBBB", W, H, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) +
            chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="server-ops/build/server-icon.png")
    args = parser.parse_args()
    target = Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(png_bytes(build_pixels()))
    print(f"Created {target} ({W}x{H} PNG). Copy to the Paper server root.")


if __name__ == "__main__":
    main()
