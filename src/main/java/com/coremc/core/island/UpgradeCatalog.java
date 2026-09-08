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

        /** Effect description for the CURRENT tier (used in lore). */
        public String effectText(final int tier) {
            return switch (id) {
                case "crop-regrowth" -> "&7Replant chance: &f" + (tier * 5) + "%";
                case "mining-cube" -> "&7Mining area: &f" + (tier + 1) + "x" + (tier + 1);
                case "fisher-blessing" -> "&7Bonus catch: &f" + (tier * 10) + "%";
                case "slayer-force" -> "&7Melee damage: &f+" + (tier * 5) + "%";
                case "woodcutter" -> "&7Double log chance: &f" + (tier * 10) + "%";
                default -> "";
            };
        }
    }

    /** All tracks, grouped by category (GUI order inside a category page). */
    public static final List<Track> TRACKS = List.of(
            new Track("mining-cube", Category.MINING, Material.COBBLESTONE,
                    "Mining Cube",
                    List.of("&7Grows your mining cube", "&7toward a &f5x5&7 mining area.")),
            new Track("fisher-blessing", Category.FISHING, Material.FISHING_ROD,
                    "Fisher's Blessing",
                    List.of("&7Chance of a bonus catch", "&7while fishing.")),
            new Track("crop-regrowth", Category.FARMING, Material.WHEAT,
                    "Crop Regrowth",
                    List.of("&7Harvested mature crops replant", "&7themselves, consuming one seed.")),
            new Track("slayer-force", Category.SLAYING, Material.IRON_SWORD,
                    "Slayer Force",
                    List.of("&7Deal more melee damage", "&7to your prey.")),
            new Track("woodcutter", Category.LOGGING, Material.OAK_LOG,
                    "Woodcutter",
                    List.of("&7Chance of double log drops", "&7while chopping.")),
            new Track("border", Category.ISLAND, Material.BEACON,
                    "Border Size",
                    List.of("&7Widens the protected", "&7island square.")),
            new Track("member-slots", Category.ISLAND, Material.PLAYER_HEAD,
                    "Member Slots",
                    List.of("&7Adds one team slot", "&7per tier.")));

    /** All tracks of one category, in display order. */
    public static List<Track> ofCategory(final Category category) {
        return TRACKS.stream().filter(track -> track.category() == category).toList();
    }
}
