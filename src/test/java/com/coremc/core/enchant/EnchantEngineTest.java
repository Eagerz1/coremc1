package com.coremc.core.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.economy.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the enchant engine's transient math
 * (combos, cooldowns, temp boosts, damage cap). The engine runs
 * with a null plugin here — granting paths are never touched.
 */
class EnchantEngineTest {

    private static Enchant enchantWithCooldown(final long cooldownSeconds) {
        return new Enchant(
                "miner.test", "miner", "Test", "Test.", "DIAMOND", 1, Currency.SKY_TOKENS,
                List.of(10L), EnchantEffect.DROP_BONUS, EnchantEffect.Trigger.MINE,
                0.1, 0.0, 1.0, 1.0, 0.0, cooldownSeconds, 1, Map.of());
    }

    @Test
    void comboStacksGrowAndCap() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        assertEquals(1, engine.recordCombo(player, "combo", 60L, 3), "first stack is 1");
        assertEquals(2, engine.recordCombo(player, "combo", 60L, 3), "second stack is 2");
        assertEquals(3, engine.recordCombo(player, "combo", 60L, 3), "third stack is 3");
        assertEquals(3, engine.recordCombo(player, "combo", 60L, 3), "stacks cap at max");
        assertEquals(3, engine.comboStacks(player, "combo", 60L), "live stacks read back");
        assertEquals(0, engine.comboStacks(player, "other", 60L), "unknown combo is 0");
    }

    @Test
    void comboExpiryRestartsChain() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        assertEquals(1, engine.recordCombo(player, "combo", 0L, 5), "first record is 1");
        assertEquals(1, engine.recordCombo(player, "combo", 0L, 5), "expired chain restarts at 1");
        assertEquals(0, engine.comboStacks(player, "combo", 0L), "expired stacks read 0");
    }

    @Test
    void cooldownBlocksUntilExpired() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        final Enchant enchant = enchantWithCooldown(60L);
        assertTrue(engine.cooldownReady(player, enchant), "fresh cooldown is ready");
        engine.markCooldown(player, enchant.id());
        assertFalse(engine.cooldownReady(player, enchant), "marked cooldown blocks");
    }

    @Test
    void zeroCooldownIsAlwaysReady() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        final Enchant enchant = enchantWithCooldown(0L);
        engine.markCooldown(player, enchant.id());
        assertTrue(engine.cooldownReady(player, enchant), "zero cooldown never blocks");
    }

    @Test
    void tempBoostReportsAndExpires() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        engine.setTempBoost(player, 50.0, 25.0, 60_000L);
        assertEquals(50.0, engine.tempXpPct(player), "xp percent reads back");
        assertEquals(25.0, engine.tempDropsPct(player), "drops percent reads back");
        engine.setTempBoost(player, 50.0, 25.0, 0L);
        assertEquals(0.0, engine.tempXpPct(player), "expired xp boost is 0");
        assertEquals(0.0, engine.tempDropsPct(player), "expired drops boost is 0");
    }

    @Test
    void clearPlayerWipesEverything() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        final Enchant enchant = enchantWithCooldown(60L);
        engine.recordCombo(player, "combo", 60L, 5);
        engine.markCooldown(player, enchant.id());
        engine.setTempBoost(player, 10.0, 10.0, 60_000L);
        engine.clearPlayer(player);
        assertEquals(0, engine.comboStacks(player, "combo", 60L), "combos cleared");
        assertTrue(engine.cooldownReady(player, enchant), "cooldowns cleared");
        assertEquals(0.0, engine.tempXpPct(player), "temp boosts cleared");
    }

    @Test
    void damageCapBoundsMultipliers() {
        assertEquals(3.5, EnchantEngine.capDamageMult(3.5), "sane mult passes through");
        assertEquals(10.0, EnchantEngine.capDamageMult(100.0), "huge mult caps at 10");
        assertEquals(0.0, EnchantEngine.capDamageMult(-2.0), "negative mult floors at 0");
    }

    @Test
    void rollHonoursBounds() {
        final EnchantEngine engine = new EnchantEngine(null);
        assertTrue(engine.roll(1.0), "chance 1 always hits");
        assertTrue(engine.roll(2.0), "chance above 1 always hits");
        assertFalse(engine.roll(0.0), "chance 0 never hits");
        assertFalse(engine.roll(-1.0), "negative chance never hits");
    }
}
