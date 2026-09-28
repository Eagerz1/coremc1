package com.coremc.core.role.xp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/**
 * Pure Slayer XP-table checks. Slayer XP only accrues while the player
 * holds the OmniTool (now a Netherite Sword) in the main hand — that
 * gating is enforced in {@link RoleXpListener} against a live inventory
 * and needs server-level testing, but the per-mob XP curve itself is
 * pure and pinned here so hostiles keep paying and passives keep not.
 */
class SlayerXpTest {

    @Test
    void hostileMobsPayScaledXp() {
        assertEquals(4L, SlayerXpListener.xpFor(EntityType.ZOMBIE), "common hostile");
        assertEquals(7L, SlayerXpListener.xpFor(EntityType.CREEPER), "mid hostile");
        assertEquals(15L, SlayerXpListener.xpFor(EntityType.BLAZE), "tough hostile");
        assertEquals(100L, SlayerXpListener.xpFor(EntityType.WARDEN), "warden");
        assertEquals(250L, SlayerXpListener.xpFor(EntityType.WITHER), "boss");
    }

    @Test
    void passiveMobsPayNothing() {
        assertEquals(0L, SlayerXpListener.xpFor(EntityType.COW), "passive animal");
        assertEquals(0L, SlayerXpListener.xpFor(EntityType.VILLAGER), "villager");
    }

    @Test
    void tougherMobsNeverPayLessThanWeakerOnes() {
        assertTrue(SlayerXpListener.xpFor(EntityType.WARDEN) > SlayerXpListener.xpFor(EntityType.BLAZE),
                "warden out-pays a blaze");
        assertTrue(SlayerXpListener.xpFor(EntityType.BLAZE) > SlayerXpListener.xpFor(EntityType.ZOMBIE),
                "blaze out-pays a zombie");
    }
}
