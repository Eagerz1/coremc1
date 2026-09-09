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
                case "generator-boost" -> "&7Gen cooldown: &f-" + (tier * 8) + "%";
                case "spawner-boost" ->
                    "&7Spawner speed: &f+" + (tier * 10) + "% &8(+extra " + (tier * 5) + "%)";
                default -> "";
            };
        }
    }

    /** All tracks, grouped by category (GUI order inside a category page). */
    public static final List<Track> TRACKS = List.of(
            new Track("mining-cube", Category.MINING, Material.COBBLESTONE,
                    "Mining Cube",
                    List.of("&7Grows your mining cube", "&7toward a &f5x5&7 mining area,",
                            "&7then faster regen & richer ores.")),
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
                    List.of("&7Widens the protected", "&7island square up to &f200x200&7.")),
            new Track("member-slots", Category.ISLAND, Material.PLAYER_HEAD,
                    "Member Slots",
                    List.of("&7Adds one team slot", "&7per tier.")),
            new Track("generator-boost", Category.ISLAND, Material.OBSERVER,
                    "Generator Boost",
                    List.of("&7Generators on your island", "&7recharge faster.")),
            new Track("spawner-boost", Category.ISLAND, Material.SPAWNER,
                    "Spawner Boost",
                    List.of("&7Spawners on your island run", "&7faster, with extra spawns.")));

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
