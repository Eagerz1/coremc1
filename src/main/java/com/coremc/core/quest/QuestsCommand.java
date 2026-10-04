package com.coremc.core.quest;

import com.coremc.core.CoreMCPlugin;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Opens the daily mission board. */
public final class QuestsCommand implements CommandExecutor {

    private final CoreMCPlugin plugin;

    public QuestsCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
            final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        plugin.gui().open(player, new QuestsGui(plugin));
        return true;
    }
}
