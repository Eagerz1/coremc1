package com.coremc.core.essence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * EssenceManager: balances never go negative, every mutation persists
 * (write-through, like the economy), and kills are tracked alongside
 * the three essence types.
 */
class EssenceManagerTest {

    /** Minimal in-memory store that records every save call. */
    private static final class RecordingStore implements EssenceStore {
        int saves;

        @Override
        public Map<UUID, EssenceProfile> loadAll() {
            return new HashMap<>();
        }

        @Override
        public void saveAll(final Map<UUID, EssenceProfile> profiles) {
            saves++;
        }
    }

    @Test
    void freshPlayersHaveZeroBalances() throws IOException {
        final EssenceManager manager = new EssenceManager(new RecordingStore(),
                Logger.getLogger("test"));
        final UUID player = UUID.randomUUID();
        assertEquals(0, manager.balance(player, EssenceType.SLAYER));
        assertEquals(0, manager.balance(player, EssenceType.MINING));
        assertEquals(0, manager.balance(player, EssenceType.FARMING));
        assertEquals(0, manager.total(player));
        assertEquals(0, manager.kills(player));
    }

    @Test
    void giveAccumulatesPerType() throws IOException {
        final EssenceManager manager = new EssenceManager(new RecordingStore(),
                Logger.getLogger("test"));
        final UUID player = UUID.randomUUID();
        manager.give(player, EssenceType.SLAYER, 5);
        manager.give(player, EssenceType.SLAYER, 7);
        manager.give(player, EssenceType.MINING, 3);
        assertEquals(12, manager.balance(player, EssenceType.SLAYER));
        assertEquals(3, manager.balance(player, EssenceType.MINING));
        assertEquals(0, manager.balance(player, EssenceType.FARMING));
        assertEquals(15, manager.total(player));
    }

    @Test
    void takeRemovesAndReportsShortfalls() throws IOException {
        final EssenceManager manager = new EssenceManager(new RecordingStore(),
                Logger.getLogger("test"));
        final UUID player = UUID.randomUUID();
        manager.give(player, EssenceType.SLAYER, 10);
        assertTrue(manager.take(player, EssenceType.SLAYER, 4));
        assertEquals(6, manager.balance(player, EssenceType.SLAYER));
        assertFalse(manager.take(player, EssenceType.SLAYER, 7));
        assertEquals(6, manager.balance(player, EssenceType.SLAYER)); // unchanged
        assertTrue(manager.take(player, EssenceType.SLAYER, 6));
        assertEquals(0, manager.balance(player, EssenceType.SLAYER));
    }

    @Test
    void balancesNeverGoNegative() throws IOException {
        final EssenceManager manager = new EssenceManager(new RecordingStore(),
                Logger.getLogger("test"));
        final UUID player = UUID.randomUUID();
        // set with a negative amount clamps to zero
        manager.set(player, EssenceType.FARMING, -100);
        assertEquals(0, manager.balance(player, EssenceType.FARMING));
        // taking more than held changes nothing
        assertFalse(manager.take(player, EssenceType.FARMING, 5));
        assertEquals(0, manager.balance(player, EssenceType.FARMING));
    }

    @Test
    void killsCountUpAndDownButNeverBelowZero() throws IOException {
        final EssenceManager manager = new EssenceManager(new RecordingStore(),
                Logger.getLogger("test"));
        final UUID player = UUID.randomUUID();
        manager.addKills(player, 4);
        manager.addKills(player, 1);
        assertEquals(5, manager.kills(player));
        manager.setKills(player, 2);
        assertEquals(2, manager.kills(player));
        // /essence take kills subtracts via setKills and clamps at zero
        manager.setKills(player, Math.max(0, manager.kills(player) - 10));
        assertEquals(0, manager.kills(player));
    }

    @Test
    void everyMutationPersistsWriteThrough() throws IOException {
        final RecordingStore store = new RecordingStore();
        final EssenceManager manager = new EssenceManager(store, Logger.getLogger("test"));
        final UUID player = UUID.randomUUID();
        assertEquals(0, store.saves);
        manager.give(player, EssenceType.SLAYER, 1);
        assertEquals(1, store.saves);
        manager.take(player, EssenceType.SLAYER, 1);
        assertEquals(2, store.saves);
        manager.set(player, EssenceType.MINING, 5);
        assertEquals(3, store.saves);
        manager.addKills(player, 1);
        assertEquals(4, store.saves);
        manager.setKills(player, 9);
        assertEquals(5, store.saves);
        manager.shutdown();
        assertEquals(6, store.saves);
    }

    @Test
    void formatGroupsDigitsWithCommas() {
        assertEquals("0", EssenceManager.format(0));
        assertEquals("999", EssenceManager.format(999));
        assertEquals("1,250", EssenceManager.format(1250));
        assertEquals("1,500,000", EssenceManager.format(1500000));
    }

    @Test
    void typesParseCaseInsensitivelyAndExposeDisplayNames() {
        assertEquals(EssenceType.SLAYER, EssenceType.of("slayer"));
        assertEquals(EssenceType.MINING, EssenceType.of("MINING"));
        assertEquals(EssenceType.FARMING, EssenceType.of("Farming"));
        assertEquals(null, EssenceType.of(null));
        assertEquals(null, EssenceType.of("blood"));
        for (final EssenceType type : EssenceType.values()) {
            assertTrue(type.display().endsWith("Essence"), type.name());
        }
    }

    @Test
    void corruptStoreFileStartsFreshInsteadOfFailing(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("essence-balances.yml");
        Files.writeString(file, "not: [valid: yaml");
        final EssenceManager manager = new EssenceManager(
                new YamlEssenceStore(file, Logger.getLogger("test")), Logger.getLogger("test"));
        final UUID player = UUID.randomUUID();
        assertEquals(0, manager.balance(player, EssenceType.SLAYER));
        manager.give(player, EssenceType.SLAYER, 3);
        assertEquals(3, manager.balance(player, EssenceType.SLAYER));
    }
}
