package com.coremc.core.island;

import java.util.List;
import org.bukkit.Material;

/**
 * The static catalogue of island upgrade tracks (balance numbers live in
 * config.yml: {@code island.upgrades.<id>}).
 *
 * Categories match the CoreMC spec exactly: Mining, Fishing, Farming,
 * Slaying, Logging and Island. Adding a track here + a config section is
 * the supported way to extend the system.
 *
 * There is deliberately no storage track: CoreMC has no island storage
 * system, so storage upgrades would have nothing to scale.
 */
public final class UpgradeCatalog {

    private UpgradeCatalog() {
    }

    /** The six upgrade categories (GUI order). */
    public enum Category {
        MINING("Mining", Material.DIAMOND_PICKAXE, "&b"),
        FISHING("Fishing", Material.FISHING_ROD, "&3"),
        FARMING("Farming", Material.WHEAT, "&e"),
        SLAYING("Slaying", Material.IRON_SWORD, "&c"),
        LOGGING("Logging", Material.OAK_LOG, "&6"),
        ISLAND("Island", Material.GRASS_BLOCK, "&a");

        private final String label;
        private final Material icon;
        private final String color;

        Category(final String label, final Material icon, final String color) {
            this.label = label;
            this.icon = icon;
            this.color = color;
        }

        public String label() {
            return label;
        }

        public Material icon() {
            return icon;
        }

        /** legacy color code prefix used in GUI titles */
        public String color() {
            return color;
        }
    }

    /** One upgrade track: config-driven tiers purchased with Sky Tokens. */
    public record Track(
            String id,
            Category category,
            Material icon,
            String display,
            List<String> summary) {

        /**
         * Effect description for the CURRENT tier (used in lore).
         * Percentages mirror the config defaults
         * ({@code *-percent-per-level}); retune both together.
         */
        public String effectText(final int tier) {
            return switch (id) {
                case "crop-regrowth" -> "&7Replant chance: &f" + (tier * 5) + "%";
                case "mining-cube" -> {
                    final int size = Math.min(tier + 1, 5);
                    final String bonus = tier >= 11 ? " &6+rich" : tier >= 5 ? " &8(max size)" : "";
                    yield "&7Mining area: &f" + size + "x" + size + bonus;
                }
                case "fisher-blessing" -> "&7Bonus catch: &f" + (tier * 10) + "%";
                case "slayer-force" -> "&7Melee damage: &f+" + (tier * 5) + "%";
                case "woodcutter" -> "&7Double log chance: &f" + (tier * 10) + "%";
                case "generator-boost" -> "&7Gen cooldown: &f-" + (tier * 8) + "% &8(+yield "
                        + (tier * 10) + "%, +" + (tier / 2) + " base, rare " + (tier * 5) + "%)";
                case "spawner-boost" ->
                    "&7Spawner speed: &f+" + (tier * 10) + "% &8(+extra " + (tier * 5)
                            + "%, drop +" + (tier * 10) + "%, xp +" + (tier * 10)
                            + "%, rare " + (tier * 5) + "%)";
                case "mining-fortune", "farming-fortune", "slayer-fortune" ->
                    "&7Bonus drops: &f" + (tier * 10) + "%";
                case "logging-fortune" -> "&7Bonus logs: &f" + (tier * 10) + "%";
                case "fishing-fortune" -> "&7Bonus catch: &f" + (tier * 10) + "%";
                case "mining-xp" -> "&7Mining XP: &f+" + (tier * 10) + "%";
                case "logging-xp" -> "&7Logging XP: &f+" + (tier * 10) + "%";
                case "fishing-xp" -> "&7Fishing XP: &f+" + (tier * 10) + "%";
                case "slayer-xp" -> "&7Slayer XP: &f+" + (tier * 10) + "%";
                case "farming-xp" -> "&7Farming XP: &f+" + (tier * 10) + "%";
                case "mining-tokens", "logging-tokens", "fishing-tokens", "slaying-tokens",
                        "farming-tokens" ->
                    "&7Sky Tokens: &f" + (tier * 5) + "%";
                case "mining-credits", "logging-credits", "fishing-credits", "slaying-credits",
                        "farming-credits" ->
                    "&7Credits: &f" + (tier * 5) + "%";
                case "mining-speed" -> hasteText("Mining", tier);
                case "harvest-speed" -> hasteText("Harvest", tier);
                case "fishing-treasure", "rare-drops", "rare-wood" ->
                    "&7Rare roll: &f" + (tier * 5) + "%";
                case "fishing-rare" ->
                    "&7Rare catch: &f" + (tier * 5) + "% &8(+" + (tier * 5) + " XP)";
                case "fishing-speed" -> "&7Wait time: &f-" + (tier * 10) + "%";
                case "crop-yield" -> tier <= 0 ? "&7Bonus crops: &8none"
                        : "&7Bonus crops: &f+" + (tier >= 4 ? 2 : 1);
                case "seed-efficiency" -> "&7Seed saved: &f" + (tier * 15) + "%";
                case "tree-growth" -> "&7Regrow: &f" + (tier * 15) + "%";
                case "tree-yield" -> "&7+2 logs: &f" + (tier * 10) + "%";
                case "mob-rate" -> "&7Twin spawn: &f" + (tier * 10) + "%";
                case "mob-cap" -> "&7Mob cap: &f" + (10 + tier * 5);
                case "boss-damage" -> "&7Boss damage: &f+" + (tier * 10) + "%";
                default -> "";
            };
        }

        private static String hasteText(final String label, final int tier) {
            if (tier <= 0) {
                return "&7" + label + " haste: &8none";
            }
            return "&7" + label + " haste: &f" + (tier >= 5 ? "III" : tier >= 3 ? "II" : "I");
        }
    }

    /** All tracks, grouped by category (GUI order inside a category page). */
    public static final List<Track> TRACKS = List.of(
            // ---- MINING (6) ----
            new Track("mining-cube", Category.MINING, Material.COBBLESTONE,
                    "Mining Cube",
                    List.of("&7Grows your mining cube", "&7toward a &f5x5&7 mining area,",
                            "&7then faster regen & richer ores.")),
            new Track("mining-fortune", Category.MINING, Material.DIAMOND,
                    "Mining Fortune",
                    List.of("&7Chance of bonus copies", "&7of your mining drops.")),
            new Track("mining-xp", Category.MINING, Material.EXPERIENCE_BOTTLE,
                    "Mining XP",
                    List.of("&7More mining XP from", "&7every block you mine.")),
            new Track("mining-tokens", Category.MINING, Material.NETHER_STAR,
                    "Mining Token Chance",
                    List.of("&7Chance of Sky Tokens", "&7while mining.")),
            new Track("mining-credits", Category.MINING, Material.GOLD_NUGGET,
                    "Mining Credit Chance",
                    List.of("&7Chance of Credits", "&7while mining.")),
            new Track("mining-speed", Category.MINING, Material.GOLDEN_PICKAXE,
                    "Mining Speed",
                    List.of("&7Haste while mining", "&7on your island.")),
            // ---- FISHING (8) ----
            new Track("fisher-blessing", Category.FISHING, Material.FISHING_ROD,
                    "Fisher's Blessing",
                    List.of("&7Chance of a bonus catch", "&7while fishing.")),
            new Track("fishing-fortune", Category.FISHING, Material.COD,
                    "Fishing Fortune",
                    List.of("&7Chance of bonus catch", "&7copies.")),
            new Track("fishing-xp", Category.FISHING, Material.EXPERIENCE_BOTTLE,
                    "Fishing XP",
                    List.of("&7More fishing XP from", "&7every catch.")),
            new Track("fishing-treasure", Category.FISHING, Material.CHEST,
                    "Treasure Chance",
                    List.of("&7Chance to roll the island", "&7treasure table on a catch.")),
            new Track("fishing-rare", Category.FISHING, Material.HEART_OF_THE_SEA,
                    "Rare Catch Chance",
                    List.of("&7Chance of a rare catch:", "&7bonus copy + bonus XP.")),
            new Track("fishing-speed", Category.FISHING, Material.SUGAR,
                    "Fishing Speed",
                    List.of("&7Shorter wait for", "&7a bite.")),
            new Track("fishing-tokens", Category.FISHING, Material.NETHER_STAR,
                    "Token Fishing",
                    List.of("&7Chance of Sky Tokens", "&7on a catch.")),
            new Track("fishing-credits", Category.FISHING, Material.GOLD_NUGGET,
                    "Credit Fishing",
                    List.of("&7Chance of Credits", "&7on a catch.")),
            // ---- FARMING (8) ----
            new Track("crop-regrowth", Category.FARMING, Material.WHEAT,
                    "Crop Regrowth",
                    List.of("&7Harvested mature crops replant", "&7themselves, consuming one seed.")),
            new Track("farming-fortune", Category.FARMING, Material.HAY_BLOCK,
                    "Farming Fortune",
                    List.of("&7Chance to double", "&7a harvest.")),
            new Track("farming-xp", Category.FARMING, Material.EXPERIENCE_BOTTLE,
                    "Farming XP",
                    List.of("&7More farming XP from", "&7every harvest.")),
            new Track("crop-yield", Category.FARMING, Material.WHEAT_SEEDS,
                    "Crop Yield",
                    List.of("&7Guaranteed bonus crop", "&7items per harvest.")),
            new Track("harvest-speed", Category.FARMING, Material.GOLDEN_HOE,
                    "Harvest Speed",
                    List.of("&7Haste while harvesting", "&7on your island.")),
            new Track("seed-efficiency", Category.FARMING, Material.BEETROOT_SEEDS,
                    "Seed Efficiency",
                    List.of("&7Chance planting a seed", "&7does not consume it.")),
            new Track("farming-tokens", Category.FARMING, Material.NETHER_STAR,
                    "Token Farming",
                    List.of("&7Chance of Sky Tokens", "&7when harvesting.")),
            new Track("farming-credits", Category.FARMING, Material.GOLD_NUGGET,
                    "Credit Farming",
                    List.of("&7Chance of Credits", "&7when harvesting.")),
            // ---- SLAYING (9) ----
            new Track("slayer-force", Category.SLAYING, Material.IRON_SWORD,
                    "Slayer Force",
                    List.of("&7Deal more melee damage", "&7to your prey.")),
            new Track("slayer-fortune", Category.SLAYING, Material.BONE,
                    "Slayer Fortune",
                    List.of("&7Chance of bonus copies", "&7of wild mob drops.")),
            new Track("slayer-xp", Category.SLAYING, Material.EXPERIENCE_BOTTLE,
                    "Slayer XP",
                    List.of("&7More slayer XP from", "&7every kill.")),
            new Track("mob-rate", Category.SLAYING, Material.ZOMBIE_SPAWN_EGG,
                    "Mob Spawn Rate",
                    List.of("&7Chance a natural island", "&7spawn brings a twin.")),
            new Track("mob-cap", Category.SLAYING, Material.LEAD,
                    "Mob Cap",
                    List.of("&7Raises the natural-mob", "&7cap on your island.")),
            new Track("slaying-tokens", Category.SLAYING, Material.NETHER_STAR,
                    "Token Slaying",
                    List.of("&7Chance of Sky Tokens", "&7on a wild kill.")),
            new Track("slaying-credits", Category.SLAYING, Material.GOLD_NUGGET,
                    "Credit Slaying",
                    List.of("&7Chance of Credits", "&7on a wild kill.")),
            new Track("rare-drops", Category.SLAYING, Material.ENDER_PEARL,
                    "Rare Drop Chance",
                    List.of("&7Chance to roll the rare", "&7table on a wild kill.")),
            new Track("boss-damage", Category.SLAYING, Material.NETHERITE_SWORD,
                    "Boss Damage",
                    List.of("&7More damage vs bosses", "&7on your island.")),
            // ---- LOGGING (8) ----
            new Track("woodcutter", Category.LOGGING, Material.OAK_LOG,
                    "Woodcutter",
                    List.of("&7Chance of double log drops", "&7while chopping.")),
            new Track("logging-fortune", Category.LOGGING, Material.OAK_SAPLING,
                    "Logging Fortune",
                    List.of("&7Chance of bonus logs", "&7per trunk break.")),
            new Track("logging-xp", Category.LOGGING, Material.EXPERIENCE_BOTTLE,
                    "Logging XP",
                    List.of("&7More logging XP from", "&7every trunk.")),
            new Track("tree-growth", Category.LOGGING, Material.BONE_MEAL,
                    "Tree Growth",
                    List.of("&7Felled trunks replant", "&7and regrow themselves.")),
            new Track("tree-yield", Category.LOGGING, Material.STICK,
                    "Tree Yield",
                    List.of("&7Chance of +2 bonus", "&7logs per break.")),
            new Track("logging-tokens", Category.LOGGING, Material.NETHER_STAR,
                    "Token Logging",
                    List.of("&7Chance of Sky Tokens", "&7while logging.")),
            new Track("logging-credits", Category.LOGGING, Material.GOLD_NUGGET,
                    "Credit Logging",
                    List.of("&7Chance of Credits", "&7while logging.")),
            new Track("rare-wood", Category.LOGGING, Material.APPLE,
                    "Rare Wood Chance",
                    List.of("&7Chance to roll the", "&7rare-wood table.")),
            // ---- ISLAND (4) ----
            new Track("border", Category.ISLAND, Material.BEACON,
                    "Border Size",
                    List.of("&7Widens the protected", "&7island square up to &f200x200&7.")),
            new Track("member-slots", Category.ISLAND, Material.PLAYER_HEAD,
                    "Member Slots",
                    List.of("&7Adds one team slot", "&7per tier.")),
            new Track("generator-boost", Category.ISLAND, Material.OBSERVER,
                    "Generator Mastery",
                    List.of("&7Generators on your island", "&7recharge faster, yield more,",
                            "&7and rarely drop riches.")),
            new Track("spawner-boost", Category.ISLAND, Material.SPAWNER,
                    "Spawner Mastery",
                    List.of("&7Spawners on your island run", "&7faster, with extra spawns,",
                            "&7better drops and XP.")));

    /** All tracks of one category, in display order. */
    public static List<Track> ofCategory(final Category category) {
        return TRACKS.stream().filter(track -> track.category() == category).toList();
    }

    /** Display name of a track id (falls back to the raw id). */
    public static String displayOf(final String trackId) {
        return TRACKS.stream()
                .filter(track -> track.id().equals(trackId))
                .map(Track::display)
                .findFirst()
                .orElse(trackId);
    }
}
