package com.coremc.core.admin;

import com.coremc.core.CoreMCPlugin;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Time-boxed island leaderboard disqualification command. */
public final class DqCommand implements CommandExecutor {
    private final CoreMCPlugin plugin;

    public DqCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
            final String label, final String[] args) {
        if (!sender.hasPermission("coremc.admin.dq")) {
            plugin.messages().sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length < 3) {
            plugin.messages().sendPrefixed(sender, "dq.usage", Map.of());
            return true;
        }
        final int weeks;
        try {
            weeks = Integer.parseInt(args[1]);
        } catch (final NumberFormatException exception) {
            plugin.messages().sendPrefixed(sender, "dq.invalid-weeks", Map.of());
            return true;
        }
        if (weeks < 1 || weeks > 8) {
            plugin.messages().sendPrefixed(sender, "dq.invalid-weeks", Map.of());
            return true;
        }
        final UUID owner = plugin.playerData().resolveUuid(args[0]).orElse(null);
        if (owner == null) {
            plugin.messages().sendPrefixed(sender, "dq.unknown-player", Map.of("player", args[0]));
            return true;
        }
        if (plugin.islands().ownedIsland(owner).isEmpty()) {
            plugin.messages().sendPrefixed(sender, "dq.no-island", Map.of("player", args[0]));
            return true;
        }
        final String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        try {
            plugin.islandDisqualifications().disqualify(owner, weeks, reason, sender.getName());
        } catch (final IOException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not save island DQ.", exception);
            plugin.messages().sendPrefixed(sender, "dq.usage", Map.of());
            return true;
        }
        plugin.messages().sendPrefixed(sender, "dq.success",
                Map.of("player", args[0], "weeks", String.valueOf(weeks), "reason", reason));
        return true;
    }
}
