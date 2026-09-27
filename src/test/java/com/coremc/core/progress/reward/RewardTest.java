package com.coremc.core.progress.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.util.GuiText;
import org.junit.jupiter.api.Test;

/** Reward types, normalisation and the labels the GUIs show. */
class RewardTest {

    @Test
    void permanentTypesApplyThemselvesAndTheRestAreClaimedByHand() {
        assertTrue(RewardType.RECIPE.permanent());
        assertTrue(RewardType.TITLE.permanent());
        assertTrue(RewardType.COSMETIC.permanent());
        assertTrue(RewardType.COLLECTION_TIER.permanent());
        assertFalse(RewardType.COINS.permanent());
        assertTrue(RewardType.COINS.manual());
        assertTrue(RewardType.ITEM.manual());
        assertTrue(RewardType.SKY_TOKENS.manual());
    }

    @Test
    void onlyItemsAndCurrenciesUseAnAmount() {
        assertTrue(RewardType.ITEM.amountBased());
        assertTrue(RewardType.COINS.amountBased());
        assertTrue(RewardType.KEY.amountBased());
        assertTrue(RewardType.JOURNEY_XP.amountBased());
        assertFalse(RewardType.TITLE.amountBased());
        assertFalse(RewardType.RECIPE.amountBased());
    }

    @Test
    void typeIdsRoundTripAndUnknownIdsAreNull() {
        for (final RewardType type : RewardType.values()) {
            assertEquals(type, RewardType.of(type.id()));
            assertEquals(type, RewardType.of(" " + type.id().toUpperCase(java.util.Locale.ROOT)));
        }
        assertNull(RewardType.of("nonsense"));
        assertNull(RewardType.of(null));
    }

    @Test
    void idsAreNormalisedAndAmountsNeverNegative() {
        final Reward reward = new Reward(RewardType.ITEM, "  Diamond_Sword ", -4, "  ");
        assertEquals("diamond_sword", reward.id());
        assertEquals(0, reward.amount());
        assertEquals("", reward.display());
        assertThrows(NullPointerException.class, () -> new Reward(null, "x", 1, ""));
    }

    @Test
    void labelsAreSmallCapsAndReadable() {
        assertEquals(GuiText.caps("Season Crown"),
                Reward.unlock(RewardType.COSMETIC, "season_crown", "Season Crown").label());
        assertEquals("$25,000", Reward.amount(RewardType.COINS, "", 25_000, "").label());
        assertTrue(Reward.amount(RewardType.ITEM, "diamond", 16, "").label().startsWith("16x "));
        assertTrue(Reward.amount(RewardType.SKY_TOKENS, "", 5, "").label().startsWith("5 "));
        assertEquals(GuiText.caps("cobble compressor recipe"),
                Reward.unlock(RewardType.RECIPE, "cobble_compressor", "").label());
        assertEquals(GuiText.caps("new farming tier"),
                Reward.unlock(RewardType.COLLECTION_TIER, "farming", "").label());
    }
}
