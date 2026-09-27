package com.coremc.core.achievements;

import java.util.Locale;
import org.bukkit.Material;

/**
 * Achievement categories. Separate from the Collection categories on
 * purpose: Collections are "how much have you gathered", Achievements
 * are "what have you done".
 */
public enum AchievementCategory {

    ISLAND("island", "Island", Material.GRASS_BLOCK, "&a"),
    ECONOMY("economy", "Economy", Material.GOLD_INGOT, "&6"),
    COMBAT("combat", "Combat", Material.DIAMOND_SWORD, "&c"),
    GATHERING("gathering", "Gathering", Material.IRON_PICKAXE, "&b"),
    PROGRESSION("progression", "Progression", Material.EXPERIENCE_BOTTLE, "&d"),
    MASTERY("mastery", "Mastery", Material.NETHER_STAR, "&5"),
    SOCIAL("social", "Social", Material.PLAYER_HEAD, "&e"),
    EVENTS("events", "Events", Material.FIREWORK_ROCKET, "&e"),
    SEASONAL("seasonal", "Seasonal", Material.CLOCK, "&6"),
    SECRET("secret", "Secret", Material.ENDER_EYE, "&8");

    private final String id;
    private final String display;
    private final Material icon;
    private final String color;

    AchievementCategory(final String id, final String display, final Material icon,
                        final String color) {
        this.id = id;
        this.display = display;
        this.icon = icon;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public Material icon() {
        return icon;
    }

    public String color() {
        return color;
    }

    /** Category for a config id (case-insensitive), or null. */
    public static AchievementCategory of(final String id) {
        if (id == null) {
            return null;
        }
        final String needle = id.trim().toLowerCase(Locale.ROOT);
        for (final AchievementCategory category : values()) {
            if (category.id.equals(needle)) {
                return category;
            }
        }
        return null;
    }
}
