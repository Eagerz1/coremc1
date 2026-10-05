package com.coremc.core.enchant;

import static com.coremc.core.enchant.EnchantEngine.boolValue;
import static com.coremc.core.enchant.EnchantEngine.longValue;
import static com.coremc.core.enchant.EnchantEngine.stringList;
import static com.coremc.core.enchant.EnchantEngine.stringValue;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Mining-activity enchant pipeline (miner role + universal):
 * combos → temp boosts → haste → drops → currency → reward
 * tables → recovery → overdrive → AoE → veins → ultimates.
 *
 * Wood and crops belong to the logging/farming pipelines and are
 * skipped here. Drops are only claimed when a drop enchant owns
 * them — the legacy OmniTool smelter cooperates via
 * {@code isDropItems}, so running order can never double-drop.
 * Mining enchants channel through the OmniTool in the main hand.
 */
public final class MiningEnchantHandler implements Listener {

    private final CoreMCPlugin plugin;
    private final EnchantEngine engine;
    private final NamespacedKey generatorBlocksKey;

    public MiningEnchantHandler(final CoreMCPlugin plugin, final EnchantEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.generatorBlocksKey = new NamespacedKey(plugin, "vein-generator-blocks");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (engine.guarded()) {
            return;
        }
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        final Material type = block.getType();
        if (EnchantBlocks.isWoodLike(type) || EnchantBlocks.isFarmBlock(type)) {
            return; // owned by the logging / farming pipelines
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        final String role = profile.roleId();
        if (!"miner".equals(role) && !"universal".equals(role)) {
            return;
        }
        final ItemStack tool = plugin.omniTool().toolInMainHand(player);
        if (tool == null) {
            return;
        }
        final List<EnchantService.EnchantLevel> active =
                engine.activeFor(profile, role, EnchantEffect.Trigger.MINE);
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
        engine.grantTables(player, profile, role, active, RoleCategory.MINING);
        engine.applyRecovery(player, active);
        engine.fireOverdrive(player, profile, active);
        breakAoE(player, block, tool, active);
        breakVeins(player, block, tool, active);
        fireCataclysm(player, profile, role, block, tool, active);
    }

    private void breakAoE(final Player player, final Block origin, final ItemStack tool,
            final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.AOE_BREAK) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            burstBreak(player, origin, tool,
                    Math.min(5, Math.max(1, (int) Math.floor(enchant.valueAt(owned.level())))),
                    longValue(enchant.values(), "max-blocks", 25L),
                    stringList(enchant.values(), "only"),
                    boolValue(enchant.values(), "break-containers", false));
        }
    }

    private void breakVeins(final Player player, final Block origin, final ItemStack tool,
            final List<EnchantService.EnchantLevel> active) {
        final var island = plugin.islands().islandAt(
                origin.getWorld().getName(), origin.getX(), origin.getZ()).orElse(null);
        if (island == null) {
            return;
        }
        final boolean miningCube = EnchantBlocks.isOre(origin.getType())
                && plugin.miningCube().isCubeBlock(island, origin);
        final boolean generator = isTrackedGeneratorBlock(origin);
        if (!miningCube && !generator) {
            return;
        }
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.VEIN
                    || !"ORE".equalsIgnoreCase(stringValue(enchant.values(), "match", "ORE"))) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            veinBreak(player, origin, tool,
                    Math.max(1, (int) Math.floor(enchant.valueAt(owned.level()))),
                    boolValue(enchant.values(), "break-containers", false),
                    island, miningCube, origin.getType());
        }
    }

    /** Marks blocks formed by a source-water/source-lava cobble generator. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGeneratorBlockForm(final BlockFormEvent event) {
        final BlockState state = event.getNewState();
        if (!isGeneratorMaterial(state.getType()) || !hasWaterAndLavaSources(state.getBlock())) {
            return;
        }
        markGeneratorBlock(state.getBlock());
    }

    /** Drop the marker when the block is mined so stale locations cannot be reused. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGeneratorBlockBreak(final BlockBreakEvent event) {
        unmarkGeneratorBlock(event.getBlock());
    }

    private boolean hasWaterAndLavaSources(final Block block) {
        boolean water = false;
        boolean lava = false;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    final Block nearby = block.getRelative(dx, dy, dz);
                    if (nearby.getBlockData() instanceof Levelled levelled && levelled.getLevel() == 0) {
                        water |= nearby.getType() == Material.WATER;
                        lava |= nearby.getType() == Material.LAVA;
                    }
                }
            }
        }
        return water && lava;
    }

    private void markGeneratorBlock(final Block block) {
        final long[] marked = block.getChunk().getPersistentDataContainer()
                .get(generatorBlocksKey, PersistentDataType.LONG_ARRAY);
        final long position = packedPosition(block);
        if (marked != null && java.util.Arrays.stream(marked).anyMatch(value -> value == position)) {
            return;
        }
        final long[] updated = marked == null ? new long[] {position}
                : java.util.Arrays.copyOf(marked, marked.length + 1);
        if (marked != null) {
            updated[updated.length - 1] = position;
        }
        block.getChunk().getPersistentDataContainer()
                .set(generatorBlocksKey, PersistentDataType.LONG_ARRAY, updated);
    }

    private void unmarkGeneratorBlock(final Block block) {
        final var pdc = block.getChunk().getPersistentDataContainer();
        final long[] marked = pdc.get(generatorBlocksKey, PersistentDataType.LONG_ARRAY);
        if (marked == null) {
            return;
        }
        final long position = packedPosition(block);
        final long[] updated = java.util.Arrays.stream(marked)
                .filter(value -> value != position).toArray();
        if (updated.length == 0) {
            pdc.remove(generatorBlocksKey);
        } else if (updated.length != marked.length) {
            pdc.set(generatorBlocksKey, PersistentDataType.LONG_ARRAY, updated);
        }
    }

    private boolean isTrackedGeneratorBlock(final Block block) {
        if (!isGeneratorMaterial(block.getType())) {
            return false;
        }
        final long[] marked = block.getChunk().getPersistentDataContainer()
                .get(generatorBlocksKey, PersistentDataType.LONG_ARRAY);
        if (marked == null) {
            return false;
        }
        final long position = packedPosition(block);
        return java.util.Arrays.stream(marked).anyMatch(value -> value == position);
    }

    private static long packedPosition(final Block block) {
        return ((long) block.getY() << 8) | ((long) (block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    private static boolean isGeneratorMaterial(final Material material) {
        return material == Material.COBBLESTONE || material == Material.STONE
                || material == Material.BASALT || material == Material.BLACKSTONE;
    }

    private void fireCataclysm(final Player player, final PlayerProfile profile, final String role,
            final Block origin, final ItemStack tool, final List<EnchantService.EnchantLevel> active) {
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.ULTIMATE
                    || !"MINE_BURST".equalsIgnoreCase(stringValue(enchant.values(), "mode", ""))) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            burstBreak(player, origin, tool,
                    Math.min(5, Math.max(1, (int) Math.floor(enchant.valueAt(owned.level())))),
                    longValue(enchant.values(), "max-blocks", 40L),
                    stringList(enchant.values(), "only"),
                    boolValue(enchant.values(), "break-containers", false));
            engine.grantRewards(player, profile, enchant, role, RoleCategory.MINING);
            engine.ultimateFx(player, enchant);
        }
    }

    /** Breaks a cube around the origin (itself excluded), honouring caps and filters. */
    private void burstBreak(final Player player, final Block origin, final ItemStack tool, final int radius,
            final long cap, final List<String> only, final boolean breakContainers) {
        int broken = 0;
        outer:
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (broken >= cap) {
                        break outer;
                    }
                    final Block extra = origin.getRelative(dx, dy, dz);
                    if (!only.isEmpty() && !only.contains(extra.getType().name())) {
                        continue;
                    }
                    if (engine.breakExtra(player, extra, tool, breakContainers)) {
                        broken++;
                    }
                }
            }
        }
    }

    /** Breadth-first break of the connected ore vein (face-neighbours only). */
    private void veinBreak(final Player player, final Block origin, final ItemStack tool, final int cap,
            final boolean breakContainers, final com.coremc.core.island.Island island,
            final boolean miningCube, final Material generatorMaterial) {
        final Set<Location> visited = new HashSet<>();
        final Deque<Block> queue = new ArrayDeque<>();
        visited.add(origin.getLocation());
        queue.addAll(faces(origin));
        int broken = 0;
        while (!queue.isEmpty() && broken < cap) {
            final Block current = queue.poll();
            if (!visited.add(current.getLocation())) {
                continue;
            }
            final boolean allowed = miningCube
                    ? EnchantBlocks.isOre(current.getType()) && plugin.miningCube().isCubeBlock(island, current)
                    : current.getType() == generatorMaterial && isTrackedGeneratorBlock(current);
            if (!allowed) {
                continue;
            }
            if (engine.breakExtra(player, current, tool, breakContainers)) {
                broken++;
                queue.addAll(faces(current));
            }
        }
    }

    private static List<Block> faces(final Block block) {
        return List.of(
                block.getRelative(BlockFace.UP),
                block.getRelative(BlockFace.DOWN),
                block.getRelative(BlockFace.NORTH),
                block.getRelative(BlockFace.SOUTH),
                block.getRelative(BlockFace.EAST),
                block.getRelative(BlockFace.WEST));
    }
}
