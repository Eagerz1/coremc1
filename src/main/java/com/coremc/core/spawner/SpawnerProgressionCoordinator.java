package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/** Owns kill caps, unlock progress, and rewards from spawner-born kills. */
public final class SpawnerProgressionCoordinator {

    /** Reset all windows past this bound so player keys cannot grow forever. */
    private static final int KILL_WINDOW_SOFT_LIMIT = 512;

    private final CoreMCPlugin plugin;
    private final SpawnerService catalogue;
    /** uuid -> rolling kill window (anti-farming), bounded by KILL_WINDOW_SOFT_LIMIT. */
    private final Map<UUID, RollingKillCap> killWindows = new ConcurrentHashMap<>();

    public SpawnerProgressionCoordinator(final CoreMCPlugin plugin, final SpawnerService catalogue) {
        this.plugin = plugin;
        this.catalogue = catalogue;
    }

    public void clear() {
        killWindows.clear();
    }

    public long killsOf(final PlayerProfile profile, final SpawnerDefinition mob) {
        return profile.killCountOf(mob.killKey());
    }

    /** Kill-accounted unlock check for one purchasable tier. */
    public boolean isUnlocked(final PlayerProfile profile, final SpawnerService.TierRef ref) {
        return killsOf(profile, ref.mob()) >= ref.tier().requiredKills();
    }

    /** Both wild and spawner kills share one rolling per-minute cap. */
    public boolean acquireKillSlot(final UUID playerId) {
        final long cap = plugin.coreConfig().spawnerKillCapPerMinute();
        final RollingKillCap window = killWindows.computeIfAbsent(
                playerId, uuid -> new RollingKillCap(cap));
        final boolean allowed = window.tryCount(System.currentTimeMillis());
        if (killWindows.size() > KILL_WINDOW_SOFT_LIMIT) {
            killWindows.clear();
        }
        return allowed;
    }

    /** Records one wild kill and notifies at each newly crossed tier threshold. */
    public void recordKill(final Player player, final PlayerProfile profile, final EntityType type) {
        if (!acquireKillSlot(player.getUniqueId())) {
            return;
        }
        final String key = type.name().toLowerCase(Locale.ROOT);
        addProgress(player, profile, catalogue.all().stream()
                .filter(mob -> mob.entityType() == type).toList(), key, 1L);
    }

    /** Spawner-born kills pay configured rewards but never advance their source wild lane. */
    public void recordSpawnerKill(
            final Player killer, final PlayerProfile profile, final org.bukkit.entity.LivingEntity victim) {
        if (!acquireKillSlot(killer.getUniqueId())) {
            return;
        }
        profile.addStat("spawner-mobs-killed", 1L);
        plugin.playerData().markDirty(profile.uuid());

        final SpawnerTags tags = catalogue.tags();
        final Optional<String> tierId = tags.tierIdOf(victim);
        final boolean ancient = tags.isAncient(victim);
        final SpawnerService.TierRef ref = tierId.flatMap(catalogue::tierFor).orElse(null);

        // Team-island scope: spawner farms only pay where the killer belongs.
        final var at = victim.getLocation();
        final Optional<com.coremc.core.island.Island> island = at.getWorld() == null
                ? Optional.empty()
                : plugin.islands().islandAt(at.getWorld().getName(), at.getBlockX(), at.getBlockZ())
                        .filter(value -> value.roleOf(killer.getUniqueId()) != null);
        island.ifPresent(value -> plugin.islandProgress()
                .awardKillXp(value, killer, plugin.getConfig().getLong("spawners.kill-rewards.island-xp", 2L)));

        if (ancient && ref != null) {
            applyAncientProgress(killer, profile, ref.mob());
        }

        final double chance = Math.max(0.0, Math.min(1.0,
                plugin.getConfig().getDouble("spawners.kill-rewards.chance", 0.75)));
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        long money = Math.max(0L, plugin.getConfig().getLong("spawners.kill-rewards.money", 3L));
        long tokens = Math.max(0L, plugin.getConfig().getLong("spawners.kill-rewards.sky-tokens", 1L));
        if (ancient) {
            final long multiplier = Math.max(1L,
                    plugin.getConfig().getLong("spawners.ancient.reward-multiplier", 5L));
            money *= multiplier;
            tokens *= multiplier;
        }
        // Tier index beyond I adds a little scaling so better spawners are worth farming.
        final int scale = ref == null ? 1 : Math.max(1, ref.tier().index());
        money *= Math.min(scale, 4);
        tokens *= Math.min(scale, 4);
        long moneyPaid = 0L;
        long tokensPaid = 0L;
        if (money > 0L) {
            plugin.economy().deposit(profile, Currency.MONEY, money);
            moneyPaid = money;
        }
        if (tokens > 0L && plugin.economy().fitsDeposit(profile, Currency.SKY_TOKENS, tokens)) {
            plugin.economy().deposit(profile, Currency.SKY_TOKENS, tokens);
            tokensPaid = tokens;
        }
        if (moneyPaid > 0L || tokensPaid > 0L) {
            plugin.messages().sendPrefixed(killer, "spawner.kill-reward", Map.of(
                    "money", String.format(Locale.ROOT, "%,d", moneyPaid),
                    "tokens", String.format(Locale.ROOT, "%,d", tokensPaid)));
        }
    }

    /** Ancient progress is one event toward the next configured lane, regardless of bonus size. */
    private void applyAncientProgress(
            final Player player, final PlayerProfile profile, final SpawnerDefinition sourceLane) {
        final List<SpawnerDefinition> ordered = catalogue.all();
        final int index = ordered.indexOf(sourceLane);
        final int bonus = Math.max(1, plugin.getConfig().getInt("spawners.ancient.progress-bonus", 3));
        if (index < 0 || index + 1 >= ordered.size()) {
            plugin.messages().sendPrefixed(player, "spawner.ancient-bounty", Map.of(
                    "mob", sourceLane.colouredDisplay(),
                    "amount", String.valueOf(bonus)));
            return;
        }
        final SpawnerDefinition next = ordered.get(index + 1);
        addProgress(player, profile, List.of(next), next.killKey(), bonus);
        plugin.messages().sendPrefixed(player, "spawner.ancient-progress", Map.of(
                "amount", String.valueOf(bonus),
                "mob", next.colouredDisplay()));
    }

    private void addProgress(
            final Player player, final PlayerProfile profile, final List<SpawnerDefinition> affectedLanes,
            final String key, final long amount) {
        final long before = profile.killCountOf(key);
        profile.addKillCount(key, amount);
        plugin.playerData().markDirty(profile.uuid());
        if (player == null) {
            return;
        }
        final long after = before + amount;
        for (final SpawnerDefinition mob : affectedLanes) {
            for (final SpawnerTier tier : mob.tiers()) {
                if (before < tier.requiredKills() && after >= tier.requiredKills()) {
                    plugin.messages().sendPrefixed(
                            player, "spawner.unlocked", Map.of("name", tier.colouredDisplay()));
                }
            }
        }
    }

    /** Admin/console helper: grants progress to a known lane and persists immediately. */
    public boolean adminGrantKills(final PlayerProfile profile, final String mobKey, final long amount) {
        final SpawnerDefinition lane = catalogue.definition(mobKey.toLowerCase(Locale.ROOT)).orElse(null);
        if (lane == null || amount < 0L) {
            return false;
        }
        final Player target = plugin.getServer().getPlayer(profile.uuid());
        addProgress(target, profile, List.of(lane), lane.killKey(), amount);
        plugin.playerData().persistImportant(profile);
        return true;
    }
}
