package com.coremc.core.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The authoritative event: normalisation, factories and action ids. */
class ProgressEventTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void keysAreLowerCasedAndTrimmed() {
        final ProgressEvent event = ProgressEvent.of(PLAYER, ProgressAction.MINE_BLOCK,
                "  Diamond_Ore ", 1, ProgressSource.WORLD);
        assertEquals("diamond_ore", event.key());
    }

    @Test
    void negativeAmountsBecomeZeroAndCountAsEmpty() {
        final ProgressEvent event = ProgressEvent.of(PLAYER, ProgressAction.MINE_BLOCK, "stone",
                -5, ProgressSource.WORLD);
        assertEquals(0, event.amount());
        assertTrue(event.empty());
    }

    @Test
    void singleCountsOne() {
        final ProgressEvent event = ProgressEvent.single(PLAYER, ProgressAction.DISCOVERY,
                "ancient_debris", ProgressSource.DISCOVERY, "dedupe");
        assertEquals(1, event.amount());
        assertEquals("dedupe", event.dedupeKey());
        assertFalse(event.empty());
    }

    @Test
    void withAmountKeepsEverythingElse() {
        final ProgressEvent event = ProgressEvent.of(PLAYER, ProgressAction.GENERATOR_OUTPUT,
                "coal", 500, ProgressSource.GENERATOR, "key");
        final ProgressEvent weighted = event.withAmount(50);
        assertEquals(50, weighted.amount());
        assertEquals(event.player(), weighted.player());
        assertEquals(event.action(), weighted.action());
        assertEquals(event.key(), weighted.key());
        assertEquals(event.source(), weighted.source());
        assertEquals(event.dedupeKey(), weighted.dedupeKey());
        assertEquals(event.timestamp(), weighted.timestamp());
    }

    @Test
    void playerActionAndSourceAreRequired() {
        assertThrows(NullPointerException.class, () -> ProgressEvent.of(null,
                ProgressAction.MINE_BLOCK, "stone", 1, ProgressSource.WORLD));
        assertThrows(NullPointerException.class, () -> ProgressEvent.of(PLAYER, null, "stone", 1,
                ProgressSource.WORLD));
        assertThrows(NullPointerException.class, () -> ProgressEvent.of(PLAYER,
                ProgressAction.MINE_BLOCK, "stone", 1, null));
    }

    @Test
    void actionIdsAreUniqueStableAndLookUpBothWays() {
        final Set<String> ids = new HashSet<>();
        for (final ProgressAction action : ProgressAction.values()) {
            assertTrue(ids.add(action.id()), action + " has a duplicate id");
            assertEquals(action, ProgressAction.of(action.id()));
            assertEquals(action, ProgressAction.of(action.id().toUpperCase(java.util.Locale.ROOT)));
        }
        assertNull(ProgressAction.of("not_an_action"));
        assertNull(ProgressAction.of(null));
    }

    @Test
    void onlyGeneratorOutputIsPassiveAndOnlyEnchantProcsAreRateLimited() {
        for (final ProgressAction action : ProgressAction.values()) {
            assertEquals(action == ProgressAction.GENERATOR_OUTPUT, action.passive(),
                    action + " passive flag");
            assertEquals(action == ProgressAction.OMNITOOL_ENCHANT_PROC, action.rateLimited(),
                    action + " rate-limit flag");
        }
    }

    @Test
    void sourceIdsRoundTrip() {
        for (final ProgressSource source : ProgressSource.values()) {
            assertEquals(source, ProgressSource.of(source.id()));
            assertNotNull(source.id());
        }
        assertNull(ProgressSource.of("nope"));
    }
}
