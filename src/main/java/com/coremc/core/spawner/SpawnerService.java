package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Spawner catalogue: per-mob kill progression unlocks progressively
 * better spawner tiers, bought with Sky Tokens.
 *
 * Kill progress lives in the player profile (schema v4+) so it survives
 * restarts and role switches. All thresholds, prices and spawner
 * throughput values come from {@code config.yml spawners:} — never
 * hard-coded. A rolling per-minute cap (anti-farming) is enforced
 * before kills are counted.
 */
public final class SpawnerService {

    private final CoreMCPlugin plugin;
    /** mobId -> lane. */
    private final Map<String, SpawnerDefinition> lanes = new LinkedHashMap<>();
    /** purchasable id ("zombie-2") -> (mob, tier). Includes legacy aliases for old placements. */
    private final Map<String, TierRef> purchasables = new LinkedHashMap<>();
    /** Shared entity PDC tags (spawner-born / spawner-id / spawner-ancient). */
    private final SpawnerTags tags;

    private final SpawnerWorldRuntime worldRuntime;
    private final SpawnerProgressionCoordinator progression;
    private final SpawnerLifecycleCoordinator lifecycle;

    /** Resolved mob + tier for one purchasable spawner id. */
    public record TierRef(SpawnerDefinition mob, SpawnerTier tier) {
    }

    public SpawnerService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.tags = new SpawnerTags(plugin);
        this.worldRuntime = new SpawnerWorldRuntime(plugin, this);
        this.progression = new SpawnerProgressionCoordinator(plugin, this);
        this.lifecycle = new SpawnerLifecycleCoordinator(plugin, this);
    }

    public SpawnerLifecycleCoordinator lifecycle() {
        return lifecycle;
    }

    /** Shared entity tags (mob tagger, island boost extras, kill routing). */
    public SpawnerTags tags() {
        return tags;
    }

    /** (Re)loads {@code spawners:} from config. Returns loaded mob lane count. */
    public int load() {
        lanes.clear();
        purchasables.clear();
        progression.clear();
        final ConfigurationSection section = plugin.getConfig().getConfigurationSection("spawners");
        if (section == null) {
            return 0;
        }
        for (final String id : section.getKeys(false)) {
            final ConfigurationSection def = section.getConfigurationSection(id);
            if (def == null) {
                continue;
            }
            // Non-mob tables live under spawners: too (kill-rewards, ancient).
            if (!def.contains("entity")) {
                continue;
            }
            final org.bukkit.entity.EntityType entity;
            try {
                entity = org.bukkit.entity.EntityType.valueOf(
                        def.getString("entity", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Spawner mob '" + id + "' has unknown entity; skipped.");
                continue;
            }
            final Material icon = Material.matchMaterial(def.getString("icon", "SPAWNER"));
            final String mobDisplay = def.getString("display", "&f" + id);
            final List<SpawnerTier> tiers = parseTiers(id, mobDisplay, def);
            if (tiers.isEmpty()) {
                plugin.getLogger().warning("Spawner mob '" + id + "' has no usable tiers; skipped.");
                continue;
            }
            final SpawnerDefinition mob = new SpawnerDefinition(
                    id, mobDisplay, entity,
                    icon == null ? Material.SPAWNER : icon, tiers);
            lanes.put(id, mob);
            for (final SpawnerTier tier : tiers) {
                purchasables.put(tier.tierId(), new TierRef(mob, tier));
            }
            // Legacy alias: pre-tier-world items minted with the plain mob id
            // resolve to lane tier 1 so old purchases/world data keep working.
            purchasables.putIfAbsent(id, new TierRef(mob, tiers.get(0)));
        }
        return lanes.size();
    }

    /** Parses the tier list of one mob, tolerating the legacy single-tier shape. */
    private List<SpawnerTier> parseTiers(final String mobId, final String mobDisplay, final ConfigurationSection def) {
        final List<SpawnerTier> tiers = new ArrayList<>();
        final List<Map<?, ?>> raw = def.getMapList("tiers");
        if (raw.isEmpty()) {
            // Legacy section: single tier directly under the mob keys.
            if (!def.contains("required-kills") && !def.contains("price")) {
                return tiers;
            }
            appendTier(mobId, tiers, mobDisplay + " Spawner",
                    def.getLong("required-kills", 0L), def.getLong("price", 0L), 1, 400, false);
            return tiers;
        }
        int index = 0;
        for (final Map<?, ?> entry : raw) {
            index++;
            final boolean ancient = Boolean.TRUE.equals(entry.get("ancient"));
            final Object rawDisplay = entry.get("display");
            appendTier(mobId, tiers,
                    rawDisplay == null
                            ? mobDisplay + " Spawner " + SpawnerTier.roman(index)
                            : String.valueOf(rawDisplay),
                    entry.get("required-kills"), entry.get("price"),
                    entry.get("spawn-count"), entry.get("spawn-delay-ticks"), ancient);
        }
        return tiers;
    }

    private void appendTier(
            final String mobId, final List<SpawnerTier> out, final String display,
            final Object requiredKills, final Object price, final Object spawnCount,
            final Object spawnDelayTicks, final boolean ancient) {
        final int index = out.size() + 1;
        try {
            out.add(new SpawnerTier(
                    SpawnerTier.tierId(mobId, index), index, display,
                    Math.max(0L, longOf(requiredKills, 0L)),
                    Math.max(0L, longOf(price, 0L)),
                    // Ancient variants always awaken a single mob per cycle.
                    ancient ? 1 : (int) Math.max(1L, longOf(spawnCount, 1L)),
                    (int) Math.max(20L, longOf(spawnDelayTicks, 400L)),
                    ancient));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Spawner tier '" + mobId + "-" + index
                    + "' skipped: " + e.getMessage());
        }
    }

    private static long longOf(final Object value, final long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** All mob lanes in config order. */
    public List<SpawnerDefinition> all() {
        return new ArrayList<>(lanes.values());
    }

    /** A mob lane by id. */
    public Optional<SpawnerDefinition> definition(final String id) {
        return Optional.ofNullable(lanes.get(id));
    }

    /** A purchasable tier by its id (also resolves legacy plain mob ids). */
    public Optional<TierRef> tierFor(final String purchasableId) {
        return Optional.ofNullable(purchasables.get(purchasableId));
    }

    // ------------------------------------------------------------------ world configuration

    public synchronized EntitySnapshot snapshotFor(final EntityType type) {
        return worldRuntime.snapshotFor(type);
    }

    public void configureWorldSpawner(final Block block, final TierRef ref, final int delayTicks) {
        worldRuntime.configureWorldSpawner(block, ref, delayTicks);
    }

    public void startWatchdog() {
        worldRuntime.startWatchdog();
    }

    public void stopWatchdog() {
        worldRuntime.stopWatchdog();
    }

    // ------------------------------------------------------------------ progress

    public long killsOf(final PlayerProfile profile, final SpawnerDefinition mob) {
        return progression.killsOf(profile, mob);
    }

    /** Kill-accounted unlock check for one purchasable tier. */
    public boolean isUnlocked(final PlayerProfile profile, final TierRef ref) {
        return progression.isUnlocked(profile, ref);
    }

    /**
     * Acquires one slot in the player's rolling kill-cap window
     * ({@code spawners.kill-cap-per-minute}). Both wild kills and spawner
     * kills consume a slot — spawner farms cannot bypass the cap.
     */
    public boolean acquireKillSlot(final UUID playerId) {
        return progression.acquireKillSlot(playerId);
    }

    /**
     * Records a WILD kill for the killer; messages fire at every tier
     * boundary crossed. Kills beyond the rolling per-minute cap do not
     * count (anti-farming; see {@code spawners.kill-cap-per-minute}).
     */
    public void recordKill(
            final Player player, final PlayerProfile profile, final org.bukkit.entity.EntityType type) {
        progression.recordKill(player, profile, type);
    }

    /**
     * Handles the death of a CoreMC spawner-born mob at a player's hands.
     * Spawner kills NEVER advance the source lane's wild unlock — instead
     * they pay the configurable Core money / Sky Token / island-XP reward
     * (chance + amounts from {@code spawners.kill-rewards}), and an
     * Ancient kill grants {@code spawners.ancient.progress-bonus} (3 by
     * default) progress toward the NEXT mob lane as ONE kill event (one
     * cap slot, one message) — never three instant kills.
     */
    public void recordSpawnerKill(final Player killer, final PlayerProfile profile,
            final org.bukkit.entity.LivingEntity victim) {
        progression.recordSpawnerKill(killer, profile, victim);
    }

    /**
     * Admin/console helper: grants kill progress for a lane, firing any
     * unlock fanfares for an online target. Returns false when the mob
     * key is unknown. Persists immediately.
     */
    public boolean adminGrantKills(final PlayerProfile profile, final String mobKey, final long amount) {
        return progression.adminGrantKills(profile, mobKey, amount);
    }

    /** The configured mob lane keys (tab-completion, admin commands). */
    public java.util.Set<String> laneKeys() {
        return java.util.Collections.unmodifiableSet(lanes.keySet());
    }

    // ------------------------------------------------------------------ purchasing

    /** Mints one typed deployable spawner for the purchasable id (tier or legacy alias). */
    public Optional<ItemStack> mint(final String id) {
        return lifecycle.mint(id);
    }

    public ItemStack mint(final TierRef ref) {
        return lifecycle.mint(ref);
    }

    /**
     * Attempts a spawner purchase: tier must be unlocked and affordable.
     * Withdraws Sky Tokens and grants the item (overflow to ender chest,
     * refund on total delivery failure).
     */
    public boolean buy(final Player player, final PlayerProfile profile, final TierRef ref) {
        return lifecycle.buy(player, profile, ref);
    }
}
