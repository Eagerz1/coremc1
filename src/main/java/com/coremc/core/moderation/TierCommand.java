package com.coremc.core.moderation;

import com.coremc.core.CoreMCPlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** /t1 through /t5 rule-based escalation commands. */
public final class TierCommand implements CommandExecutor, TabCompleter {

    private final ModerationService moderation;
    private final int tier;

    public TierCommand(final CoreMCPlugin plugin, final int tier) {
        this.moderation = plugin.moderation();
        this.tier = tier;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length < 2) {
            moderation.prefixed(sender, "&cUsage: /" + label + " <rule> <player> [custom reason]");
            return true;
        }
        moderation.executeTier(sender, tier, args[0], args[1], args.length <= 2 ? List.of()
                : new ArrayList<>(Arrays.asList(args).subList(2, args.length)));
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!moderation.hasCapability(sender, "tier." + tier)) {
            return List.of();
        }
        if (args.length == 1) {
            final String partial = args[0].toLowerCase(Locale.ROOT);
            final List<String> completions = new ArrayList<>();
            for (final String key : moderation.config().ruleKeys(tier, false)) {
                if (key.startsWith(partial)) {
                    completions.add(key);
                }
            }
            return completions;
        }
        if (args.length == 2) {
            return moderation.tabOnlinePlayers(args[1]);
        }
        if (args.length == 3) {
            final RuleDefinition rule = moderation.config().rule(tier, args[0]).orElse(null);
            if (rule != null && rule.requiresAcknowledgement() && "--ack".startsWith(args[2].toLowerCase(Locale.ROOT))) {
                return List.of("--ack");
            }
        }
        return List.of();
    }
}
