package com.coremc.core.progress;

import com.coremc.core.essence.EssenceConfig;
import com.coremc.core.essence.PlacedBlockTracker;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Turns real gameplay into authoritative progression events.
 *
 * <p>This is the only place that touches Bukkit events for
 * progression, and it is deliberately thin: it extracts the facts,
 * asks {@link ProgressGuards} whether they count, and posts one event
 * per real action to the {@link ProgressSink}. All the counting,
 * deduplication and reward logic lives behind the sink.</p>
 *
 * <p>Exactly-once is enforced three ways: {@code MONITOR} priority with
 * cancelled events ignored (so a protection plugin's denial never
 * counts), the {@link PlacedBlockTracker} shared with the Essence
 * system (so a placed-then-broken block is never mining progress), and
 * a dedupe key per real action (block position, entity uuid, hook
 * catch).</p>
 */
public final class CoreProgressBridge implements Listener {

    private final ProgressSink sink;
    private final EssenceConfig essences;
    private final PlacedBlockTracker placedBlocks;

    public CoreProgressBridge(final ProgressSink sink, final EssenceConfig essences,
                              final PlacedBlockTracker placedBlocks) {
        this.sink = sink;
        this.essences = essences;
        this.placedBlocks = placedBlocks;
    }

    /**
     * Mining and farming. Uses the same block maps as the Essence
     * system, so a block that pays Mining Essence is exactly the block
     * that fills a Mining Collection — no second source of truth.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        if (!countable(player)) {
            return;
        }
        final String world = block.getWorld().getName();
        final boolean placed = placedBlocks != null
                && placedBlocks.wasPlayerPlaced(world, block.getX(), block.getY(), block.getZ());
        final Material material = block.getType();
        final String key = key(material);
        final String dedupe = world + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ()
                + ":" + key;

        if (essences != null && essences.miningBlocks().containsKey(material)
                && ProgressGuards.countableMining(placed, true)) {
            sink.post(ProgressEvent.of(player.getUniqueId(), ProgressAction.MINE_BLOCK, key, 1,
                    ProgressSource.WORLD, dedupe));
            if (isDiscovery(material)) {
                // the genuinely rare finds also fill a hidden Discovery
                sink.post(ProgressEvent.of(player.getUniqueId(), ProgressAction.DISCOVERY, key, 1,
                        ProgressSource.DISCOVERY, dedupe + ":discovery"));
            }
        }
        if (essences != null && essences.farmingBlocks().containsKey(material)
                && ProgressGuards.countableHarvest(placed, fullyGrown(block))) {
            sink.post(ProgressEvent.of(player.getUniqueId(), ProgressAction.HARVEST_CROP, key, 1,
                    ProgressSource.WORLD, dedupe));
        }
    }

    /**
     * Keeps the placed-block tracker fed for farming blocks too. The
     * Essence listener already tracks mining placements; tracking
     * farming placements here means a placed melon cannot be farmed
     * for Collection progress either.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        if (placedBlocks == null || essences == null) {
            return;
        }
        final Block block = event.getBlock();
        if (essences.farmingBlocks().containsKey(block.getType())
                && !(block.getBlockData() instanceof Ageable)) {
            placedBlocks.add(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    /**
     * Slayer kills. One event per dead mob, keyed by the victim's
     * entity uuid, so a mob can never be counted twice no matter how
     * many systems saw it die.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(final EntityDeathEvent event) {
        final LivingEntity victim = event.getEntity();
        final Player killer = victim.getKiller();
        if (killer == null || !countable(killer)) {
            return;
        }
        final String type = victim.getType().name();
        if (!ProgressGuards.countableKill(true, type)) {
            return;
        }
        sink.post(ProgressEvent.of(killer.getUniqueId(), ProgressAction.SLAYER_KILL,
                type.toLowerCase(Locale.ROOT), 1, ProgressSource.WORLD,
                victim.getUniqueId().toString()));
    }

    /**
     * Fishing. Treasure catches also post a treasure event so a
     * "Treasure Hunter" Collection is possible without a second
     * listener.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        final Player player = event.getPlayer();
        if (!countable(player) || !(event.getCaught() instanceof Item caught)) {
            return;
        }
        final ItemStack stack = caught.getItemStack();
        final String key = key(stack.getType());
        final String dedupe = caught.getUniqueId().toString();
        sink.post(ProgressEvent.of(player.getUniqueId(), ProgressAction.FISH_CATCH, key, 1,
                ProgressSource.FISHING, dedupe));
        if (isTreasure(stack.getType())) {
            sink.post(ProgressEvent.of(player.getUniqueId(), ProgressAction.FISH_TREASURE, key, 1,
                    ProgressSource.FISHING, dedupe + ":treasure"));
            sink.post(ProgressEvent.of(player.getUniqueId(), ProgressAction.DISCOVERY, key, 1,
                    ProgressSource.DISCOVERY, dedupe + ":discovery"));
        }
    }

    private boolean countable(final Player player) {
        return sink != null && player != null
                && ProgressGuards.validGameMode(player.getGameMode().name());
    }

    private static boolean fullyGrown(final Block block) {
        if (!(block.getBlockData() instanceof Ageable age)) {
            return true;
        }
        return age.getAge() >= age.getMaximumAge();
    }

    /** The handful of blocks rare enough to count as a Discovery. */
    public static boolean isDiscovery(final Material material) {
        return switch (material) {
            case ANCIENT_DEBRIS, EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> true;
            default -> false;
        };
    }

    /** Fishing catches that count as treasure rather than food. */
    public static boolean isTreasure(final Material material) {
        return switch (material) {
            case BOW, ENCHANTED_BOOK, FISHING_ROD, NAME_TAG, NAUTILUS_SHELL, SADDLE -> true;
            default -> false;
        };
    }

    /** Stable lower-case subject key for a material. */
    public static String key(final Material material) {
        return material == null ? "" : material.name().toLowerCase(Locale.ROOT);
    }
}
