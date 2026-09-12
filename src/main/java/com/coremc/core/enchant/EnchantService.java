package com.coremc.core.enchant;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.Role;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Player-facing custom-enchant operations: purchases, GUI lore, and
 * the boost queries the behaviour handlers consult.
 *
 * Purchase flow is fully transactional (role check → level-gate
 * check → maxed check → withdraw → persist → re-stamp tools →
 * confirm), mirroring the OmniTool upgrade contract.
 *
 * Boost math: passive MULTIPLIER enchants contribute {@code valueAt}
 * as percentage points (level-scaled in the definition), summed
 * across the player's role track and the universal track; 1.0 means
 * "no bonus". Combo stacks and temporary boosts live in the engine
 * (they are transient) and multiply on top — one code path per
 * bonus, so nothing is ever double-applied.
 */
public final class EnchantService {

    private final CoreMCPlugin plugin;
    private final EnchantRegistry registry;
    /** enchant id -> parsed reward table (parsed once at load, not per trigger). */
    private final Map<String, List<RewardRoll>> rewardCache = new ConcurrentHashMap<>();

    public EnchantService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.registry = new EnchantRegistry(plugin);
    }

    /** (Re)loads the catalogue; returns the valid enchant count. */
    public int load() {
        rewardCache.clear();
        return registry.load();
    }

    public EnchantRegistry registry() {
        return registry;
    }

    public int levelOf(final PlayerProfile profile, final String enchantId) {
        return profile.enchantLevel(enchantId);
    }

    /**
     * Buys the next level of {@code enchant}. Returns true when the
     * level was granted; every denial is messaged to the player.
     */
    public boolean purchase(final Player player, final PlayerProfile profile, final Enchant enchant) {
        if (!enchant.universal() && !enchant.role().equals(profile.roleId())) {
            plugin.messages().sendPrefixed(player, "enchant.wrong-role",
                    Map.of("role", roleLabel(enchant.role())));
            return false;
        }
        if (enchant.universal() && !plugin.roles().hasRole(profile)) {
            plugin.messages().sendPrefixed(player, "enchant.no-role", Map.of());
            return false;
        }
        if (!levelGateMet(profile, enchant)) {
            plugin.messages().sendPrefixed(player, "enchant.locked", Map.of(
                    "role", roleLabel(enchant.universal() ? profile.roleId() : enchant.role()),
                    "level", String.valueOf(enchant.minRoleLevel())));
            return false;
        }
        final int current = profile.enchantLevel(enchant.id());
        if (current >= enchant.maxLevel()) {
            plugin.messages().sendPrefixed(player, "enchant.maxed",
                    Map.of("name", ColorUtil.colorize(enchant.display())));
            return false;
        }
        final long cost = enchant.costForLevel(current + 1);
        final String costText = String.format(Locale.ROOT, "%,d", cost);
        if (cost > 0L && !plugin.economy().withdraw(profile, enchant.currency(), cost)) {
            plugin.messages().sendPrefixed(player, "enchant.insufficient", Map.of(
                    "name", ColorUtil.colorize(enchant.display()),
                    "level", String.valueOf(current + 1),
                    "cost", costText,
                    "currency", currencyName(enchant.currency())));
            return false;
        }
        profile.setEnchantLevel(enchant.id(), current + 1);
        plugin.playerData().persistImportant(profile); // permanent paid progression — write through
        plugin.omniTool().refreshHeldTools(player, profile);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.2f);
        plugin.messages().sendPrefixed(player, "enchant.bought", Map.of(
                "name", ColorUtil.colorize(enchant.display()),
                "level", (current + 1) + "/" + enchant.maxLevel(),
                "cost", costText,
                "currency", currencyName(enchant.currency())));
        return true;
    }

    /** Whether the player meets the enchant's minimum role level. */
    public boolean levelGateMet(final PlayerProfile profile, final Enchant enchant) {
        if (enchant.minRoleLevel() <= 1) {
            return true;
        }
        final String key = enchant.universal() ? profile.roleId() : enchant.role();
        final Role role = Role.byKey(key).orElse(null);
        if (role == null) {
            return false;
        }
        return plugin.roles().roleView(profile, role).level() >= enchant.minRoleLevel();
    }

    /** GUI lore for one enchant at the player's current level (spec §11 format). */
    public List<String> lore(final PlayerProfile profile, final Enchant enchant) {
        final int level = profile.enchantLevel(enchant.id());
        final List<String> lore = new ArrayList<>();
        lore.add(enchant.description());
        lore.add("");
        lore.add("&7Your level: &b" + level + "&7/&b" + enchant.maxLevel());
        if (level >= enchant.maxLevel()) {
            lore.add("&a&lMAXED OUT");
        } else if (!levelGateMet(profile, enchant)) {
            lore.add("&cRequires " + roleLabel(enchant.universal() ? profile.roleId() : enchant.role())
                    + " level " + enchant.minRoleLevel() + ".");
        } else {
            final long cost = enchant.costForLevel(level + 1);
            lore.add("&7Next level: &f" + (level + 1) + " &8(→ "
                    + String.format(Locale.ROOT, "%,d", cost) + " " + currencyName(enchant.currency()) + "&8)");
            lore.add("&8Chance " + percent(enchant.chanceAt(level)) + " → "
                    + percent(enchant.chanceAt(level + 1)) + " &8| value "
                    + trim(enchant.valueAt(level)) + " → " + trim(enchant.valueAt(level + 1)));
        }
        lore.add("");
        if (level >= enchant.maxLevel()) {
            lore.add("&8This enchant is fully maxed.");
        } else if (!levelGateMet(profile, enchant)) {
            lore.add("&cLocked.");
        } else {
            lore.add("&eLeft-click: &7Upgrade to " + (level + 1) + ".");
            lore.add("&8Right-click: &7View details.");
        }
        return lore;
    }

    /** Right-click details: full stat block in chat. */
    public void sendDetails(final Player player, final PlayerProfile profile, final Enchant enchant) {
        final int level = profile.enchantLevel(enchant.id());
        player.sendMessage(ColorUtil.colorize("&8&m──────── &r "
                + enchant.display() + " &8&m────────"));
        player.sendMessage(ColorUtil.colorize(enchant.description()));
        player.sendMessage(ColorUtil.colorize("&7Track: &f" + roleLabel(enchant.role())
                + " &8| &7Effect: &f" + enchant.effect() + " &8| &7Trigger: &f" + enchant.trigger()));
        player.sendMessage(ColorUtil.colorize("&7Level: &b" + level + "&7/&b" + enchant.maxLevel()
                + " &8| &7Chance: &f" + percent(enchant.chanceAt(Math.max(level, 1)))
                + " &8| &7Value: &f" + trim(enchant.valueAt(Math.max(level, 1)))));
        if (enchant.cooldownSeconds() > 0L) {
            player.sendMessage(ColorUtil.colorize(
                    "&7Cooldown: &f" + enchant.cooldownSeconds() + "s"));
        }
        if (enchant.minRoleLevel() > 1) {
            player.sendMessage(ColorUtil.colorize("&7Requires role level: &f" + enchant.minRoleLevel()));
        }
        if (level < enchant.maxLevel()) {
            player.sendMessage(ColorUtil.colorize("&7Next level cost: &f"
                    + String.format(Locale.ROOT, "%,d", enchant.costForLevel(level + 1))
                    + " " + currencyName(enchant.currency())));
        }
    }

    /** Owned (level > 0) enchants of one track, in catalogue order. */
    public List<Enchant> ownedOf(final PlayerProfile profile, final String roleKey) {
        final List<Enchant> owned = new ArrayList<>();
        for (final Enchant enchant : registry.forRole(roleKey)) {
            if (profile.enchantLevel(enchant.id()) > 0) {
                owned.add(enchant);
            }
        }
        return owned;
    }

    /** Total owned enchant levels across every track. */
    public int ownedLevels(final PlayerProfile profile) {
        int total = 0;
        for (final int level : profile.enchantLevels().values()) {
            total += level;
        }
        return total;
    }

    /** One MULTIPLIER enchant plus the player's level in it. */
    public record EnchantLevel(Enchant enchant, int level) {
    }

    /**
     * Owned MULTIPLIER enchants (role track + universal) whose target
     * matches — the engine's single query point for passive boosts.
     */
    public List<EnchantLevel> multipliersFor(
            final PlayerProfile profile, final String roleKey, final String target) {
        final List<EnchantLevel> result = new ArrayList<>();
        collectMultipliers(profile, roleKey, target, result);
        if (!"universal".equals(roleKey)) {
            collectMultipliers(profile, "universal", target, result);
        }
        return result;
    }

    private void collectMultipliers(
            final PlayerProfile profile,
            final String roleKey,
            final String target,
            final List<EnchantLevel> result) {
        for (final Enchant enchant : registry.forRole(roleKey)) {
            if (enchant.effect() != EnchantEffect.MULTIPLIER) {
                continue;
            }
            final int level = profile.enchantLevel(enchant.id());
            if (level <= 0) {
                continue;
            }
            final String enchantTarget =
                    String.valueOf(enchant.values().getOrDefault("target", "XP")).trim();
            if (enchantTarget.equalsIgnoreCase(target)
                    || "ECONOMY".equalsIgnoreCase(enchantTarget)
                            && ("TOKENS".equalsIgnoreCase(target) || "CREDITS".equalsIgnoreCase(target))) {
                result.add(new EnchantLevel(enchant, level));
            }
        }
    }

    /**
     * Passive 1.0-based multiplier for {@code target}
     * (XP|TOKENS|CREDITS|SOULS): 1.0 when nothing applies.
     */
    public double passiveMultiplier(
            final PlayerProfile profile, final String roleKey, final String target) {
        double percent = 0.0;
        for (final EnchantLevel owned : multipliersFor(profile, roleKey, target)) {
            percent += owned.enchant().valueAt(owned.level());
        }
        return 1.0 + percent / 100.0;
    }

    /** Parsed reward table for {@code enchant} (cached, validated at load). */
    public List<RewardRoll> rewardTable(final Enchant enchant) {
        return rewardCache.computeIfAbsent(enchant.id(), ignored -> {
            final List<RewardRoll> table = new ArrayList<>();
            final Object rewards = enchant.values().get("rewards");
            if (rewards instanceof List<?> list) {
                final List<String> errors = new ArrayList<>();
                for (final Object entry : list) {
                    RewardRoll.parse(entry, errors, enchant.id() + ": rewards").ifPresent(table::add);
                }
            }
            return List.copyOf(table);
        });
    }

    /** Human track label for GUI/messages. */
    public String roleLabel(final String roleKey) {
        return Role.byKey(roleKey).map(Role::display).orElse(roleKey);
    }

    /** Human currency name (the enum's canonical display name). */
    public static String currencyName(final Currency currency) {
        return currency.displayName();
    }

    private static String percent(final double fraction) {
        if (fraction >= 1.0) {
            return "always";
        }
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    private static String trim(final double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", ".0");
    }
}
