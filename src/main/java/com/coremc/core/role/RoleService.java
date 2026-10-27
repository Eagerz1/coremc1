package com.coremc.core.role;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerProfile;
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
    private final ProgressionService progression = new ProgressionService();
    private final RoleProgressionCoordinator awards;

    public RoleService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.playerData = plugin.playerData();
        this.awards = new RoleProgressionCoordinator(plugin, playerData, plugin.messages(), progression);
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
        return awards.awardCategoryXp(player, profile, category, baseAmount);
    }

    /**
     * Highest role level across all six roles (universal included):
     * the "role level" island-upgrade gates measure the buyer against.
     */
    public int maxRoleLevel(final PlayerProfile profile) {
        int best = 0;
        for (final Role role : Role.values()) {
            best = Math.max(best, roleView(profile, role).level());
        }
        return best;
    }
}
