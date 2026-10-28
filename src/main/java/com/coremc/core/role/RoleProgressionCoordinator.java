package com.coremc.core.role;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerProfile;
import java.util.Map;
import org.bukkit.entity.Player;

/** Routes category XP through role, enchant, island, and OmniTool progression. */
public final class RoleProgressionCoordinator {

    private final CoreMCPlugin plugin;
    private final PlayerDataService playerData;
    private final MessageService messages;
    private final ProgressionService progression;

    public RoleProgressionCoordinator(
            final CoreMCPlugin plugin,
            final PlayerDataService playerData,
            final MessageService messages,
            final ProgressionService progression) {
        this.plugin = plugin;
        this.playerData = playerData;
        this.messages = messages;
        this.progression = progression;
    }

    /**
     * Routes XP from a role-category action. Relevant-role players get the
     * full amount, Universal players a configured share; others get none.
     * OmniTool XP receives the same awarded amount.
     *
     * @return XP actually awarded (0 if role does not match)
     */
    public long awardCategoryXp(
            final Player player, final PlayerProfile profile, final RoleCategory category, final long baseAmount) {
        final java.util.Optional<Role> role = Role.byKey(profile.roleId());
        if (role.isEmpty()) {
            return 0L;
        }
        final double multiplier = Math.max(0.0, plugin.coreConfig().roleXpMultiplier());
        // Custom-enchant XP boosts (role track + universal) multiply every award exactly once,
        // then live combo stacks and temporary overdrives multiply on top (single code path).
        final double enchantBoost = plugin.enchants().passiveMultiplier(profile, role.get().key(), "XP");
        final double engineBoost = plugin.enchantEngine().comboMultiplier(profile, role.get().key(), "XP")
                * (1.0 + plugin.enchantEngine().tempXpPct(profile.uuid()) / 100.0);
        // Island *-xp tracks multiply inside this single funnel (1.0 in the
        // wild): island × enchant × combo, stacked intentionally, applied once.
        final double islandBoost = plugin.islandProgress().xpMultiplier(player, category);
        // BUFF stage of the pipeline: the island xp-boost multiplies here,
        // exactly once, for members standing on their own island.
        final double buffBoost = plugin.islandBuffs().xpMult(player);
        long awarded = 0L;
        if (role.get().category() == category) {
            awarded = Math.round(baseAmount * multiplier * enchantBoost * engineBoost * islandBoost * buffBoost);
        } else if (role.get() == Role.UNIVERSAL) {
            awarded = Math.round(baseAmount * multiplier * plugin.coreConfig().roleUniversalShare()
                    * enchantBoost * engineBoost * islandBoost * buffBoost);
        }
        if (awarded <= 0L) {
            return 0L;
        }

        // Role progression
        final var progress = profile.progressOf(role.get().key());
        final int level = ((Number) progress.get("level")).intValue();
        final long xp = ((Number) progress.get("xp")).longValue();
        final ProgressionService.Result roleResult = progression.award(
                ProgressionService.ROLE_BASE_XP, level, xp, awarded);
        profile.setProgress(role.get().key(), roleResult.level, roleResult.xp);

        // OmniTool progression (same amount)
        final ProgressionService.Result toolResult = progression.award(
                ProgressionService.TOOL_BASE_XP, profile.omniToolLevel(), profile.omniToolXp(), awarded);
        profile.setOmniToolProgress(toolResult.level, toolResult.xp);

        playerData.markDirty(profile.uuid());

        if (roleResult.levelsGained > 0) {
            messages.sendPrefixed(
                    player,
                    "role.level-up",
                    Map.of("role", role.get().display(), "level", String.valueOf(roleResult.level)));
        }
        if (toolResult.levelsGained > 0) {
            messages.sendPrefixed(
                    player, "omnitool.level-up", Map.of("level", String.valueOf(toolResult.level)));
        }
        return awarded;
    }
}
