package com.coremc.core.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.economy.Currency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the custom-enchant catalogue rules: level
 * math, definition parsing, and reward-roll parsing. No server.
 */
class EnchantDefinitionTest {

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

    @Test
    void costCurveUsesBaseTimesGrowth() {
        final Enchant enchant = parseValid();
        assertEquals(100L, enchant.costForLevel(1), "level 1 costs the base");
        assertEquals(110L, enchant.costForLevel(2), "level 2 costs base*growth");
        assertEquals(Math.round(100.0 * Math.pow(1.1, 9)), enchant.costForLevel(10),
                "level 10 follows curve");
    }

    @Test
    void chanceAndValueInterpolateLinearly() {
        final Enchant enchant = parseValid();
        assertEquals(0.05, enchant.chanceAt(1), 1e-9, "chance at 1 is base");
        assertEquals(0.14, enchant.chanceAt(10), 1e-9, "chance scales linearly");
        assertEquals(1.0, enchant.valueAt(1), 1e-9, "value at 1 is base");
        assertEquals(5.5, enchant.valueAt(10), 1e-9, "value scales linearly");
    }

    @Test
    void chanceIsCapped() {
        final Map<String, Object> def = validDef();
        def.put("chance-cap", 0.1);
        final List<String> errors = new ArrayList<>();
        final Enchant enchant = EnchantRegistry.parseDefinition("miner.test", def, errors).orElseThrow();
        assertEquals(0.1, enchant.chanceAt(10), 1e-9, "chance never exceeds the cap");
    }

    @Test
    void levelZeroYieldsNothing() {
        final Enchant enchant = parseValid();
        assertEquals(0.0, enchant.chanceAt(0), "unowned chance is 0");
        assertEquals(0.0, enchant.valueAt(0), "unowned value is 0");
    }

    @Test
    void explicitCostsWinOverFormula() {
        final Map<String, Object> def = validDef();
        def.put("max-level", 3);
        def.put("costs", List.of(5L, 10L, 25L));
        final List<String> errors = new ArrayList<>();
        final Enchant enchant = EnchantRegistry.parseDefinition("miner.test", def, errors).orElseThrow();
        assertEquals(5L, enchant.costForLevel(1), "explicit cost level 1");
        assertEquals(25L, enchant.costForLevel(3), "explicit cost level 3");
        assertEquals(Currency.SKY_TOKENS, enchant.currency(), "currency parsed");
    }

    @Test
    void unknownEffectRejectsDefinition() {
        final Map<String, Object> def = validDef();
        def.put("effect", "FLY");
        final List<String> errors = new ArrayList<>();
        assertTrue(EnchantRegistry.parseDefinition("miner.test", def, errors).isEmpty(),
                "unknown effect must reject the definition");
        assertTrue(!errors.isEmpty(), "rejection must explain itself");
    }

    @Test
    void unknownTriggerRejectsDefinition() {
        final Map<String, Object> def = validDef();
        def.put("trigger", "DANCE");
        final List<String> errors = new ArrayList<>();
        assertTrue(EnchantRegistry.parseDefinition("miner.test", def, errors).isEmpty(),
                "unknown trigger must reject the definition");
    }

    @Test
    void unknownRoleRejectsDefinition() {
        final Map<String, Object> def = validDef();
        def.put("role", "ninja");
        final List<String> errors = new ArrayList<>();
        assertTrue(EnchantRegistry.parseDefinition("miner.test", def, errors).isEmpty(),
                "unknown role must reject the definition");
    }

    @Test
    void multiplierRequiresPassiveTrigger() {
        final Map<String, Object> def = validDef();
        def.put("effect", "MULTIPLIER");
        def.put("trigger", "MINE");
        final List<String> errors = new ArrayList<>();
        assertTrue(EnchantRegistry.parseDefinition("miner.test", def, errors).isEmpty(),
                "MULTIPLIER with a non-PASSIVE trigger must be rejected");
    }

    @Test
    void rewardTableWithoutRewardsWarns() {
        final Map<String, Object> def = validDef();
        def.put("effect", "REWARD_TABLE");
        def.put("values", Map.of());
        final List<String> errors = new ArrayList<>();
        assertTrue(EnchantRegistry.parseDefinition("miner.test", def, errors).isPresent(),
                "missing table warns but keeps the enchant");
        assertTrue(errors.stream().anyMatch(line -> line.contains("rewards")),
                "warning must mention the rewards table");
    }

    @Test
    void rewardRollParsesAllTypes() {
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
        assertTrue(RewardRoll.parse(Map.of("chance", 0.4, "type", "SOULS", "amount", 3), errors, "t")
                .map(roll -> roll.type() == RewardRoll.RewardType.SOULS).orElse(false), "SOULS parses");
        assertTrue(errors.isEmpty(), "valid rolls must not warn: " + errors);
    }

    @Test
    void rewardRollParsesAmountRanges() {
        final List<String> errors = new ArrayList<>();
        final RewardRoll roll = RewardRoll.parse(
                        Map.of("chance", 0.3, "type", "TOKENS", "amount", "3-8"), errors, "t")
                .orElseThrow();
        assertEquals(3, roll.minAmount(), "range min parses");
        assertEquals(8, roll.maxAmount(), "range max parses");
        final int sampled = roll.rollAmount(new java.util.Random(42));
        assertTrue(sampled >= 3 && sampled <= 8, "rollAmount stays in range");
    }

    @Test
    void rewardRollRejectsBadEntries() {
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
}
