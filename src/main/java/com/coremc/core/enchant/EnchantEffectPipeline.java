package com.coremc.core.enchant;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.List;
import java.util.Map;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Applies shared passive and temporary enchant effects for activity handlers. */
final class EnchantEffectPipeline {

    private final CoreMCPlugin plugin;
    private final EnchantEngine engine;

    EnchantEffectPipeline(final CoreMCPlugin plugin, final EnchantEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    void recordCombos(final Player player, final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.COMBO) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            engine.recordCombo(player.getUniqueId(), enchant.id(),
                    EnchantEngine.longValue(enchant.values(), "window-seconds", 5L),
                    (int) EnchantEngine.longValue(enchant.values(), "max-stacks", 20L));
        }
    }

    void triggerTempBoosts(final Player player, final PlayerProfile profile, final String roleKey,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.TEMP_BOOST) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            final int need = (int) EnchantEngine.longValue(enchant.values(), "trigger-count", 10L);
            if (engine.highestComboStacks(profile, roleKey) < need) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final long seconds = EnchantEngine.longValue(enchant.values(), "duration-seconds", 15L);
            engine.setTempBoost(player.getUniqueId(),
                    EnchantEngine.doubleValue(enchant.values(), "mult-xp", 0.0),
                    EnchantEngine.doubleValue(enchant.values(), "mult-drops", 0.0), seconds * 1000L);
            for (final String spec : EnchantEngine.stringList(enchant.values(), "effects")) {
                final PotionEffect effect = engine.parseEffect(spec);
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

    void applyHaste(final Player player, final List<EnchantService.EnchantLevel> active) {
        int bestAmp = -1;
        int bestTicks = 0;
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.HASTE) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            bestAmp = Math.max(bestAmp, (int) Math.floor(enchant.valueAt(owned.level())));
            bestTicks = Math.max(bestTicks,
                    (int) EnchantEngine.longValue(enchant.values(), "duration-seconds", 6L) * 20);
        }
        if (bestAmp >= 0) {
            engine.applyPotion(player, PotionEffectType.HASTE, bestAmp, bestTicks);
        }
    }

    void applyRecovery(final Player player, final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.RECOVERY) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final double hearts = EnchantEngine.doubleValue(enchant.values(), "hearts", 0.0);
            if (hearts > 0.0) {
                player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + hearts));
            }
            final long food = EnchantEngine.longValue(enchant.values(), "food", 0L);
            if (food > 0L) {
                player.setFoodLevel(Math.min(20, player.getFoodLevel() + (int) Math.min(food, 20L)));
            }
            if (EnchantEngine.boolValue(enchant.values(), "extinguish", false)) {
                player.setFireTicks(0);
            }
        }
    }

    void fireOverdrive(final Player player, final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.ULTIMATE
                    || !"OVERDRIVE".equalsIgnoreCase(
                            EnchantEngine.stringValue(enchant.values(), "mode", ""))) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final long seconds = EnchantEngine.longValue(enchant.values(), "duration-seconds", 30L);
            engine.setTempBoost(player.getUniqueId(),
                    EnchantEngine.doubleValue(enchant.values(), "mult-xp", 0.0),
                    EnchantEngine.doubleValue(enchant.values(), "mult-drops", 0.0), seconds * 1000L);
            for (final String spec : EnchantEngine.stringList(enchant.values(), "effects")) {
                final PotionEffect effect = engine.parseEffect(spec);
                if (effect != null) {
                    player.addPotionEffect(effect, true);
                }
            }
            engine.ultimateFx(player, enchant);
        }
    }
}
