package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.placeable.PlaceableService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.spawner.SpawnerEntry;
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

    /** Start sweeping kill windows once this many players hold entries. */
    private static final int KILL_WINDOW_SOFT_LIMIT = 512;

    private final CoreMCPlugin plugin;
    /** mobId -> lane. */
    private final Map<String, SpawnerDefinition> lanes = new LinkedHashMap<>();
    /** purchasable id ("zombie-2") -> (mob, tier). Includes legacy aliases for old placements. */
    private final Map<String, TierRef> purchasables = new LinkedHashMap<>();
    /** Shared entity PDC tags (spawner-born / spawner-id / spawner-ancient). */
    private final SpawnerTags tags;

    /** uuid -> rolling kill window (anti-farming); bounded by KILL_WINDOW_SOFT_LIMIT. */
    private final Map<UUID, RollingKillCap> killWindows = new ConcurrentHashMap<>();

    /** One vanilla-compatible snapshot per entity type, for writing SpawnPotentials. */
    private final Map<EntityType, EntitySnapshot> snapshots = new EnumMap<>(EntityType.class);
    /** Placement key -> consecutive stalled-at-zero observations (watchdog). */
    private final Map<String, Integer> stalled = new HashMap<>();
    private org.bukkit.scheduler.BukkitTask watchdog;

    /** Resolved mob + tier for one purchasable spawner id. */
    public record TierRef(SpawnerDefinition mob, SpawnerTier tier) {
    }

    public SpawnerService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.tags = new SpawnerTags(plugin);
    }

    /** Shared entity tags (mob tagger, island boost extras, kill routing). */
    public SpawnerTags tags() {
        return tags;
    }

    /** (Re)loads {@code spawners:} from config. Returns loaded mob lane count. */
    public int load() {
        lanes.clear();
        purchasables.clear();
        killWindows.clear();
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

    /**
     * Builds the vanilla-compatible entity snapshot a spawner tile needs
     * for both {@code SpawnData} and {@code SpawnPotentials}. Spawner tiles
     * whose potential list is empty stall permanently at {@code Delay:0}
     * once any spawn attempt is refused (mob cap, cancelled spawn event),
     * because Paper's {@code BaseSpawner} only rolls a fresh timer from
     * the potential list after a refused attempt on some paths.
     */
    public synchronized EntitySnapshot snapshotFor(final EntityType type) {
        EntitySnapshot snapshot = snapshots.get(type);
        if (snapshot == null) {
            final String nbt = "{id:\"" + type.getKey() + "\"}";
            snapshot = Bukkit.getEntityFactory().createEntitySnapshot(nbt);
            snapshots.put(type, snapshot);
        }
        return snapshot;
    }

    /**
     * Applies the full CoreMC tier configuration to a spawner block state:
     * entity type, spawn count, min/max delay, a positive first delay, and
     * — critically — a populated potential-spawn list identical to what a
     * vanilla {@code /setblock} spawner carries.
     */
    public void configureWorldSpawner(final Block block, final TierRef ref, final int delayTicks) {
        if (!(block.getState() instanceof CreatureSpawner spawner)) {
            return;
        }
        applyToState(spawner, ref, delayTicks, delayTicks);
        spawner.update(true);
    }

    /**
     * Spawn range is pinned to one block. Islands are small void platforms:
     * the vanilla range of 4 lets most sampled positions fall into the void
     * (and a failed single-position cycle leaves the tile parked at Delay:0),
     * so purchased spawners would look dead. A one-block box keeps every
     * candidate directly over the platform; the liveness watchdog re-rolls
     * the occasional bad y sample.
     */
    private static final int SPAWN_RANGE_BLOCKS = 1;
    /** When a stalled tile is re-armed, re-roll within half a second. */
    private static final int REROLL_DELAY_TICKS = 10;

    private void applyToState(final CreatureSpawner spawner, final TierRef ref,
            final int delayTicks, final int firstDelayTicks) {
        final EntitySnapshot snapshot = snapshotFor(ref.mob().entityType());
        final SpawnerEntry entry = new SpawnerEntry(snapshot, 1, null);
        // NB: never call setSpawnedType() here — it resets SpawnPotentials to
        // an empty WeightedList, which makes Paper stall the tile at Delay:0.
        spawner.setSpawnedEntity(entry);
        spawner.setPotentialSpawns(List.of(entry));
        spawner.setSpawnCount(Math.max(1, ref.tier().spawnCount()));
        final int delay = Math.max(20, delayTicks);
        spawner.setMinSpawnDelay(delay);
        spawner.setMaxSpawnDelay(delay);
        spawner.setSpawnRange(SPAWN_RANGE_BLOCKS);
        spawner.setDelay(Math.max(1, Math.min(firstDelayTicks, delay)));
    }

    /**
     * Starts the liveness watchdog: registered spawner tiles in loaded
     * chunks are normalised (missing SpawnPotentials filled in — heals
     * tiles placed by older builds) and any tile whose timer has been
     * stuck at zero while a player stands in range is re-armed. Runs a
     * few times a minute; cost is one block-state snapshot per registered
     * spawner in an already-loaded chunk.
     */
    public void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdog = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWatchdog, 100L, 40L);
    }

    public void stopWatchdog() {
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
    }

    private void tickWatchdog() {
        for (final Map.Entry<String, PlaceableService.Placement> entry
                : plugin.placeables().placements().entrySet()) {
            final PlaceableService.Placement placement = entry.getValue();
            if (placement.type() != PlaceableService.Type.SPAWNER) {
                continue;
            }
            final String key = entry.getKey();
            final int split = key.indexOf(':');
            if (split < 0) {
                continue;
            }
            final World world = Bukkit.getWorld(key.substring(0, split));
            if (world == null || !world.isChunkLoaded(placement.x() >> 4, placement.z() >> 4)) {
                stalled.remove(key);
                continue;
            }
            final Block block = world.getBlockAt(placement.x(), placement.y(), placement.z());
            if (!(block.getState() instanceof CreatureSpawner spawner)) {
                stalled.remove(key);
                continue;
            }
            final TierRef ref = tierFor(placement.id()).orElse(null);
            final boolean needsPotentials = spawner.getPotentialSpawns().isEmpty();
            if (ref != null && (needsPotentials || spawner.getSpawnCount() <= 0)) {
                applyToState(spawner, ref, ref.tier().spawnDelayTicks(),
                        ref.tier().spawnDelayTicks());
                spawner.update(true);
                stalled.remove(key);
                plugin.getLogger().fine("Normalised spawner at " + key + " (" + placement.id() + ")");
                continue;
            }
            final int range = Math.max(1, spawner.getRequiredPlayerRange());
            final boolean playerInRange = world.getPlayers().stream().anyMatch(player ->
                    player.getWorld() == world && player.getLocation().distanceSquared(
                            block.getLocation().add(0.5, 0.5, 0.5)) <= (double) range * range);
            if (spawner.getDelay() <= 0 && playerInRange) {
                final int strikes = stalled.merge(key, 1, Integer::sum);
                if (strikes >= 2 && ref != null) {
                    applyToState(spawner, ref, ref.tier().spawnDelayTicks(),
                            REROLL_DELAY_TICKS);
                    spawner.update(true);
                    stalled.remove(key);
                    plugin.getLogger().fine("Re-armed stalled spawner at " + key
                            + " (" + placement.id() + ")");
                }
            } else {
                stalled.remove(key);
            }
        }
    }

    // ------------------------------------------------------------------ progress

    public long killsOf(final PlayerProfile profile, final SpawnerDefinition mob) {
        return profile.killCountOf(mob.killKey());
    }

    /** Kill-accounted unlock check for one purchasable tier. */
    public boolean isUnlocked(final PlayerProfile profile, final TierRef ref) {
        return killsOf(profile, ref.mob()) >= ref.tier().requiredKills();
    }

    /**
     * Acquires one slot in the player's rolling kill-cap window
     * ({@code spawners.kill-cap-per-minute}). Both wild kills and spawner
     * kills consume a slot — spawner farms cannot bypass the cap.
     */
    public boolean acquireKillSlot(final UUID playerId) {
        final long cap = plugin.coreConfig().spawnerKillCapPerMinute();
        final RollingKillCap window = killWindows.computeIfAbsent(
                playerId, uuid -> new RollingKillCap(cap));
        final boolean allowed = window.tryCount(System.currentTimeMillis());
        // Bound the player-keyed map: past the soft limit the whole windows
        // set resets (the cap re-seeds on each player's next kill — worst
        // case a refilled minute window, never leaked memory).
        if (killWindows.size() > KILL_WINDOW_SOFT_LIMIT) {
            killWindows.clear();
        }
        return allowed;
    }

    /**
     * Records a WILD kill for the killer; messages fire at every tier
     * boundary crossed. Kills beyond the rolling per-minute cap do not
     * count (anti-farming; see {@code spawners.kill-cap-per-minute}).
     */
    public void recordKill(
            final Player player, final PlayerProfile profile, final org.bukkit.entity.EntityType type) {
        if (!acquireKillSlot(player.getUniqueId())) {
            return; // throttled: this kill does not count towards unlock progression
        }
        final String key = type.name().toLowerCase(Locale.ROOT);
        addProgress(player, profile, lanes.values().stream()
                .filter(mob -> mob.entityType() == type).toList(), key, 1);
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
        if (!acquireKillSlot(killer.getUniqueId())) {
            return; // throttled: no rewards, no ancient progress
        }
        profile.addStat("spawner-mobs-killed", 1L);
        plugin.playerData().markDirty(profile.uuid());

        final Optional<String> tierId = tags.tierIdOf(victim);
        final boolean ancient = tags.isAncient(victim);
        final TierRef ref = tierId.flatMap(this::tierFor).orElse(null);

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

    /**
     * Ancient kill = one event granting {@code progress-bonus} kills
     * toward the NEXT lane (fanfares for any tiers crossed). The final
     * lane has no successor and instead pays a flat bounty message.
     */
    private void applyAncientProgress(
            final Player player, final PlayerProfile profile, final SpawnerDefinition sourceLane) {
        final List<SpawnerDefinition> ordered = all();
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

    /** Adds {@code amount} kills for {@code key}, firing unlock fanfares for every tier crossed. */
    private void addProgress(final Player player, final PlayerProfile profile,
            final List<SpawnerDefinition> affectedLanes, final String key, final long amount) {
        final long before = profile.killCountOf(key);
        for (long step = 0; step < amount; step++) {
            profile.addKillCount(key);
        }
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

    /**
     * Admin/console helper: grants kill progress for a lane, firing any
     * unlock fanfares for an online target. Returns false when the mob
     * key is unknown. Persists immediately.
     */
    public boolean adminGrantKills(final PlayerProfile profile, final String mobKey, final long amount) {
        final SpawnerDefinition lane = lanes.get(mobKey.toLowerCase(Locale.ROOT));
        if (lane == null || amount < 0L) {
            return false;
        }
        final Player target = plugin.getServer().getPlayer(profile.uuid());
        addProgress(target, profile, List.of(lane), lane.killKey(), amount);
        plugin.playerData().persistImportant(profile);
        return true;
    }

    /** The configured mob lane keys (tab-completion, admin commands). */
    public java.util.Set<String> laneKeys() {
        return java.util.Collections.unmodifiableSet(lanes.keySet());
    }

    // ------------------------------------------------------------------ purchasing

    /** Mints one typed deployable spawner for the purchasable id (tier or legacy alias). */
    public Optional<ItemStack> mint(final String id) {
        return tierFor(id).map(this::mint);
    }

    public ItemStack mint(final TierRef ref) {
        final ItemStack stack = new ItemStack(Material.SPAWNER);
        final var meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ref.tier().colouredDisplay());
            final List<String> lore = new ArrayList<>();
            lore.add(ColorUtil.colorize(ref.tier().ancient()
                    ? "&7Ancient variant &8— " + ref.tier().throughputLine()
                    : "&7Tier &b" + SpawnerTier.roman(ref.tier().index())
                            + "&7 — " + ref.tier().throughputLine()));
            if (!ref.tier().specialLine().isEmpty()) {
                lore.add(ColorUtil.colorize(ref.tier().specialLine()));
            }
            lore.add(ColorUtil.colorize("&7Place me to set me down."));
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return plugin.placeables().identify(stack, PlaceableService.Type.SPAWNER, ref.tier().tierId());
    }

    /**
     * Attempts a spawner purchase: tier must be unlocked and affordable.
     * Withdraws Sky Tokens and grants the item (overflow to ender chest,
     * refund on total delivery failure).
     */
    public boolean buy(final Player player, final PlayerProfile profile, final TierRef ref) {
        if (!isUnlocked(profile, ref)) {
            plugin.messages().sendPrefixed(player, "spawner.locked", Map.of(
                    "kills", String.valueOf(killsOf(profile, ref.mob())),
                    "needed", String.valueOf(ref.tier().requiredKills())));
            return false;
        }
        final String price = String.format(Locale.ROOT, "%,d", ref.tier().priceSkyTokens());
        final boolean free = ref.tier().priceSkyTokens() <= 0L;
        if (!free && !plugin.economy().withdraw(profile, Currency.SKY_TOKENS, ref.tier().priceSkyTokens())) {
            plugin.messages().sendPrefixed(player, "spawner.insufficient", Map.of("price", price));
            return false;
        }
        final ItemStack item = mint(ref);
        final var delivery = com.coremc.core.util.ItemDelivery.deliverDetailed(player, item);
        if (delivery == com.coremc.core.util.ItemDelivery.Result.FAILED) {
            if (!free) {
                plugin.economy().deposit(profile, Currency.SKY_TOKENS, ref.tier().priceSkyTokens());
            }
            plugin.messages().sendPrefixed(player, "purchase.no-space", Map.of());
            return false;
        }
        if (delivery == com.coremc.core.util.ItemDelivery.Result.DELIVERED_TO_ENDER_CHEST) {
            plugin.messages().sendPrefixed(player, "gen.bought-enderchest", Map.of());
        }
        plugin.messages().sendPrefixed(
                player, "spawner.bought", Map.of("name", ref.tier().colouredDisplay(), "price", price));
        return true;
    }
}
