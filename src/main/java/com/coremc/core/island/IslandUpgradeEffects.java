package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Live effects of the non-Island upgrade tracks. All of them are
 * island-team scoped and config-balanced:
 *
 *  - Farming / Crop Regrowth: harvesting a fully-grown crop on your
 *    island rolls L*5% — on success the crop replants itself and one
 *    seed is consumed from the item drops (no duplication possible),
 *  - Logging / Woodcutter: chopping a log rolls L*10% for a second log,
 *  - Fishing / Fisher's Blessing: a caught fish rolls L*10% for a bonus,
 *  - Slaying / Slayer Force: melee hits on your island deal +L*5%.
 *
 * Also hosts the purchase hook called from
 * {@link IslandService#purchaseUpgrade} so side-effecting tracks
 * (Mining Cube rebuild) act the moment they are bought.
 */
public final class IslandUpgradeEffects implements Listener {

    private final CoreMCPlugin plugin;

    /** crop -> replant material (itself) + the seed material it consumes. */
    private static final Map<Material, Material> SEEDS = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS);

    public IslandUpgradeEffects(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called right after a successful upgrade purchase (same tick). */
    public void onPurchased(final Island island, final String upgradeId, final int newTier) {
        if ("mining-cube".equals(upgradeId)) {
            plugin.miningCube().rebuild(island);
        }
    }

    // ------------------------------------------------------------------ crop regrowth + woodcutter

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        final var island = plugin.islands()
                .islandAt(block.getWorld().getName(), block.getX(), block.getZ());
        if (island.isEmpty() || island.get().roleOf(event.getPlayer().getUniqueId()) == null) {
            return;
        }
        final Island value = island.get();
        tryWoodcutter(event, value, block);
        tryCropRegrowth(event, value, block);
    }

    private void tryWoodcutter(final BlockBreakEvent event, final Island island, final Block block) {
        if (!org.bukkit.Tag.LOGS.isTagged(block.getType())) {
            return;
        }
        final int tier = island.upgrades().getOrDefault("woodcutter", 0);
        if (tier <= 0) {
            return;
        }
        final double chance = tier * configPercent("woodcutter", "chance-percent-per-level", 10) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(block.getType()));
        }
    }

    private void tryCropRegrowth(final BlockBreakEvent event, final Island island, final Block crop) {
        final Material seed = SEEDS.get(crop.getType());
        if (seed == null || !(crop.getBlockData() instanceof Ageable ageable)
                || ageable.getAge() < ageable.getMaximumAge()) {
            return; // not a mature, regrowable crop
        }
        final int tier = island.upgrades().getOrDefault("crop-regrowth", 0);
        if (tier <= 0) {
            return;
        }
        final double chance = tier * configPercent("crop-regrowth", "chance-percent-per-level", 5) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        // One seed is consumed: replay the drops ourselves minus one seed.
        final List<ItemStack> drops = new java.util.ArrayList<>(crop.getDrops(event.getPlayer()
                .getInventory()
                .getItemInMainHand()));
        boolean consumed = false;
        for (int i = 0; i < drops.size(); i++) {
            if (drops.get(i).getType() == seed) {
                final ItemStack stack = drops.get(i);
                if (stack.getAmount() > 1) {
                    stack.setAmount(stack.getAmount() - 1);
                } else {
                    drops.remove(i);
                }
                consumed = true;
                break;
            }
        }
        if (!consumed) {
            return; // no seed in the harvest — nothing to replant with
        }
        event.setDropItems(false);
        for (final ItemStack remaining : drops) {
            crop.getWorld().dropItemNaturally(crop.getLocation(), remaining);
        }
        final Material cropType = crop.getType();
        plugin.tasks().run(() -> {
            if (crop.getType().isAir() && crop.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND) {
                crop.setType(cropType);
            }
        });
    }

    // ------------------------------------------------------------------ fisher's blessing

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item caught)) {
            return;
        }
        final Block water = event.getHook().getLocation().getBlock();
        final var island = plugin.islands()
                .islandAt(water.getWorld().getName(), water.getX(), water.getZ());
        if (island.isEmpty() || island.get().roleOf(event.getPlayer().getUniqueId()) == null) {
            return;
        }
        final int tier = island.get().upgrades().getOrDefault("fisher-blessing", 0);
        if (tier <= 0) {
            return;
        }
        final double chance = tier * configPercent("fisher-blessing", "chance-percent-per-level", 10) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            water.getWorld().dropItemNaturally(caught.getLocation(), caught.getItemStack().clone());
        }
    }

    // ------------------------------------------------------------------ slayer force

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeleeDamage(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        final Entity victim = event.getEntity();
        final var island = plugin.islands()
                .islandAt(victim.getWorld().getName(), victim.getLocation().getBlockX(), victim.getLocation().getBlockZ());
        if (island.isEmpty() || island.get().roleOf(player.getUniqueId()) == null) {
            return;
        }
        final int tier = island.get().upgrades().getOrDefault("slayer-force", 0);
        if (tier <= 0) {
            return;
        }
        final double bonus = tier * configPercent("slayer-force", "damage-percent-per-level", 5) / 100.0;
        event.setDamage(event.getDamage() * (1.0 + bonus));
    }

    private int configPercent(final String trackId, final String key, final int fallback) {
        return Math.max(0, plugin.getConfig().getInt("island.upgrades." + trackId + "." + key, fallback));
    }
}
