package com.coremc.core.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Grant descriptor round-trips: pending rewards persist as compact
 * descriptor lines, so serialization must survive every field —
 * including separators inside display names — and corrupt lines must
 * fail loudly instead of silently corrupting deliveries.
 */
class RewardGrantTest {

    @Test
    void roundTripsEveryType() {
        final List<RewardGrant> grants = List.of(
                new RewardGrant(RewardType.MONEY, "", 12000, ""),
                new RewardGrant(RewardType.SKY_TOKENS, "", 1500, ""),
                new RewardGrant(RewardType.CREDITS, "", 75, ""),
                new RewardGrant(RewardType.KEY, "river", 2, "&b&lRiver Key"),
                new RewardGrant(RewardType.LOOTBOX, "core", 1, "&b&lCore Lootbox"),
                new RewardGrant(RewardType.ITEM, "core_fragment", 3, "&5Core Fragment",
                        "ECHO_SHARD"),
                new RewardGrant(RewardType.COMMAND, "cmd", 1, "Special",
                        "say hi;;give %player% dirt 1"));
        for (final RewardGrant grant : grants) {
            assertEquals(grant, RewardGrant.parse(grant.serialize()), grant.serialize());
        }
    }

    @Test
    void escapesSeparatorsInsideFields() {
        final RewardGrant tricky = new RewardGrant(RewardType.ITEM, "we|ird",
                7, "Name | with \\ pipes", "IRON_BLOCK");
        assertEquals(tricky, RewardGrant.parse(tricky.serialize()));
    }

    @Test
    void corruptLinesFailLoudly() {
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.parse(null));
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.parse(""));
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.parse("money"));
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.parse("nope|x|1|d"));
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.parse("money|x|NaN|d"));
        assertThrows(IllegalArgumentException.class, () -> RewardGrant.parse("money|x|-5|d"));
    }

    @Test
    void ofCarriesMaterialAndCommands() {
        final RewardDef item = new RewardDef("frag", RewardType.ITEM, "&5Core Fragment",
                "rare", 1, 3, 5, Material.ECHO_SHARD, null);
        final RewardGrant grant = RewardGrant.of(item, 2);
        assertEquals("ECHO_SHARD", grant.extra());
        assertEquals("frag", grant.id());
        assertEquals(2, grant.amount());

        final RewardDef command = new RewardDef("cmd", RewardType.COMMAND, "Special",
                "rare", 1, 1, 5, null, List.of("say a", "say b"));
        assertEquals(List.of("say a", "say b"), RewardGrant.of(command, 1).commands());
    }

    @Test
    void tokensAliasParses() {
        assertEquals(RewardType.SKY_TOKENS, RewardType.parse("tokens"));
        assertEquals(RewardType.SKY_TOKENS, RewardType.parse("sky-tokens"));
        assertEquals(RewardType.MONEY, RewardType.parse("MONEY"));
        assertEquals(null, RewardType.parse("gold"));
    }
}
