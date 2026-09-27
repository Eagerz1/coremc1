package com.coremc.core.quest;

import com.coremc.core.island.Island;
import com.coremc.core.progression.IslandProgressionService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Central registry for systems and reward providers that may live on later
 * Arena branches. Missing integrations are explicit no-ops that leave claims
 * pending rather than silently deleting rewards.
 */
public final class QuestIntegrationRegistry {

    private final Set<String> systems = new LinkedHashSet<>();
    private final Map<String, QuestRewardIntegration> rewards = new LinkedHashMap<>();

    public QuestIntegrationRegistry system(final String system, final boolean available) {
        if (available) {
            systems.add(QuestConfig.normalise(system));
        }
        return this;
    }

    public QuestIntegrationRegistry reward(final QuestRewardIntegration integration) {
        rewards.put(QuestConfig.normalise(integration.type()), integration);
        return this;
    }

    public boolean systemAvailable(final String system) {
        return systems.contains(QuestConfig.normalise(system));
    }

    public Set<String> systems() {
        return Set.copyOf(systems);
    }

    public QuestRewardIntegration reward(final String type) {
        return rewards.getOrDefault(QuestConfig.normalise(type),
                QuestRewardIntegration.unavailable(type));
    }

    public static QuestIntegrationRegistry defaults(final IslandProgressionService progression) {
        final QuestIntegrationRegistry registry = new QuestIntegrationRegistry();
        registry.system("islands", true)
                .system("progression", progression != null)
                .system("farming", true)
                .system("fishing", progression != null)
                .system("slayer", true)
                .system("spawners", true)
                .system("events", true)
                .system("core-buffs", progression != null)
                .reward(skyTokens(progression))
                .reward(islandXp(progression))
                .reward(vanillaItem())
                .reward(QuestRewardIntegration.unavailable("credits"))
                .reward(QuestRewardIntegration.unavailable("keys"))
                .reward(QuestRewardIntegration.unavailable("key"))
                .reward(QuestRewardIntegration.unavailable("core-fragments"))
                .reward(QuestRewardIntegration.unavailable("core-fragment"))
                .reward(QuestRewardIntegration.unavailable("generator-material"))
                .reward(QuestRewardIntegration.unavailable("companion-material"))
                .reward(QuestRewardIntegration.unavailable("omnitool-material"))
                .reward(QuestRewardIntegration.unavailable("seasonal-xp"));
        return registry;
    }

    public static QuestRewardIntegration skyTokens(final IslandProgressionService progression) {
        return new QuestRewardIntegration() {
            @Override
            public String type() {
                return "sky-tokens";
            }

            @Override
            public boolean available() {
                return progression != null;
            }

            @Override
            public Result deliver(final Player player, final Island island,
                                  final QuestConfig.RewardDef reward) {
                if (progression == null || island == null) {
                    return Result.UNAVAILABLE;
                }
                progression.grantSkyTokens(island, reward.amount());
                return Result.DELIVERED;
            }
        };
    }

    public static QuestRewardIntegration islandXp(final IslandProgressionService progression) {
        return new QuestRewardIntegration() {
            @Override
            public String type() {
                return "island-xp";
            }

            @Override
            public boolean available() {
                return progression != null;
            }

            @Override
            public Result deliver(final Player player, final Island island,
                                  final QuestConfig.RewardDef reward) {
                if (progression == null || island == null) {
                    return Result.UNAVAILABLE;
                }
                progression.awardMilestoneXp(player, island, reward.amount());
                return Result.DELIVERED;
            }
        };
    }

    public static QuestRewardIntegration vanillaItem() {
        return new QuestRewardIntegration() {
            @Override
            public String type() {
                return "item";
            }

            @Override
            public Result deliver(final Player player, final Island island,
                                  final QuestConfig.RewardDef reward) {
                if (player == null || !player.isOnline()) {
                    return Result.UNAVAILABLE;
                }
                final Material material;
                try {
                    material = Material.valueOf(reward.item().toUpperCase(java.util.Locale.ROOT));
                } catch (final IllegalArgumentException exception) {
                    return Result.FAILED;
                }
                final ItemStack stack = new ItemStack(material, Math.toIntExact(Math.min(64L, reward.amount())));
                final Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
                return leftovers.isEmpty() ? Result.DELIVERED : Result.UNAVAILABLE;
            }
        };
    }
}
