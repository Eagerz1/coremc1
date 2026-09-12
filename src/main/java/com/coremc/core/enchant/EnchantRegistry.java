package com.coremc.core.enchant;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Loads and owns the custom-enchant catalogue from
 * {@code enchants.yml}. Tolerant by contract: malformed entries are
 * skipped with a loud warning, never fatal — a typo must not brick
 * the whole track. {@link #load} rebuilds the catalogue from disk so
 * {@code /coremc reload} picks up tuning without a restart.
 *
 * Parsing itself is pure ({@link #parseDefinition}) so the catalogue
 * rules are unit-testable without a server.
 */
public final class EnchantRegistry {

    /** Role keys that may own enchants (universal = every role). */
    public static final Set<String> ROLES =
            Set.of("miner", "logger", "fisher", "slayer", "farmer", "universal");

    /** Sanity cap: no enchant may declare more levels than this. */
    public static final int MAX_LEVEL_HARD_CAP = 1000;

    private final CoreMCPlugin plugin;
    private final Map<String, Enchant> byId = new LinkedHashMap<>();

    public EnchantRegistry(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * (Re)loads {@code enchants.yml}; returns the number of valid enchants.
     *
     * The file is read with RAW SnakeYAML maps (never Bukkit's
     * {@code YamlConfiguration}): enchant ids are dotted
     * ("miner.treasure-miner") and Bukkit would explode them into nested
     * path sections, silently emptying the catalogue.
     */
    public int load() {
        com.coremc.core.util.YamlFiles.mergeNewDefaults(plugin, "enchants.yml");
        final File file = new File(plugin.getDataFolder(), "enchants.yml");
        final Map<String, Object> yaml = com.coremc.core.util.RawYaml.loadMap(file);
        final List<String> errors = new ArrayList<>();
        final Map<String, Enchant> parsed = new LinkedHashMap<>();
        final Object rawRoot = yaml.get("enchants");
        if (!(rawRoot instanceof Map<?, ?> root)) {
            errors.add("missing 'enchants:' section — catalogue is empty");
        } else {
            for (final Map.Entry<?, ?> entry : root.entrySet()) {
                final String id = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> rawDef)) {
                    errors.add(id + ": definition must be a map");
                    continue;
                }
                final Map<String, Object> def = new LinkedHashMap<>();
                for (final Map.Entry<?, ?> field : rawDef.entrySet()) {
                    def.put(String.valueOf(field.getKey()), field.getValue());
                }
                parseDefinition(id, def, errors).ifPresent(enchant -> parsed.put(id, enchant));
            }
        }
        for (final String error : errors) {
            plugin.getLogger().warning("[enchants] " + error);
        }
        byId.clear();
        byId.putAll(parsed);
        if (parsed.size() != 90) {
            plugin.getLogger().warning("[enchants] catalogue holds " + parsed.size()
                    + " enchants (CoreMC ships 90 — 15 per role track).");
        }
        return parsed.size();
    }

    public Optional<Enchant> enchant(final String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The track for {@code roleKey}, in config order. */
    public List<Enchant> forRole(final String roleKey) {
        final List<Enchant> result = new ArrayList<>();
        for (final Enchant enchant : byId.values()) {
            if (enchant.role().equals(roleKey)) {
                result.add(enchant);
            }
        }
        return result;
    }

    public Collection<Enchant> all() {
        return java.util.Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }

    /**
     * Parses one enchant definition from a plain map (pure — no Bukkit).
     * Problems go to {@code errors}; fatal ones yield empty.
     */
    static Optional<Enchant> parseDefinition(
            final String id, final Map<String, Object> def, final List<String> errors) {
        final String where = id + ": ";
        final String role = string(def.get("role"), "").trim().toLowerCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
            errors.add(where + "unknown role '" + def.get("role") + "' (want one of " + ROLES + ")");
            return Optional.empty();
        }
        if (!id.startsWith(role + ".")) {
            errors.add(where + "id should start with '" + role + ".' by convention");
        }
        final String display = string(def.get("display"), "&f" + id);
        final String description = string(def.get("description"), "&7No description configured.");
        final String icon = string(def.get("icon"), "ENCHANTED_BOOK").trim().toUpperCase(Locale.ROOT);

        int maxLevel = number(def.get("max-level"), 1);
        if (maxLevel < 1) {
            errors.add(where + "max-level must be >= 1");
            return Optional.empty();
        }
        if (maxLevel > MAX_LEVEL_HARD_CAP) {
            errors.add(where + "max-level capped at " + MAX_LEVEL_HARD_CAP);
            maxLevel = MAX_LEVEL_HARD_CAP;
        }

        Currency currency = Currency.SKY_TOKENS;
        final Object currencyRaw = def.get("currency");
        if (currencyRaw != null) {
            try {
                currency = Currency.valueOf(
                        String.valueOf(currencyRaw).trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException unknown) {
                errors.add(where + "unknown currency '" + currencyRaw + "' — defaulting to SKY_TOKENS");
            }
        }

        final EnchantEffect effect = EnchantEffect.parse(string(def.get("effect"), null)).orElse(null);
        if (effect == null) {
            errors.add(where + "unknown effect '" + def.get("effect") + "'");
            return Optional.empty();
        }
        final EnchantEffect.Trigger trigger =
                EnchantEffect.parseTrigger(string(def.get("trigger"), null)).orElse(null);
        if (trigger == null) {
            errors.add(where + "unknown trigger '" + def.get("trigger") + "'");
            return Optional.empty();
        }
        if (effect == EnchantEffect.MULTIPLIER && trigger != EnchantEffect.Trigger.PASSIVE) {
            errors.add(where + "MULTIPLIER must use trigger PASSIVE");
            return Optional.empty();
        }

        final List<Long> costs = buildCosts(id, def, maxLevel, errors);
        final double chanceBase = clamp01(decimal(def.get("chance-base"), 0.0), where, "chance-base", errors);
        final double chanceScale = decimal(def.get("chance-scale"), 0.0);
        final double chanceCap = clamp01(decimal(def.get("chance-cap"), 1.0), where, "chance-cap", errors);
        final double valueBase = decimal(def.get("value-base"), 0.0);
        final double valueScale = decimal(def.get("value-scale"), 0.0);
        final long cooldownSeconds = Math.max(0L, (long) decimal(def.get("cooldown-seconds"), 0.0));
        final int minRoleLevel = Math.max(1, number(def.get("min-role-level"), 1));

        Map<String, Object> values = Map.of();
        final Object valuesRaw = def.get("values");
        if (valuesRaw instanceof Map<?, ?> raw) {
            final Map<String, Object> copy = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : raw.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            values = copy;
        }
        validateRewards(id, effect, trigger, values, errors);

        try {
            return Optional.of(new Enchant(
                    id, role, display, description, icon, maxLevel, currency, costs,
                    effect, trigger, chanceBase, chanceScale, chanceCap,
                    valueBase, valueScale, cooldownSeconds, minRoleLevel, values));
        } catch (final IllegalArgumentException bad) {
            errors.add(where + bad.getMessage());
            return Optional.empty();
        }
    }

    /** Validates reward tables eagerly so typos warn at load, not at trigger time. */
    private static void validateRewards(
            final String id,
            final EnchantEffect effect,
            final EnchantEffect.Trigger trigger,
            final Map<String, Object> values,
            final List<String> errors) {
        final boolean needsTable = effect == EnchantEffect.REWARD_TABLE
                || (effect == EnchantEffect.FISH_BONUS
                        && "TREASURE".equalsIgnoreCase(string(values.get("mode"), "")))
                || (effect == EnchantEffect.ULTIMATE && values.containsKey("rewards"));
        if (!needsTable) {
            return;
        }
        final Object rewards = values.get("rewards");
        if (!(rewards instanceof List<?> list) || list.isEmpty()) {
            errors.add(id + ": effect needs a non-empty values.rewards table");
            return;
        }
        for (final Object entry : list) {
            RewardRoll.parse(entry, errors, id + ": rewards");
        }
    }

    /** Builds the per-level cost list from explicit `costs:` or the base/growth formula. */
    private static List<Long> buildCosts(
            final String id, final Map<String, Object> def, final int maxLevel, final List<String> errors) {
        final Object explicit = def.get("costs");
        if (explicit instanceof List<?> list) {
            final List<Long> costs = new ArrayList<>();
            for (final Object entry : list) {
                costs.add(Math.max(0L, (long) decimal(entry, 0.0)));
            }
            while (costs.size() < maxLevel && !costs.isEmpty()) {
                costs.add(costs.get(costs.size() - 1)); // repeat last tier
            }
            while (costs.size() < maxLevel) {
                costs.add(0L);
            }
            if (costs.size() != list.size()) {
                errors.add(id + ": costs list padded/truncated to max-level " + maxLevel);
            }
            return costs.subList(0, maxLevel);
        }
        double base = decimal(def.get("cost-base"), 100.0);
        double growth = decimal(def.get("cost-growth"), 1.10);
        if (base < 0.0) {
            errors.add(id + ": cost-base must be >= 0");
            base = 0.0;
        }
        if (growth < 1.0) {
            errors.add(id + ": cost-growth must be >= 1.0");
            growth = 1.0;
        }
        final List<Long> costs = new ArrayList<>(maxLevel);
        for (int level = 1; level <= maxLevel; level++) {
            final double raw = base * Math.pow(growth, level - 1);
            costs.add(!Double.isFinite(raw) || raw > 9.0e17 ? 900_000_000_000_000_000L : Math.round(raw));
        }
        return costs;
    }

    /** Deep-converts a config section to plain maps/lists/primitives. */
    private static Map<String, Object> deepMap(final ConfigurationSection section) {
        final Map<String, Object> map = new LinkedHashMap<>();
        for (final String key : section.getKeys(false)) {
            map.put(key, deepValue(section.get(key)));
        }
        return map;
    }

    private static Object deepValue(final Object value) {
        if (value instanceof ConfigurationSection section) {
            return deepMap(section);
        }
        if (value instanceof List<?> list) {
            final List<Object> copy = new ArrayList<>(list.size());
            for (final Object entry : list) {
                copy.add(deepValue(entry));
            }
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> copy = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepValue(entry.getValue()));
            }
            return copy;
        }
        return value;
    }

    private static String string(final Object value, final String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int number(final Object value, final int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double decimal(final Object value, final double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double clamp01(
            final double value, final String where, final String field, final List<String> errors) {
        if (value < 0.0 || value > 1.0) {
            errors.add(where + field + " must be 0..1 — clamped");
            return Math.min(1.0, Math.max(0.0, value));
        }
        return value;
    }
}
