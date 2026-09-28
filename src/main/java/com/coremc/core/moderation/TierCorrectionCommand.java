package com.coremc.core.moderation;

import com.coremc.core.CoreMCPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** /tiercorrect <player> <tier> <set|add> <amount> [reason]. */
public final class TierCorrectionCommand implements CommandExecutor, TabCompleter {

    private final ModerationService moderation;

    public TierCorrectionCommand(final CoreMCPlugin plugin) {
        this.moderation = plugin.moderation();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length < 4) {
            moderation.prefixed(sender, "&cUsage: /tiercorrect <player> <tier> <set|add> <amount> [reason]");
            return true;
        }
        final int tier;
        final int amount;
        try {
            tier = Integer.parseInt(args[1]);
            amount = Integer.parseInt(args[3]);
        } catch (final NumberFormatException exception) {
            moderation.prefixed(sender, "&cTier and amount must be numbers.");
            return true;
        }
        moderation.executeTierCorrection(sender, args[0], tier, args[2], amount, join(args, 4));
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!moderation.hasCapability(sender, "correction")) {
            return List.of();
        }
        if (args.length == 1) {
            return moderation.tabOnlinePlayers(args[0]);
        }
        if (args.length == 2) {
            return starts(List.of("1", "2", "3", "4", "5"), args[1]);
        }
        if (args.length == 3) {
            return starts(List.of("set", "add"), args[2]);
        }
        return List.of();
    }

    private static List<String> starts(final List<String> values, final String partial) {
        final String lower = partial.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String value : values) {
            if (value.startsWith(lower)) {
                out.add(value);
            }
        }
        return out;
    }

    private static String join(final String[] args, final int start) {
        if (start >= args.length) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }
}
