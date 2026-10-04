package com.coremc.core.companion;

import com.coremc.core.role.RoleCategory;
import org.bukkit.Material;

/** One earnable companion and its category-specific XP ability. */
public record CompanionDefinition(
        String id,
        String display,
        Material icon,
        RoleCategory category,
        long priceTokens,
        int maxLevel,
        double xpBonusPerLevel) {
}
