package com.coremc.core.enchant;

import static com.coremc.core.enchant.EnchantEngine.doubleValue;
import static com.coremc.core.enchant.EnchantEngine.stringValue;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import java.util.List;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Fishing-activity enchant pipeline (fisher role + universal). No
 * OmniTool requirement — a pickaxe cannot fish, so the rod in hand
 * is inherent to the event itself and the role gates the magic.
 *
 * Casts shorten the hook's wait window (reel speed); catches run
 * combos → temp boosts → currency → treasure tables → extra catches
 * → magnet → recovery → leviathan → overdrive.
 */
public final class FishingEnchantHandler implements Listener {

    private final CoreMCPlugin plugin;
    private final EnchantEngine engine;

    public FishingEnchantHandler(final CoreMCPlugin plugin, final EnchantEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        if (engine.guarded()) {
            return;
        }
        final Player player = event.getPlayer();
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        final String role = profile.roleId();
        if (!"fisher".equals(role) && !"universal".equals(role)) {
            return;
        }
        final List<EnchantService.EnchantLevel> active =
                engine.activeFor(profile, role, EnchantEffect.Trigger.FISH);
        if (active.isEmpty()) {
            return;
        }
        switch (event.getState()) {
            case FISHING -> reelSpeed(player, event, active);
            case CAUGHT_FISH -> onCatch(player, profile, role, event, active);
            default -> {
            }
        }
    }

    /**
     * Reel speed: shrinks the hook's wait window on cast, so bites
     * come faster (cast-paced, so no cooldown needed). Reduction =
     * {@code valueAt} percent, capped by
     * {@code values.wait-cap-percent}; scales the hook's CURRENT
     * window so Lure keeps working underneath.
     */
    private void reelSpeed(final Player player, final PlayerFishEvent event,
            final List<EnchantService.EnchantLevel> active) {
        if (event.getHook() == null) {
            return;
        }
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.REEL_SPEED) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))) {
                continue;
            }
            final double cap = Math.min(95.0,
                    Math.max(0.0, doubleValue(enchant.values(), "wait-cap-percent", 80.0)));
            final double reduction =
                    Math.min(cap, Math.max(0.0, enchant.valueAt(owned.level()))) / 100.0;
            if (reduction <= 0.0) {
                continue;
            }
            final int min = Math.max(1, (int) Math.round(event.getHook().getMinWaitTime() * (1.0 - reduction)));
            final int max = Math.max(min + 1,
                    (int) Math.round(event.getHook().getMaxWaitTime() * (1.0 - reduction)));
            event.getHook().setMinWaitTime(min);
            event.getHook().setMaxWaitTime(max);
        }
    }

    private void onCatch(final Player player, final PlayerProfile profile, final String role,
            final PlayerFishEvent event, final List<EnchantService.EnchantLevel> active) {
        if (!(event.getCaught() instanceof Item hooked)) {
            return;
        }
        engine.recordCombos(player, profile, active);
        engine.triggerTempBoosts(player, profile, role, active);
        engine.grantCurrencies(player, profile, role, active);
        engine.grantTables(player, profile, role, active, RoleCategory.FISHING);
        extraCatch(player, profile, role, hooked, active);
        magnetCatch(player, hooked, active);
        engine.applyRecovery(player, active);
        fireLeviathan(player, profile, role, active);
        engine.fireOverdrive(player, profile, active);
    }

    /** EXTRA_CATCH: bonus copies of the hooked item (drop boosts apply). */
    private void extraCatch(final Player player, final PlayerProfile profile, final String role,
            final Item hooked, final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.FISH_BONUS
                    || !"EXTRA_CATCH".equalsIgnoreCase(stringValue(enchant.values(), "mode", ""))) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final double mult = engine.comboMultiplier(profile, role, "DROPS")
                    * (1.0 + engine.tempDropsPct(player.getUniqueId()) / 100.0);
            final int count = Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()) * mult));
            final ItemStack copy = hooked.getItemStack().clone();
            for (int copyIndex = 0; copyIndex < count; copyIndex++) {
                engine.giveOrDrop(player, copy.clone());
            }
        }
    }

    /** Magnetises the hooked item straight to the inventory (first proc wins). */
    private void magnetCatch(final Player player, final Item hooked,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.MAGNET) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final ItemStack caught = hooked.getItemStack().clone();
            hooked.remove();
            engine.collectOrDrop(player, List.of(caught),
                    stringValue(enchant.values(), "fallback", "DROP"), player.getLocation());
            return;
        }
    }

    private void fireLeviathan(final Player player, final PlayerProfile profile, final String role,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.ULTIMATE
                    || !"FISH_BOUNTY".equalsIgnoreCase(stringValue(enchant.values(), "mode", ""))) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            engine.grantRewards(player, profile, enchant, role, RoleCategory.FISHING);
            engine.ultimateFx(player, enchant);
        }
    }
}
