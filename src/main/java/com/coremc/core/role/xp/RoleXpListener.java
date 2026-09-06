package com.coremc.core.role.xp;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.RoleCategory;
import com.coremc.core.role.RoleService;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

/**
 * Base for category XP listeners.
 *
 * Gating contract (the "OmniTool only rewards relevant actions" rule):
 *  1. the player must be holding THEIR OmniTool in the main hand,
 *  2. the player must have a role,
 *  3. the category must match the role (Universal receives a share,
 *     handled inside {@link RoleService}).
 *
 * Each concrete listener owns ONE event type and ONE category; nothing
 * is shared beyond this 40-line helper.
 */
abstract class RoleXpListener implements Listener {

    protected final CoreMCPlugin plugin;
    private final RoleCategory category;

    RoleXpListener(final CoreMCPlugin plugin, final RoleCategory category) {
        this.plugin = plugin;
        this.category = category;
    }

    /** Awards base XP for an action; returns the amount actually granted (>= 0). */
    protected long award(final Player player, final long baseAmount) {
        if (plugin.omniTool().toolInMainHand(player) == null) {
            return 0L;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return 0L;
        }
        return plugin.roles().awardCategoryXp(player, profile, category, baseAmount);
    }
}
