package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.role.RoleCategory;
import com.coremc.core.scheduler.TaskService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

/**
 * Island progression: lifetime stats, island XP and the island level.
 *
 * Team actions on the island feed per-island counters (blocks-mined,
 * logs-chopped, crops-harvested, fish-caught, mobs-killed,
 * generator-harvests) plus weighted island XP. Level is a pure
 * function of stored XP + purchased upgrade tiers:
 * {@code 1 + floor(sqrt(score / divisor))} — meaningful, unbounded,
 * and fully config-tuned ({@code island.level}).
 *
 * Writes are batched: every record marks the island dirty and a
 * once-a-minute timer flushes dirty islands (plus a shutdown flush),
 * so heavy activity never causes per-action disk I/O.
 */
public final class IslandProgressService implements Listener {

    private final CoreMCPlugin plugin;
    private final IslandProgressCoordinator progression;

    public IslandProgressService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.progression = new IslandProgressCoordinator(plugin);
    }

    /** Starts the batched progression flush (once a minute). */
    public void start(final TaskService tasks) {
        tasks.runTimer(() -> plugin.islands().flushDirty(), 1200L, 1200L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        progression.onBlockBreak(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        progression.onFish(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        progression.onDeath(event);
    }

    /** Generator harvests feed island XP (called from the harvest hook). */
    public void recordGeneratorHarvest(final Island island, final Player actor) {
        progression.recordGeneratorHarvest(island, actor);
    }

    /**
     * Extra island XP from a CoreMC spawner kill (config
     * {@code spawners.kill-rewards.island-xp}), on top of the regular
     * mobs-killed weight; fires the level-up message like every other
     * island XP gain.
     */
    public void awardKillXp(final Island island, final Player actor, final long bonusXp) {
        progression.awardKillXp(island, actor, bonusXp);
    }

    /** Current computed level for an island (stored XP + purchased tiers). */
    public int levelFor(final Island island) {
        return progression.levelFor(island);
    }

    /** level = 1 + floor(sqrt(score / divisor)); non-positive score/divisor → 1. Pure. */
    public static int scoreToLevel(final long score, final long divisor) {
        return IslandProgressCoordinator.scoreToLevel(score, divisor);
    }

    /**
     * Island XP multiplier for a role-category action: 1.0 in the wild
     * or without the track, else 1 + tier*pct/100. Applied inside the
     * single XP funnel after enchant/combo boosts (stacks, never
     * double-applies).
     */
    public double xpMultiplier(final Player player, final RoleCategory category) {
        return progression.xpMultiplier(player, category);
    }
}
