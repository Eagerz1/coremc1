package com.coremc.core.lootbox;

import com.coremc.core.reward.RewardDef;
import com.coremc.core.reward.WeightedTable;
import java.util.List;

/**
 * One configured lootbox (Core / Monthly / Seasonal): always a physical
 * ENDER_CHEST item, identified by PDC, that cannot be placed — right
 * clicking a block plays the premium opening animation and pays
 * {@code NORMAL_REWARDS} rolls from the normal pool plus exactly one
 * guaranteed roll from the rare pool.
 */
public record LootboxDef(
        String id,
        String name,
        long price,
        List<String> categories,
        WeightedTable normal,
        WeightedTable rare) {

    /** Normal reveals per opening (the "8" in "8 + 1 guaranteed Rare"). */
    public static final int NORMAL_REWARDS = 8;

    public LootboxDef {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public List<RewardDef> normalPool() {
        return normal.entries();
    }

    public List<RewardDef> rarePool() {
        return rare.entries();
    }
}
