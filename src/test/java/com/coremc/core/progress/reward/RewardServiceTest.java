package com.coremc.core.progress.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.progress.adapter.ExternalSystems;
import com.coremc.core.progress.adapter.RewardAdapter;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Delivery policy: currencies whose system is on another branch are
 * parked, never dropped, and permanent unlocks are owned by the
 * profiles rather than handed out here.
 */
class RewardServiceTest {

    @TempDir
    Path folder;

    private RewardService service(final ExternalSystems externals) {
        final PendingRewardStore store = new PendingRewardStore(folder.resolve("p.yml"), null);
        store.load();
        return new RewardService(null, externals, store, null);
    }

    @Test
    void currenciesFromAbsentSystemsArePendingNotLost() {
        final RewardService rewards = service(new ExternalSystems());
        final UUID player = UUID.randomUUID();
        assertEquals(RewardGrant.PENDING, rewards.deliver(player,
                Reward.amount(RewardType.SKY_TOKENS, "", 10, ""), "collection:test"));
        assertEquals(RewardGrant.PENDING, rewards.deliver(player,
                Reward.amount(RewardType.CREDITS, "", 5, ""), "collection:test"));
        assertEquals(RewardGrant.PENDING, rewards.deliver(player,
                Reward.amount(RewardType.KEY, "river", 1, ""), "collection:test"));
        assertEquals(RewardGrant.PENDING, rewards.deliver(player,
                Reward.amount(RewardType.JOURNEY_XP, "", 100, ""), "collection:test"));
        assertEquals(4, rewards.pending().count(player));
    }

    @Test
    void anAvailableAdapterDeliversStraightAway() {
        final ExternalSystems externals = new ExternalSystems();
        externals.rewards(new RewardAdapter() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public boolean grantCredits(final UUID player, final long amount) {
                return true;
            }

            @Override
            public boolean grantSkyTokens(final UUID player, final long amount) {
                return true;
            }

            @Override
            public boolean grantKey(final UUID player, final String keyId, final int amount) {
                return true;
            }
        });
        final RewardService rewards = service(externals);
        final UUID player = UUID.randomUUID();
        assertEquals(RewardGrant.GRANTED, rewards.deliver(player,
                Reward.amount(RewardType.SKY_TOKENS, "", 10, ""), "collection:test"));
        assertEquals(0, rewards.pending().count(player));
    }

    @Test
    void permanentUnlocksAreOwnedByTheProfilesNotTheRewardService() {
        final RewardService rewards = service(new ExternalSystems());
        final UUID player = UUID.randomUUID();
        assertEquals(RewardGrant.UNLOCKED, rewards.deliver(player,
                Reward.unlock(RewardType.TITLE, "angler", "Angler"), "collection:cod"));
        assertEquals(0, rewards.pending().count(player));
    }

    @Test
    void coinsWithoutAnEconomyAreParkedRatherThanLost() {
        final RewardService rewards = service(new ExternalSystems());
        final UUID player = UUID.randomUUID();
        assertEquals(RewardGrant.PENDING, rewards.deliver(player,
                Reward.amount(RewardType.COINS, "", 2_500, ""), "collection:test"));
        assertEquals(1, rewards.pending().count(player));
        assertEquals(RewardType.COINS, rewards.pending().of(player).get(0).type());
    }

    @Test
    void nullInputsFailLoudlyButSafely() {
        final RewardService rewards = service(new ExternalSystems());
        assertEquals(RewardGrant.FAILED, rewards.deliver(null,
                Reward.amount(RewardType.COINS, "", 1, ""), "x"));
        assertEquals(RewardGrant.FAILED, rewards.deliver(UUID.randomUUID(), null, "x"));
        assertEquals(0, rewards.deliverPending(null));
        assertTrue(RewardGrant.GRANTED.successful());
        assertTrue(RewardGrant.PENDING.successful());
        assertTrue(RewardGrant.UNLOCKED.successful());
        assertTrue(!RewardGrant.FAILED.successful() && !RewardGrant.ALREADY.successful());
    }
}
