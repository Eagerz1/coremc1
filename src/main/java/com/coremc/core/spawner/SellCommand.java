package com.coremc.core.spawner;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Sells authenticated CoreMC rare drops held in the player's inventory. */
public final class SellCommand implements CommandExecutor {

    private final CoreMCPlugin plugin;

    public SellCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            player.sendMessage(ColorUtil.colorize("&cYour profile is still loading."));
            return true;
        }
        final long payout = plugin.rareDrops().sellAll(player, profile);
        if (payout <= 0L) {
            player.sendMessage(ColorUtil.colorize("&eYou have no sellable CoreMC rare drops, or your balance cannot fit the payout."));
            return true;
        }
        player.sendMessage(ColorUtil.colorize("&aSold your CoreMC rare drops for &f" + String.format(java.util.Locale.ROOT, "%,d", payout) + " &aCore Money."));
        return true;
    }
}
