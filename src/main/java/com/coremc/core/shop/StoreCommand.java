package com.coremc.core.shop;
import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
/** Opens the CoreMC Credits store. */
public final class StoreCommand implements CommandExecutor {
    private final CoreMCPlugin plugin;
    private final MessageService messages;
    public StoreCommand(final CoreMCPlugin plugin) { this.plugin = plugin; this.messages = plugin.messages(); }
    @Override public boolean onCommand(final CommandSender sender, final Command command,
            final String label, final String[] args) {
        if (!(sender instanceof Player player)) { messages.sendPrefixed(sender, "player-only", Map.of()); return true; }
        plugin.gui().open(player, new StoreGui(plugin));
        return true;
    }
}