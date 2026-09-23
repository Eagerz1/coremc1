package com.coremc.core.island;

import com.coremc.core.config.MessageService;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.shop.Money;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Buying island upgrades and buffs with coins. Upgrades are permanent
 * per island (stored in the island file); buffs are timed (see
 * {@link IslandBuffService}). Owner-only, like every island decision.
 */
public final class IslandUpgradeService {

    private final IslandUpgradeConfig config;
    private final IslandService islands;
    private final IslandBuffService buffs;
    private final EconomyService economy;
    private final MessageService messages;
    /** Island top: every purchased upgrade earns the island +500 points. */
    private final IslandPointsService points;

    public IslandUpgradeService(final IslandUpgradeConfig config, final IslandService islands,
                                final IslandBuffService buffs, final EconomyService economy,
                                final MessageService messages, final IslandPointsService points) {
        this.config = config;
        this.islands = islands;
        this.buffs = buffs;
        this.economy = economy;
        this.messages = messages;
        this.points = points;
    }

    /** Buys the next claim-size level for the player's island. */
    public void buyClaimSize(final Player player) {
        final Island island = ownIslandOrComplain(player);
        if (island == null || !available(player)) {
            return;
        }
        final IslandUpgradeConfig.ClaimSizeDef def = config.claimSize();
        final int level = island.upgradeLevel("claim-size");
        if (level >= def.maxLevel()) {
            messages.sendPrefixed(player, "island.upgrade.claim-maxed",
                    Map.of("size", String.valueOf(island.borderSize())));
            return;
        }
        final double price = config.claimSizePrice(level + 1);
        if (!pay(player, price)) {
            return;
        }
        island.setUpgradeLevel("claim-size", level + 1);
        islands.resizeClaim(island, island.borderSize() + def.growthPerLevel());
        points.add(island, IslandPointsService.UPGRADE_POINTS);
        messages.sendPrefixed(player, "island.upgrade.claim-bought", Map.of(
                "size", String.valueOf(island.borderSize()),
                "cost", Money.format(price, "$")));
    }

    /** Buys the next member-slots level for the player's island. */
    public void buyMemberSlots(final Player player) {
        final Island island = ownIslandOrComplain(player);
        if (island == null || !available(player)) {
            return;
        }
        final IslandUpgradeConfig.MemberSlotsDef def = config.memberSlots();
        final int level = island.upgradeLevel("member-slots");
        if (level >= def.maxLevel()) {
            messages.sendPrefixed(player, "island.upgrade.slots-maxed",
                    Map.of("slots", String.valueOf(config.memberLimit(level))));
            return;
        }
        final double price = config.memberSlotsPrice(level + 1);
        if (!pay(player, price)) {
            return;
        }
        island.setUpgradeLevel("member-slots", level + 1);
        islands.save(island);
        points.add(island, IslandPointsService.UPGRADE_POINTS);
        messages.sendPrefixed(player, "island.upgrade.slots-bought", Map.of(
                "slots", String.valueOf(config.memberLimit(level + 1)),
                "cost", Money.format(price, "$")));
    }

    /** Starts (or restarts) a timed buff for the player's island. */
    public void buyBuff(final Player player, final String buffId) {
        final Island island = ownIslandOrComplain(player);
        if (island == null || !available(player)) {
            return;
        }
        final IslandUpgradeConfig.BuffDef def = config.buff(buffId);
        if (def == null) {
            return;
        }
        if (!pay(player, def.price())) {
            return;
        }
        buffs.activate(island, def);
        points.add(island, IslandPointsService.UPGRADE_POINTS);
        messages.sendPrefixed(player, "island.buff.bought", Map.of(
                "buff", def.name(),
                "minutes", String.valueOf(def.durationMinutes())));
    }

    // ------------------------------------------------------------------
    // guards
    // ------------------------------------------------------------------

    private Island ownIslandOrComplain(final Player player) {
        final Island island = islands.islandOf(player.getUniqueId());
        if (island == null) {
            messages.sendPrefixed(player, "island.no-island");
            return null;
        }
        if (!island.isOwner(player.getUniqueId())) {
            messages.sendPrefixed(player, "island.not-owner");
            return null;
        }
        return island;
    }

    private boolean available(final Player player) {
        if (!config.enabled()) {
            messages.sendPrefixed(player, "island.upgrade.unavailable");
            return false;
        }
        return true;
    }

    private boolean pay(final Player player, final double price) {
        if (!economy.has(player.getUniqueId(), price)) {
            messages.sendPrefixed(player, "island.upgrade.cannot-afford", Map.of(
                    "cost", Money.format(price, "$"),
                    "balance", Money.format(economy.balance(player.getUniqueId()), "$")));
            return false;
        }
        economy.withdraw(player.getUniqueId(), price);
        return true;
    }
}
