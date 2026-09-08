package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.placeable.PlaceableService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
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

    /** uuid -> rolling kill window (anti-farming); bounded by KILL_WINDOW_SOFT_LIMIT. */
    private final Map<UUID, RollingKillCap> killWindows = new ConcurrentHashMap<>();

    /** Resolved mob + tier for one purchasable spawner id. */
    public record TierRef(SpawnerDefinition mob, SpawnerTier tier) {
    }

    public SpawnerService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
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
                    def.getLong("required-kills", 0L), def.getLong("price", 0L), 1, 400);
            return tiers;
        }
        int index = 0;
        for (final Map<?, ?> entry : raw) {
            index++;
            appendTier(mobId, tiers,
                    String.valueOf(entry.getOrDefault(
                            "display", mobDisplay + " Spawner " + SpawnerTier.roman(index))),
                    entry.get("required-kills"), entry.get("price"),
                    entry.get("spawn-count"), entry.get("spawn-delay-ticks"));
        }
        return tiers;
    }

    private void appendTier(
            final String mobId, final List<SpawnerTier> out, final String display,
            final Object requiredKills, final Object price, final Object spawnCount, final Object spawnDelayTicks) {
        try {
            out.add(new SpawnerTier(
                    SpawnerTier.tierId(mobId, out.size() + 1), out.size() + 1, display,
                    Math.max(0L, longOf(requiredKills, 0L)),
                    Math.max(0L, longOf(price, 0L)),
                    (int) Math.max(1L, longOf(spawnCount, 1L)),
                    (int) Math.max(20L, longOf(spawnDelayTicks, 400L))));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Spawner tier '" + mobId + "-" + (out.size() + 1)
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

    // ------------------------------------------------------------------ progress

    public long killsOf(final PlayerProfile profile, final SpawnerDefinition mob) {
        return profile.killCountOf(mob.killKey());
    }

    /** Kill-accounted unlock check for one purchasable tier. */
    public boolean isUnlocked(final PlayerProfile profile, final TierRef ref) {
        return killsOf(profile, ref.mob()) >= ref.tier().requiredKills();
    }

    /**
     * Records a kill for the killer; messages fire at every tier boundary
     * crossed. Kills beyond the rolling per-minute cap do not count
     * (anti-farming; see {@code spawners.kill-cap-per-minute}).
     */
    public void recordKill(
            final Player player, final PlayerProfile profile, final org.bukkit.entity.EntityType type) {
        final long cap = plugin.coreConfig().spawnerKillCapPerMinute();
        final RollingKillCap window = killWindows.computeIfAbsent(
                player.getUniqueId(), uuid -> new RollingKillCap(cap));
        if (!window.tryCount(System.currentTimeMillis())) {
            return; // throttled: this kill does not count towards unlock progression
        }
        // Bound the player-keyed map: past the soft limit the whole windows
        // set resets (the cap re-seeds on each player's next kill — worst
        // case a refilled minute window, never leaked memory).
        if (killWindows.size() > KILL_WINDOW_SOFT_LIMIT) {
            killWindows.clear();
        }
        final String key = type.name().toLowerCase(Locale.ROOT);
        final long before = profile.killCountOf(key);
        profile.addKillCount(key);
        plugin.playerData().markDirty(profile.uuid());
        for (final SpawnerDefinition mob : lanes.values()) {
            if (mob.entityType() != type) {
                continue;
            }
            for (final SpawnerTier tier : mob.tiers()) {
                if (before < tier.requiredKills() && before + 1 >= tier.requiredKills()) {
                    plugin.messages().sendPrefixed(
                            player, "spawner.unlocked", Map.of("name", tier.colouredDisplay()));
                }
            }
        }
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
            meta.setLore(List.of(
                    ColorUtil.colorize("&7Tier &b" + SpawnerTier.roman(ref.tier().index())
                            + "&7 — " + ref.tier().throughputLine()),
                    ColorUtil.colorize("&7Place me to set me down.")));
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
