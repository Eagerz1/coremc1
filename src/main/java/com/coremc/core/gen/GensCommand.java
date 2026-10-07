package com.coremc.core.gen;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.island.IslandProgressionCatalog;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /gens} — opens the generator market GUI. */
public final class GensCommand implements CommandExecutor {

    private final CoreMCPlugin plugin;
    private final MessageService messages;

    public GensCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        final var island = plugin.islands().islandOf(player.getUniqueId()).orElse(null);
        if (island == null) {
            messages.sendPrefixed(player, "island.none", Map.of());
            return true;
        }
        final int level = plugin.islandProgress().levelFor(island);
        final int required = IslandProgressionCatalog.requiredIslandLevel("generators");
        if (level < required) {
            player.sendMessage(com.coremc.core.util.ColorUtil.colorize(
                    "&cGenerators unlock at island level &f" + required + "&c. Your island is level &f" + level + "&c."));
            return true;
        }
        plugin.gui().open(player, new GensGui(plugin));
        return true;
    }
}
