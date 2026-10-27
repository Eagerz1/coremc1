package com.coremc.core.enchant;

import com.coremc.core.CoreMCPlugin;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Owns the custom-enchant drop lifecycle for block and kill activity:
 * smelt conversion, bonus copies, magnet collection, and final delivery.
 * The engine remains the compatibility facade for existing activity handlers.
 */
final class EnchantDropPipeline {

    private final CoreMCPlugin plugin;
    private final EnchantEngine engine;
    private final EnchantRuntimeState runtimeState;

    EnchantDropPipeline(final CoreMCPlugin plugin, final EnchantEngine engine,
            final EnchantRuntimeState runtimeState) {
        this.plugin = plugin;
        this.engine = engine;
        this.runtimeState = runtimeState;
    }

    /**
     * Runs the shared block drop stage. Returns true when a drop enchant claimed
     * the drops and vanilla behavior must be suppressed.
     */
    boolean process(final EnchantEngine.DropContext ctx) {
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
            engine.collectOrDrop(ctx.player(), drops, fallback, ctx.dropAt());
        } else {
            for (final ItemStack stack : drops) {
                if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                    ctx.block().getWorld().dropItemNaturally(ctx.dropAt(), stack);
                }
            }
        }
        return true;
    }

    private List<ItemStack> applySmelt(final EnchantEngine.DropContext ctx, final List<ItemStack> drops) {
        List<ItemStack> current = drops;
        for (final EnchantService.EnchantLevel owned : ctx.active()) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.SMELT) {
                continue;
            }
            if (!runtimeState.roll(enchant.chanceAt(owned.level()))
                    || !runtimeState.cooldownReady(ctx.player().getUniqueId(), enchant)) {
                continue;
            }
            runtimeState.markCooldown(ctx.player().getUniqueId(), enchant.id());
            final List<ItemStack> converted = new ArrayList<>(current.size());
            for (final ItemStack stack : current) {
                final Material out = EnchantBlocks.smeltResult(stack.getType(), enchant.values(), engine);
                converted.add(out == null ? stack : new ItemStack(out, stack.getAmount()));
            }
            current = converted;
        }
        return current;
    }

    void collectBlockBonus(final EnchantEngine.DropContext ctx, final List<ItemStack> base,
            final List<ItemStack> extras) {
        if (base.isEmpty()) {
            return;
        }
        final double mult = engine.comboMultiplier(ctx.profile(), ctx.roleKey(), "DROPS")
                * (1.0 + runtimeState.tempDropsPct(ctx.player().getUniqueId()) / 100.0);
        for (final EnchantService.EnchantLevel owned : ctx.active()) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.DROP_BONUS) {
                continue;
            }
            if (!runtimeState.roll(enchant.chanceAt(owned.level()))
                    || !runtimeState.cooldownReady(ctx.player().getUniqueId(), enchant)) {
                continue;
            }
            runtimeState.markCooldown(ctx.player().getUniqueId(), enchant.id());
            final int amount = Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()) * mult));
            dupeFiltered(base, amount, EnchantEngine.stringList(enchant.values(), "only-materials"), extras);
        }
        for (final EnchantService.EnchantLevel owned :
                plugin.enchants().multipliersFor(ctx.profile(), ctx.roleKey(), "DROPS")) {
            final Enchant enchant = owned.enchant();
            final long maxY = EnchantEngine.longValue(enchant.values(), "max-y", Long.MIN_VALUE);
            if (maxY != Long.MIN_VALUE && ctx.block().getY() > maxY) {
                continue;
            }
            if (!runtimeState.roll(enchant.chanceAt(owned.level()))
                    || !runtimeState.cooldownReady(ctx.player().getUniqueId(), enchant)) {
                continue;
            }
            runtimeState.markCooldown(ctx.player().getUniqueId(), enchant.id());
            final int amount = Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()) * mult));
            dupeFiltered(base, amount, EnchantEngine.stringList(enchant.values(), "only-materials"), extras);
        }
    }

    String magnetFallback(final EnchantEngine.DropContext ctx) {
        String fallback = null;
        for (final EnchantService.EnchantLevel owned : ctx.active()) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.MAGNET) {
                continue;
            }
            if (!runtimeState.roll(enchant.chanceAt(owned.level()))
                    || !runtimeState.cooldownReady(ctx.player().getUniqueId(), enchant)) {
                continue;
            }
            runtimeState.markCooldown(ctx.player().getUniqueId(), enchant.id());
            fallback = EnchantEngine.stringValue(enchant.values(), "fallback", "DROP");
        }
        return fallback;
    }

    /** Adds configured bonus copies to the slayer's ordinary death drops. */
    void collectKillBonus(final Player player, final com.coremc.core.player.PlayerProfile profile,
            final String roleKey, final List<EnchantService.EnchantLevel> active,
            final List<ItemStack> deathDrops) {
        if (deathDrops.isEmpty()) {
            return;
        }
        final double mult = engine.comboMultiplier(profile, roleKey, "DROPS")
                * (1.0 + runtimeState.tempDropsPct(player.getUniqueId()) / 100.0);
        final List<ItemStack> extras = new ArrayList<>();
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.DROP_BONUS) {
                continue;
            }
            if (!runtimeState.roll(enchant.chanceAt(owned.level()))
                    || !runtimeState.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            runtimeState.markCooldown(player.getUniqueId(), enchant.id());
            dupeFiltered(deathDrops,
                    Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()) * mult)),
                    EnchantEngine.stringList(enchant.values(), "only-materials"), extras);
        }
        for (final EnchantService.EnchantLevel owned :
                plugin.enchants().multipliersFor(profile, roleKey, "DROPS")) {
            final Enchant enchant = owned.enchant();
            if (enchant.values().containsKey("max-y")) {
                continue;
            }
            if (!runtimeState.roll(enchant.chanceAt(owned.level()))
                    || !runtimeState.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            runtimeState.markCooldown(player.getUniqueId(), enchant.id());
            dupeFiltered(deathDrops,
                    Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()) * mult)),
                    EnchantEngine.stringList(enchant.values(), "only-materials"), extras);
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
}
