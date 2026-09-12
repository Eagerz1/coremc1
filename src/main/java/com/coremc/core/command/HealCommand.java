package com.coremc.core.command;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /heal [player]} — restores a player's health, hunger and
 * extinguishes fire.
 *
 * No-argument form heals the sender (player-only). The targeted form
 * requires {@code coremc.command.heal.others} and also works from the
 * console.
 */
public final class HealCommand implements CommandExecutor, TabCompleter {

    private static final double FULL_FOOD_LEVEL = 20.0;

    private final MessageService messages;

    public HealCommand(final CoreMCPlugin plugin) {
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.sendPrefixed(sender, "player-only", Map.of());
                return true;
            }
            heal(player);
            messages.sendPrefixed(sender, "heal.self", Map.of());
            return true;
        }

        if (!sender.hasPermission("coremc.command.heal.others")) {
            messages.sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }

        final Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.sendPrefixed(sender, "player-not-online", Map.of("player", args[0]));
            return true;
        }

        heal(target);
        if (!target.equals(sender)) {
            messages.sendPrefixed(sender, "heal.other", Map.of("player", target.getName()));
            messages.sendPrefixed(target, "heal.by-other", Map.of("sender", sender.getName()));
        } else {
            messages.sendPrefixed(sender, "heal.self", Map.of());
        }
        return true;
    }

    /** Restores health and hunger, and puts out any fire. */
    private void heal(final Player player) {
        final double maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        player.setHealth(maxHealth);
        player.setFoodLevel((int) FULL_FOOD_LEVEL);
        player.setSaturation((float) FULL_FOOD_LEVEL);
        player.setFireTicks(0);
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> completions = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("coremc.command.heal.others")) {
            final String partial = args[0].toLowerCase();
            for (final Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(partial)) {
                    completions.add(online.getName());
                }
            }
        }
        return completions;
    }
}
