package com.coremc.core.companion;

import com.coremc.core.CoreMCPlugin;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Opens the companion collection and summon menu. */
public final class CompanionsCommand implements CommandExecutor {

    private final CoreMCPlugin plugin;

    public CompanionsCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
            final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        plugin.gui().open(player, new CompanionsGui(plugin));
        return true;
    }
}
