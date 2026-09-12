package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.role.RoleCategory;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.entity.Player;

/**
 * The BUFF stage of the effect pipeline (BASE → UPGRADE → BUFF →
 * ROLE → ENCHANT → TEMP).
 *
 * Every query is scope-checked: buffs apply to island members while
 * they stand on their own island, otherwise the multiplier is exactly
 * 1.0. Callers apply the returned multiplier ONCE in their funnel —
 * currency in {@code EnchantEngine#grantCurrency}, role XP in
 * {@code RoleService#awardCategoryXp}, island grants at their own
 * sites — so no buff can ever double-apply.
 */
public final class IslandBuffService {

    private final CoreMCPlugin plugin;

    public IslandBuffService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ pure math

    /** Multiplier for a purchased tier: 1 + tier × pct/100 (tier 0 = 1.0). Pure. */
    public static double multForTier(final int tier, final int pctPerLevel) {
        if (tier <= 0 || pctPerLevel <= 0) {
            return 1.0;
        }
        return 1.0 + tier * pctPerLevel / 100.0;
    }

    /**
     * Delay multiplier for a speed buff: 1 / (1 + tier × pct/100).
     * Smooth, never negative, tier 0 = 1.0. Pure.
     */
    public static double delayForTier(final int tier, final int pctPerLevel) {
        if (tier <= 0 || pctPerLevel <= 0) {
            return 1.0;
        }
        return 1.0 / (1.0 + tier * pctPerLevel / 100.0);
    }

    /**
     * Scales an integer grant by a multiplier with probabilistic
     * rounding (a ×1.5 on 1 yields 1 or 2 fairly). Pure apart from the
     * roll. Never returns less than 1 for a positive base.
     */
    public static int scaleCount(final int base, final double mult) {
        if (base <= 0) {
            return 0;
        }
        if (mult <= 1.0) {
            return base;
        }
        final double scaled = base * mult;
        final int whole = (int) Math.floor(scaled);
        final double frac = scaled - whole;
        final int result = whole + (ThreadLocalRandom.current().nextDouble() < frac ? 1 : 0);
        return Math.max(1, result);
    }

    /** Buff-vs-upgrade pipeline order guard: buff ids never collide with upgrade ids. */
    public static boolean isBuffId(final String id) {
        return BuffCatalog.byId(id) != null;
    }

    // ------------------------------------------------------------------ scoped queries

    /** The member's own island when they stand inside it, else empty. */
    private java.util.Optional<Island> scopedIsland(final Player player) {
        final org.bukkit.Location at = player.getLocation();
        if (at.getWorld() == null) {
            return java.util.Optional.empty();
        }
        return plugin.islands().islandAt(at.getWorld().getName(), at.getBlockX(), at.getBlockZ())
                .filter(island -> island.roleOf(player.getUniqueId()) != null);
    }

    /** Raw multiplier of a buff on an island (no scope check; 1.0 when unowned). */
    public double islandMult(final Island island, final String buffId) {
        final int tier = island.buffs().getOrDefault(buffId, 0);
        if (tier <= 0) {
            return 1.0;
        }
        return multForTier(tier, plugin.coreConfig().buffPercent(buffId));
    }

    /** Scoped multiplier of a buff for a player (1.0 outside their island). */
    public double mult(final Player player, final String buffId) {
        return scopedIsland(player).map(island -> islandMult(island, buffId)).orElse(1.0);
    }

    /** Activity-yield multiplier (mining/farming/fishing/slaying/logging boost). */
    public double activityMult(final Player player, final RoleCategory category) {
        if (category == null) {
            return 1.0;
        }
        final String buff = switch (category) {
            case MINING -> "mining-boost";
            case FARMING -> "farming-boost";
            case FISHING -> "fishing-boost";
            case SLAYING -> "slaying-boost";
            case LOGGING -> "logging-boost";
        };
        return mult(player, buff);
    }

    /** Currency-gain multiplier (token/credit boosts; money has no buff). */
    public double currencyMult(final Player player, final Currency currency) {
        return switch (currency) {
            case SKY_TOKENS -> mult(player, "token-boost");
            case CREDITS -> mult(player, "credit-boost");
            case MONEY -> 1.0;
        };
    }

    /** Role-XP multiplier (xp-boost). */
    public double xpMult(final Player player) {
        return mult(player, "xp-boost");
    }

    /** Shop-sell multiplier (sell-boost). */
    public double sellMult(final Player player) {
        return mult(player, "sell-boost");
    }

    /** Generator-harvest multiplier (generator-boost). */
    public double generatorMult(final Player player) {
        return mult(player, "generator-boost");
    }

    /** Rare/treasure chance multiplier (island-luck). */
    public double luckMult(final Player player) {
        return mult(player, "island-luck");
    }

    /** Spawner delay multiplier for a player (spawner-boost, ≤ 1.0). */
    public double spawnerDelayMult(final Player player) {
        return scopedIsland(player).map(this::islandSpawnerDelayMult).orElse(1.0);
    }

    /** Spawner delay multiplier for an island (spawner-boost, ≤ 1.0). */
    public double islandSpawnerDelayMult(final Island island) {
        final int tier = island.buffs().getOrDefault("spawner-boost", 0);
        return delayForTier(tier, plugin.coreConfig().buffPercent("spawner-boost"));
    }
}
