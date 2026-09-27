package com.coremc.core.moderation;

import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Immutable moderation actor snapshot captured on the server thread. */
public record ActorContext(UUID uuid, String name, StaffRank rank, boolean permissionWildcard, boolean override) {

    public static ActorContext console(final CommandSender sender) {
        return new ActorContext(null, sender.getName(), StaffRank.console(), true, true);
    }

    public static ActorContext player(final Player player, final StaffRank rank) {
        final boolean wildcard = player.hasPermission("coremc.moderation.*") || player.hasPermission("coremc.*");
        final boolean override = wildcard || player.hasPermission("coremc.moderation.override") || rank.has("override");
        return new ActorContext(player.getUniqueId(), player.getName(), rank, wildcard, override);
    }

    public boolean has(final String capability) {
        return permissionWildcard || rank.has(capability);
    }

    public boolean isConsole() {
        return uuid == null;
    }
}
