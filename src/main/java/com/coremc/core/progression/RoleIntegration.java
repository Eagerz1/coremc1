package com.coremc.core.progression;

import org.bukkit.entity.Player;

/**
 * Integration seam for Roles. The current branch has no native Role service,
 * so active progression sources map to role-like categories. A future Role
 * branch should provide a real adapter here.
 */
public interface RoleIntegration {

    /** Role key for this recent active action, or sourceId as a safe fallback. */
    default String roleFor(final Player player, final String sourceId) {
        return sourceId == null ? "unknown" : sourceId;
    }

    static RoleIntegration fallback() {
        return new RoleIntegration() {
        };
    }
}
