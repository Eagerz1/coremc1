package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.enchant.EnchantBlocks;
import com.coremc.core.role.RoleCategory;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

/** Owns island action admission, lifetime stats, level XP, and XP boosts. */
public final class IslandProgressCoordinator {

    private final CoreMCPlugin plugin;

    public IslandProgressCoordinator(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    public void onBlockBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        if (plugin.placeables().at(block.getLocation()).isPresent()) {
            return; // breaking cores is not mining
        }
        final Player player = event.getPlayer();
        final var island = plugin.islands()
                .islandAt(block.getWorld().getName(), block.getX(), block.getZ())
                .filter(value -> value.roleOf(player.getUniqueId()) != null);
        if (island.isEmpty()) {
            return;
        }
        final Material type = block.getType();
        if (EnchantBlocks.isLog(type)) {
            record(island.get(), "logs-chopped", player);
        } else if (EnchantBlocks.isFarmBlock(type) && EnchantBlocks.isMatureHarvest(block)) {
            record(island.get(), "crops-harvested", player);
        } else if (!EnchantBlocks.isWoodLike(type) && !EnchantBlocks.isFarmBlock(type)) {
            record(island.get(), "blocks-mined", player);
        }
    }

    public void onFish(final PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        final Location hook = event.getHook().getLocation();
        if (hook.getWorld() == null) {
            return;
        }
        plugin.islands()
                .islandAt(hook.getWorld().getName(), hook.getBlockX(), hook.getBlockZ())
                .filter(value -> value.roleOf(event.getPlayer().getUniqueId()) != null)
                .ifPresent(island -> record(island, "fish-caught", event.getPlayer()));
    }

    public void onDeath(final EntityDeathEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || victim instanceof Player) {
            return;
        }
        final Player killer = victim.getKiller();
        if (killer == null) {
            return;
        }
        final Location at = victim.getLocation();
        if (at.getWorld() == null) {
            return;
        }
        plugin.islands()
                .islandAt(at.getWorld().getName(), at.getBlockX(), at.getBlockZ())
                .filter(value -> value.roleOf(killer.getUniqueId()) != null)
                .ifPresent(island -> record(island, "mobs-killed", killer));
    }

    /** Generator harvests feed island XP. */
    public void recordGeneratorHarvest(final Island island, final Player actor) {
        record(island, "generator-harvests", actor);
    }

    /** Bonus island XP from CoreMC spawner kills, in addition to ordinary mob stats. */
    public void awardKillXp(final Island island, final Player actor, final long bonusXp) {
        if (bonusXp <= 0L) {
            return;
        }
        island.addXp(bonusXp);
        plugin.islands().markDirty(island);
        final int next = levelFor(island);
        if (next > island.level()) {
            island.level(next);
            plugin.messages().sendPrefixed(
                    actor, "island.level-up", Map.of("level", String.valueOf(next)));
        }
    }

    private void record(final Island island, final String stat, final Player actor) {
        final long weight = Math.max(0L, plugin.getConfig().getLong("island.level.weights." + stat, 1L));
        island.addStat(stat, 1L);
        island.addXp(weight);
        plugin.islands().markDirty(island);
        final int next = levelFor(island);
        if (next > island.level()) {
            island.level(next);
            plugin.messages().sendPrefixed(
                    actor, "island.level-up", Map.of("level", String.valueOf(next)));
        }
    }

    /** Current computed level for an island (stored XP + purchased tiers). */
    public int levelFor(final Island island) {
        final long tiers = island.upgrades().values().stream().mapToLong(Integer::longValue).sum();
        final long perTier = Math.max(0L, plugin.getConfig().getLong("island.level.xp-per-upgrade-tier", 10L));
        final long divisor = plugin.getConfig().getLong("island.level.xp-divisor", 100L);
        return scoreToLevel(island.xp() + tiers * perTier, divisor);
    }

    /** level = 1 + floor(sqrt(score / divisor)); non-positive score/divisor → 1. Pure. */
    public static int scoreToLevel(final long score, final long divisor) {
        if (score <= 0L || divisor <= 0L) {
            return 1;
        }
        return 1 + (int) Math.floor(Math.sqrt(score / (double) divisor));
    }

    /** Island XP multiplier for a role-category action: 1.0 in the wild or without the track. */
    public double xpMultiplier(final Player player, final RoleCategory category) {
        final String track = switch (category) {
            case MINING -> "mining-xp";
            case LOGGING -> "logging-xp";
            case FISHING -> "fishing-xp";
            case FARMING -> "farming-xp";
            case SLAYING -> "slayer-xp";
        };
        final Location location = player.getLocation();
        if (location.getWorld() == null) {
            return 1.0;
        }
        final int tier = plugin.islands()
                .islandAt(location.getWorld().getName(), location.getBlockX(), location.getBlockZ())
                .filter(island -> island.roleOf(player.getUniqueId()) != null)
                .map(island -> island.upgrades().getOrDefault(track, 0))
                .orElse(0);
        if (tier <= 0) {
            return 1.0;
        }
        final int pct = Math.max(0, plugin.getConfig()
                .getInt("island.upgrades." + track + ".xp-percent-per-level", 10));
        return 1.0 + tier * pct / 100.0;
    }
}
