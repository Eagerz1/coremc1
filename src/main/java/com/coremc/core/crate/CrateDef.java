package com.coremc.core.crate;

import com.coremc.core.reward.RewardDef;
import com.coremc.core.reward.WeightedTable;
import java.util.List;
import org.bukkit.Material;

/**
 * One configured crate: name, the block it displays as, the key that
 * opens it, its weighted reward table, sounds, particles and whether
 * rare wins broadcast. Everything an admin can balance lives in
 * {@code crates.yml}.
 */
public record CrateDef(
        String id,
        String name,
        Material displayMaterial,
        String keyId,
        WeightedTable rewards,
        String openSound,
        String winSound,
        String particle,
        boolean broadcastRare) {

    /** The raw reward pool (preview + rolls share this table). */
    public List<RewardDef> pool() {
        return rewards.entries();
    }
}
