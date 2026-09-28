package com.coremc.core.moderation;

import java.util.UUID;
import org.bukkit.entity.Player;

/** UUID/name resolution result for online or offline moderation targets. */
public record ResolvedTarget(UUID uuid, String name, Player onlinePlayer, StaffRank rank) {
    public boolean online() {
        return onlinePlayer != null && onlinePlayer.isOnline();
    }
}
