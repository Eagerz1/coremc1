package com.coremc.core.rank;

import com.coremc.core.config.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /fly} — toggles survival flight. Requires any rank on the
 * ladder (Core and above); the service sends the deny line otherwise.
 */
public final class FlyCommand implements CommandExecutor {

    private final RankService ranks;
    private final MessageService messages;

    public FlyCommand(final RankService ranks, final MessageService messages) {
        this.ranks = ranks;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "rank.only-players");
            return true;
        }
        if (ranks == null) {
            messages.sendPrefixed(player, "rank.unavailable");
            return true;
        }
        ranks.toggleFly(player);
        return true;
    }
}
