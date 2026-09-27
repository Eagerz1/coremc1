package com.coremc.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GameplayModifierRulesTest {

    @Test
    void slayerFrenzyOverlapUsesMaxNotProduct() {
        assertEquals(2.0, GameplayModifierRules.cappedMax(2.0, 2.0, 2.0), 0.001,
                "island frenzy plus server frenzy must stay x2, not x4");
    }

    @Test
    void capsPreventRunawayChains() {
        assertEquals(2.0, GameplayModifierRules.cappedMax(2.0, 3.0), 0.001);
        assertEquals(3.0, GameplayModifierRules.cappedAdditive(3.0, 2.0, 2.0, 2.0), 0.001);
    }

    @Test
    void additiveFrequencyCanStackSafelyWithinClamp() {
        assertEquals(1.5, GameplayModifierRules.cappedAdditive(3.0, 1.2, 1.3), 0.001);
    }
}
