package com.coremc.core.admin;

import com.coremc.core.CoreMCPlugin;
import java.io.IOException;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Administrative island and profile reset that preserves Credits. */
public final class WipeCommand implements CommandExecutor {
    private final CoreMCPlugin plugin;

    public WipeCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
            final String label, final String[] args) {
        if (!sender.hasPermission("coremc.admin.wipe")) {
            plugin.messages().sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length != 1) {
            plugin.messages().sendPrefixed(sender, "wipe.usage", Map.of());
            return true;
        }
        final var uuid = plugin.playerData().resolveUuid(args[0]);
        if (uuid.isEmpty()) {
            plugin.messages().sendPrefixed(sender, "wipe.unknown-player", Map.of("player", args[0]));
            return true;
        }
        try {
            plugin.islands().removeForWipe(uuid.get());
        } catch (final IOException | RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not remove island for wipe: " + args[0], exception);
            plugin.messages().sendPrefixed(sender, "wipe.failed", Map.of("player", args[0]));
            return true;
        }
        plugin.playerData().wipeProfile(uuid.get(), success -> plugin.messages().sendPrefixed(sender,
                success ? "wipe.success" : "wipe.failed", Map.of("player", args[0])));
        return true;
    }
}
