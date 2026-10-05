package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.island.IslandProgressionCatalog;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /spawners} — opens the spawner unlock/purchase GUIs. */
public final class SpawnersCommand implements CommandExecutor {

    private final CoreMCPlugin plugin;
    private final MessageService messages;

    public SpawnersCommand(final CoreMCPlugin plugin) {
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
        final int required = IslandProgressionCatalog.requiredIslandLevel("spawners");
        if (level < required) {
            player.sendMessage(com.coremc.core.util.ColorUtil.colorize(
                    "&cSlaying unlocks at island level &f" + required + "&c. Your island is level &f" + level + "&c."));
            return true;
        }
        plugin.gui().open(player, new SpawnersGui(plugin));
        return true;
    }
}
