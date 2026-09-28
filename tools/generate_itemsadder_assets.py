#!/usr/bin/env python3
"""Generate the CoreMC ItemsAdder source pack deterministically.

This generator intentionally uses only the Python standard library.  It makes
small, hand-authored pixel sprites rather than downloading or copying pack
assets.  Re-run it after changing the catalog; the output is reproducible.
"""
from __future__ import annotations

import colorsys
import json
import math
import os
import random
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "src/main/resources/itemsadder/contents/coremc"
ASSETS = PACK / "resourcepack/assets/coremc"
TEXTURES = ASSETS / "textures/item"
MODELS = ASSETS / "models/item"
MANIFEST = ROOT / "src/main/resources/itemsadder/model-ids.yml"
CATALOG = ROOT / "src/main/resources/itemsadder/fishing-catalog.yml"
SKINS = ROOT / "src/main/resources/itemsadder/skin-registry.yml"

# Stable ranges.  Leave generous gaps so future assets never need to renumber
# an item that may already be held by a player.
RANGES = {
    "fish_common": 20000,
    "fish_uncommon": 20100,
    "fish_rare": 20200,
    "fish_epic": 20300,
    "fish_mythic": 20400,
    "key": 21000,
    "lootbox": 21100,
    "progression": 21200,
    "omnitool": 21400,
    "skin": 21500,
}

# slug, display name, body form, surface pattern, fin/tail signature
FISH = {
    "common": [
        ("creek_dart", "Creek Dart", "dart", "lateral", "fork"),
        ("reed_pikelet", "Reed Pikelet", "pike", "bars", "highfin"),
        ("pebble_loach", "Pebble Loach", "loach", "spots", "lowfin"),
        ("willow_bream", "Willow Bream", "bream", "bands", "roundtail"),
        ("mudskipper", "Mudskipper", "mud", "mottled", "feet"),
        ("glass_minnow", "Glass Minnow", "minnow", "clear", "split"),
        ("sunscale", "Sunscale", "scale", "scales", "sunray"),
        ("marsh_killifish", "Marsh Killifish", "killifish", "eye", "spear"),
        ("sand_goby", "Sand Goby", "goby", "mask", "fan"),
        ("rainbarb", "Rain Barb", "barb", "rainbow", "barbs"),
        ("moss_tetra", "Moss Tetra", "tetra", "chevron", "tiny"),
        ("drift_dace", "Drift Dace", "dace", "stripe", "sweep"),
        ("lantern_anchovy", "Lantern Anchovy", "anchovy", "glowdots", "needle"),
        ("kelp_sardine", "Kelp Sardine", "sardine", "kelp", "notch"),
        ("shellback_sculpin", "Shellback Sculpin", "sculpin", "shell", "armour"),
        ("frost_ide", "Frost Ide", "ide", "ice", "icefin"),
        ("copper_roe", "Copper Roe", "roe", "cluster", "roundtail"),
        ("tide_mullet", "Tide Mullet", "mullet", "splitline", "blunt"),
        ("bluegill", "Bluegill", "bluegill", "gill", "gill"),
        ("pond_perch", "Pond Perch", "perch", "perch", "spiny"),
        ("brook_trout", "Brook Trout", "trout", "speckle", "adipose"),
        ("ember_guppy", "Ember Guppy", "guppy", "ember", "veil"),
        ("willow_carp", "Willow Carp", "carp", "scalegrid", "barbel"),
        ("salt_sprat", "Salt Sprat", "sprat", "salt", "fork"),
    ],
    "uncommon": [
        ("moonfin", "Moonfin", "crescent", "moonspot", "crescent"),
        ("coral_snapper", "Coral Snapper", "snapper", "coral", "spine"),
        ("dusk_koi", "Dusk Koi", "koi", "cloud", "flow"),
        ("silverneedle", "Silver Needlefish", "needlefish", "needle", "needle"),
        ("thunder_tench", "Thunder Tench", "tench", "bolt", "bolt"),
        ("lantern_loach", "Lantern Loach", "lanternloach", "lantern", "whisker"),
        ("mangrove_bass", "Mangrove Bass", "bass", "root", "root"),
        ("aurora_char", "Aurora Char", "char", "aurora", "halo"),
        ("glasscat", "Glasscat", "glasscat", "bones", "whisker"),
        ("tide_runner", "Tide Runner", "runner", "speed", "sweep"),
        ("ribbon_eel", "Ribbon Eel", "ribbon", "ribbon", "crest"),
        ("speckled_mandarin", "Speckled Mandarin", "mandarin", "speckle", "fan"),
        ("cloud_darter", "Cloud Darter", "cloudfish", "cloud", "wing"),
        ("storm_goby", "Storm Goby", "stormgoby", "storm", "spike"),
        ("jade_codlet", "Jade Codlet", "cod", "jade", "chin"),
        ("firetail_minnow", "Firetail Minnow", "fireminnow", "firetail", "flame"),
        ("brine_puffer", "Brine Puffer", "puffer", "brine", "spines"),
        ("reed_needlefish", "Reed Needlefish", "needlefish", "reed", "needle"),
        ("opal_bream", "Opal Bream", "bream", "opal", "roundtail"),
        ("dusk_swordlet", "Dusk Swordlet", "sword", "dusk", "sword"),
        ("crystal_molly", "Crystal Molly", "molly", "crystal", "sail"),
        ("mossy_pike", "Mossy Pike", "pike", "moss", "highfin"),
        ("bluewater_hake", "Bluewater Hake", "hake", "water", "twin"),
        ("copperhead_dace", "Copperhead Dace", "dace", "copper", "sweep"),
    ],
    "rare": [
        ("abyssal_lanternfish", "Abyssal Lanternfish", "angler", "abyss", "lure"),
        ("sunken_sawfish", "Sunken Sawfish", "sawfish", "sunken", "saw"),
        ("sapphire_stingray", "Sapphire Stingray", "stingray", "sapphire", "wings"),
        ("ghost_koi", "Ghost Koi", "koi", "ghost", "flow"),
        ("prism_tetra", "Prism Tetra", "tetra", "prism", "diamond"),
        ("ironjaw_barracuda", "Ironjaw Barracuda", "barracuda", "ironjaw", "jaw"),
        ("pearl_seahorse", "Pearl Seahorse", "seahorse", "pearl", "curl"),
        ("emberfin_salmon", "Emberfin Salmon", "salmon", "ember", "fin"),
        ("void_catfish", "Void Catfish", "catfish", "void", "whisker"),
        ("thunder_manta", "Thunder Manta", "manta", "thunder", "wings"),
        ("frostbite_pike", "Frostbite Pike", "pike", "frostbite", "icefin"),
        ("bloodscale_tuna", "Bloodscale Tuna", "tuna", "bloodscale", "crescent"),
        ("moonlit_angler", "Moonlit Angler", "angler", "moonlit", "lure"),
        ("coral_crownfish", "Coral Crownfish", "crownfish", "coral", "crown"),
        ("stormglass_eel", "Stormglass Eel", "eel", "stormglass", "ribbon"),
        ("blackwater_ray", "Blackwater Ray", "ray", "blackwater", "wings"),
        ("golden_grouper", "Golden Grouper", "grouper", "golden", "mouth"),
        ("deepforge_ide", "Deepforge Ide", "ide", "forge", "hammer"),
        ("amethyst_betta", "Amethyst Betta", "betta", "amethyst", "veil"),
        ("tempest_mackerel", "Tempest Mackerel", "mackerel", "tempest", "fork"),
        ("obsidian_gar", "Obsidian Gar", "gar", "obsidian", "snout"),
        ("starfall_fangfish", "Starfall Fangfish", "fangfish", "starfall", "fang"),
        ("royal_discus", "Royal Discus", "discus", "royal", "roundtail"),
        ("rift_skate", "Rift Skate", "skate", "rift", "wings"),
    ],
    "epic": [
        ("celestial_koi", "Celestial Koi", "koi", "celestial", "flow"),
        ("leviathan_sardine", "Leviathan Sardine", "sardine", "leviathan", "notch"),
        ("phoenix_betta", "Phoenix Betta", "betta", "phoenix", "veil"),
        ("astral_swordfish", "Astral Swordfish", "swordfish", "astral", "sword"),
        ("nebula_ray", "Nebula Ray", "ray", "nebula", "wings"),
        ("eclipse_angelfish", "Eclipse Angelfish", "angelfish", "eclipse", "highfin"),
        ("comet_tail", "Comet Tail", "cometfish", "comet", "comet"),
        ("aurora_arowana", "Aurora Arowana", "arowana", "aurora", "barbel"),
        ("chronicle_sturgeon", "Chronicle Sturgeon", "sturgeon", "chronicle", "scute"),
        ("voidfin_shark", "Voidfin Shark", "shark", "voidfin", "dorsal"),
        ("tempest_dragonfish", "Tempest Dragonfish", "dragonfish", "tempest", "fang"),
        ("sunflare_mola", "Sunflare Mola", "mola", "sunflare", "dorsal"),
        ("deepcrown_seahorse", "Deepcrown Seahorse", "seahorse", "deepcrown", "crown"),
        ("prism_wing", "Prism Wing", "wingfish", "prism", "wings"),
        ("obsidian_orca", "Obsidian Orca", "orca", "obsidian", "dorsal"),
        ("moonbreaker_tuna", "Moonbreaker Tuna", "tuna", "moonbreaker", "crescent"),
        ("rift_wyrm", "Rift Wyrm", "wyrmfish", "rift", "ribbon"),
        ("sapphire_marlin", "Sapphire Marlin", "marlin", "sapphire", "spear"),
        ("thunderhead_manta", "Thunderhead Manta", "manta", "thunderhead", "wings"),
        ("gilded_coelacanth", "Gilded Coelacanth", "coelacanth", "gilded", "lobes"),
        ("starforge_puffer", "Starforge Puffer", "puffer", "starforge", "spines"),
        ("bloodmoon_barracuda", "Bloodmoon Barracuda", "barracuda", "bloodmoon", "jaw"),
        ("crystal_krakenfish", "Crystal Krakenfish", "krakenfish", "crystal", "tentacles"),
        ("skyvault_flyfish", "Skyvault Flyfish", "flyfish", "skyvault", "wings"),
    ],
    "mythic": [
        ("worldroot_koi", "Worldroot Koi", "koi", "worldroot", "flow"),
        ("elder_leviathan", "Elder Leviathan", "leviathan", "elder", "crown"),
        ("astral_whalelet", "Astral Whalelet", "whalelet", "astral", "flukes"),
        ("void_emperor", "Void Emperor", "emperor", "void", "crown"),
        ("solar_sawfish", "Solar Sawfish", "sawfish", "solar", "saw"),
        ("lunar_seraph", "Lunar Seraph", "seraph", "lunar", "halo"),
        ("genesis_ray", "Genesis Ray", "ray", "genesis", "wings"),
        ("rift_angler", "Rift Angler", "angler", "rift", "lure"),
        ("timeworn_sturgeon", "Timeworn Sturgeon", "sturgeon", "timeworn", "scute"),
        ("crownfire_arowana", "Crownfire Arowana", "arowana", "crownfire", "barbel"),
        ("stormking_marlin", "Stormking Marlin", "marlin", "stormking", "spear"),
        ("auric_dragonfish", "Auric Dragonfish", "dragonfish", "auric", "fang"),
        ("abyss_crown_shark", "Abyss Crown Shark", "shark", "abysscrown", "dorsal"),
        ("starheart_betta", "Starheart Betta", "betta", "starheart", "veil"),
        ("eternal_coelacanth", "Eternal Coelacanth", "coelacanth", "eternal", "lobes"),
        ("nebula_manta", "Nebula Manta", "manta", "nebula", "wings"),
        ("nightveil_eel", "Nightveil Eel", "eel", "nightveil", "ribbon"),
        ("dawnbringer_tuna", "Dawnbringer Tuna", "tuna", "dawnbringer", "crescent"),
        ("prismatic_mola", "Prismatic Mola", "mola", "prismatic", "dorsal"),
        ("obsidian_leviathan", "Obsidian Leviathan", "leviathan", "obsidian", "crown"),
        ("celestial_seahorse", "Celestial Seahorse", "seahorse", "celestial", "curl"),
        ("frostnova_pike", "Frostnova Pike", "pike", "frostnova", "icefin"),
        ("crimson_cometfish", "Crimson Cometfish", "cometfish", "crimson", "comet"),
        ("skyfather_orca", "Skyfather Orca", "orca", "skyfather", "dorsal"),
    ],
}

RARITY = {
    "common": ("Common", "#6f9b9a", 0.0, 0),
    "uncommon": ("Uncommon", "#45c89b", 0.0, 1),
    "rare": ("Rare", "#4e8cff", 0.0, 2),
    "epic": ("Epic", "#bd63ff", 0.0, 3),
    "mythic": ("Mythic", "#ffb52e", 0.0, 4),
}

# Non-fish catalog: id, display, model id, vanilla fallback material, texture path,
# model parent.  Existing Java data IDs are deliberately represented rather than
# replacing them; PDC identities continue to be owned by CoreMC.
ITEMS = [
    ("vote_key", "Vote Key", 21000, "PAPER", "keys/vote_key", "generated"),
    ("river_key", "River Key", 21001, "TRIPWIRE_HOOK", "keys/river_key", "generated"),
    ("sky_key", "Sky Key", 21002, "TRIPWIRE_HOOK", "keys/sky_key", "generated"),
    ("crimson_key", "Crimson Key", 21003, "BLAZE_POWDER", "keys/crimson_key", "generated"),
    ("boost_key", "Boost Key", 21004, "AMETHYST_SHARD", "keys/boost_key", "generated"),
    # Existing crate-key ids remain available as visual aliases for old stacks.
    ("ember_key", "Ember Key", 21005, "BLAZE_POWDER", "keys/ember_key", "generated"),
    ("rune_key", "Rune Key", 21006, "AMETHYST_SHARD", "keys/rune_key", "generated"),
    ("titan_key", "Titan Key", 21007, "NETHERITE_SCRAP", "keys/titan_key", "generated"),
    ("mythic_key", "Mythic Key", 21008, "NETHER_STAR", "keys/mythic_key", "generated"),
    ("daily_key", "Daily Key", 21009, "CLOCK", "keys/daily_key", "generated"),
    ("core_lootbox", "Core Lootbox", 21100, "CHEST", "lootboxes/core", "generated"),
    ("monthly_lootbox", "Monthly Lootbox", 21101, "ENDER_CHEST", "lootboxes/monthly", "generated"),
    ("seasonal_lootbox", "Seasonal Lootbox", 21102, "SHULKER_BOX", "lootboxes/seasonal", "generated"),
    ("sky_crate", "Sky Crate", 21103, "ENDER_CHEST", "lootboxes/sky", "generated"),
    ("ember_crate", "Ember Crate", 21104, "ENDER_CHEST", "lootboxes/ember", "generated"),
    ("rune_crate", "Rune Crate", 21105, "ENDER_CHEST", "lootboxes/rune", "generated"),
    ("titan_crate", "Titan Crate", 21106, "ENDER_CHEST", "lootboxes/titan", "generated"),
    ("mythic_crate", "Mythic Crate", 21107, "ENDER_CHEST", "lootboxes/mythic", "generated"),
    ("daily_crate", "Daily Crate", 21108, "CHEST", "lootboxes/daily", "generated"),
    ("core_fragment", "Core Fragment", 21200, "AMETHYST_SHARD", "progression/core_fragment", "generated"),
    ("generator_core", "Generator Core", 21201, "HEART_OF_THE_SEA", "progression/generator_core", "generated"),
    ("ancient_seed", "Ancient Seed", 21202, "WHEAT_SEEDS", "progression/ancient_seed", "generated"),
    ("ancient_ore", "Ancient Ore", 21203, "ANCIENT_DEBRIS", "progression/ancient_ore", "generated"),
    ("sunken_cache", "Sunken Cache", 21204, "CHEST", "progression/sunken_cache", "generated"),
    ("monster_relic", "Monster Relic", 21205, "WITHER_SKELETON_SKULL", "progression/monster_relic", "generated"),
    ("rift_shard", "Rift Shard", 21206, "ECHO_SHARD", "progression/rift_shard", "generated"),
    ("rift_crystal", "Rift Crystal", 21207, "AMETHYST_CLUSTER", "progression/rift_crystal", "generated"),
    ("rift_essence", "Rift Essence", 21208, "DRAGON_BREATH", "progression/rift_essence", "generated"),
    ("island_module_frame", "Island Module Frame", 21209, "IRON_BLOCK", "progression/island_module_frame", "generated"),
    ("island_module_core", "Island Module Core", 21210, "NETHER_STAR", "progression/island_module_core", "generated"),
    ("island_module_wiring", "Island Module Wiring", 21211, "REDSTONE", "progression/island_module_wiring", "generated"),
    ("companion_evolution_stone", "Companion Evolution Stone", 21212, "GLOWSTONE_DUST", "progression/companion_evolution_stone", "generated"),
    ("companion_evolution_shard", "Companion Evolution Shard", 21213, "PRISMARINE_CRYSTALS", "progression/companion_evolution_shard", "generated"),
    ("companion_memory", "Companion Memory", 21214, "KNOWLEDGE_BOOK", "progression/companion_memory", "generated"),
    ("spring_token", "Spring Token", 21215, "PINK_TULIP", "seasonal/spring_token", "generated"),
    ("summer_token", "Summer Token", 21216, "SUNFLOWER", "seasonal/summer_token", "generated"),
    ("autumn_token", "Autumn Token", 21217, "ORANGE_DYE", "seasonal/autumn_token", "generated"),
    ("winter_token", "Winter Token", 21218, "SNOWBALL", "seasonal/winter_token", "generated"),
    # The Java OmniTool remains a NETHERITE_PICKAXE with its existing PDC
    # marker and role binding; these definitions document the matching visual
    # models and provide safe pack-side icons without replacing that identity.
    ("omnitool_miner", "Miner Omni-Tool", 21400, "NETHERITE_PICKAXE", "tools/omnitool_miner", "handheld"),
    ("omnitool_farmer", "Farmer Omni-Tool", 21401, "NETHERITE_PICKAXE", "tools/omnitool_farmer", "handheld"),
    ("omnitool_fisher", "Fisher Omni-Tool", 21402, "NETHERITE_PICKAXE", "tools/omnitool_fisher", "handheld"),
    ("omnitool_slayer", "Slayer Omni-Tool", 21403, "NETHERITE_PICKAXE", "tools/omnitool_slayer", "handheld"),
    ("omnitool_logger", "Logger Omni-Tool", 21404, "NETHERITE_PICKAXE", "tools/omnitool_logger", "handheld"),
    ("omnitool_universal", "Universal Omni-Tool", 21405, "NETHERITE_PICKAXE", "tools/omnitool_universal", "handheld"),
    ("generator_skin_cobble", "Generator Skin: Cinderstone", 21500, "PAPER", "skins/generator/cinderstone", "generated"),
    ("generator_skin_ice", "Generator Skin: Glacial Relay", 21501, "PAPER", "skins/generator/glacial_relay", "generated"),
    ("generator_skin_rift", "Generator Skin: Rift Prism", 21502, "PAPER", "skins/generator/rift_prism", "generated"),
    ("companion_skin_emberfox", "Companion Skin: Emberfox", 21510, "PAPER", "skins/companion/emberfox", "generated"),
    ("companion_skin_moonmoth", "Companion Skin: Moonmoth", 21511, "PAPER", "skins/companion/moonmoth", "generated"),
    ("companion_skin_starseed", "Companion Skin: Starseed", 21512, "PAPER", "skins/companion/starseed", "generated"),
]


def esc(value: str) -> str:
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def hex_rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip('#')
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def blend(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def rgba(hex_or_rgb, alpha=255):
    if isinstance(hex_or_rgb, str):
        rgb = hex_rgb(hex_or_rgb)
    else:
        rgb = hex_or_rgb
    return (*rgb, alpha)


class Canvas:
    def __init__(self, size=32):
        self.size = size
        self.pixels = [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]

    def set(self, x, y, color):
        if 0 <= x < self.size and 0 <= y < self.size and color[3] > 0:
            self.pixels[y][x] = color

    def rect(self, x0, y0, x1, y1, color):
        for y in range(max(0, y0), min(self.size, y1 + 1)):
            for x in range(max(0, x0), min(self.size, x1 + 1)):
                self.set(x, y, color)

    def poly(self, points, color):
        # Integer scanline polygon fill, intentionally crisp/no anti-aliasing.
        ys = range(max(0, min(y for _, y in points)), min(self.size - 1, max(y for _, y in points)) + 1)
        for y in ys:
            intersections = []
            for (x1, y1), (x2, y2) in zip(points, points[1:] + points[:1]):
                if y1 == y2:
                    continue
                if min(y1, y2) <= y < max(y1, y2):
                    intersections.append(x1 + (y - y1) * (x2 - x1) / (y2 - y1))
            intersections.sort()
            for left, right in zip(intersections[::2], intersections[1::2]):
                for x in range(max(0, math.ceil(left)), min(self.size, math.floor(right) + 1)):
                    self.set(x, y, color)

    def ellipse(self, box, color):
        x0, y0, x1, y1 = box
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        rx, ry = max(0.5, (x1 - x0) / 2), max(0.5, (y1 - y0) / 2)
        for y in range(max(0, y0), min(self.size, y1 + 1)):
            for x in range(max(0, x0), min(self.size, x1 + 1)):
                if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1:
                    self.set(x, y, color)

    def png(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        raw = b''.join(b'\x00' + b''.join(bytes(px) for px in row) for row in self.pixels)

        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

        data = b'\x89PNG\r\n\x1a\n'
        data += chunk(b'IHDR', struct.pack('>IIBBBBB', self.size, self.size, 8, 6, 0, 0, 0))
        data += chunk(b'IDAT', zlib.compress(raw, 9))
        data += chunk(b'IEND', b'')
        path.write_bytes(data)


def palette_for(rarity: str, seed: int):
    _, rarity_hex, _, tier = RARITY[rarity]
    random.seed(seed)
    hue = (seed * 0.071 + tier * 0.17) % 1.0
    # The tier raises saturation/value and is part of the rarity language.
    saturation = 0.34 + tier * 0.11
    value = 0.55 + tier * 0.09
    base = tuple(round(v * 255) for v in colorsys.hsv_to_rgb(hue, saturation, min(0.98, value)))
    shadow = blend(base, (10, 12, 26), 0.52)
    light = blend(base, (255, 255, 255), 0.28 + tier * 0.05)
    accent = hex_rgb(rarity_hex)
    outline = blend(shadow, (8, 8, 18), 0.45)
    return rgba(outline), rgba(shadow), rgba(base), rgba(light), rgba(accent)


def tail(c: Canvas, signature: str, outline, accent, seed):
    variants = {
        "fork": [(5, 13), (1, 8), (2, 15), (1, 23), (7, 18)],
        "highfin": [(7, 14), (2, 8), (5, 18)],
        "lowfin": [(7, 18), (2, 24), (9, 21)],
        "roundtail": [(7, 13), (2, 10), (1, 16), (2, 22), (7, 19)],
        "spear": [(7, 15), (0, 12), (4, 16), (0, 20), (7, 18)],
        "fan": [(7, 14), (1, 7), (0, 16), (2, 24), (8, 19)],
        "wings": [(8, 13), (1, 5), (0, 15), (0, 27), (9, 20)],
        "crescent": [(8, 13), (2, 6), (0, 13), (3, 18), (8, 20)],
        "needle": [(8, 16), (0, 12), (0, 20)],
    }
    pts = variants.get(signature, variants["fork"])
    c.poly(pts, outline)
    if len(pts) >= 4:
        c.poly([(x + 1, y) for x, y in pts[1:-1]], accent)


def draw_fish(rarity: str, index: int, slug: str, form: str, pattern: str, signature: str) -> Canvas:
    seed = sum((i + 1) * ord(ch) for i, ch in enumerate(slug)) + index * 997
    random.seed(seed)
    outline, shadow, body, light, accent = palette_for(rarity, seed)
    _, _, _, tier = RARITY[rarity]
    c = Canvas()
    # Higher rarities get a restrained halo and stronger silhouette treatment,
    # never just a flat recolour.
    if tier >= 3:
        for x, y in ((15, 5), (24, 9), (27, 22), (12, 25)):
            c.set(x, y, rgba(accent[:3], 80))
        if tier == 4:
            c.rect(14, 3, 16, 3, rgba(accent[:3], 95))
            c.rect(13, 28, 17, 28, rgba(accent[:3], 95))

    tail(c, signature, outline, accent, seed)
    # Form geometry: each form has its own silhouette, with no shared generic
    # oval.  The small forms are intentionally legible at inventory scale.
    if form in {"dart", "minnow", "dace", "anchovy", "sardine", "sprat", "runner", "hake", "mackerel"}:
        bodypts = [(6, 16), (10, 11), (20, 10), (26, 15), (22, 21), (11, 21)]
        c.poly(bodypts, outline); c.poly([(7, 16), (11, 12), (20, 12), (24, 15), (21, 19), (11, 19)], body)
        c.poly([(13, 11), (17, 7 - tier), (19, 11)], shadow)
        c.poly([(13, 20), (17, 24 + tier), (19, 20)], shadow)
    elif form in {"pike", "needlefish", "needle", "sword", "swordfish", "marlin", "gar", "sawfish"}:
        bodypts = [(5, 15), (12, 11), (22, 12), (29, 15), (22, 19), (11, 20)]
        c.poly(bodypts, outline); c.poly([(7, 15), (12, 13), (22, 14), (26, 15), (21, 18), (11, 18)], body)
        if form in {"sword", "swordfish", "marlin", "sawfish", "gar"}:
            c.poly([(24, 14), (31, 13), (31, 15), (24, 16)], outline)
            c.rect(28, 14, 31, 14, accent)
        c.poly([(14, 12), (17, 6), (19, 12)], shadow)
    elif form in {"loach", "eel", "ribbon", "wyrmfish"}:
        pts = [(3, 16), (7, 11), (13, 13), (18, 10), (25, 12), (28, 16), (24, 20), (17, 18), (11, 22), (6, 20)]
        c.poly(pts, outline); c.poly([(5, 16), (8, 13), (13, 15), (18, 12), (24, 13), (26, 16), (23, 18), (17, 16), (11, 20), (7, 19)], body)
        c.poly([(16, 12), (18, 7 - tier), (20, 12)], shadow)
    elif form in {"bream", "bluegill", "perch", "discus", "angelfish", "molly", "tench", "grouper", "bass"}:
        c.ellipse((5, 8 - tier // 2, 25, 23 + tier // 2), outline)
        c.ellipse((7, 10, 23, 21), body)
        c.poly([(11, 10), (14, 3 - tier), (18, 10)], outline)
        c.poly([(12, 10), (14, 6 - tier // 2), (16, 10)], accent)
        c.poly([(13, 21), (16, 26 + tier), (19, 21)], shadow)
    elif form in {"mud", "goby", "stormgoby", "cod", "sculpin", "catfish", "glasscat"}:
        pts = [(5, 16), (8, 10), (18, 9), (24, 13), (27, 16), (23, 21), (12, 23), (7, 20)]
        c.poly(pts, outline); c.poly([(7, 16), (9, 12), (18, 11), (23, 14), (25, 16), (22, 19), (12, 21), (8, 19)], body)
        c.poly([(10, 11), (14, 6), (20, 11)], shadow)
        c.poly([(10, 21), (13, 26), (17, 21)], shadow)
        if form in {"goby", "catfish", "glasscat"}:
            c.rect(24, 16, 31, 16, outline); c.rect(28, 17, 31, 17, shadow)
            c.set(25, 12, accent)
    elif form in {"scale", "koi", "carp", "salmon", "coelacanth", "sturgeon", "arowana", "ide", "tuna", "orca", "leviathan", "whalelet", "emperor", "shark", "dragonfish", "barracuda", "cometfish", "sunfish", "mola"}:
        pts = [(4, 16), (9, 10), (21, 9), (27, 13), (28, 17), (22, 22), (10, 22)]
        c.poly(pts, outline); c.poly([(6, 16), (10, 12), (20, 11), (25, 14), (26, 17), (21, 20), (10, 20)], body)
        c.poly([(12, 10), (15, 4 - tier), (19, 10)], shadow)
        c.poly([(11, 21), (15, 26 + tier), (19, 21)], shadow)
        if form in {"shark", "orca", "leviathan", "whalelet", "mola"}:
            c.poly([(16, 10), (19, 3 - tier), (22, 11)], outline)
    elif form in {"killifish", "guppy", "betta", "seahorse", "seraph", "crownfish", "puffer", "pikelet", "phoenix"}:
        c.ellipse((7, 9, 22, 22), outline); c.ellipse((9, 11, 20, 20), body)
        c.poly([(8, 11), (2, 6 - tier), (10, 14)], outline)
        c.poly([(8, 20), (3, 26 + tier), (12, 19)], outline)
        c.poly([(13, 10), (16, 3 - tier), (19, 10)], accent)
        c.poly([(13, 20), (16, 26 + tier), (19, 20)], shadow)
    elif form in {"ray", "stingray", "manta", "skate", "wingfish", "flyfish"}:
        c.poly([(2, 17), (10, 10), (15, 12), (20, 10), (30, 17), (21, 19), (16, 26 + tier), (11, 19)], outline)
        c.poly([(5, 17), (11, 13), (15, 15), (20, 13), (27, 17), (20, 18), (16, 23), (12, 18)], body)
        c.poly([(14, 15), (16, 10 - tier), (18, 15)], shadow)
    elif form in {"angler", "krakenfish"}:
        c.ellipse((7, 10, 24, 22), outline); c.ellipse((9, 12, 22, 20), body)
        c.poly([(18, 11), (22, 5), (27, 5), (24, 12)], outline)
        c.set(27, 5, accent); c.set(28, 5, accent)
        for x in (4, 6, 26, 28): c.set(x, 23 + (x % 2), accent)
    else:
        # A deliberately asymmetrical fallback form, used only for novel forms.
        c.poly([(4, 16), (9, 9), (18, 11), (25, 14), (23, 21), (12, 23), (6, 20)], outline)
        c.poly([(7, 16), (10, 12), (18, 13), (23, 15), (21, 19), (12, 20), (8, 18)], body)

    # Species pattern layer.  These are structural markings, not a rarity-only
    # tint, and provide a second recognition cue in a crowded inventory.
    if pattern in {"lateral", "stripe", "speed", "water", "root", "jade", "copper"}:
        c.rect(10, 16, 22, 17, accent)
    elif pattern in {"bars", "bands", "rainbow", "gill", "scalegrid", "scales", "coral"}:
        for x in (11, 14, 17, 20):
            c.rect(x, 12, x + 1, 20, accent if x % 2 else light)
    elif pattern in {"spots", "speckle", "cluster", "starfall", "prism", "crystal", "opal"}:
        for j in range(3 + tier):
            x = 11 + (seed + j * 7) % 11
            y = 12 + (seed // 3 + j * 5) % 7
            c.rect(x, y, x + 1, y + 1, accent)
    elif pattern in {"eye", "moonspot", "moonlit", "eclipse", "nightveil", "lunar", "ghost"}:
        c.ellipse((12, 12, 17, 17), accent); c.set(15, 14, rgba((8, 8, 18)))
    elif pattern in {"chevron", "bolt", "storm", "tempest", "thunder", "thunderhead", "stormking"}:
        c.poly([(10, 13), (14, 16), (12, 19), (18, 16), (16, 13)], accent)
    elif pattern in {"clear", "glass", "bones", "stormglass", "glasscat"}:
        c.rect(10, 15, 22, 15, light); c.rect(13, 18, 19, 18, shadow)
    elif pattern in {"ember", "firetail", "phoenix", "fire", "crimson", "bloodmoon", "crownfire"}:
        c.poly([(11, 19), (14, 14), (16, 18), (19, 12), (20, 19)], accent)
    elif pattern in {"aurora", "celestial", "astral", "nebula", "genesis", "prismatic", "starheart", "skyvault", "skyfather"}:
        c.rect(10, 15, 22, 16, light)
        for j in range(2 + tier):
            c.set(11 + (seed + j * 5) % 12, 11 + (j * 3 + seed) % 9, accent)
    elif pattern in {"ice", "frostbite", "frostnova", "brine"}:
        c.poly([(10, 18), (14, 13), (17, 18), (21, 12), (20, 19)], light)
    elif pattern in {"void", "abyss", "blackwater", "obsidian", "abysscrown", "voidfin"}:
        c.rect(11, 14, 20, 18, rgba((8, 7, 22), 235))
        c.set(18, 14, accent); c.set(20, 17, accent)
    else:
        # Every named pattern has a seed-specific lateral motif as a safe visual.
        c.rect(11, 16, 18, 16, accent)
        c.set(13 + seed % 7, 13 + seed % 5, light)

    # Face and gill marks are universal readability anchors.
    c.set(22 + (seed % 2), 14, rgba((245, 245, 235)))
    c.set(22 + (seed % 2), 14, rgba((12, 12, 20)))
    c.rect(20, 17, 21, 18, shadow)
    if tier >= 1:
        c.set(24, 12, accent)
    if tier >= 2:
        c.set(26, 10, light); c.set(27, 10, accent)
    if tier >= 3:
        # Rarity detail: extra fins and a small highlight, while preserving body.
        c.poly([(8, 11), (7, 5), (11, 10)], accent)
        c.poly([(9, 21), (7, 27), (12, 21)], accent)
        c.set(15, 12, rgba((255, 255, 255), 220))
    if tier == 4:
        c.poly([(17, 9), (19, 2), (21, 9)], light)
        c.set(6, 14, light); c.set(6, 18, light)
    return c


def item_model(path: str, parent: str = "generated") -> dict:
    return {
        "parent": "minecraft:item/handheld" if parent == "handheld" else "minecraft:item/generated",
        "textures": {"layer0": f"coremc:item/{path}"},
    }


def model_file(path: str, parent: str = "generated"):
    out = MODELS / (path + ".json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(item_model(path, parent), indent=2) + "\n", encoding="utf-8")


def write_yaml():
    lines = [
        "# CoreMC ItemsAdder content source. Namespace is intentionally stable: coremc.",
        "# Every item has a vanilla material fallback. PDC identity is owned by CoreMC,",
        "# so removing/rebuilding the resource pack never invalidates held items.",
        "info:",
        "  namespace: coremc",
        "items:",
    ]
    manifest = []
    for rarity, fish in FISH.items():
        base = RANGES[f"fish_{rarity}"]
        for index, (slug, display, form, pattern, signature) in enumerate(fish):
            item_id = f"fish_{rarity}_{slug}"
            model_id = base + index
            path = f"fish/{rarity}/{slug}"
            lines += [
                f"  {item_id}:",
                f"    display_name: {esc(display)}",
                "    lore:",
                f"      - {esc('&8Fishing catch &7• ' + RARITY[rarity][0])}",
                f"      - {esc('&7Species: &f' + display)}",
                "    resource:",
                "      material: COD",
                "      generate: false",
                f"      model_path: {esc('item/' + path)}",
                f"      model_id: {model_id}",
                "",
            ]
            manifest.append((item_id, display, model_id, "COD", path, rarity, form, pattern, signature))
    for item_id, display, model_id, material, path, parent in ITEMS:
        lines += [
            f"  {item_id}:",
            f"    display_name: {esc(display)}",
            "    lore:",
            f"      - {esc('&8CoreMC progression visual')}",
            f"      - {esc('&7Vanilla fallback: ' + material)}",
            "    resource:",
            f"      material: {material}",
            "      generate: false",
            f"      model_path: {esc('item/' + path)}",
            f"      model_id: {model_id}",
            "",
        ]
        manifest.append((item_id, display, model_id, material, path, "progression", "item", "item", "item"))
    config_file = PACK / "configs/items.yml"
    config_file.parent.mkdir(parents=True, exist_ok=True)
    config_file.write_text("\n".join(lines), encoding="utf-8")
    return manifest


def write_manifest(manifest):
    lines = [
        "# Stable CoreMC custom model-data allocation.",
        "# Ranges are intentionally disjoint from vanilla and from each other.",
        "# Do not renumber: old stacks use these visual IDs when the pack is present.",
        "# IDs 21600-21632 are reserved for the active animated-skins catalogue;",
        "# this branch intentionally does not duplicate those assets or entries.",
        "namespace: coremc",
        "base_material_fallback: true",
        "ranges:",
    ]
    for key, start in RANGES.items():
        count = 24 if key.startswith("fish_") else (10 if key == "key" else 1)
        if key == "lootbox": count = 9
        if key == "progression": count = 19
        if key == "omnitool": count = 6
        if key == "skin": count = 13
        lines += [f"  {key}:", f"    start: {start}", f"    end: {start + count - 1}"]
    lines += ["", "items:"]
    for item_id, display, model_id, material, path, rarity, form, pattern, signature in manifest:
        lines += [
            f"  {item_id}:",
            f"    model_id: {model_id}",
            f"    model_path: {path}",
            f"    fallback_material: {material}",
            f"    category: {rarity}",
        ]
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_catalog(manifest):
    lines = [
        "# Human-auditable fishing catalog. The ItemsAdder source contains exactly",
        "# 120 entries with category FISHING by convention: 24 per rarity.",
        "namespace: coremc",
        "category: fishing",
        "rarities:",
    ]
    for rarity in ("common", "uncommon", "rare", "epic", "mythic"):
        lines += [f"  {rarity}:", "    count: 24", "    items:"]
        for slug, display, form, pattern, signature in FISH[rarity]:
            item_id = f"fish_{rarity}_{slug}"
            model_id = RANGES[f"fish_{rarity}"] + FISH[rarity].index((slug, display, form, pattern, signature))
            lines += [
                f"      - id: {item_id}",
                f"        species: {esc(display)}",
                f"        model_id: {model_id}",
                f"        archetype: {form}",
                f"        pattern: {pattern}",
                f"        fin_signature: {signature}",
            ]
    CATALOG.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_skin_registry():
    SKINS.write_text("""# CoreMC cosmetic skin contract. This registry is intentionally outside the
# ItemsAdder namespace YAML: it is read by the CoreMC skin layer, while the
# adjacent items.yml entries are the visual tokens shown in menus.
namespace: coremc
contract:
  pdc_key: coremc:cosmetic-skin
  cosmetic_only: true
  stat_effect: none
  preserves: [stats, traits, xp, rarity, abilities]
  selection_rule: model-only override; never replace the base progression object
  fallback: retain the base generator/companion model when the pack is missing
generator_skins:
  cinderstone:
    item_id: generator_skin_cobble
    model_id: 21500
    applies_to: generator
    cosmetic_only: true
  glacial_relay:
    item_id: generator_skin_ice
    model_id: 21501
    applies_to: generator
    cosmetic_only: true
  rift_prism:
    item_id: generator_skin_rift
    model_id: 21502
    applies_to: generator
    cosmetic_only: true
companion_skins:
  emberfox:
    item_id: companion_skin_emberfox
    model_id: 21510
    applies_to: companion
    cosmetic_only: true
  moonmoth:
    item_id: companion_skin_moonmoth
    model_id: 21511
    applies_to: companion
    cosmetic_only: true
  starseed:
    item_id: companion_skin_starseed
    model_id: 21512
    applies_to: companion
    cosmetic_only: true
""", encoding="utf-8")


def generate():
    # Remove generated model/texture files only; never touch unrelated namespace files.
    for rarity, fish in FISH.items():
        for index, (slug, _display, form, pattern, signature) in enumerate(fish):
            path = f"fish/{rarity}/{slug}"
            draw_fish(rarity, index, slug, form, pattern, signature).png(TEXTURES / (path + ".png"))
            model_file(path)
    for item_id, _display, _model_id, _material, path, parent in ITEMS:
        # A handful of flat assets use the same procedural icon family. They
        # remain separate files/models and therefore can be art-directed later.
        rarity = "mythic" if "mythic" in item_id or "seasonal" in path else "rare"
        form = "ray" if "rift" in path else ("puffer" if "core" in item_id else "scale")
        pattern = "prism" if "rift" in path else ("celestial" if "lootbox" in path else "crystal")
        signature = "wings" if "skin" in path else "crown"
        draw_fish(rarity, _model_id % 24, item_id, form, pattern, signature).png(TEXTURES / (path + ".png"))
        model_file(path, "handheld" if item_id.startswith("omnitool_") else parent)
    # Role tool source models are intentionally separate from item definitions:
    # CoreMC creates them from the existing soulbound PDC item and only stamps
    # model data, preserving the existing role/omnitool data model.
    for role, model_id in [("miner", 21400), ("farmer", 21401), ("fisher", 21402), ("slayer", 21403), ("logger", 21404), ("universal", 21405)]:
        path = f"tools/omnitool_{role}"
        draw_fish("epic", model_id % 24, "omnitool_" + role, "pike", "celestial", "highfin").png(TEXTURES / (path + ".png"))
        model_file(path, "handheld")
    manifest = write_yaml()
    write_manifest(manifest)
    write_catalog(manifest)
    write_skin_registry()
    print(f"generated {sum(len(v) for v in FISH.values())} fish, {len(ITEMS)} supporting items, 6 OmniTool models")


if __name__ == "__main__":
    generate()
