package com.coremc.core.role;

import java.util.Optional;
import org.bukkit.Material;

/**
 * CoreMC roles. Each owns a display identity and — through
 * {@link RoleCategory} — which gameplay actions feed its progression.
 */
public enum Role {
    MINER("miner", "&eMiner", Material.IRON_PICKAXE, RoleCategory.MINING),
    LOGGER("logger", "&6Logger", Material.OAK_LOG, RoleCategory.LOGGING),
    FISHER("fisher", "&bFisher", Material.FISHING_ROD, RoleCategory.FISHING),
    SLAYER("slayer", "&cSlayer", Material.IRON_SWORD, RoleCategory.SLAYING),
    FARMER("farmer", "&aFarmer", Material.WHEAT, RoleCategory.FARMING),
    UNIVERSAL("universal", "&dUniversal", Material.NETHER_STAR, null);

    private final String key;
    private final String display;
    private final Material icon;
    private final RoleCategory category;

    Role(final String key, final String display, final Material icon, final RoleCategory category) {
        this.key = key;
        this.display = display;
        this.icon = icon;
        this.category = category;
    }

    public String key() {
        return key;
    }

    public String display() {
        return display;
    }

    public Material icon() {
        return icon;
    }

    /** Category this role progresses from; null = Universal (feeds from all). */
    public RoleCategory category() {
        return category;
    }

    public static Optional<Role> byKey(final String key) {
        for (final Role role : values()) {
            if (role.key.equalsIgnoreCase(key)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
