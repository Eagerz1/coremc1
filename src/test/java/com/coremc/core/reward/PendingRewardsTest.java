package com.coremc.core.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pending delivery + transaction idempotency: duplicate txn ids are
 * no-ops, only confirmed grants leave the ledger, refusals stay
 * pending, and the YAML store round-trips everything.
 */
class PendingRewardsTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static final class MemoryStore implements PendingStore {
        private Map<UUID, List<PendingRewards.PendingTxn>> data = new LinkedHashMap<>();
        private int saves;

        @Override
        public Map<UUID, List<PendingRewards.PendingTxn>> loadAll() {
            final Map<UUID, List<PendingRewards.PendingTxn>> copy = new LinkedHashMap<>();
            for (final var entry : data.entrySet()) {
                copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            return copy;
        }

        @Override
        public void saveAll(final Map<UUID, List<PendingRewards.PendingTxn>> pending) {
            data = new LinkedHashMap<>();
            for (final var entry : pending.entrySet()) {
                data.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            saves++;
        }
    }

    private static PendingRewards fresh(final PendingStore store) {
        try {
            return new PendingRewards(store, LOGGER);
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RewardGrant money(final long amount) {
        return new RewardGrant(RewardType.MONEY, "", amount, "");
    }

    private static RewardGrant key(final String id, final long amount) {
        return new RewardGrant(RewardType.KEY, id, amount, "&bKey");
    }

    @Test
    void addIsIdempotentByTxnId() {
        final PendingRewards pending = fresh(new MemoryStore());
        assertTrue(pending.add(PLAYER, "txn-1", "lootbox:core", List.of(money(100))));
        assertFalse(pending.add(PLAYER, "txn-1", "lootbox:core", List.of(money(100))));
        assertEquals(1, pending.of(PLAYER).size());
        assertEquals(1, pending.grantCount(PLAYER));
    }

    @Test
    void emptyGrantListsAreRejected() {
        final PendingRewards pending = fresh(new MemoryStore());
        assertFalse(pending.add(PLAYER, "txn-1", "x", List.of()));
        assertFalse(pending.add(PLAYER, "txn-2", "x", null));
        assertEquals(0, pending.grantCount(PLAYER));
    }

    @Test
    void deliveryRemovesOnlyConfirmedGrants() {
        final PendingRewards pending = fresh(new MemoryStore());
        pending.add(PLAYER, "txn-1", "purchase:bundle",
                List.of(money(100), key("river", 2), key("sky", 1)));
        // deliverer accepts everything but the sky key (inventory full)
        final int delivered = pending.deliver(PLAYER,
                grant -> !(grant.type() == RewardType.KEY && grant.id().equals("sky")));
        assertEquals(2, delivered);
        assertEquals(1, pending.grantCount(PLAYER));
        assertTrue(pending.has(PLAYER, "txn-1"));
        // second attempt: the rest arrives, the txn closes — no duplicates
        assertEquals(1, pending.deliver(PLAYER, grant -> true));
        assertEquals(0, pending.grantCount(PLAYER));
        assertFalse(pending.has(PLAYER, "txn-1"));
        // a third run finds nothing (idempotent)
        assertEquals(0, pending.deliver(PLAYER, grant -> true));
    }

    @Test
    void throwingDelivererKeepsGrantsPending() {
        final PendingRewards pending = fresh(new MemoryStore());
        pending.add(PLAYER, "txn-1", "crate:vote", List.of(money(100)));
        assertEquals(0, pending.deliver(PLAYER, grant -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, pending.grantCount(PLAYER));
    }

    @Test
    void removeDropsWholeTransactions() {
        final PendingRewards pending = fresh(new MemoryStore());
        pending.add(PLAYER, "txn-1", "x", List.of(money(1), money(2)));
        assertTrue(pending.remove(PLAYER, "txn-1"));
        assertFalse(pending.remove(PLAYER, "txn-1"));
        assertEquals(0, pending.grantCount(PLAYER));
    }

    @Test
    void survivesAStoreRoundTrip() {
        final MemoryStore store = new MemoryStore();
        final PendingRewards first = fresh(store);
        first.add(PLAYER, "txn-9", "lootbox:core", List.of(money(500), key("crimson", 1)));
        // "restart": a new service over the same store
        final PendingRewards second = fresh(store);
        assertTrue(second.has(PLAYER, "txn-9"));
        assertEquals(2, second.grantCount(PLAYER));
        assertEquals("lootbox:core", second.of(PLAYER).get(0).source());
    }

    @Test
    void yamlStoreRoundTripsGrantsAndSkipsCorruption(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("pending-rewards.yml");
        final YamlPendingStore store = new YamlPendingStore(file, LOGGER);
        final Map<UUID, List<PendingRewards.PendingTxn>> out = new HashMap<>();
        out.put(PLAYER, List.of(new PendingRewards.PendingTxn("txn-1", "lootbox:core", 123L,
                List.of(money(12000), key("river", 2),
                        new RewardGrant(RewardType.ITEM, "core_fragment", 3,
                                "&5Core Fragment", "ECHO_SHARD")))));
        store.saveAll(out);
        final var loaded = store.loadAll();
        assertEquals(1, loaded.get(PLAYER).size());
        final PendingRewards.PendingTxn txn = loaded.get(PLAYER).get(0);
        assertEquals("txn-1", txn.txnId());
        assertEquals("lootbox:core", txn.source());
        assertEquals(123L, txn.created());
        assertEquals(3, txn.grants().size());
        assertEquals("ECHO_SHARD", txn.grants().get(2).extra());

        // corrupt grant lines are skipped, not fatal
        Files.writeString(file, PLAYER + ":\n  txn-2:\n    source: x\n    created: 1\n"
                + "    grants:\n      - \"garbage-line\"\n      - \"money||500|\"\n");
        final var partial = store.loadAll();
        assertEquals(1, partial.get(PLAYER).get(0).grants().size());
        assertEquals(500, partial.get(PLAYER).get(0).grants().get(0).amount());

        // a whole corrupt file starts fresh instead of crashing
        Files.writeString(file, "\t\tnot: yaml: at: all");
        assertTrue(store.loadAll().isEmpty());
    }
}
