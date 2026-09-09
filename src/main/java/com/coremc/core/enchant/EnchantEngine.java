package com.coremc.core.enchant;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.economy.Currency;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Shared core of the custom-enchant behaviour system: transient
 * stores (cooldowns, combo stacks, temporary boosts), the recursion
 * guard, protection checks for bonus-block breaking, every grant
 * primitive (currency, souls, rewards, drops, potions), and the
 * pipeline steps shared by all five activity handlers.
 *
 * The per-activity handlers ({@code MiningEnchantHandler} and
 * siblings — never one giant listener) own their trigger pipelines
 * and call into this engine so bonus math has exactly one code path:
 * nothing is ever double-applied.
 *
 * Combo timing note: stacks are recorded by the activity handlers,
 * which run at HIGH — after some XP listeners may already have
 * awarded for the same action. A stack therefore boosts the NEXT
 * chained action, never the current one. Chains are long; the
 * off-by-one is gameplay-invisible and keeps every listener
 * protection-safe (all proc AFTER deny-plugins cancel).
 *
 * All stores are bounded with opportunistic sweeps and cleared on
 * quit — nothing player-scoped leaks.
 */
public final class EnchantEngine implements Listener {

    /** Materials bonus-breaking never touches, whatever the config says. */
    private static final Set<Material> NEVER_BREAK = EnumSet.of(
            Material.BEDROCK,
            Material.BARRIER,
            Material.END_PORTAL,
            Material.END_PORTAL_FRAME,
            Material.END_GATEWAY,
            Material.NETHER_PORTAL,
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK,
            Material.JIGSAW,
            Material.SPAWNER);

    private static final int STORE_SWEEP_THRESHOLD = 64;
    private static final double MAX_DAMAGE_MULT = 10.0;

    private final CoreMCPlugin plugin;
    private final Random random = new Random();
    private final ThreadLocal<Boolean> guarded = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, ComboState>> combos = new ConcurrentHashMap<>();
    private final Map<UUID, TempBoost> tempBoosts = new ConcurrentHashMap<>();
    /** Misconfigured names already warned about (warn-once, no log spam). */
    private final Set<String> badNames = ConcurrentHashMap.newKeySet();

    private record ComboState(int stacks, long expiresAt) {
    }

    private record TempBoost(double xpPct, double dropsPct, long expiresAt) {
    }

    /** Pipeline context for the shared drop stage. */
    public record DropContext(
            Player player,
            PlayerProfile profile,
            String roleKey,
            Block block,
            ItemStack tool,
            List<EnchantService.EnchantLevel> active,
            Location dropAt) {
    }

    /**
     * @param plugin the plugin ({@code null} only in unit tests, which
     *               exercise the transient math without granting anything)
     */
    public EnchantEngine(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** Quit hook: transient stores never outlive the session. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        clearPlayer(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ guard

    /** True while inside a bonus-block event fired by this engine (handlers must skip). */
    public boolean guarded() {
        return guarded.get();
    }

    /** Runs {@code work} with the recursion guard set. */
    public void runGuarded(final Runnable work) {
        guarded.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            guarded.set(Boolean.FALSE);
        }
    }

    /** Calls {@code work} with the recursion guard set. */
    public <T> T callGuarded(final Supplier<T> work) {
        guarded.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            guarded.set(Boolean.FALSE);
        }
    }

    // ------------------------------------------------------------------ queries

    /**
     * Owned, trigger-matching enchants for an activity: the player's
     * role track entries with {@code trigger}, plus universal ANY
     * entries. Role track first, catalogue order preserved.
     */
    public List<EnchantService.EnchantLevel> activeFor(
            final PlayerProfile profile, final String roleKey, final EnchantEffect.Trigger trigger) {
        final List<EnchantService.EnchantLevel> result = new ArrayList<>();
        collectActive(profile, roleKey, trigger, false, result);
        if (!"universal".equals(roleKey)) {
            collectActive(profile, "universal", trigger, true, result);
        }
        return result;
    }

    private void collectActive(
            final PlayerProfile profile,
            final String track,
            final EnchantEffect.Trigger trigger,
            final boolean universal,
            final List<EnchantService.EnchantLevel> result) {
        for (final Enchant enchant : plugin.enchants().registry().forRole(track)) {
            if (universal && enchant.trigger() != EnchantEffect.Trigger.ANY) {
                continue;
            }
            if (!universal && enchant.trigger() != trigger) {
                continue;
            }
            final int level = profile.enchantLevel(enchant.id());
            if (level > 0) {
                result.add(new EnchantService.EnchantLevel(enchant, level));
            }
        }
    }

    /** Chance roll: 1+ always, 0- never, else uniform. */
    public boolean roll(final double chance) {
        if (chance >= 1.0) {
            return true;
        }
        if (chance <= 0.0) {
            return false;
        }
        return random.nextDouble() < chance;
    }

    public Random random() {
        return random;
    }

    // ------------------------------------------------------------------ cooldowns

    /** Whether {@code enchant}'s cooldown has expired for the player. */
    public boolean cooldownReady(final UUID player, final Enchant enchant) {
        if (enchant.cooldownSeconds() <= 0L) {
            return true;
        }
        final Map<String, Long> playerCooldowns = cooldowns.get(player);
        if (playerCooldowns == null) {
            return true;
        }
        final Long last = playerCooldowns.get(enchant.id());
        return last == null || System.currentTimeMillis() - last >= enchant.cooldownSeconds() * 1000L;
    }

    /** Starts {@code enchant}'s cooldown for the player (call when the effect FIRES). */
    public void markCooldown(final UUID player, final String enchantId) {
        final Map<String, Long> playerCooldowns =
                cooldowns.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>());
        playerCooldowns.put(enchantId, System.currentTimeMillis());
        if (playerCooldowns.size() > STORE_SWEEP_THRESHOLD) {
            final long now = System.currentTimeMillis();
            playerCooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 3_600_000L);
        }
    }

    // ------------------------------------------------------------------ combos

    /**
     * Records one chained action, returning the new stack count
     * (1-based, capped at {@code maxStacks}; expired chains restart).
     */
    public int recordCombo(final UUID player, final String enchantId, final long windowSeconds,
            final int maxStacks) {
        final long now = System.currentTimeMillis();
        final Map<String, ComboState> playerCombos =
                combos.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>());
        final ComboState current = playerCombos.get(enchantId);
        final int stacks = current == null || now >= current.expiresAt()
                ? 1
                : Math.min(maxStacks, current.stacks() + 1);
        playerCombos.put(enchantId, new ComboState(stacks, now + windowSeconds * 1000L));
        if (playerCombos.size() > STORE_SWEEP_THRESHOLD) {
            playerCombos.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
        }
        return stacks;
    }

    /** Current live stacks (0 when expired or never started). */
    public int comboStacks(final UUID player, final String enchantId, final long windowSeconds) {
        final Map<String, ComboState> playerCombos = combos.get(player);
        if (playerCombos == null) {
            return 0;
        }
        final ComboState current = playerCombos.get(enchantId);
        if (current == null || System.currentTimeMillis() >= current.expiresAt()) {
            return 0;
        }
        return current.stacks();
    }

    /**
     * 1.0-based combo multiplier for {@code target}
     * (XP|DROPS|DAMAGE|CURRENCY|SOULS): every owned role + universal
     * COMBO enchant whose target matches contributes
     * stacks × valueAt(level) percent points. REWARDS-target combos
     * match DROPS and CURRENCY queries.
     */
    public double comboMultiplier(final PlayerProfile profile, final String roleKey, final String target) {
        double percent = comboPercent(profile, roleKey, target);
        if (!"universal".equals(roleKey)) {
            percent += comboPercent(profile, "universal", target);
        }
        return 1.0 + percent / 100.0;
    }

    private double comboPercent(final PlayerProfile profile, final String roleKey, final String target) {
        double percent = 0.0;
        for (final Enchant enchant : plugin.enchants().registry().forRole(roleKey)) {
            if (enchant.effect() != EnchantEffect.COMBO) {
                continue;
            }
            final int level = profile.enchantLevel(enchant.id());
            if (level <= 0) {
                continue;
            }
            final String enchantTarget =
                    stringValue(enchant.values(), "target", "XP");
            final boolean rewardsMatch = "REWARDS".equalsIgnoreCase(enchantTarget)
                    && ("DROPS".equalsIgnoreCase(target) || "CURRENCY".equalsIgnoreCase(target));
            if (!enchantTarget.equalsIgnoreCase(target) && !rewardsMatch) {
                continue;
            }
            final int stacks = comboStacks(
                    profile.uuid(), enchant.id(), longValue(enchant.values(), "window-seconds", 5L));
            percent += stacks * enchant.valueAt(level);
        }
        return percent;
    }

    /** Highest live combo stack count across all of the player's combos (TEMP_BOOST trigger). */
    public int highestComboStacks(final PlayerProfile profile, final String roleKey) {
        int best = highestInTrack(profile, roleKey);
        if (!"universal".equals(roleKey)) {
            best = Math.max(best, highestInTrack(profile, "universal"));
        }
        return best;
    }

    private int highestInTrack(final PlayerProfile profile, final String roleKey) {
        int best = 0;
        for (final Enchant enchant : plugin.enchants().registry().forRole(roleKey)) {
            if (enchant.effect() != EnchantEffect.COMBO || profile.enchantLevel(enchant.id()) <= 0) {
                continue;
            }
            best = Math.max(best, comboStacks(
                    profile.uuid(), enchant.id(), longValue(enchant.values(), "window-seconds", 5L)));
        }
        return best;
    }

    // ------------------------------------------------------------------ temporary boosts

    /** Starts a temporary boost (TEMP_BOOST / OVERDRIVE ultimates). */
    public void setTempBoost(final UUID player, final double xpPct, final double dropsPct,
            final long durationMillis) {
        tempBoosts.put(player, new TempBoost(xpPct, dropsPct, System.currentTimeMillis() + durationMillis));
    }

    /** Live temporary XP percent (0 when expired). */
    public double tempXpPct(final UUID player) {
        return tempBoost(player).xpPct();
    }

    /** Live temporary drops percent (0 when expired). */
    public double tempDropsPct(final UUID player) {
        return tempBoost(player).dropsPct();
    }

    private TempBoost tempBoost(final UUID player) {
        final TempBoost boost = tempBoosts.get(player);
        if (boost == null || System.currentTimeMillis() >= boost.expiresAt()) {
            if (boost != null) {
                tempBoosts.remove(player);
            }
            return new TempBoost(0.0, 0.0, 0L);
        }
        return boost;
    }

    /** Clears every transient entry for a quitting player. */
    public void clearPlayer(final UUID player) {
        cooldowns.remove(player);
        combos.remove(player);
        tempBoosts.remove(player);
    }

    // ------------------------------------------------------------------ grants

    /**
     * Grants {@code base} currency with passive + (optionally) combo
     * multipliers applied exactly once. Returns the final amount.
     * Persistence rides the economy service (premium write-through).
     */
    public long grantCurrency(
            final Player player,
            final PlayerProfile profile,
            final Currency currency,
            final long base,
            final String roleKey,
            final boolean applyCombo) {
        if (base <= 0L) {
            return 0L;
        }
        final String target = switch (currency) {
            case SKY_TOKENS -> "TOKENS";
            case CREDITS -> "CREDITS";
            case MONEY -> "MONEY";
        };
        double mult = plugin.enchants().passiveMultiplier(profile, roleKey, target);
        if (applyCombo) {
            mult *= comboMultiplier(profile, roleKey, "CURRENCY");
        }
        final long total = Math.max(1L, Math.round(base * mult));
        try {
            plugin.economy().deposit(profile, currency, total);
        } catch (final IllegalArgumentException overflow) {
            plugin.getLogger().fine("Currency grant overflow for " + player.getName() + ": " + total);
            return 0L;
        }
        return total;
    }

    /** Grants {@code base} souls with passive + combo multipliers. Returns the final amount. */
    public long grantSouls(final Player player, final PlayerProfile profile, final long base,
            final String roleKey) {
        if (base <= 0L) {
            return 0L;
        }
        final double mult = plugin.enchants().passiveMultiplier(profile, roleKey, "SOULS")
                * comboMultiplier(profile, roleKey, "SOULS");
        final long total = Math.max(1L, Math.round(base * mult));
        profile.souls(profile.souls() > Long.MAX_VALUE - total ? Long.MAX_VALUE : profile.souls() + total);
        plugin.playerData().markDirty(player.getUniqueId());
        return total;
    }

    /**
     * Rolls {@code enchant}'s reward table once: every entry rolls
     * independently and all hits are granted. ITEM → inventory
     * (overflow drops at feet, never voided); TOKENS/CREDITS → the
     * boosted currency path; XP → the standard XP funnel (boosts
     * apply); KEY → key items + receipt message; SOULS → soul path.
     */
    public void grantRewards(
            final Player player,
            final PlayerProfile profile,
            final Enchant enchant,
            final String roleKey,
            final RoleCategory xpCategory) {
        for (final RewardRoll roll : plugin.enchants().rewardTable(enchant)) {
            if (!roll(roll.chance())) {
                continue;
            }
            final int amount = roll.rollAmount(random);
            switch (roll.type()) {
                case ITEM -> {
                    final Material material = resolveMaterial(roll.material(), null);
                    if (material == null) {
                        warnOnce(enchant.id() + ":material:" + roll.material(),
                                "Unknown reward material '" + roll.material() + "' — skipped.");
                        continue;
                    }
                    giveOrDrop(player, new ItemStack(material, amount));
                }
                case TOKENS -> grantCurrency(
                        player, profile, Currency.SKY_TOKENS, amount, roleKey, true);
                case CREDITS ->
                    grantCurrency(player, profile, Currency.CREDITS, amount, roleKey, true);
                case XP -> plugin.roles().awardCategoryXp(player, profile, xpCategory, amount);
                case KEY -> {
                    plugin.keys().giveKeys(player, roll.keyId(), amount);
                    final String name = plugin.keys().key(roll.keyId())
                            .map(key -> ColorUtil.colorize(key.display()))
                            .orElse(roll.keyId());
                    plugin.messages().sendPrefixed(player, "key.received",
                            Map.of("amount", String.valueOf(amount), "name", name));
                }
                case SOULS -> grantSouls(player, profile, amount, roleKey);
            }
        }
    }

    /** Inventory-first item grant; overflow drops at the player's feet (never voided). */
    public void giveOrDrop(final Player player, final ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        player.getInventory().addItem(stack).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }

    /**
     * Magnet collection: every stack goes to the inventory; leftovers
     * drop at {@code dropAt} unless {@code fallback} is DESPAWN.
     */
    public void collectOrDrop(final Player player, final List<ItemStack> drops, final String fallback,
            final Location dropAt) {
        for (final ItemStack stack : drops) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            if ("DESPAWN".equalsIgnoreCase(fallback)) {
                continue;
            }
            leftover.values().forEach(rest -> player.getWorld().dropItemNaturally(dropAt, rest));
        }
    }

    // ------------------------------------------------------------------ shared pipeline steps

    /** Records one combo stack per owned COMBO enchant in scope. */
    public void recordCombos(final Player player, final PlayerProfile profile,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.COMBO) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(player.getUniqueId(), enchant.id());
            recordCombo(player.getUniqueId(), enchant.id(),
                    longValue(enchant.values(), "window-seconds", 5L),
                    (int) longValue(enchant.values(), "max-stacks", 20L));
        }
    }

    /** Fires TEMP_BOOST enchants whose combo threshold is currently met. */
    public void triggerTempBoosts(final Player player, final PlayerProfile profile, final String roleKey,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.TEMP_BOOST) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            final int need = (int) longValue(enchant.values(), "trigger-count", 10L);
            if (highestComboStacks(profile, roleKey) < need) {
                continue;
            }
            markCooldown(player.getUniqueId(), enchant.id());
            final long seconds = longValue(enchant.values(), "duration-seconds", 15L);
            setTempBoost(player.getUniqueId(),
                    doubleValue(enchant.values(), "mult-xp", 0.0),
                    doubleValue(enchant.values(), "mult-drops", 0.0),
                    seconds * 1000L);
            for (final String spec : stringList(enchant.values(), "effects")) {
                final PotionEffect effect = parseEffect(spec);
                if (effect != null) {
                    player.addPotionEffect(effect, true);
                }
            }
            plugin.messages().sendPrefixed(player, "enchant.overcharge",
                    Map.of("name", ColorUtil.colorize(enchant.display()),
                            "seconds", String.valueOf(seconds)));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        }
    }

    /** Applies the strongest owned HASTE in scope (haste sources never stack). */
    public void applyHaste(final Player player, final List<EnchantService.EnchantLevel> active) {
        int bestAmp = -1;
        int bestTicks = 0;
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.HASTE) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(player.getUniqueId(), enchant.id());
            bestAmp = Math.max(bestAmp, (int) Math.floor(enchant.valueAt(owned.level())));
            bestTicks = Math.max(bestTicks,
                    (int) longValue(enchant.values(), "duration-seconds", 6L) * 20);
        }
        if (bestAmp >= 0) {
            applyPotion(player, PotionEffectType.HASTE, bestAmp, bestTicks);
        }
    }

    /** Fires CURRENCY enchants (grant currency = values.currency, else purchase currency). */
    public void grantCurrencies(final Player player, final PlayerProfile profile, final String roleKey,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.CURRENCY) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(player.getUniqueId(), enchant.id());
            final Currency currency = currencyValue(enchant.values(), "currency", enchant.currency());
            final long base = Math.max(1L, (long) Math.floor(enchant.valueAt(owned.level())));
            grantCurrency(player, profile, currency, base, roleKey, true);
        }
    }

    /** Rolls REWARD_TABLE enchants (plus TREASURE-mode FISH_BONUS) in scope. */
    public void grantTables(final Player player, final PlayerProfile profile, final String roleKey,
            final List<EnchantService.EnchantLevel> active, final RoleCategory xpCategory) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            final boolean table = enchant.effect() == EnchantEffect.REWARD_TABLE
                    || enchant.effect() == EnchantEffect.FISH_BONUS
                            && "TREASURE".equalsIgnoreCase(stringValue(enchant.values(), "mode", ""));
            if (!table) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(player.getUniqueId(), enchant.id());
            grantRewards(player, profile, enchant, roleKey, xpCategory);
        }
    }

    /** Applies RECOVERY enchants (heal + feed + extinguish). */
    public void applyRecovery(final Player player, final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.RECOVERY) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(player.getUniqueId(), enchant.id());
            final double hearts = doubleValue(enchant.values(), "hearts", 0.0);
            if (hearts > 0.0) {
                player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + hearts));
            }
            final long food = longValue(enchant.values(), "food", 0L);
            if (food > 0L) {
                player.setFoodLevel(Math.min(20, player.getFoodLevel() + (int) Math.min(food, 20L)));
            }
            if (boolValue(enchant.values(), "extinguish", false)) {
                player.setFireTicks(0);
            }
        }
    }

    /** Fires OVERDRIVE ultimates (the ANY-trigger ultimate, checked by every pipeline). */
    public void fireOverdrive(final Player player, final PlayerProfile profile,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.ULTIMATE
                    || !"OVERDRIVE".equalsIgnoreCase(stringValue(enchant.values(), "mode", ""))) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(player.getUniqueId(), enchant.id());
            final long seconds = longValue(enchant.values(), "duration-seconds", 30L);
            setTempBoost(player.getUniqueId(),
                    doubleValue(enchant.values(), "mult-xp", 0.0),
                    doubleValue(enchant.values(), "mult-drops", 0.0),
                    seconds * 1000L);
            for (final String spec : stringList(enchant.values(), "effects")) {
                final PotionEffect effect = parseEffect(spec);
                if (effect != null) {
                    player.addPotionEffect(effect, true);
                }
            }
            ultimateFx(player, enchant);
        }
    }

    // ------------------------------------------------------------------ shared drop stage

    /**
     * Shared drop stage for block pipelines (mine/log/farm): smelt
     * conversions first, then bonus rolls (DROP_BONUS +
     * MULTIPLIER/DROPS), then magnet collection or natural drops.
     * Returns true when vanilla drops must be suppressed (a drop
     * enchant claimed them); false leaves vanilla behaviour intact.
     */
    public boolean processDrops(final DropContext ctx) {
        boolean smelt = false;
        boolean bonus = false;
        boolean magnet = false;
        for (final EnchantService.EnchantLevel owned : ctx.active()) {
            switch (owned.enchant().effect()) {
                case SMELT -> smelt = true;
                case DROP_BONUS -> bonus = true;
                case MAGNET -> magnet = true;
                default -> {
                }
            }
        }
        if (!plugin.enchants().multipliersFor(ctx.profile(), ctx.roleKey(), "DROPS").isEmpty()) {
            bonus = true;
        }
        if (!smelt && !bonus && !magnet) {
            return false;
        }
        List<ItemStack> drops = new ArrayList<>(ctx.block().getDrops(ctx.tool()));
        if (smelt) {
            drops = applySmelt(ctx, drops);
        }
        if (bonus) {
            final List<ItemStack> extras = new ArrayList<>();
            collectBlockBonus(ctx, drops, extras);
            drops.addAll(extras);
        }
        final String fallback = magnet ? magnetFallback(ctx) : null;
        if (fallback != null) {
            collectOrDrop(ctx.player(), drops, fallback, ctx.dropAt());
        } else {
            for (final ItemStack stack : drops) {
                if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                    ctx.block().getWorld().dropItemNaturally(ctx.dropAt(), stack);
                }
            }
        }
        return true;
    }

    private List<ItemStack> applySmelt(final DropContext ctx, final List<ItemStack> drops) {
        List<ItemStack> current = drops;
        for (final EnchantService.EnchantLevel owned : ctx.active()) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.SMELT) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(ctx.player().getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(ctx.player().getUniqueId(), enchant.id());
            final List<ItemStack> converted = new ArrayList<>(current.size());
            for (final ItemStack stack : current) {
                final Material out = EnchantBlocks.smeltResult(stack.getType(), enchant.values(), this);
                converted.add(out == null ? stack : new ItemStack(out, stack.getAmount()));
            }
            current = converted;
        }
        return current;
    }

    /** Bonus-drop rolls for a block pipeline (DROP_BONUS + MULT/DROPS with depth gates). */
    public void collectBlockBonus(final DropContext ctx, final List<ItemStack> base,
            final List<ItemStack> extras) {
        if (base.isEmpty()) {
            return;
        }
        final double mult = comboMultiplier(ctx.profile(), ctx.roleKey(), "DROPS")
                * (1.0 + tempDropsPct(ctx.player().getUniqueId()) / 100.0);
        for (final EnchantService.EnchantLevel owned : ctx.active()) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.DROP_BONUS) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(ctx.player().getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(ctx.player().getUniqueId(), enchant.id());
            final int amount =
                    Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()) * mult));
            dupeFiltered(base, amount, stringList(enchant.values(), "only-materials"), extras);
        }
        for (final EnchantService.EnchantLevel owned :
                plugin.enchants().multipliersFor(ctx.profile(), ctx.roleKey(), "DROPS")) {
            final Enchant enchant = owned.enchant();
            final long maxY = longValue(enchant.values(), "max-y", Long.MIN_VALUE);
            if (maxY != Long.MIN_VALUE && ctx.block().getY() > maxY) {
                continue; // depth gate (deep-miner)
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(ctx.player().getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(ctx.player().getUniqueId(), enchant.id());
            final int amount =
                    Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()) * mult));
            dupeFiltered(base, amount, stringList(enchant.values(), "only-materials"), extras);
        }
    }

    /** Rolls every MAGNET in scope; returns the winning fallback, or null when none proc. */
    public String magnetFallback(final DropContext ctx) {
        String fallback = null;
        for (final EnchantService.EnchantLevel owned : ctx.active()) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.MAGNET) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(ctx.player().getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(ctx.player().getUniqueId(), enchant.id());
            fallback = stringValue(enchant.values(), "fallback", "DROP");
        }
        return fallback;
    }

    /**
     * Bonus-drop rolls for the kill pipeline (DROP_BONUS +
     * MULT/DROPS; depth-gated multipliers need a block and skip).
     */
    public void collectKillBonus(
            final Player player,
            final PlayerProfile profile,
            final String roleKey,
            final List<EnchantService.EnchantLevel> active,
            final List<ItemStack> deathDrops) {
        if (deathDrops.isEmpty()) {
            return;
        }
        final double mult = comboMultiplier(profile, roleKey, "DROPS")
                * (1.0 + tempDropsPct(player.getUniqueId()) / 100.0);
        final List<ItemStack> extras = new ArrayList<>();
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.DROP_BONUS) {
                continue;
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(player.getUniqueId(), enchant.id());
            dupeFiltered(deathDrops,
                    Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()) * mult)),
                    stringList(enchant.values(), "only-materials"), extras);
        }
        for (final EnchantService.EnchantLevel owned :
                plugin.enchants().multipliersFor(profile, roleKey, "DROPS")) {
            final Enchant enchant = owned.enchant();
            if (enchant.values().containsKey("max-y")) {
                continue; // depth gates need a broken block — kills skip them
            }
            if (!roll(enchant.chanceAt(owned.level())) || !cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            markCooldown(player.getUniqueId(), enchant.id());
            dupeFiltered(deathDrops,
                    Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()) * mult)),
                    stringList(enchant.values(), "only-materials"), extras);
        }
        deathDrops.addAll(extras);
    }

    private void dupeFiltered(final List<ItemStack> base, final int copies, final List<String> only,
            final List<ItemStack> extras) {
        for (final ItemStack stack : base) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            if (!only.isEmpty() && !only.contains(stack.getType().name())) {
                continue;
            }
            for (int copy = 0; copy < copies; copy++) {
                extras.add(stack.clone());
            }
        }
    }

    // ------------------------------------------------------------------ bonus-block breaking

    /**
     * Whether a bonus effect (AoE/vein/ultimate) may break
     * {@code block} for {@code player}: never air/liquid, never the
     * hard blacklist, never containers (unless the enchant opts in),
     * never registered placeables (generator/spawner cores), never
     * inside an island the player doesn't belong to. Wilderness and
     * own-island blocks are fair game.
     */
    public boolean canBreakExtra(final Player player, final Block block, final boolean breakContainers) {
        final Material type = block.getType();
        if (type.isAir() || type == Material.WATER || type == Material.LAVA
                || type == Material.BUBBLE_COLUMN || NEVER_BREAK.contains(type)) {
            return false;
        }
        if (block.getY() < block.getWorld().getMinHeight() || block.getY() > block.getWorld().getMaxHeight()) {
            return false;
        }
        if (!breakContainers && block.getState() instanceof Container) {
            return false;
        }
        if (plugin.placeables().at(block.getLocation()).isPresent()) {
            return false; // generator/spawner cores and their kin are untouchable
        }
        final var island = plugin.islands()
                .islandAt(block.getWorld().getName(), block.getX(), block.getZ())
                .orElse(null);
        return island == null || island.roleOf(player.getUniqueId()) != null;
    }

    /**
     * Guarded bonus break: fires a real BlockBreakEvent (so deny
     * plugins and protection can still cancel) with our own handlers
     * skipped, then breaks naturally with {@code tool} when allowed.
     * Bonus blocks DO award role XP via the normal XP listeners —
     * they are genuinely mined blocks, bounded by per-enchant caps,
     * chances and cooldowns.
     */
    public boolean breakExtra(final Player player, final Block block, final ItemStack tool,
            final boolean breakContainers) {
        if (!canBreakExtra(player, block, breakContainers)) {
            return false;
        }
        final BlockBreakEvent extra = new BlockBreakEvent(block, player);
        final boolean allowed = callGuarded(() -> {
            Bukkit.getPluginManager().callEvent(extra);
            return !extra.isCancelled();
        });
        if (!allowed) {
            return false;
        }
        block.breakNaturally(tool);
        return true;
    }

    // ------------------------------------------------------------------ combat

    /** Caps a stacked damage multiplier (runaway one-shots stay impossible). */
    public static double capDamageMult(final double mult) {
        return Math.min(MAX_DAMAGE_MULT, Math.max(0.0, mult));
    }

    // ------------------------------------------------------------------ fx

    /** Branded ultimate fanfare: message + sound + particles. */
    public void ultimateFx(final Player player, final Enchant enchant) {
        plugin.messages().sendPrefixed(player, "enchant.ultimate",
                Map.of("name", ColorUtil.colorize(enchant.display())));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f);
        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                player.getLocation().add(0.0, 1.0, 0.0), 30, 0.5, 0.5, 0.5, 0.1);
    }

    /** Applies a potion effect, refreshing any weaker running instance. */
    public void applyPotion(final Player player, final PotionEffectType type, final int amplifier,
            final int durationTicks) {
        if (type == null) {
            return;
        }
        player.addPotionEffect(
                new PotionEffect(type, Math.max(20, durationTicks), Math.min(9, Math.max(0, amplifier)),
                        false, false, true),
                true);
    }

    /** Parses a {@code "TYPE:amplifier:seconds"} effect spec; null when malformed (warn-once). */
    @SuppressWarnings("deprecation")
    public PotionEffect parseEffect(final String spec) {
        if (spec == null) {
            return null;
        }
        final String[] parts = spec.trim().split(":", 3);
        if (parts.length != 3) {
            warnOnce("effect:" + spec, "Bad effect spec '" + spec + "' (want TYPE:amplifier:seconds).");
            return null;
        }
        final PotionEffectType type;
        try {
            type = PotionEffectType.getByName(parts[0].trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            warnOnce("effect:" + spec, "Unknown potion '" + parts[0].trim() + "'.");
            return null;
        }
        if (type == null) {
            warnOnce("effect:" + spec, "Unknown potion '" + parts[0].trim() + "'.");
            return null;
        }
        try {
            final int amplifier = Math.max(0, Integer.parseInt(parts[1].trim()));
            final int seconds = Math.max(1, Integer.parseInt(parts[2].trim()));
            return new PotionEffect(type, seconds * 20, Math.min(9, amplifier), false, false, true);
        } catch (final NumberFormatException bad) {
            warnOnce("effect:" + spec, "Bad effect numbers in '" + spec + "'.");
            return null;
        }
    }

    /** Resolves a material name with warn-once fallback. */
    public Material resolveMaterial(final String name, final Material fallback) {
        if (name == null) {
            return fallback;
        }
        final Material material = Material.matchMaterial(name.trim());
        if (material == null || material.isAir()) {
            warnOnce("material:" + name, "Unknown material '" + name + "' — using fallback.");
            return fallback;
        }
        return material;
    }

    /** Resolves a currency name with warn-once fallback. */
    public Currency currencyValue(final Map<String, Object> values, final String key,
            final Currency fallback) {
        final Object raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Currency.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            warnOnce("currency:" + raw, "Unknown currency '" + raw + "' — using " + fallback + ".");
            return fallback;
        }
    }

    private void warnOnce(final String key, final String message) {
        if (badNames.add(key) && plugin != null) {
            plugin.getLogger().warning("[enchants] " + message);
        }
    }

    // ------------------------------------------------------------------ value bag helpers

    public static String stringValue(final Map<String, Object> values, final String key,
            final String fallback) {
        final Object raw = values.get(key);
        return raw == null ? fallback : String.valueOf(raw);
    }

    public static long longValue(final Map<String, Object> values, final String key, final long fallback) {
        final Object raw = values.get(key);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static double doubleValue(final Map<String, Object> values, final String key,
            final double fallback) {
        final Object raw = values.get(key);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean boolValue(final Map<String, Object> values, final String key,
            final boolean fallback) {
        final Object raw = values.get(key);
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    public static List<String> stringList(final Map<String, Object> values, final String key) {
        final Object raw = values.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        final List<String> result = new ArrayList<>(list.size());
        for (final Object entry : list) {
            result.add(String.valueOf(entry).trim());
        }
        return result;
    }
}
