package com.coremc.core.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Credit arithmetic: balances start at zero, never go negative, and
 * every mutation persists. Rapid double-spends can never overdraw.
 */
class CreditServiceTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** In-memory store that counts saves. */
    private static final class MemoryStore implements BalanceStore {
        private final Map<UUID, Long> data = new HashMap<>();
        private int saves;

        @Override
        public Map<UUID, Long> loadAll() {
            return new HashMap<>(data);
        }

        @Override
        public void saveAll(final Map<UUID, Long> balances) {
            data.clear();
            data.putAll(balances);
            saves++;
        }
    }

    private static CreditService service(final MemoryStore store) {
        try {
            return new CreditService(store, LOGGER);
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void unknownPlayersHoldZero() {
        final CreditService credits = service(new MemoryStore());
        assertEquals(0, credits.balance(PLAYER));
        assertTrue(credits.has(PLAYER, 0));
        assertFalse(credits.has(PLAYER, 1));
    }

    @Test
    void addAndTakeRoundTrip() {
        final CreditService credits = service(new MemoryStore());
        credits.add(PLAYER, 1850, CreditReason.PURCHASE);
        assertEquals(1850, credits.balance(PLAYER));
        assertTrue(credits.take(PLAYER, 850, CreditReason.PURCHASE));
        assertEquals(1000, credits.balance(PLAYER));
    }

    @Test
    void balancesNeverGoNegative() {
        final CreditService credits = service(new MemoryStore());
        credits.add(PLAYER, 100, CreditReason.ADMIN);
        assertFalse(credits.take(PLAYER, 101, CreditReason.PURCHASE));
        assertEquals(100, credits.balance(PLAYER));
        assertFalse(credits.take(PLAYER, -5, CreditReason.PURCHASE));
        assertEquals(100, credits.balance(PLAYER));
        credits.set(PLAYER, -50, CreditReason.ADMIN);
        assertEquals(0, credits.balance(PLAYER));
    }

    @Test
    void doubleSpendCannotOverdraw() {
        // the "rapid purchases do not duplicate" invariant at the
        // balance level: two takes for one balance — only one wins
        final CreditService credits = service(new MemoryStore());
        credits.add(PLAYER, 250, CreditReason.ADMIN);
        assertTrue(credits.take(PLAYER, 250, CreditReason.PURCHASE));
        assertFalse(credits.take(PLAYER, 250, CreditReason.PURCHASE));
        assertEquals(0, credits.balance(PLAYER));
    }

    @Test
    void missingReportsExactShortfall() {
        final CreditService credits = service(new MemoryStore());
        credits.add(PLAYER, 100, CreditReason.VOTE);
        assertEquals(150, credits.missing(PLAYER, 250));
        assertEquals(0, credits.missing(PLAYER, 100));
        assertEquals(0, credits.missing(PLAYER, 50));
    }

    @Test
    void ignoredMutationsDoNotPersistOrChange() {
        final MemoryStore store = new MemoryStore();
        final CreditService credits = service(store);
        credits.add(PLAYER, 0, CreditReason.ADMIN);
        credits.add(PLAYER, -10, CreditReason.ADMIN);
        assertEquals(0, credits.balance(PLAYER));
        assertEquals(0, store.saves);
        assertTrue(credits.take(PLAYER, 0, CreditReason.PURCHASE));
    }

    @Test
    void everyRealMutationPersistsImmediately() {
        final MemoryStore store = new MemoryStore();
        final CreditService credits = service(store);
        credits.add(PLAYER, 10, CreditReason.QUEST);
        credits.take(PLAYER, 5, CreditReason.PURCHASE);
        credits.set(PLAYER, 7, CreditReason.ADMIN);
        assertEquals(3, store.saves);
        assertEquals(7L, store.data.get(PLAYER));
    }

    @Test
    void formatGroupsThousands() {
        assertEquals("1,850", CreditService.format(1850));
        assertEquals("0", CreditService.format(0));
        assertEquals("1,000,000", CreditService.format(1_000_000));
    }

    @Test
    void reasonParsingIsLenientAndDefaultsToAdmin() {
        assertEquals(CreditReason.VOTE, CreditReason.parse("vote"));
        assertEquals(CreditReason.ISLAND_MILESTONE, CreditReason.parse("ISLAND_MILESTONE"));
        assertEquals(CreditReason.ADMIN, CreditReason.parse("nonsense"));
        assertEquals(CreditReason.ADMIN, CreditReason.parse(null));
    }

    @Test
    void skyTokensFollowTheSameRules() throws IOException {
        final SkyTokenService tokens = new SkyTokenService(new MemoryStore(), LOGGER);
        tokens.add(PLAYER, 1500);
        assertEquals(1500, tokens.balance(PLAYER));
        assertFalse(tokens.take(PLAYER, 1501));
        assertTrue(tokens.take(PLAYER, 1500));
        assertEquals(0, tokens.balance(PLAYER));
    }
}
