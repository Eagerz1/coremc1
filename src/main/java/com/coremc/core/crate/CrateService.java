package com.coremc.core.crate;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.ItemDelivery;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The crate lineup: weighted reward pools opened with physical keys
 * at {@code /crates}, with a pity guarantee per crate.
 *
 * Every reward reuses an existing grant path — economy deposits,
 * key mints, spawner/generator mints, plain item delivery — so crates
 * add no new item pipelines. Crate currency bypasses island buffs by
 * design (burst income never double-dips): deposits go straight to
 * the economy, never through the activity funnels.
 *
 * Pity: each open without a jackpot increments the crate's counter
 * (player profile stats, survives restarts); at {@code pity-count}
 * the pity reward pays instead of rolling, and any natural jackpot
 * resets the counter. Must load AFTER keys, spawners and generators
 * (reward references are validated against the live catalogues).
 */
public final class CrateService {

    private final CoreMCPlugin plugin;
    private final Map<String, CrateDefinition> crates = new LinkedHashMap<>();

    public CrateService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads {@code crates.yml crates:}; returns the crate count. */
    public int load() {
        final File file = new File(plugin.getDataFolder(), "crates.yml");
        if (!file.exists()) {
            plugin.saveResource("crates.yml", false);
        }
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        final ConfigurationSection root = yaml.getConfigurationSection("crates");
        final Map<String, CrateDefinition> parsed = new LinkedHashMap<>();
        if (root != null) {
            for (final String id : root.getKeys(false)) {
                final ConfigurationSection def = root.getConfigurationSection(id);
                if (def == null) {
                    plugin.getLogger().warning("[crates] crate '" + id + "' must be a map — skipped.");
                    continue;
                }
                parseCrate(id.toLowerCase(Locale.ROOT), def).ifPresent(crate -> parsed.put(crate.id(), crate));
            }
        }
        crates.clear();
        crates.putAll(parsed);
        return parsed.size();
    }

    private Optional<CrateDefinition> parseCrate(final String id, final ConfigurationSection def) {
        final List<String> keys = def.getStringList("keys");
        if (keys.isEmpty() && def.getString("key") != null) {
            keys.add(def.getString("key"));
        }
        if (keys.isEmpty()) {
            plugin.getLogger().warning("[crates] crate '" + id + "' names no key — skipped.");
            return Optional.empty();
        }
        for (final String keyId : keys) {
            if (plugin.keys().key(keyId).isEmpty()) {
                plugin.getLogger().warning("[crates] crate '" + id + "' wants unknown key '"
                        + keyId + "' — skipped.");
                return Optional.empty();
            }
        }
        final List<CrateReward> rewards = new ArrayList<>();
        for (final Map<?, ?> raw : def.getMapList("rewards")) {
            parseReward(id, raw).ifPresent(rewards::add);
        }
        if (rewards.isEmpty()) {
            plugin.getLogger().warning("[crates] crate '" + id + "' has no usable rewards — skipped.");
            return Optional.empty();
        }
        final int pityCount = Math.max(0, def.getInt("pity-count", 0));
        CrateReward pity = null;
        if (pityCount > 0) {
            final ConfigurationSection pitySection = def.getConfigurationSection("pity-reward");
            if (pitySection != null) {
                pity = parseReward(id, pitySection.getValues(false)).orElse(null);
            }
            if (pity == null) {
                plugin.getLogger().warning("[crates] crate '" + id + "' has pity-count but no"
                        + " usable pity-reward — pity disabled.");
                return Optional.of(new CrateDefinition(id, def.getString("display", "&e" + id),
                        def.getString("icon", "ENDER_CHEST"), List.copyOf(keys), List.copyOf(rewards), 0, null));
            }
        }
        return Optional.of(new CrateDefinition(id, def.getString("display", "&e" + id),
                def.getString("icon", "ENDER_CHEST"), List.copyOf(keys), List.copyOf(rewards), pityCount, pity));
    }

    private Optional<CrateReward> parseReward(final String crateId, final Map<?, ?> raw) {
        final String typeName = strOf(raw.get("type"), "").toUpperCase(Locale.ROOT);
        final CrateReward.RewardType type;
        try {
            type = CrateReward.RewardType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[crates] crate '" + crateId + "' has reward with bad type '"
                    + typeName + "' — skipped.");
            return Optional.empty();
        }
        final int weight = intOf(raw.get("weight"), 0);
        final String rarity = strOf(raw.get("rarity"), "common");
        final String currency = strOf(raw.get("currency"), "MONEY").toUpperCase(Locale.ROOT);
        final long min = longOf(raw.get("min"), longOf(raw.get("amount"), 1L));
        final long max = longOf(raw.get("max"), min);
        final String key = strOf(raw.get("key"), "");
        final int amount = Math.max(1, intOf(raw.get("amount"), 1));
        final String refId = strOf(raw.get("ref"), "");
        final String material = strOf(raw.get("material"), "").toUpperCase(Locale.ROOT);
        // Validate references against the live catalogues (load order matters).
        switch (type) {
            case CURRENCY -> {
                try {
                    Currency.valueOf(currency);
                } catch (IllegalArgumentException e) {
                    warn(crateId, "bad currency '" + currency + "'");
                    return Optional.empty();
                }
                if (min <= 0L || max < min) {
                    warn(crateId, "bad currency range");
                    return Optional.empty();
                }
            }
            case KEY -> {
                if (plugin.keys().key(key).isEmpty()) {
                    warn(crateId, "unknown key '" + key + "'");
                    return Optional.empty();
                }
            }
            case SPAWNER -> {
                if (plugin.spawners().tierFor(refId).isEmpty()) {
                    warn(crateId, "unknown spawner tier '" + refId + "'");
                    return Optional.empty();
                }
            }
            case GENERATOR -> {
                if (plugin.generators().mint(refId).isEmpty()) {
                    warn(crateId, "unknown generator '" + refId + "'");
                    return Optional.empty();
                }
            }
            case ITEM -> {
                if (Material.matchMaterial(material) == null) {
                    warn(crateId, "bad material '" + material + "'");
                    return Optional.empty();
                }
            }
        }
        final CrateReward reward = new CrateReward(type, Math.max(0, weight), rarity, currency,
                Math.max(1L, min), Math.max(1L, max), key, amount, refId, material, "");
        return Optional.of(new CrateReward(type, reward.weight(), rarity, currency, reward.min(), reward.max(),
                key, amount, refId, material, labelFor(reward)));
    }

    private void warn(final String crateId, final String detail) {
        plugin.getLogger().warning("[crates] crate '" + crateId + "' reward skipped: " + detail + ".");
    }

    private static String strOf(final Object value, final String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intOf(final Object value, final int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longOf(final Object value, final long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    /** Coloured single-line label for previews and open messages. */
    private String labelFor(final CrateReward reward) {
        final String color = CrateReward.rarityColor(reward.rarity());
        return switch (reward.type()) {
            case CURRENCY -> {
                final String name = Currency.valueOf(reward.currency()).displayName();
                yield reward.min() == reward.max()
                        ? color + String.format(Locale.ROOT, "%,d", reward.min()) + " " + name
                        : color + String.format(Locale.ROOT, "%,d", reward.min()) + "-"
                                + String.format(Locale.ROOT, "%,d", reward.max()) + " " + name;
            }
            case KEY -> {
                final String display = plugin.keys().key(reward.key())
                        .map(CrateKey::display).orElse("&e" + reward.key() + " key");
                yield display + (reward.amount() > 1 ? color + " x" + reward.amount() : "");
            }
            case SPAWNER -> plugin.spawners().tierFor(reward.refId())
                    .map(ref -> ref.tier().colouredDisplay()).orElse(color + reward.refId());
            case GENERATOR -> plugin.generators().mint(reward.refId())
                    .map(stack -> stack.getItemMeta() == null
                            ? color + reward.refId()
                            : stack.getItemMeta().getDisplayName())
                    .orElse(color + reward.refId());
            case ITEM -> color + prettify(reward.material()) + (reward.amount() > 1 ? " x" + reward.amount() : "");
        };
    }

    private static String prettify(final String material) {
        final String[] parts = material.toLowerCase(Locale.ROOT).split("_");
        final StringBuilder name = new StringBuilder();
        for (final String part : parts) {
            if (!part.isEmpty()) {
                if (name.length() > 0) {
                    name.append(' ');
                }
                name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return name.toString();
    }

    /** All crates in file order. */
    public List<CrateDefinition> all() {
        return new ArrayList<>(crates.values());
    }

    /** A crate by id. */
    public Optional<CrateDefinition> crate(final String id) {
        return Optional.ofNullable(crates.get(id.toLowerCase(Locale.ROOT)));
    }

    /**
     * Weighted roll over the pool: {@code roll01} in [0,1) picks the
     * reward. Pure (unit-tested); null only when the pool has no weight.
     */
    public static CrateReward roll(final CrateDefinition crate, final double roll01) {
        final int total = crate.totalWeight();
        if (total <= 0 || crate.rewards().isEmpty()) {
            return null;
        }
        double needle = Math.min(0.999999999, Math.max(0.0, roll01)) * total;
        CrateReward last = crate.rewards().get(crate.rewards().size() - 1);
        for (final CrateReward reward : crate.rewards()) {
            needle -= Math.max(0, reward.weight());
            if (needle < 0) {
                return reward;
            }
            last = reward;
        }
        return last;
    }

    /** Display chance of a reward (weight share, whole percent). */
    public static int chancePct(final CrateDefinition crate, final CrateReward reward) {
        final int total = crate.totalWeight();
        return total <= 0 ? 0 : Math.max(0, reward.weight()) * 100 / total;
    }

    /** Preview icon for a reward (resolves live catalogue materials). */
    public Material previewIcon(final CrateReward reward) {
        return switch (reward.type()) {
            case CURRENCY -> switch (reward.currency()) {
                case "SKY_TOKENS" -> Material.NETHER_STAR;
                case "CREDITS" -> Material.GOLD_INGOT;
                default -> Material.GOLD_NUGGET;
            };
            case KEY -> plugin.keys().key(reward.key())
                    .map(key -> {
                        final Material icon = Material.matchMaterial(key.icon());
                        return icon == null || icon.isAir() ? Material.TRIPWIRE_HOOK : icon;
                    })
                    .orElse(Material.TRIPWIRE_HOOK);
            case SPAWNER -> Material.SPAWNER;
            case GENERATOR -> plugin.generators().all().stream()
                    .filter(def -> def.id().equalsIgnoreCase(reward.refId()))
                    .findFirst()
                    .map(com.coremc.core.gen.GeneratorDefinition::blockMaterial)
                    .orElse(Material.OBSERVER);
            case ITEM -> {
                final Material icon = Material.matchMaterial(reward.material());
                yield icon == null || icon.isAir() ? Material.STONE : icon;
            }
        };
    }

    /**
     * Opens a crate: consumes one key, rolls (or pays pity), grants the
     * reward, advances/resets the pity counter. The key is consumed only
     * after the reward resolves — a broken pool never eats keys.
     *
     * @return true when a reward paid out
     */
    public boolean open(final Player player, final CrateDefinition crate) {
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            plugin.messages().sendPrefixed(player, "shop.unavailable", Map.of());
            return false;
        }
        String keyUsed = null;
        for (final String keyId : crate.keys()) {
            if (plugin.keys().countKeys(player, keyId) > 0) {
                keyUsed = keyId;
                break;
            }
        }
        if (keyUsed == null) {
            final String keyName = plugin.keys().key(crate.keys().get(0))
                    .map(key -> ColorUtil.colorize(key.display()))
                    .orElse(crate.keys().get(0));
            plugin.messages().sendPrefixed(player, "crate.no-key", Map.of(
                    "key", keyName, "crate", ColorUtil.colorize(crate.display())));
            return false;
        }
        final long seen = profile.statOf(crate.pityStatKey()) + 1L;
        final boolean pityDue = crate.pityReward() != null && crate.pityCount() > 0 && seen >= crate.pityCount();
        final CrateReward reward =
                pityDue ? crate.pityReward() : roll(crate, ThreadLocalRandom.current().nextDouble());
        if (reward == null) {
            plugin.getLogger().warning("[crates] crate '" + crate.id() + "' has an empty pool — open refused.");
            plugin.messages().sendPrefixed(player, "crate.broken", Map.of());
            return false;
        }
        final ItemStack preResolved = preResolve(reward);
        if (preResolved == null && needsItem(reward)) {
            plugin.getLogger().warning("[crates] crate '" + crate.id() + "' reward no longer resolves — refused.");
            plugin.messages().sendPrefixed(player, "crate.broken", Map.of());
            return false;
        }
        profile.setStat(crate.pityStatKey(),
                (pityDue || CrateReward.isJackpot(reward.rarity())) ? 0L : seen);
        plugin.playerData().markDirty(profile.uuid());
        plugin.keys().takeKeys(player, keyUsed, 1);
        final String actual = grant(player, profile, reward, preResolved);
        plugin.messages().sendPrefixed(player, pityDue ? "crate.pity" : "crate.opened", Map.of(
                "crate", ColorUtil.colorize(crate.display()),
                "reward", actual));
        return true;
    }

    private static boolean needsItem(final CrateReward reward) {
        return reward.type() == CrateReward.RewardType.SPAWNER
                || reward.type() == CrateReward.RewardType.GENERATOR
                || reward.type() == CrateReward.RewardType.ITEM;
    }

    /** Builds the item for item-ish rewards ahead of key consumption (abort-safe). */
    private ItemStack preResolve(final CrateReward reward) {
        return switch (reward.type()) {
            case SPAWNER -> plugin.spawners().mint(reward.refId()).orElse(null);
            case GENERATOR -> plugin.generators().mint(reward.refId()).orElse(null);
            case ITEM -> {
                final Material material = Material.matchMaterial(reward.material());
                yield material == null ? null : new ItemStack(material, Math.min(64, reward.amount()));
            }
            default -> null;
        };
    }

    /** Grants a resolved reward; returns the coloured actual-payout label. */
    private String grant(
            final Player player, final PlayerProfile profile, final CrateReward reward, final ItemStack preResolved) {
        switch (reward.type()) {
            case CURRENCY -> {
                final long amount = reward.min() >= reward.max()
                        ? reward.min()
                        : reward.min() + ThreadLocalRandom.current().nextLong(reward.max() - reward.min() + 1L);
                final Currency currency = Currency.valueOf(reward.currency());
                plugin.economy().deposit(profile, currency, amount);
                return CrateReward.rarityColor(reward.rarity())
                        + String.format(Locale.ROOT, "%,d", amount) + " " + currency.displayName();
            }
            case KEY -> {
                plugin.keys().giveKeys(player, reward.key(), reward.amount());
                return ColorUtil.colorize(reward.label());
            }
            default -> {
                final ItemStack stack = preResolved == null ? new ItemStack(Material.STONE) : preResolved.clone();
                stack.setAmount(reward.type() == CrateReward.RewardType.ITEM
                        ? Math.min(64, reward.amount()) : Math.max(1, stack.getAmount()));
                if (ItemDelivery.deliverDetailed(player, stack) == ItemDelivery.Result.FAILED) {
                    player.getWorld().dropItemNaturally(player.getLocation(), stack);
                    plugin.messages().sendPrefixed(player, "crate.dropped", Map.of());
                }
                return ColorUtil.colorize(reward.label());
            }
        }
    }
}
