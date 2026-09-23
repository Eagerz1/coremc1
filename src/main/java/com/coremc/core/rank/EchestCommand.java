package com.coremc.core.rank;

import com.coremc.core.config.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /echest} (aliases {@code /ec}, {@code /enderchest}) — opens
 * the player's ender chest anywhere. Requires any rank (Core and up);
 * the ender chest is the real vanilla one, so contents persist.
 */
public final class EchestCommand implements CommandExecutor {

    private final RankService ranks;
    private final MessageService messages;

    public EchestCommand(final RankService ranks, final MessageService messages) {
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
        ranks.openEchest(player);
        return true;
    }
}
