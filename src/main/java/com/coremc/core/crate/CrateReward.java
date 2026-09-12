package com.coremc.core.crate;

/**
 * One weighted reward in a crate pool. A flat record on purpose: every
 * reward shape (currency / key / spawner / generator / item) shares one
 * parse path, one preview renderer and one grant path, with unused
 * fields defaulted. All balance lives in {@code crates.yml}.
 */
public record CrateReward(
        /** CURRENCY, KEY, SPAWNER, GENERATOR or ITEM. */
        RewardType type,
        /** Relative weight inside the pool (chance = weight / pool total). */
        int weight,
        /** common / uncommon / rare / epic / legendary (display + pity reset). */
        String rarity,
        /** CURRENCY: MONEY, CREDITS or SKY_TOKENS. */
        String currency,
        /** CURRENCY: rolled amount is uniform in [min, max]. */
        long min,
        /** CURRENCY: rolled amount is uniform in [min, max]. */
        long max,
        /** KEY: key id (sky/ember/...). */
        String key,
        /** KEY / ITEM: stack size granted. */
        int amount,
        /** SPAWNER: purchasable tier id (zombie-2). GENERATOR: gen id (quartz). */
        String refId,
        /** ITEM: Bukkit material name. */
        String material,
        /** Pre-rendered coloured label for previews and open messages. */
        String label) {

    /** Reward shapes a crate pool can pay out. */
    public enum RewardType {
        CURRENCY,
        KEY,
        SPAWNER,
        GENERATOR,
        ITEM
    }

    /** Colour code for a rarity (common/uncommon/rare/epic/legendary). */
    public static String rarityColor(final String rarity) {
        return switch (rarity.toLowerCase(java.util.Locale.ROOT)) {
            case "uncommon" -> "&a";
            case "rare" -> "&b";
            case "epic" -> "&d";
            case "legendary" -> "&6";
            default -> "&7";
        };
    }

    /** Jackpot rarities reset the pity counter when rolled naturally. */
    public static boolean isJackpot(final String rarity) {
        final String lower = rarity.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("legendary");
    }
}
