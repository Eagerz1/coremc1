package com.coremc.core.market;

import com.coremc.core.credits.BalanceStore;
import com.coremc.core.credits.CreditService;
import com.coremc.core.credits.SkyTokenService;
import com.coremc.core.reward.RewardDef;
import com.coremc.core.reward.RewardType;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.shop.EconomyStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Shared in-memory fixtures for the Black Market unit tests. */
final class MarketTestSupport {

    static final Logger LOGGER = Logger.getLogger("market-test");
    static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID RIVAL = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private MarketTestSupport() {
    }

    /** A mutable wall clock for schedule tests. */
    static final class Clock {
        long now;

        Clock(final long start) {
            this.now = start;
        }
    }

    static EconomyService economy(final double starting) {
        try {
            return new EconomyService(new MemoryEconomyStore(), starting, LOGGER);
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static SkyTokenService tokens() {
        try {
            return new SkyTokenService(new MemoryBalanceStore(), LOGGER);
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static CreditService credits() {
        try {
            return new CreditService(new MemoryBalanceStore(), LOGGER);
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static RewardDef itemReward(final String id) {
        return new RewardDef(id, RewardType.ITEM, "&6" + id, "epic", 1, 1, 1,
                org.bukkit.Material.LODESTONE, null);
    }

    static MarketOffer offer(final String id, final MarketRarity pool, final long money,
                             final int stock, final int perPlayer) {
        return new MarketOffer(id, itemReward(id), List.of("test offer"), pool,
                CostBand.fixed(money, 0, 0), stock, perPlayer, List.of(), false);
    }

    static AuctionLotDef lot(final String id, final long startingBid, final long increment) {
        return new AuctionLotDef(id, itemReward(id), List.of("test lot"),
                MarketRarity.EPIC, startingBid, increment);
    }

    private static final class MemoryEconomyStore implements EconomyStore {
        private final Map<UUID, Double> data = new HashMap<>();

        @Override
        public Map<UUID, Double> loadAll() {
            return new HashMap<>(data);
        }

        @Override
        public void saveAll(final Map<UUID, Double> balances) {
            data.clear();
            data.putAll(balances);
        }
    }

    private static final class MemoryBalanceStore implements BalanceStore {
        private final Map<UUID, Long> data = new HashMap<>();

        @Override
        public Map<UUID, Long> loadAll() {
            return new HashMap<>(data);
        }

        @Override
        public void saveAll(final Map<UUID, Long> balances) {
            data.clear();
            data.putAll(balances);
        }
    }
}
