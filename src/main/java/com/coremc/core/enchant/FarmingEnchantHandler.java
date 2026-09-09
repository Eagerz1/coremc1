package com.coremc.core.enchant;

import static com.coremc.core.enchant.EnchantEngine.longValue;
import static com.coremc.core.enchant.EnchantEngine.stringValue;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Farming-activity enchant pipeline (farmer role + universal):
 * combos → temp boosts → haste → drops → currency → reward
 * tables → recovery → overdrive → replant → growth → ultimates.
 *
 * Only real harvests proc (ageable crops must be fully grown —
 * breaking sprouts yields nothing). Replanting is idempotent with
 * the island crop-regrowth track: whichever fires second finds the
 * block no longer air and stands down.
 */
public final class FarmingEnchantHandler implements Listener {

    private final CoreMCPlugin plugin;
    private final EnchantEngine engine;

    public FarmingEnchantHandler(final CoreMCPlugin plugin, final EnchantEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (engine.guarded()) {
            return;
        }
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        if (!EnchantBlocks.isFarmBlock(block.getType()) || !EnchantBlocks.isMatureHarvest(block)) {
            return;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        final String role = profile.roleId();
        if (!"farmer".equals(role) && !"universal".equals(role)) {
            return;
        }
        final ItemStack tool = plugin.omniTool().toolInMainHand(player);
        if (tool == null) {
            return;
        }
        final List<EnchantService.EnchantLevel> active =
                engine.activeFor(profile, role, EnchantEffect.Trigger.FARM);
        if (active.isEmpty()) {
            return;
        }
        engine.recordCombos(player, profile, active);
        engine.triggerTempBoosts(player, profile, role, active);
        engine.applyHaste(player, active);
        if (event.isDropItems()) {
            final var ctx = new EnchantEngine.DropContext(
                    player, profile, role, block, tool, active, block.getLocation());
            if (engine.processDrops(ctx)) {
                event.setDropItems(false);
            }
        }
        engine.grantCurrencies(player, profile, role, active);
        engine.grantTables(player, profile, role, active, RoleCategory.FARMING);
        engine.applyRecovery(player, active);
        engine.fireOverdrive(player, profile, active);
        replant(player, block, active);
        neighboursGrowth(player, block, active);
        fireBlessing(player, profile, role, block, active);
    }

    /** Resows the harvested crop (consumes a seed unless the free roll hits). */
    private void replant(final Player player, final Block origin,
            final List<EnchantService.EnchantLevel> active) {
        if (!EnchantBlocks.isReplantable(origin.getType())) {
            return;
        }
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.REPLANT) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            final Material seed = EnchantBlocks.seedFor(origin.getType());
            final boolean free = engine.roll(enchant.valueAt(owned.level()) / 100.0);
            if (!free && (seed == null || !takeSeed(player, seed))) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final Material crop = origin.getType();
            final Location at = origin.getLocation();
            final long delay = Math.max(1L, longValue(enchant.values(), "delay-ticks", 2L));
            plugin.tasks().runLater(() -> {
                final Block target = at.getBlock();
                if (!target.getType().isAir()) {
                    return; // island regrowth (or a rival) got here first
                }
                target.setType(crop);
                if (target.getBlockData() instanceof Ageable ageable) {
                    ageable.setAge(0);
                    target.setBlockData(ageable);
                }
            }, delay);
            return; // one replant per break
        }
    }

    private static boolean takeSeed(final Player player, final Material seed) {
        final ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            final ItemStack item = contents[slot];
            if (item != null && item.getType() == seed && item.getAmount() > 0) {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(slot, item.getAmount() <= 0 ? null : item);
                return true;
            }
        }
        return false;
    }

    /** NEIGHBOURS growth: harvesting feeds the crops around you. */
    private void neighboursGrowth(final Player player, final Block origin,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.GROWTH
                    || !"NEIGHBOURS".equalsIgnoreCase(stringValue(enchant.values(), "mode", ""))) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final int radius =
                    Math.min(8, Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()))));
            feedCrops(origin, radius);
        }
    }

    private void fireBlessing(final Player player, final PlayerProfile profile, final String role,
            final Block origin, final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.ULTIMATE
                    || !"HARVEST_BLESSING".equalsIgnoreCase(stringValue(enchant.values(), "mode", ""))) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final int radius =
                    Math.min(10, Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()))));
            feedCrops(origin, radius);
            engine.grantRewards(player, profile, enchant, role, RoleCategory.FARMING);
            engine.ultimateFx(player, enchant);
        }
    }

    /** Bonemeals every growing crop in a cube around the origin. */
    private void feedCrops(final Block origin, final int radius) {
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final Block nearby = origin.getRelative(dx, dy, dz);
                    if (nearby.getBlockData() instanceof Ageable ageable
                            && ageable.getAge() < ageable.getMaximumAge()) {
                        nearby.applyBoneMeal(BlockFace.UP);
                    }
                }
            }
        }
    }
}
