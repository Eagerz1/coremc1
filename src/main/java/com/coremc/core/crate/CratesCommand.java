package com.coremc.core.crate;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /crates} — opens the crate lineup (preview + open panels). */
public final class CratesCommand implements CommandExecutor {

    private final CoreMCPlugin plugin;
    private final MessageService messages;

    public CratesCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        if (plugin.crates().all().isEmpty()) {
            messages.sendPrefixed(player, "crate.empty", Map.of());
            return true;
        }
        plugin.gui().open(player, new CratesGui(plugin));
        return true;
    }
}
