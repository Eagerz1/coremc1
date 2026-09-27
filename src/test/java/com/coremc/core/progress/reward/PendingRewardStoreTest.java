package com.coremc.core.progress.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Nothing valuable is ever dropped: a reward that cannot be delivered
 * is written to disk and survives a restart.
 */
class PendingRewardStoreTest {

    @TempDir
    Path folder;

    @Test
    void parkedRewardsSurviveARestart() {
        final Path file = folder.resolve("pending-rewards.yml");
        final UUID player = UUID.randomUUID();
        final PendingRewardStore store = new PendingRewardStore(file, null);
        store.load();
        store.add(player, Reward.amount(RewardType.SKY_TOKENS, "", 10, ""), "collection:diamond:4");
        store.add(player, Reward.amount(RewardType.ITEM, "diamond", 16, ""), "achievement:x");
        assertEquals(2, store.count(player));

        final PendingRewardStore reloaded = new PendingRewardStore(file, null);
        reloaded.load();
        assertEquals(2, reloaded.count(player));
        final List<PendingReward> held = reloaded.of(player);
        assertEquals(RewardType.SKY_TOKENS, held.get(0).type());
        assertEquals(10, held.get(0).amount());
        assertEquals("collection:diamond:4", held.get(0).source());
        assertEquals("diamond", held.get(1).subject());
    }

    @Test
    void deliveringARecordRemovesItExactlyOnce() {
        final PendingRewardStore store = new PendingRewardStore(folder.resolve("p.yml"), null);
        store.load();
        final UUID player = UUID.randomUUID();
        final PendingReward record =
                store.add(player, Reward.amount(RewardType.COINS, "", 500, ""), "test");
        assertTrue(store.remove(player, record.id()));
        assertFalse(store.remove(player, record.id()));
        assertEquals(0, store.count(player));
        assertTrue(store.of(player).isEmpty());
    }

    @Test
    void anUnknownPlayerHasNothingWaiting() {
        final PendingRewardStore store = new PendingRewardStore(folder.resolve("p.yml"), null);
        store.load();
        assertEquals(0, store.count(UUID.randomUUID()));
        assertFalse(store.remove(UUID.randomUUID(), "nope"));
    }

    @Test
    void aCorruptFileNeverTakesTheServerDown() throws IOException {
        final Path file = folder.resolve("broken.yml");
        Files.writeString(file, "pending:\n  not-a-uuid:\n    - {id: a, type: coins, amount: 5}\n"
                + "  " + UUID.randomUUID() + ":\n    - {type: nope}\n", StandardCharsets.UTF_8);
        final PendingRewardStore store = new PendingRewardStore(file, null);
        store.load();
        assertEquals(0, store.count(UUID.randomUUID()));
    }

    @Test
    void recordsRoundTripThroughTheirMapForm() {
        final UUID player = UUID.randomUUID();
        final PendingReward record = PendingReward.of(player,
                Reward.amount(RewardType.KEY, "river", 2, ""), "achievement:test");
        final PendingReward parsed = PendingReward.fromMap(player, record.toMap());
        assertNotNull(parsed);
        assertEquals(record.id(), parsed.id());
        assertEquals(RewardType.KEY, parsed.type());
        assertEquals("river", parsed.subject());
        assertEquals(2, parsed.amount());
        assertEquals(record.reward().type(), parsed.reward().type());
        assertNull(PendingReward.fromMap(player, null));
    }
}
