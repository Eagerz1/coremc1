package com.coremc.core.role;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerProfile;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Role selection and progression routing.
 *
 * Select: binds the role, persists immediately (paid-progression class
 * data) and re-issues the OmniTool.
 *
 * XP routing: per-category listeners call {@link #awardCategoryXp} with
 * the action's XP. The selected role matching the category receives the
 * full amount; Universal receives a configured share of every category;
 * other roles receive nothing (the OmniTool only rewards relevant
 * actions). Role XP and OmniTool XP advance together and both fire
 * level-up messages; everything persists via the profile dirty/flush path.
 */
public final class RoleService {

    private final CoreMCPlugin plugin;
    private final PlayerDataService playerData;
    private final MessageService messages;
    private final ProgressionService progression = new ProgressionService();

    public RoleService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.playerData = plugin.playerData();
        this.messages = plugin.messages();
    }

    /** Whether the player currently holds a role. */
    public boolean hasRole(final PlayerProfile profile) {
        return Role.byKey(profile.roleId()).isPresent();
    }

    /** The player's role, or empty for "none". */
    public java.util.Optional<Role> roleOf(final PlayerProfile profile) {
        return Role.byKey(profile.roleId());
    }

    /**
     * Selects a role: stores it + writes through, then hands out the
     * appropriately-bound OmniTool (any previous one is removed first —
     * absolute no-dupe guarantee).
     */
    public boolean select(final Player player, final PlayerProfile profile, final Role role) {
        final java.util.Optional<Role> previous = roleOf(profile);
        if (previous.isPresent() && previous.get() == role) {
            return false;
        }
        profile.roleId(role.key());
        // Mirror the progress map onto the legacy single-slot fields for /role info reads.
        final var progress = profile.progressOf(role.key());
        profile.setProgress(
                role.key(),
                ((Number) progress.get("level")).intValue(),
                ((Number) progress.get("xp")).longValue());
        playerData.persistImportant(profile);
        plugin.omniTool().grantFresh(player, role, profile);
        return true;
    }

    /** Progression view for GUIs: per role (level, xp, xp-to-next). */
    public record ProgressView(int level, long xp, long xpToNext, boolean maxed) {
    }

    public ProgressView roleView(final PlayerProfile profile, final Role role) {
        final var progress = profile.progressOf(role.key());
        final int level = ((Number) progress.get("level")).intValue();
        final long xp = ((Number) progress.get("xp")).longValue();
        final long needed = progression.xpForNext(ProgressionService.ROLE_BASE_XP, level);
        return new ProgressView(level, xp, needed, level >= ProgressionService.MAX_LEVEL);
    }

    public ProgressView toolView(final PlayerProfile profile) {
        final long needed =
                progression.xpForNext(ProgressionService.TOOL_BASE_XP, profile.omniToolLevel());
        return new ProgressView(
                profile.omniToolLevel(), profile.omniToolXp(), needed,
                profile.omniToolLevel() >= ProgressionService.MAX_LEVEL);
    }

    /**
     * Routes XP from a role-category action. Relevant-role players get the
     * full amount, Universal players a configured share; others nothing.
     * Also feeds OmniTool XP by the same amount. Sends branded level-up
     * messages. Returns the XP actually awarded (0 if role doesn't match).
     */
    public long awardCategoryXp(
            final Player player, final PlayerProfile profile, final RoleCategory category, final long baseAmount) {
        final java.util.Optional<Role> role = roleOf(profile);
        if (role.isEmpty()) {
            return 0L;
        }
        final double multiplier = Math.max(0.0, plugin.coreConfig().roleXpMultiplier());
        // Custom-enchant XP boosts (role track + universal) multiply every award exactly once,
        // then live combo stacks and temporary overdrives multiply on top (single code path).
        final double enchantBoost = plugin.enchants().passiveMultiplier(profile, role.get().key(), "XP");
        final double engineBoost = plugin.enchantEngine().comboMultiplier(profile, role.get().key(), "XP")
                * (1.0 + plugin.enchantEngine().tempXpPct(profile.uuid()) / 100.0);
        long awarded = 0L;
        if (role.get().category() == category) {
            awarded = Math.round(baseAmount * multiplier * enchantBoost * engineBoost);
        } else if (role.get() == Role.UNIVERSAL) {
            awarded = Math.round(baseAmount * multiplier * plugin.coreConfig().roleUniversalShare()
                    * enchantBoost * engineBoost);
        }
        if (awarded <= 0L) {
            return 0L;
        }

        // Role progression
        final ProgressView before = roleView(profile, role.get());
        final ProgressionService.Result roleResult =
                progression.award(ProgressionService.ROLE_BASE_XP, before.level(), before.xp(), awarded);
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
