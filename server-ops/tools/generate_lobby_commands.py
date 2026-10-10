#!/usr/bin/env python3
"""Create a *greybox*, separate-world CoreMC lobby using vanilla commands.

Safety: the emitted commands target ONE EXPLICIT Minecraft dimension using
'execute in <dimension> run ...'. The operator must create an isolated staging
world, determine its exact dimension key and inspect the output before running.
This tool does not run commands on a Minecraft server.
"""
from __future__ import annotations
import argparse
from pathlib import Path


def build(x: int, y: int, z: int) -> list[str]:
    commands: list[str] = []
    def fill(x1, y1, z1, x2, y2, z2, block):
        commands.append(f"fill {x+x1} {y+y1} {z+z1} {x+x2} {y+y2} {z+z2} {block}")
    def block(dx, dy, dz, material):
        commands.append(f"setblock {x+dx} {y+dy} {z+dz} {material}")
    # Floating core island, deliberately small enough for ordinary fill limits.
    for radius, dy, material in [
        (27, -9, "deepslate_tiles"),
        (30, -8, "polished_deepslate"),
        (32, -7, "tuff_bricks"),
        (34, -6, "polished_tuff"),
        (35, -5, "smooth_stone")]:
        fill(-radius, dy, -radius, radius, dy, radius, material)
    # Central light plinth + start portal marker.
    fill(-10, -4, -10, 10, -4, 10, "polished_deepslate")
    fill(-7, -3, -7, 7, -3, 7, "smooth_quartz")
    fill(-4, -2, -4, 4, -2, 4, "cyan_concrete")
    fill(-1, -1, -1, 1, -1, 1, "sea_lantern")
    block(0, 0, 0, "lodestone")
    # Accessible roads; each road connects main plinth to district pads.
    fill(-3, -4, -29, 3, -4, -8, "smooth_quartz")
    fill(-3, -4, 8, 3, -4, 29, "smooth_quartz")
    fill(-29, -4, -3, -8, -4, 3, "smooth_quartz")
    fill(8, -4, -3, 29, -4, 3, "smooth_quartz")
    fill(-24, -4, 17, -5, -4, 24, "smooth_quartz")
    # Five labelled-by-material role plinths; NPC signage must be built manually.
    pads = [
        (-25, 0, "oxidized_copper", "lantern"),       # Miner
        (0, 25, "moss_block", "shroomlight"),           # Farmer
        (25, 0, "dark_prismarine", "sea_lantern"),      # Fisher
        (0, -25, "polished_blackstone", "soul_lantern"),# Slayer
        (-21, 21, "spruce_planks", "lantern"),          # Logger
    ]
    for px, pz, base, light in pads:
        fill(px-4, -3, pz-4, px+4, -3, pz+4, base)
        fill(px-2, -2, pz-2, px+2, -2, pz+2, "smooth_quartz")
        block(px, -1, pz, light)
        for dx, dz in [(-4,-4),(4,-4),(-4,4),(4,4)]:
            block(px+dx, -2, pz+dz, "end_rod")
    # Contrast trim and no mobs/fire/fluid/entities.
    for cx, cz in [(-10,-10),(10,-10),(-10,10),(10,10)]:
        block(cx, -3, cz, "sea_lantern")
    return commands


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dimension", default="minecraft:coremc_spawn",
                        help="Exact registered dimension ID (verify with /execute in autocomplete)")
    parser.add_argument("--x", type=int, default=0)
    parser.add_argument("--y", type=int, default=100)
    parser.add_argument("--z", type=int, default=0)
    parser.add_argument("--output", default="server-ops/build/lobby-shell-commands.txt")
    args = parser.parse_args()
    if ":" not in args.dimension or not all(c.isalnum() or c in "_:./-" for c in args.dimension):
        parser.error("--dimension must be an explicit, valid namespaced dimension key")
    if args.dimension in {"minecraft:overworld", "minecraft:the_end", "minecraft:the_nether", "minecraft:islands"}:
        parser.error("Refusing known gameplay or vanilla dimensions; use an independent lobby dimension")
    actions = build(args.x, args.y, args.z)
    result = [
        "# Generated prototype, NOT a finished lobby. Run ONLY on staging.",
        f"# Dimension: {args.dimension}. Verify it actually exists and is an EMPTY separate world.",
        "# Each line is for a console or RCON operator. Review all commands and take a backup.",
        "# If the dimension is unregistered, commands should fail instead of touching another world.",
    ] + [f"execute in {args.dimension} run {cmd}" for cmd in actions]
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(result) + "\n", encoding="utf-8")
    print(f"Wrote {len(actions)} block operations to {out}. No live server was touched.")


if __name__ == "__main__":
    main()
