package com.coremc.core.enchant;

import com.coremc.core.economy.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure unit tests for the enchant engine's transient math
 * (combos, cooldowns, temp boosts, damage cap). The engine runs
 * with a null plugin here — granting paths are never touched.
 */
public final class EnchantEngineTest {

    private EnchantEngineTest() {
    }

    public static void runAll() {
        comboStacksGrowAndCap();
        comboExpiryRestartsChain();
        cooldownBlocksUntilExpired();
        zeroCooldownIsAlwaysReady();
        tempBoostReportsAndExpires();
        clearPlayerWipesEverything();
        damageCapBoundsMultipliers();
        rollHonoursBounds();
    }

    private static Enchant enchantWithCooldown(final long cooldownSeconds) {
        return new Enchant(
                "miner.test", "miner", "Test", "Test.", "DIAMOND", 1, Currency.SKY_TOKENS,
                List.of(10L), EnchantEffect.DROP_BONUS, EnchantEffect.Trigger.MINE,
                0.1, 0.0, 1.0, 1.0, 0.0, cooldownSeconds, 1, Map.of());
    }

    private static void comboStacksGrowAndCap() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        assertTrue(engine.recordCombo(player, "combo", 60L, 3) == 1, "first stack is 1");
        assertTrue(engine.recordCombo(player, "combo", 60L, 3) == 2, "second stack is 2");
        assertTrue(engine.recordCombo(player, "combo", 60L, 3) == 3, "third stack is 3");
        assertTrue(engine.recordCombo(player, "combo", 60L, 3) == 3, "stacks cap at max");
        assertTrue(engine.comboStacks(player, "combo", 60L) == 3, "live stacks read back");
        assertTrue(engine.comboStacks(player, "other", 60L) == 0, "unknown combo is 0");
    }

    private static void comboExpiryRestartsChain() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        assertTrue(engine.recordCombo(player, "combo", 0L, 5) == 1, "first record is 1");
        assertTrue(engine.recordCombo(player, "combo", 0L, 5) == 1, "expired chain restarts at 1");
        assertTrue(engine.comboStacks(player, "combo", 0L) == 0, "expired stacks read 0");
    }

    private static void cooldownBlocksUntilExpired() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        final Enchant enchant = enchantWithCooldown(60L);
        assertTrue(engine.cooldownReady(player, enchant), "fresh cooldown is ready");
        engine.markCooldown(player, enchant.id());
        assertTrue(!engine.cooldownReady(player, enchant), "marked cooldown blocks");
    }

    private static void zeroCooldownIsAlwaysReady() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        final Enchant enchant = enchantWithCooldown(0L);
        engine.markCooldown(player, enchant.id());
        assertTrue(engine.cooldownReady(player, enchant), "zero cooldown never blocks");
    }

    private static void tempBoostReportsAndExpires() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        engine.setTempBoost(player, 50.0, 25.0, 60_000L);
        assertTrue(engine.tempXpPct(player) == 50.0, "xp percent reads back");
        assertTrue(engine.tempDropsPct(player) == 25.0, "drops percent reads back");
        engine.setTempBoost(player, 50.0, 25.0, 0L);
        assertTrue(engine.tempXpPct(player) == 0.0, "expired xp boost is 0");
        assertTrue(engine.tempDropsPct(player) == 0.0, "expired drops boost is 0");
    }

    private static void clearPlayerWipesEverything() {
        final EnchantEngine engine = new EnchantEngine(null);
        final UUID player = UUID.randomUUID();
        final Enchant enchant = enchantWithCooldown(60L);
        engine.recordCombo(player, "combo", 60L, 5);
        engine.markCooldown(player, enchant.id());
        engine.setTempBoost(player, 10.0, 10.0, 60_000L);
        engine.clearPlayer(player);
        assertTrue(engine.comboStacks(player, "combo", 60L) == 0, "combos cleared");
        assertTrue(engine.cooldownReady(player, enchant), "cooldowns cleared");
        assertTrue(engine.tempXpPct(player) == 0.0, "temp boosts cleared");
    }

    private static void damageCapBoundsMultipliers() {
        assertTrue(EnchantEngine.capDamageMult(3.5) == 3.5, "sane mult passes through");
        assertTrue(EnchantEngine.capDamageMult(100.0) == 10.0, "huge mult caps at 10");
        assertTrue(EnchantEngine.capDamageMult(-2.0) == 0.0, "negative mult floors at 0");
    }

    private static void rollHonoursBounds() {
        final EnchantEngine engine = new EnchantEngine(null);
        assertTrue(engine.roll(1.0), "chance 1 always hits");
        assertTrue(engine.roll(2.0), "chance above 1 always hits");
        assertTrue(!engine.roll(0.0), "chance 0 never hits");
        assertTrue(!engine.roll(-1.0), "negative chance never hits");
    }

    private static void assertTrue(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
