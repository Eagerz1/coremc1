package com.coremc.core.companion;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.island.IslandProgressionCatalog;
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
        final var island = plugin.islands().islandOf(player.getUniqueId()).orElse(null);
        if (island == null) {
            plugin.messages().sendPrefixed(player, "island.none", Map.of());
            return true;
        }
        final int level = plugin.islandProgress().levelFor(island);
        final int required = IslandProgressionCatalog.requiredIslandLevel("companions");
        if (level < required) {
            player.sendMessage(com.coremc.core.util.ColorUtil.colorize(
                    "&cCompanions unlock at island level &f" + required + "&c. Your island is level &f" + level + "&c."));
            return true;
        }
        plugin.gui().open(player, new CompanionsGui(plugin));
        return true;
    }
}
