package com.coremc.core.moderation;

import com.coremc.core.CoreMCPlugin;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /vanish, /spectate, /cps, /rotate and /freeze. */
public final class StaffToolCommand implements CommandExecutor, TabCompleter {

    private final CoreMCPlugin plugin;
    private final ModerationService moderation;

    public StaffToolCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.moderation = plugin.moderation();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        return switch (command.getName().toLowerCase(java.util.Locale.ROOT)) {
            case "vanish" -> vanish(sender);
            case "spectate" -> spectate(sender, args);
            case "cps" -> cps(sender, args);
            case "rotate" -> rotate(sender, args);
            case "freeze" -> freeze(sender, args);
            default -> false;
        };
    }

    private boolean vanish(final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            moderation.prefixed(sender, "&cThat command can only be used by players.");
            return true;
        }
        if (!moderation.require(sender, "vanish")) {
            return true;
        }
        moderation.visibility().toggle(player);
        return true;
    }

    private boolean spectate(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player staff)) {
            moderation.prefixed(sender, "&cThat command can only be used by players.");
            return true;
        }
        if (!moderation.require(sender, "spectate")) {
            return true;
        }
        if (args.length == 0) {
            if (moderation.spectate().isSpectating(staff.getUniqueId())) {
                moderation.spectate().exit(staff, "manual exit");
            } else {
                moderation.prefixed(sender, "&cUsage: /spectate <player>");
            }
            return true;
        }
        final Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            moderation.prefixed(sender, "&cNo online player named '&f" + args[0] + "&c'.");
            return true;
        }
        final ActorContext actor = moderation.actor(sender);
        final ResolvedTarget resolved = new ResolvedTarget(target.getUniqueId(), target.getName(), target,
                moderation.rankOf(target));
        if (actor.uuid() != null && actor.uuid().equals(resolved.uuid())) {
            moderation.prefixed(sender, "&cYou cannot spectate yourself.");
            return true;
        }
        if (!actor.override() && resolved.rank().weight() > 0 && resolved.rank().weight() >= actor.rank().weight()) {
            moderation.prefixed(sender, "&cYou cannot spectate staff of equal or higher rank (&f"
                    + resolved.rank().display() + "&c).");
            return true;
        }
        moderation.spectate().startOrSwitch(staff, target);
        return true;
    }

    private boolean cps(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player staff)) {
            moderation.prefixed(sender, "&cThat command can only be used by players.");
            return true;
        }
        if (!moderation.require(sender, "cps")) {
            return true;
        }
        if (args.length != 1) {
            moderation.prefixed(sender, "&cUsage: /cps <player>");
            return true;
        }
        final Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            moderation.prefixed(sender, "&cNo online player named '&f" + args[0] + "&c'.");
            return true;
        }
        moderation.cps().start(staff, target);
        return true;
    }

    private boolean rotate(final CommandSender sender, final String[] args) {
        if (!moderation.require(sender, "rotate")) {
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            moderation.prefixed(sender, "&cUsage: /rotate <player> [degrees]");
            return true;
        }
        final double degrees;
        if (args.length == 2) {
            try {
                degrees = Double.parseDouble(args[1]);
            } catch (final NumberFormatException exception) {
                moderation.prefixed(sender, "&cDegrees must be a number.");
                return true;
            }
        } else {
            degrees = 180.0D;
        }
        moderation.executeRotate(sender, args[0], degrees);
        return true;
    }

    private boolean freeze(final CommandSender sender, final String[] args) {
        if (!moderation.require(sender, "freeze")) {
            return true;
        }
        if (args.length < 1) {
            moderation.prefixed(sender, "&cUsage: /freeze <player> [reason]");
            return true;
        }
        moderation.executeFreeze(sender, args[0], join(args, 1));
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final String name = command.getName().toLowerCase(java.util.Locale.ROOT);
        final List<String> completions = new ArrayList<>();
        if (name.equals("vanish")) {
            return completions;
        }
        if (args.length == 1 && switch (name) {
            case "spectate" -> moderation.hasCapability(sender, "spectate");
            case "cps" -> moderation.hasCapability(sender, "cps");
            case "rotate" -> moderation.hasCapability(sender, "rotate");
            case "freeze" -> moderation.hasCapability(sender, "freeze");
            default -> false;
        }) {
            return moderation.tabOnlinePlayers(args[0]);
        }
        if (name.equals("rotate") && args.length == 2 && moderation.hasCapability(sender, "rotate")) {
            for (final String degree : List.of("90", "180", "270", "360")) {
                if (degree.startsWith(args[1])) {
                    completions.add(degree);
                }
            }
        }
        return completions;
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
