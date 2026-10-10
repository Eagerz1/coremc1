# Map, hub and structure briefs (1.21.11)

These are construction instructions for builders / WorldEdit, **not completed world files**.

## Spawn world — Sky Citadel
- `coremc_spawn`, dedicated empty world, center around `x=0 y=100 z=0`.
- Safe world border: 256×256, spectators can't descend into void, immediate spawn recall.
- Spawn platform: radius 36 main citadel; raised 9×9 cyan core on dark sandstone/deepslate plinth.
- Main thoroughfare: 5-block-wide path from center to role pads; any player reaches the first role within 12 seconds.
- Mainland hub should be <= 15 loaded chunks per player in normal viewing; pre-render structure, remove unused entities/armor stands.
- Palette: polished deepslate, tuff brick, smooth quartz, cyan concrete, weathered copper, dark prismarine. Accent patterns in blocks, not constant particle emitters.
- Spawn sightline: forward (south), largest CoreMC letters visible in 12–24 blocks, 3 goals visible: **Start Island**, **Choose Role**, **Season Champions**.
- Protections: deny building, PVP, TNT, vehicle griefing, water/lava flows, fire spread and enemy spawns, except where specifically approved. Verify CoreMC/WorldGuard/host protection interplay on staging.
- Deliver `hub_v1.schem` plus a PNG topdown plan, bill of blocks, and spawn coordinate.

## District locations / role storytelling
| District | Center (relative X/Z) | Shape and sign | Action |
|---|---:|---|---|
| Miner | -30,0 | rusted mine elevator / copper | /role -> Miner |
| Farmer | 0,30 | glass greenhouse / wheat | /role -> Farmer |
| Fisher | 30,0 | water wheel / prismarine | /role -> Fisher |
| Slayer | 0,-30 | tower gate / basalt | /role -> Slayer |
| Logger | -21,21 | cedar grove / moss | /role -> Logger |

All navigation destinations are cosmetic until NPC interactions are verified. When a player selects a role with CoreMC, send them through the existing `/role` menu. Do not simulate free resources or drop collectible build items at spawn.

## Landmark priorities
1. 17×17 **central island portal plaza** (highest value thumbnail).
2. 3-player season podium at 16,0; signs explicitly labeled as demo until actually wired.
3. Cosmetics/skins gallery at 16,16; previews should open native CoreMC `/skins`, not third-party inventories.
4. Rule/tutorial wall at 0,-16; four panels, 1–2 short instructions each.
5. Fountain and fishing district, controlled still water (no physics stress).
6. Seasonal banner stands that replace only decorative blocks between seasons.

## Lobby shell generator
`python3 tools/generate_lobby_commands.py --world coremc_spawn --y 100 > lobby-build-commands.txt` writes RCON/console-ready `execute in minecraft:overworld run ...`? **No**: it intentionally writes bare world-relative `fill` and `setblock` commands to execute **by a player present in the intended lobby world**. The commands are destructive where they place blocks and must be reviewed before execution. The server console has no world-relative execution context; see generator usage.
Do not paste into an islands world. The generated shell is a greybox for blocking out paths, **not a finished lobby**.

## Final handoff and acceptance
Map builder must supply a schematic created on matching target version, world origin info, full material list, exact NPC/leaderboard attach positions, spawn safety/fall rescue, accessible signage with color + text, nighttime lighting without darkness traps, no paid third-party model imports without a proper licence, and <5-minute render target from first join. Test with non-OP accounts at default view distance.
