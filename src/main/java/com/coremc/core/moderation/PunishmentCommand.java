package com.coremc.core.moderation;

import com.coremc.core.CoreMCPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** Direct moderation commands: kick, mute, ban, unmute and unban. */
public final class PunishmentCommand implements CommandExecutor, TabCompleter {

    private final ModerationService moderation;

    public PunishmentCommand(final CoreMCPlugin plugin) {
        this.moderation = plugin.moderation();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "kick" -> kick(sender, args);
            case "mute" -> mute(sender, args);
            case "ban" -> ban(sender, args);
            case "unmute" -> unmute(sender, args);
            case "unban" -> unban(sender, args);
            default -> false;
        };
    }

    private boolean kick(final CommandSender sender, final String[] args) {
        if (args.length < 1) {
            moderation.prefixed(sender, "&cUsage: /kick <player> [reason]");
            return true;
        }
        moderation.executeKick(sender, args[0], join(args, 1));
        return true;
    }

    private boolean mute(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            moderation.prefixed(sender, "&cUsage: /mute <player> <duration|permanent> [reason]");
            return true;
        }
        moderation.executeMuteOrBan(sender, PunishmentType.MUTE, args[0], args[1], join(args, 2));
        return true;
    }

    private boolean ban(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            moderation.prefixed(sender, "&cUsage: /ban <player> <duration|permanent> [reason]");
            return true;
        }
        moderation.executeMuteOrBan(sender, PunishmentType.BAN, args[0], args[1], join(args, 2));
        return true;
    }

    private boolean unmute(final CommandSender sender, final String[] args) {
        if (args.length < 1) {
            moderation.prefixed(sender, "&cUsage: /unmute <player> [reason]");
            return true;
        }
        moderation.executeUnpunish(sender, PunishmentType.MUTE, args[0], join(args, 1));
        return true;
    }

    private boolean unban(final CommandSender sender, final String[] args) {
        if (args.length < 1) {
            moderation.prefixed(sender, "&cUsage: /unban <player> [reason]");
            return true;
        }
        moderation.executeUnpunish(sender, PunishmentType.BAN, args[0], join(args, 1));
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            final String capability = switch (name) {
                case "kick" -> "kick";
                case "mute" -> "mute";
                case "ban" -> "ban";
                case "unmute" -> "unmute";
                case "unban" -> "unban";
                default -> "";
            };
            if (!capability.isBlank() && moderation.hasCapability(sender, capability)) {
                return moderation.tabOnlinePlayers(args[0]);
            }
        }
        if ((name.equals("mute") || name.equals("ban")) && args.length == 2
                && moderation.hasCapability(sender, name)) {
            final List<String> completions = new ArrayList<>();
            for (final String duration : List.of("5m", "15m", "30m", "1h", "6h", "12h", "1d", "3d", "7d", "30d", "permanent")) {
                if (duration.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    completions.add(duration);
                }
            }
            return completions;
        }
        return List.of();
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
