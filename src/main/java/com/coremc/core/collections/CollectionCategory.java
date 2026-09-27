package com.coremc.core.collections;

import java.util.Locale;
import org.bukkit.Material;

/**
 * The permanent Collection categories. Ids are stable storage keys;
 * the display name and icon drive the root GUI.
 *
 * <p>Categories whose content lives on another branch (Companions,
 * OmniTools) still exist here with entries and hooks, so the moment
 * those systems arrive their events land in a Collection that is
 * already configured, tested and visible.</p>
 */
public enum CollectionCategory {

    MINING("mining", "Mining", Material.IRON_PICKAXE, "&b"),
    FARMING("farming", "Farming", Material.WHEAT, "&a"),
    FISHING("fishing", "Fishing", Material.FISHING_ROD, "&9"),
    SLAYER("slayer", "Slayer", Material.IRON_SWORD, "&c"),
    GENERATORS("generators", "Generators", Material.IRON_BLOCK, "&f"),
    SPAWNERS("spawners", "Spawners", Material.SPAWNER, "&d"),
    COMPANIONS("companions", "Companions", Material.BONE, "&6"),
    OMNITOOLS("omnitools", "OmniTools", Material.NETHERITE_PICKAXE, "&5"),
    DISCOVERIES("discoveries", "Discoveries", Material.AMETHYST_SHARD, "&b"),
    EVENTS("events", "Events", Material.FIREWORK_ROCKET, "&e"),
    SEASONAL("seasonal", "Seasonal", Material.CLOCK, "&6");

    private final String id;
    private final String display;
    private final Material icon;
    private final String color;

    CollectionCategory(final String id, final String display, final Material icon,
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

    /** The category's & colour code. */
    public String color() {
        return color;
    }

    /** Category for a config id (case-insensitive), or null. */
    public static CollectionCategory of(final String id) {
        if (id == null) {
            return null;
        }
        final String needle = id.trim().toLowerCase(Locale.ROOT);
        for (final CollectionCategory category : values()) {
            if (category.id.equals(needle)) {
                return category;
            }
        }
        return null;
    }
}
