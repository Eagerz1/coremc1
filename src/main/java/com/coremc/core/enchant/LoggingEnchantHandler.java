package com.coremc.core.enchant;

import static com.coremc.core.enchant.EnchantEngine.boolValue;
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
 * Logging-activity enchant pipeline (logger role + universal):
 * combos → temp boosts → haste → drops → currency → reward
 * tables → recovery → overdrive → stump regrowth → veins →
 * ultimates. Owns logs and leaves only; logging enchants channel
 * through the OmniTool in the main hand.
 */
public final class LoggingEnchantHandler implements Listener {

    private final CoreMCPlugin plugin;
    private final EnchantEngine engine;

    public LoggingEnchantHandler(final CoreMCPlugin plugin, final EnchantEngine engine) {
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
        if (!EnchantBlocks.isWoodLike(block.getType())) {
            return; // owned by the mining / farming pipelines
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return;
        }
        final String role = profile.roleId();
        if (!"logger".equals(role) && !"universal".equals(role)) {
            return;
        }
        final ItemStack tool = plugin.omniTool().toolInMainHand(player);
        if (tool == null) {
            return;
        }
        final List<EnchantService.EnchantLevel> active =
                engine.activeFor(profile, role, EnchantEffect.Trigger.LOG);
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
        engine.grantTables(player, profile, role, active, RoleCategory.LOGGING);
        engine.applyRecovery(player, active);
        engine.fireOverdrive(player, profile, active);
        stumpGrowth(player, block, active);
        veinLogs(player, block, tool, active);
        fireWorldTree(player, profile, role, block, tool, active);
    }

    /** STUMP growth: the felled trunk replants and bonemeals itself. */
    private void stumpGrowth(final Player player, final Block origin,
            final List<EnchantService.EnchantLevel> active) {
        if (!EnchantBlocks.isLog(origin.getType())) {
            return;
        }
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.GROWTH
                    || !"STUMP".equalsIgnoreCase(stringValue(enchant.values(), "mode", ""))) {
                continue;
            }
            if (!engine.roll(enchant.chanceAt(owned.level()))
                    || !engine.cooldownReady(player.getUniqueId(), enchant)) {
                continue;
            }
            engine.markCooldown(player.getUniqueId(), enchant.id());
            final Material sapling = EnchantBlocks.saplingFor(origin.getType());
            if (sapling == null) {
                return;
            }
            final Location at = origin.getLocation();
            final Block below = origin.getRelative(BlockFace.DOWN);
            plugin.tasks().runLater(() -> {
                final Block target = at.getBlock();
                if (!target.getType().isAir() || below.getType().isAir() || below.isLiquid()) {
                    return;
                }
                target.setType(sapling);
                target.applyBoneMeal(BlockFace.UP);
            }, 2L);
            return; // one stump per break
        }
    }

    private void veinLogs(final Player player, final Block origin, final ItemStack tool,
            final List<EnchantService.EnchantLevel> active) {
        if (!EnchantBlocks.isLog(origin.getType())) {
            return; // veins fell from the trunk, not from leaves
        }
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.VEIN
                    || !"LOG".equalsIgnoreCase(stringValue(enchant.values(), "match", "ORE"))) {
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

    private void fireWorldTree(final Player player, final PlayerProfile profile, final String role,
            final Block origin, final ItemStack tool, final List<EnchantService.EnchantLevel> active) {
        if (!EnchantBlocks.isLog(origin.getType())) {
            return;
        }
        for (final EnchantService.EnchantLevel owned : active) {
            final Enchant enchant = owned.enchant();
            if (enchant.effect() != EnchantEffect.ULTIMATE
                    || !"TREE_BLESSING".equalsIgnoreCase(stringValue(enchant.values(), "mode", ""))) {
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
            engine.grantRewards(player, profile, enchant, role, RoleCategory.LOGGING);
            engine.ultimateFx(player, enchant);
        }
    }

    /** Breadth-first break of the connected logs (face-neighbours only). */
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
            if (!EnchantBlocks.isLog(current.getType())) {
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
