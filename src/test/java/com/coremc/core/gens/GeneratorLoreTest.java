package com.coremc.core.gens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * The generator lore — the visible half of the feature. Every line is
 * asserted against the CoreMC design language: small caps, short
 * blocks separated by blank lines, coloured values and a live ✔ / ✖
 * on every price. Locked entries must never read like buyable ones.
 */
class GeneratorLoreTest {

    private static final GeneratorTier IRON = new GeneratorTier("iron", "Iron Generator", 3,
            "&f", Material.IRON_BLOCK, Material.IRON_BLOCK, 25000, 30 * 20, 450,
            Material.IRON_INGOT, 3, 500, 24, "gold", 50000);
    private static final GeneratorTier GOLD = new GeneratorTier("gold", "Gold Generator", 4,
            "&6", Material.GOLD_BLOCK, Material.GOLD_BLOCK, 100000, 20 * 20, 700,
            Material.GOLD_INGOT, 3, 1500, 24, null, 0);

    // ------------------------------------------------------------------
    // titles
    // ------------------------------------------------------------------

    @Test
    void titlesAreColouredSmallCaps() {
        assertEquals("&f&lɪʀᴏɴ ɢᴇɴᴇʀᴀᴛᴏʀ", GeneratorLore.title(IRON, 1));
        assertEquals("&f&l8x ɪʀᴏɴ ɢᴇɴᴇʀᴀᴛᴏʀ", GeneratorLore.title(IRON, 8));
        assertEquals("&6&lɢᴏʟᴅ ɢᴇɴᴇʀᴀᴛᴏʀ", GeneratorLore.title(GOLD, 1));
    }

    @Test
    void lockedTitlesLookNothingLikeBuyableOnes() {
        assertEquals("&8&lɪʀᴏɴ ɢᴇɴᴇʀᴀᴛᴏʀ", GeneratorLore.lockedTitle(IRON));
        assertFalse(GeneratorLore.lockedTitle(IRON).equals(GeneratorLore.title(IRON, 1)));
    }

    @Test
    void theSummaryNamesWhatItGenerates() {
        assertEquals("&7ɢᴇɴᴇʀᴀᴛᴇs ɪʀᴏɴ ɪɴɢᴏᴛ ᴏᴠᴇʀ ᴛɪᴍᴇ.", GeneratorLore.summary(IRON));
    }

    // ------------------------------------------------------------------
    // menu entries
    // ------------------------------------------------------------------

    @Test
    void anAffordableEntryShowsAGreenTickAndAClickAction() {
        final List<String> lore = GeneratorLore.menu(IRON, true, true, 900, 2);
        assertTrue(lore.contains("&7ᴛɪᴇʀ: &fɪɪɪ"));
        assertTrue(lore.contains("&7ɪɴᴛᴇʀᴠᴀʟ: &f30s"));
        assertTrue(lore.contains("&7ɢᴇɴ ᴠᴀʟᴜᴇ: &a$450"));
        assertTrue(lore.contains("&7ᴘʀɪᴄᴇ: &a$25,000 &a✔"));
        assertTrue(lore.contains("&eᴄʟɪᴄᴋ ᴛᴏ ᴘᴜʀᴄʜᴀsᴇ"));
        assertTrue(lore.contains("&7ᴘʟᴀᴄᴇᴅ: &f2/24"));
    }

    @Test
    void anUnaffordableEntryShowsARedCrossAndNoClickAction() {
        final List<String> lore = GeneratorLore.menu(IRON, true, false, 900, 0);
        assertTrue(lore.contains("&7ᴘʀɪᴄᴇ: &c$25,000 &c✖"));
        assertFalse(lore.contains("&eᴄʟɪᴄᴋ ᴛᴏ ᴘᴜʀᴄʜᴀsᴇ"));
        assertTrue(lore.stream().anyMatch(line -> line.startsWith("&c")));
    }

    @Test
    void aLockedEntryShowsTheMissingRequirement() {
        final List<String> lore = GeneratorLore.menu(IRON, false, true, 120, 0);
        assertTrue(lore.contains("&7ɪsʟᴀɴᴅ ᴘᴏɪɴᴛs: &c500 &c✖"));
        assertTrue(lore.contains("&7ᴘʀɪᴄᴇ: &c$25,000 &c✖"),
                "a locked generator never advertises an affordable price");
        assertFalse(lore.contains("&eᴄʟɪᴄᴋ ᴛᴏ ᴘᴜʀᴄʜᴀsᴇ"));
        assertTrue(lore.stream().anyMatch(line -> line.contains("ʟᴏᴄᴋᴇᴅ")));
    }

    @Test
    void loreStaysShortAndUsesBlankSeparators() {
        final List<String> lore = GeneratorLore.menu(IRON, true, true, 900, 2);
        assertTrue(lore.size() <= 12, "lore must not become a wall of text: " + lore.size());
        assertTrue(lore.contains(""), "blank lines separate the blocks");
    }

    // ------------------------------------------------------------------
    // items and management
    // ------------------------------------------------------------------

    @Test
    void theItemLoreScalesWithTheStack() {
        final List<String> single = GeneratorLore.item(IRON, 1);
        assertTrue(single.contains("&7ɢᴇɴ ᴠᴀʟᴜᴇ: &a$450"));
        assertTrue(single.stream().anyMatch(line -> line.contains("sᴛᴀᴄᴋ")));

        final List<String> stacked = GeneratorLore.item(IRON, 8);
        assertTrue(stacked.contains("&7sᴛᴀᴄᴋ: &f8x"));
        assertTrue(stacked.contains("&7ɢᴇɴ ᴠᴀʟᴜᴇ: &a$3,600"));
    }

    @Test
    void theManagementPanelShowsOwnerAndIsland() {
        final List<String> lore = GeneratorLore.manage(IRON, 4, "Steve", "Steve's island");
        assertTrue(lore.contains("&7sᴛᴀᴄᴋ: &f4x"));
        assertTrue(lore.contains("&7ʀᴀᴛᴇ: &f30s"));
        assertTrue(lore.contains("&7ɢᴇɴ ᴠᴀʟᴜᴇ: &a$1,800"));
        assertTrue(lore.contains("&7ᴘᴇʀ ʜᴏᴜʀ: &a$216,000"));
        assertTrue(lore.contains("&7ᴏᴡɴᴇʀ: &fSteve"));
        assertTrue(lore.contains("&7ɪsʟᴀɴᴅ: &fSteve's island"));
    }

    // ------------------------------------------------------------------
    // upgrades
    // ------------------------------------------------------------------

    @Test
    void theUpgradeButtonShowsTheWholeStep() {
        final List<String> lore = GeneratorLore.upgrade(IRON, GOLD, 1, 50000, true, true);
        assertEquals("&7ɪʀᴏɴ ɢᴇɴᴇʀᴀᴛᴏʀ", lore.get(0));
        assertEquals("&8→", lore.get(1));
        assertEquals("&6ɢᴏʟᴅ ɢᴇɴᴇʀᴀᴛᴏʀ", lore.get(2));
        assertTrue(lore.contains("&7ɴᴇᴡ ʀᴀᴛᴇ: &f20s"));
        assertTrue(lore.contains("&7ɴᴇᴡ ᴠᴀʟᴜᴇ: &a$700"));
        assertTrue(lore.contains("&7ᴜᴘɢʀᴀᴅᴇ ᴄᴏsᴛ: &a$50,000 &a✔"));
        assertTrue(lore.contains("&eᴄʟɪᴄᴋ ᴛᴏ ᴜᴘɢʀᴀᴅᴇ"));
    }

    @Test
    void anUnaffordableUpgradeShowsTheRedCross() {
        final List<String> lore = GeneratorLore.upgrade(IRON, GOLD, 1, 50000, false, true);
        assertTrue(lore.contains("&7ᴜᴘɢʀᴀᴅᴇ ᴄᴏsᴛ: &c$50,000 &c✖"));
        assertFalse(lore.contains("&eᴄʟɪᴄᴋ ᴛᴏ ᴜᴘɢʀᴀᴅᴇ"));
    }

    @Test
    void upgradingAStackScalesCostAndValue() {
        final List<String> lore = GeneratorLore.upgrade(IRON, GOLD, 8, 400000, true, true);
        assertTrue(lore.contains("&7ɴᴇᴡ ᴠᴀʟᴜᴇ: &a$5,600"));
        assertTrue(lore.contains("&7ᴜᴘɢʀᴀᴅᴇ ᴄᴏsᴛ: &a$400,000 &a✔"));
        assertTrue(lore.stream().anyMatch(line -> line.contains("ᴀʟʟ 8")));
    }

    @Test
    void theTopTierSaysSoInsteadOfOfferingAnUpgrade() {
        final List<String> lore = GeneratorLore.upgrade(GOLD, null, 1, 0, true, true);
        assertTrue(lore.stream().anyMatch(line -> line.contains("ᴍᴀxɪᴍᴜᴍ ᴛɪᴇʀ")));
        assertFalse(lore.contains("&eᴄʟɪᴄᴋ ᴛᴏ ᴜᴘɢʀᴀᴅᴇ"));
    }

    @Test
    void pickupTellsYouWhatYouGetBack() {
        final List<String> allowed = GeneratorLore.pickup(IRON, 8, true);
        assertTrue(allowed.contains("&7ʏᴏᴜ ʀᴇᴄᴇɪᴠᴇ: &f8x ɪʀᴏɴ ɢᴇɴᴇʀᴀᴛᴏʀ"));
        assertTrue(allowed.contains("&eᴄʟɪᴄᴋ ᴛᴏ ᴘɪᴄᴋ ᴜᴘ"));

        final List<String> denied = GeneratorLore.pickup(IRON, 8, false);
        assertFalse(denied.contains("&eᴄʟɪᴄᴋ ᴛᴏ ᴘɪᴄᴋ ᴜᴘ"));
        assertTrue(denied.stream().anyMatch(line -> line.startsWith("&c")));
    }

    @Test
    void theHologramCarriesTheStackAndTheTierColour() {
        assertEquals("&fɪʀᴏɴ ɢᴇɴᴇʀᴀᴛᴏʀ", GeneratorLore.hologram(IRON, 1));
        assertEquals("&f8x &6ɢᴏʟᴅ ɢᴇɴᴇʀᴀᴛᴏʀ", GeneratorLore.hologram(GOLD, 8));
    }

    @Test
    void perHourMathsFollowsTheInterval() {
        assertEquals(450.0 * 120, IRON.valuePerHour());
        assertEquals(700.0 * 180, GOLD.valuePerHour());
        assertTrue(IRON.hasUpgrade());
        assertFalse(GOLD.hasUpgrade());
    }
}
