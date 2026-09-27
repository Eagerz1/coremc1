package com.coremc.core.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Picks one rotation's offers from the configured pools:
 * a configurable count of Commons and Rares, an Epic, a small
 * Legendary chance and optional Cosmetic / Seasonal slots. Prefers
 * offers that were NOT in the previous rotation (falling back to
 * repeats only when a pool is too small), and resolves each offer's
 * concrete price inside its band. Pure: inject the {@link Random}.
 */
public final class RotationSelector {

    /** How many of each pool one rotation shows. */
    public record Shape(int commonMin, int commonMax, int rare, int epic,
                        double legendaryChance, double cosmeticChance, double seasonalChance) {

        public Shape {
            if (commonMin < 0 || commonMax < commonMin || rare < 0 || epic < 0) {
                throw new IllegalArgumentException("negative selection counts");
            }
            if (bad(legendaryChance) || bad(cosmeticChance) || bad(seasonalChance)) {
                throw new IllegalArgumentException("chances must be 0..1");
            }
        }

        private static boolean bad(final double chance) {
            return !(chance >= 0.0 && chance <= 1.0);
        }
    }

    /** One selected offer with its rotation price. */
    public record Selected(MarketOffer offer, MarketCost price) {
    }

    private RotationSelector() {
    }

    /**
     * Selects a rotation. {@code previousOfferIds} are avoided where
     * the pool allows it — never a hard error when it cannot.
     */
    public static List<Selected> select(final List<MarketOffer> offers, final Shape shape,
                                        final Set<String> previousOfferIds, final Random random) {
        final Map<MarketRarity, List<MarketOffer>> pools = new LinkedHashMap<>();
        for (final MarketOffer offer : offers) {
            pools.computeIfAbsent(offer.pool(), key -> new ArrayList<>()).add(offer);
        }
        final List<Selected> picked = new ArrayList<>();
        final int commons = shape.commonMin()
                + (shape.commonMax() > shape.commonMin()
                        ? random.nextInt(shape.commonMax() - shape.commonMin() + 1) : 0);
        pick(pools.get(MarketRarity.COMMON), commons, previousOfferIds, random, picked);
        pick(pools.get(MarketRarity.RARE), shape.rare(), previousOfferIds, random, picked);
        pick(pools.get(MarketRarity.EPIC), shape.epic(), previousOfferIds, random, picked);
        if (random.nextDouble() < shape.legendaryChance()) {
            pick(pools.get(MarketRarity.LEGENDARY), 1, previousOfferIds, random, picked);
        }
        if (random.nextDouble() < shape.cosmeticChance()) {
            pick(pools.get(MarketRarity.COSMETIC), 1, previousOfferIds, random, picked);
        }
        if (random.nextDouble() < shape.seasonalChance()) {
            pick(pools.get(MarketRarity.SEASONAL), 1, previousOfferIds, random, picked);
        }
        return picked;
    }

    private static void pick(final List<MarketOffer> pool, final int count,
                             final Set<String> previous, final Random random,
                             final List<Selected> out) {
        if (pool == null || pool.isEmpty() || count <= 0) {
            return;
        }
        // fresh offers first (not shown last rotation), then repeats —
        // both shuffled, so small pools still rotate fairly
        final List<MarketOffer> fresh = new ArrayList<>();
        final List<MarketOffer> repeats = new ArrayList<>();
        for (final MarketOffer offer : pool) {
            (previous.contains(offer.id()) ? repeats : fresh).add(offer);
        }
        Collections.shuffle(fresh, random);
        Collections.shuffle(repeats, random);
        final List<MarketOffer> ordered = new ArrayList<>(fresh);
        ordered.addAll(repeats);
        for (int index = 0; index < Math.min(count, ordered.size()); index++) {
            final MarketOffer offer = ordered.get(index);
            out.add(new Selected(offer, offer.cost().resolve(random)));
        }
    }
}
