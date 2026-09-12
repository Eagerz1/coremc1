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
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

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

    public MiningEnchantHandler(final CoreMCPlugin plugin, final EnchantEngine engine) {
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
        if (!EnchantBlocks.isOre(origin.getType())) {
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
                    boolValue(enchant.values(), "break-containers", false));
        }
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
            final boolean breakContainers) {
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
            if (!EnchantBlocks.isOre(current.getType())) {
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
