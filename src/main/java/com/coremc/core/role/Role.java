package com.coremc.core.role;

import java.util.Optional;
import org.bukkit.Material;

/**
 * CoreMC roles. Each owns a display identity and — through
 * {@link RoleCategory} — which gameplay actions feed its progression.
 */
public enum Role {
    MINER("miner", "&eMiner", Material.IRON_PICKAXE, Material.NETHERITE_PICKAXE, RoleCategory.MINING),
    LOGGER("logger", "&6Logger", Material.OAK_LOG, Material.NETHERITE_AXE, RoleCategory.LOGGING),
    FISHER("fisher", "&bFisher", Material.FISHING_ROD, Material.FISHING_ROD, RoleCategory.FISHING),
    SLAYER("slayer", "&cSlayer", Material.IRON_SWORD, Material.NETHERITE_SWORD, RoleCategory.SLAYING),
    FARMER("farmer", "&aFarmer", Material.WHEAT, Material.NETHERITE_HOE, RoleCategory.FARMING),
    // Universal aggregates every category but still carries a single physical
    // OmniTool; a Netherite Pickaxe is the most versatile default (mines and
    // still deals melee damage), matching the historical Universal tool form.
    UNIVERSAL("universal", "&dUniversal", Material.NETHERITE_PICKAXE, Material.NETHERITE_PICKAXE, null);

    private final String key;
    private final String display;
    private final Material icon;
    private final Material toolMaterial;
    private final RoleCategory category;

    Role(final String key, final String display, final Material icon, final Material toolMaterial,
            final RoleCategory category) {
        this.key = key;
        this.display = display;
        this.icon = icon;
        this.toolMaterial = toolMaterial;
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

    /** The physical tool material the OmniTool takes when bound to this role. */
    public Material toolMaterial() {
        return toolMaterial;
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
