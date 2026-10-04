package com.coremc.core.scoreboard;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Lets each player hide or restore their CoreMC sidebar for the current session. */
public final class HudCommand implements CommandExecutor {

    private final CoreMCPlugin plugin;

    public HudCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
            final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can toggle the sidebar.");
            return true;
        }
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            player.sendMessage(ColorUtil.colorize("&cThe CoreMC sidebar is disabled."));
            return true;
        }
        final boolean visible = plugin.scoreboard().toggle(player);
        player.sendMessage(ColorUtil.colorize(visible
                ? "&aCoreMC sidebar enabled."
                : "&7CoreMC sidebar hidden. Run &f/hud &7to restore it."));
        return true;
    }
}
