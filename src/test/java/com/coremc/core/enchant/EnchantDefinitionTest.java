package com.coremc.core.enchant;

import com.coremc.core.economy.Currency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure unit tests for the custom-enchant catalogue rules: level
 * math, definition parsing, and reward-roll parsing. No server.
 */
public final class EnchantDefinitionTest {

    private EnchantDefinitionTest() {
    }

    public static void runAll() {
        costCurveUsesBaseTimesGrowth();
        chanceAndValueInterpolateLinearly();
        chanceIsCapped();
        levelZeroYieldsNothing();
        explicitCostsWinOverFormula();
        unknownEffectRejectsDefinition();
        unknownTriggerRejectsDefinition();
        unknownRoleRejectsDefinition();
        multiplierRequiresPassiveTrigger();
        rewardTableWithoutRewardsWarns();
        rewardRollParsesAllTypes();
        rewardRollParsesAmountRanges();
        rewardRollRejectsBadEntries();
    }

    private static Map<String, Object> validDef() {
        final Map<String, Object> def = new LinkedHashMap<>();
        def.put("role", "miner");
        def.put("display", "&b&lTEST");
        def.put("description", "&7Test enchant.");
        def.put("icon", "DIAMOND_PICKAXE");
        def.put("max-level", 10);
        def.put("currency", "SKY_TOKENS");
        def.put("cost-base", 100.0);
        def.put("cost-growth", 1.1);
        def.put("effect", "DROP_BONUS");
        def.put("trigger", "MINE");
        def.put("chance-base", 0.05);
        def.put("chance-scale", 0.01);
        def.put("value-base", 1.0);
        def.put("value-scale", 0.5);
        return def;
    }

    private static Enchant parseValid() {
        final List<String> errors = new ArrayList<>();
        final Optional<Enchant> parsed = EnchantRegistry.parseDefinition("miner.test", validDef(), errors);
        assertTrue(parsed.isPresent(), "valid definition must parse, errors: " + errors);
        return parsed.get();
    }

    private static void costCurveUsesBaseTimesGrowth() {
        final Enchant enchant = parseValid();
        assertTrue(enchant.costForLevel(1) == 100L, "level 1 costs the base");
        assertTrue(enchant.costForLevel(2) == 110L, "level 2 costs base*growth");
        assertTrue(enchant.costForLevel(10) == Math.round(100.0 * Math.pow(1.1, 9)), "level 10 follows curve");
    }

    private static void chanceAndValueInterpolateLinearly() {
        final Enchant enchant = parseValid();
        assertTrue(Math.abs(enchant.chanceAt(1) - 0.05) < 1e-9, "chance at 1 is base");
        assertTrue(Math.abs(enchant.chanceAt(10) - 0.14) < 1e-9, "chance scales linearly");
        assertTrue(Math.abs(enchant.valueAt(1) - 1.0) < 1e-9, "value at 1 is base");
        assertTrue(Math.abs(enchant.valueAt(10) - 5.5) < 1e-9, "value scales linearly");
    }

    private static void chanceIsCapped() {
        final Map<String, Object> def = validDef();
        def.put("chance-cap", 0.1);
        final List<String> errors = new ArrayList<>();
        final Enchant enchant = EnchantRegistry.parseDefinition("miner.test", def, errors).orElseThrow();
        assertTrue(Math.abs(enchant.chanceAt(10) - 0.1) < 1e-9, "chance never exceeds the cap");
    }

    private static void levelZeroYieldsNothing() {
        final Enchant enchant = parseValid();
        assertTrue(enchant.chanceAt(0) == 0.0, "unowned chance is 0");
        assertTrue(enchant.valueAt(0) == 0.0, "unowned value is 0");
    }

    private static void explicitCostsWinOverFormula() {
        final Map<String, Object> def = validDef();
        def.put("max-level", 3);
        def.put("costs", List.of(5L, 10L, 25L));
        final List<String> errors = new ArrayList<>();
        final Enchant enchant = EnchantRegistry.parseDefinition("miner.test", def, errors).orElseThrow();
        assertTrue(enchant.costForLevel(1) == 5L, "explicit cost level 1");
        assertTrue(enchant.costForLevel(3) == 25L, "explicit cost level 3");
        assertTrue(enchant.currency() == Currency.SKY_TOKENS, "currency parsed");
    }

    private static void unknownEffectRejectsDefinition() {
        final Map<String, Object> def = validDef();
        def.put("effect", "FLY");
        final List<String> errors = new ArrayList<>();
        assertTrue(EnchantRegistry.parseDefinition("miner.test", def, errors).isEmpty(),
                "unknown effect must reject the definition");
        assertTrue(!errors.isEmpty(), "rejection must explain itself");
    }

    private static void unknownTriggerRejectsDefinition() {
        final Map<String, Object> def = validDef();
        def.put("trigger", "DANCE");
        final List<String> errors = new ArrayList<>();
        assertTrue(EnchantRegistry.parseDefinition("miner.test", def, errors).isEmpty(),
                "unknown trigger must reject the definition");
    }

    private static void unknownRoleRejectsDefinition() {
        final Map<String, Object> def = validDef();
        def.put("role", "ninja");
        final List<String> errors = new ArrayList<>();
        assertTrue(EnchantRegistry.parseDefinition("miner.test", def, errors).isEmpty(),
                "unknown role must reject the definition");
    }

    private static void multiplierRequiresPassiveTrigger() {
        final Map<String, Object> def = validDef();
        def.put("effect", "MULTIPLIER");
        def.put("trigger", "MINE");
        final List<String> errors = new ArrayList<>();
        assertTrue(EnchantRegistry.parseDefinition("miner.test", def, errors).isEmpty(),
                "MULTIPLIER with a non-PASSIVE trigger must be rejected");
    }

    private static void rewardTableWithoutRewardsWarns() {
        final Map<String, Object> def = validDef();
        def.put("effect", "REWARD_TABLE");
        def.put("values", Map.of());
        final List<String> errors = new ArrayList<>();
        assertTrue(EnchantRegistry.parseDefinition("miner.test", def, errors).isPresent(),
                "missing table warns but keeps the enchant");
        assertTrue(errors.stream().anyMatch(line -> line.contains("rewards")),
                "warning must mention the rewards table");
    }

    private static void rewardRollParsesAllTypes() {
        final List<String> errors = new ArrayList<>();
        assertTrue(RewardRoll.parse(Map.of("chance", 0.5, "type", "ITEM", "material", "DIAMOND",
                "amount", 2), errors, "t").map(roll -> roll.type()
                == RewardRoll.RewardType.ITEM && roll.minAmount() == 2).orElse(false), "ITEM parses");
        assertTrue(RewardRoll.parse(Map.of("chance", 0.5, "type", "TOKENS", "amount", 5), errors, "t")
                .map(roll -> roll.type() == RewardRoll.RewardType.TOKENS).orElse(false), "TOKENS parses");
        assertTrue(RewardRoll.parse(Map.of("chance", 0.5, "type", "CREDITS"), errors, "t")
                .map(roll -> roll.minAmount() == 1).orElse(false), "missing amount defaults to 1");
        assertTrue(RewardRoll.parse(Map.of("chance", 1.0, "type", "XP", "amount", 10), errors, "t")
                .map(roll -> roll.type() == RewardRoll.RewardType.XP).orElse(false), "XP parses");
        assertTrue(RewardRoll.parse(Map.of("chance", 0.05, "type", "KEY", "key", "sky"), errors, "t")
                .map(roll -> "sky".equals(roll.keyId())).orElse(false), "KEY parses");
        assertTrue(errors.isEmpty(), "valid rolls must not warn: " + errors);
    }

    private static void rewardRollParsesAmountRanges() {
        final List<String> errors = new ArrayList<>();
        final RewardRoll roll = RewardRoll.parse(
                        Map.of("chance", 0.3, "type", "TOKENS", "amount", "3-8"), errors, "t")
                .orElseThrow();
        assertTrue(roll.minAmount() == 3 && roll.maxAmount() == 8, "range parses to min/max");
        final int sampled = roll.rollAmount(new java.util.Random(42));
        assertTrue(sampled >= 3 && sampled <= 8, "rollAmount stays in range");
    }

    private static void rewardRollRejectsBadEntries() {
        final List<String> errors = new ArrayList<>();
        assertTrue(RewardRoll.parse(Map.of("chance", 2.0, "type", "ITEM", "material", "DIRT"),
                errors, "t").isEmpty(), "chance > 1 rejected");
        assertTrue(RewardRoll.parse(Map.of("chance", 0.5, "type", "ITEM"), errors, "t").isEmpty(),
                "ITEM without material rejected");
        assertTrue(RewardRoll.parse(Map.of("chance", 0.5, "type", "KEY"), errors, "t").isEmpty(),
                "KEY without key id rejected");
        assertTrue(RewardRoll.parse(Map.of("chance", 0.5, "type", "DRAGON"), errors, "t").isEmpty(),
                "unknown type rejected");
        assertTrue(RewardRoll.parse("not-a-map", errors, "t").isEmpty(), "non-map rejected");
    }

    private static void assertTrue(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
