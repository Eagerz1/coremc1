package com.coremc.core.event;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Player-facing status for the next scheduled Core Hour. */
public final class EventCommand implements CommandExecutor {

    private final CoreMCPlugin plugin;

    public EventCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
            final String label, final String[] args) {
        final EventService events = plugin.events();
        if (events.active()) {
            sender.sendMessage(ColorUtil.colorize("&b&lCORE HOUR &7is live for &f"
                    + EventService.formatDuration(events.remainingMillis()) + "&7."));
            sender.sendMessage(ColorUtil.colorize("&7Active bonuses: &a2x Island XP &8• &e2x Slaying Money"
                    + " &8• &d2x Omni-Tool XP&7."));
        } else {
            sender.sendMessage(ColorUtil.colorize("&b&lCORE HOUR &7starts in &f"
                    + EventService.formatDuration(events.remainingMillis()) + "&7."));
            sender.sendMessage(ColorUtil.colorize("&7Next event: &a2x Island XP &8• &e2x Slaying Money"
                    + " &8• &d2x Omni-Tool XP&7."));
        }
        return true;
    }
}
