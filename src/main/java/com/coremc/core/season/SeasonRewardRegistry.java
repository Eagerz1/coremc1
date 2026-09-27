package com.coremc.core.season;

import com.coremc.core.island.Island;
import com.coremc.core.progression.IslandProgressionService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Configurable Season reward adapter registry with safe pending fallback. */
public final class SeasonRewardRegistry {

    private final Map<String, SeasonRewardIntegration> integrations = new LinkedHashMap<>();
    private final SeasonRewardIntegration fallback = (player, island, reward) -> SeasonRewardIntegration.Result.PENDING;

    public SeasonRewardRegistry register(final String type, final SeasonRewardIntegration integration) {
        if (type != null && integration != null) {
            integrations.put(SeasonConfig.normalise(type), integration);
        }
        return this;
    }

    public SeasonRewardIntegration integration(final String type) {
        return integrations.getOrDefault(SeasonConfig.normalise(type), fallback);
    }

    public SeasonRewardIntegration.Result deliver(final Player player, final Island island,
                                                  final SeasonConfig.Reward reward) {
        return integration(reward.type()).deliver(player, island, reward);
    }

    public static SeasonRewardRegistry defaults(final IslandProgressionService progression) {
        final SeasonRewardRegistry registry = new SeasonRewardRegistry();
        registry.register("sky-tokens", (player, island, reward) -> {
            if (progression == null || island == null) {
                return SeasonRewardIntegration.Result.PENDING;
            }
            progression.grantSkyTokens(island, reward.amount());
            return SeasonRewardIntegration.Result.DELIVERED;
        });
        registry.register("island-xp", (player, island, reward) -> {
            if (progression == null || island == null) {
                return SeasonRewardIntegration.Result.PENDING;
            }
            progression.awardMilestoneXp(player, island, reward.amount());
            return SeasonRewardIntegration.Result.DELIVERED;
        });
        registry.register("item", SeasonRewardRegistry::deliverItem);
        return registry;
    }

    private static SeasonRewardIntegration.Result deliverItem(final Player player, final Island island,
                                                              final SeasonConfig.Reward reward) {
        if (player == null || !player.isOnline() || reward.item().isBlank()) {
            return SeasonRewardIntegration.Result.PENDING;
        }
        final Material material;
        try {
            material = Material.valueOf(reward.item().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            return SeasonRewardIntegration.Result.PENDING;
        }
        long remaining = reward.amount();
        while (remaining > 0L) {
            final int size = (int) Math.min(64L, remaining);
            final Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(material, size));
            if (!leftovers.isEmpty()) {
                return SeasonRewardIntegration.Result.PENDING;
            }
            remaining -= size;
        }
        return SeasonRewardIntegration.Result.DELIVERED;
    }
}
