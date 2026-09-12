package com.coremc.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Schema v6: custom enchants, souls, tags, companions, quests, playtime, chat, stats, claims, rank. */
class PlayerProfileV6Test {

    @Test
    void v6FieldsRoundTrip() {
        final PlayerProfile profile =
                PlayerProfile.createNew(UUID.randomUUID(), "Tester", System.currentTimeMillis());
        profile.setEnchantLevel("miner.excavator", 25);
        profile.setEnchantLevel("miner.fortune-core", 3);
        profile.souls(150L);
        assertTrue(profile.addTag("star"));
        assertFalse(profile.addTag("star"));
        profile.equippedTag("star");
        final Map<String, Object> companion = new LinkedHashMap<>();
        companion.put("rarity", "RARE");
        companion.put("level", 12);
        companion.put("xp", 340L);
        profile.setCompanion("ember-fox", companion);
        profile.equippedCompanion("ember-fox");
        profile.questDay("2026-09-09");
        profile.dailyQuests(List.of("mine-100", "kill-25"));
        final Map<String, Object> quest = new LinkedHashMap<>();
        quest.put("progress", 42L);
        quest.put("done", false);
        quest.put("claimed", false);
        profile.setQuestProgress("mine-100", quest);
        profile.addPlaytimeMinutes(90L);
        assertTrue(profile.claimPlaytimeMilestone("60min"));
        assertFalse(profile.claimPlaytimeMilestone("60min"));
        profile.chatColor("ocean");
        profile.chatBold(true);
        profile.addStat("blocks-mined", 500L);
        profile.markSubscriptionClaimed("monthly", "2026-09-09");
        profile.rankId("core+");

        final PlayerProfile reloaded =
                PlayerProfile.fromMap(profile.uuid(), profile.toMap());

        assertEquals(25, reloaded.enchantLevel("miner.excavator"));
        assertEquals(3, reloaded.enchantLevel("miner.fortune-core"));
        assertEquals(0, reloaded.enchantLevel("miner.unknown"));
        assertEquals(150L, reloaded.souls());
        assertTrue(reloaded.ownedTags().contains("star"));
        assertEquals("star", reloaded.equippedTag());
        assertEquals("RARE", reloaded.companionOf("ember-fox").get("rarity"));
        assertEquals("ember-fox", reloaded.equippedCompanion());
        assertEquals("2026-09-09", reloaded.questDay());
        assertEquals(List.of("mine-100", "kill-25"), reloaded.dailyQuests());
        assertEquals(42L, ((Number) reloaded.questProgressOf("mine-100").get("progress")).longValue());
        assertEquals(90L, reloaded.playtimeMinutes());
        assertTrue(reloaded.playtimeMilestoneClaimed("60min"));
        assertEquals("ocean", reloaded.chatColor());
        assertTrue(reloaded.chatBold());
        assertEquals(500L, reloaded.statOf("blocks-mined"));
        assertEquals("2026-09-09", reloaded.subscriptionClaimDay("monthly"));
        assertEquals("core+", reloaded.rankId());
    }

    @Test
    void legacyMapsLoadWithV6Defaults() {
        final Map<String, Object> v5 = new LinkedHashMap<>();
        v5.put("username", "Old");
        v5.put("money", 10L);
        final PlayerProfile profile = PlayerProfile.fromMap(UUID.randomUUID(), v5);

        assertEquals(0, profile.enchantLevel("anything"));
        assertEquals(0L, profile.souls());
        assertTrue(profile.ownedTags().isEmpty());
        assertEquals("none", profile.equippedTag());
        assertTrue(profile.companionOf("x").isEmpty());
        assertEquals("none", profile.equippedCompanion());
        assertEquals("", profile.questDay());
        assertTrue(profile.dailyQuests().isEmpty());
        assertEquals(0L, profile.playtimeMinutes());
        assertEquals("none", profile.chatColor());
        assertFalse(profile.chatBold());
        assertEquals(0L, profile.statOf("anything"));
        assertEquals("", profile.subscriptionClaimDay("monthly"));
        assertEquals("member", profile.rankId());
    }

    @Test
    void zeroEnchantLevelClearsEntry() {
        final PlayerProfile profile =
                PlayerProfile.createNew(UUID.randomUUID(), "Tester", System.currentTimeMillis());
        profile.setEnchantLevel("slayer.reaper", 1);
        assertEquals(1, profile.enchantLevels().size());
        profile.setEnchantLevel("slayer.reaper", 0);
        assertTrue(profile.enchantLevels().isEmpty());
        assertFalse(profile.toMap().containsKey("slayer.reaper"));
    }
}
