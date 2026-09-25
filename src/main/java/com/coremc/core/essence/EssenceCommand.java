package com.coremc.core.essence;

import com.coremc.core.config.MessageService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /essence} (aliases {@code /essences}, {@code /ess}):
 *
 * <ul>
 *   <li>{@code /essence [balance]} — your Slayer/Mining/Farming
 *       balances and lifetime mob kills.</li>
 *   <li>{@code /essence balance <player>} — someone else's.</li>
 *   <li>{@code /essence give|take|set <player> <type> <amount>} —
 *       admin tools ({@code coremc.admin.essence}). {@code <type>} is
 *       {@code slayer|mining|farming}, plus {@code kills} for the
 *       lifetime kill count used by upgrade requirements.</li>
 * </ul>
 */
public final class EssenceCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "coremc.admin.essence";

    private final EssenceManager essences;
    private final MessageService messages;

    public EssenceCommand(final EssenceManager essences, final MessageService messages) {
        this.essences = essences;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("balance")) {
            if (!(sender instanceof Player player)) {
                messages.sendPrefixed(sender, "essence.only-players");
                return true;
            }
            if (args.length >= 2) {
                final Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    messages.sendPrefixed(sender, "spawner.player-not-found", Map.of("player", args[1]));
                    return true;
                }
                sendBalance(sender, target, true);
            } else {
                sendBalance(sender, player, false);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take")
                || args[0].equalsIgnoreCase("set")) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                messages.sendPrefixed(sender, "essence.no-permission");
                return true;
            }
            handleAdmin(sender, args);
            return true;
        }

        messages.sendPrefixed(sender, "essence.usage");
        return true;
    }

    private void handleAdmin(final CommandSender sender, final String[] args) {
        if (args.length != 4) {
            messages.sendPrefixed(sender, "essence.usage");
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "spawner.player-not-found", Map.of("player", args[1]));
            return;
        }
        final boolean isKills = args[2].equalsIgnoreCase("kills");
        final EssenceType type = EssenceType.of(args[2]);
        if (!isKills && type == null) {
            messages.sendPrefixed(sender, "essence.unknown-type", Map.of("type", args[2]));
            return;
        }
        final long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (final NumberFormatException exception) {
            messages.sendPrefixed(sender, "essence.usage");
            return;
        }
        final String typeName = isKills ? "Mob Kills" : type.display();

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (isKills) {
                    essences.addKills(target.getUniqueId(), amount);
                } else {
                    essences.give(target.getUniqueId(), type, amount);
                }
                messages.sendPrefixed(sender, "essence.admin-changed", Map.of(
                        "action", "Given", "amount", EssenceManager.format(Math.max(0, amount)),
                        "item", typeName, "player", target.getName()));
            }
            case "take" -> {
                if (isKills) {
                    final long held = essences.kills(target.getUniqueId());
                    essences.setKills(target.getUniqueId(), held - Math.max(0, amount));
                } else if (!essences.take(target.getUniqueId(), type, amount)) {
                    messages.sendPrefixed(sender, "essence.not-enough", Map.of(
                            "item", typeName, "player", target.getName(),
                            "amount", EssenceManager.format(essences.balance(target.getUniqueId(), type))));
                    return;
                }
                messages.sendPrefixed(sender, "essence.admin-changed", Map.of(
                        "action", "Taken", "amount", EssenceManager.format(Math.max(0, amount)),
                        "item", typeName, "player", target.getName()));
            }
            case "set" -> {
                if (isKills) {
                    essences.setKills(target.getUniqueId(), amount);
                } else {
                    essences.set(target.getUniqueId(), type, amount);
                }
                messages.sendPrefixed(sender, "essence.admin-changed", Map.of(
                        "action", "Set", "amount", EssenceManager.format(Math.max(0, amount)),
                        "item", typeName, "player", target.getName()));
            }
            default -> messages.sendPrefixed(sender, "essence.usage");
        }
    }

    private void sendBalance(final CommandSender sender, final Player target, final boolean other) {
        final Map<String, String> placeholders = Map.of(
                "player", target.getName(),
                "slayer", EssenceManager.format(essences.balance(target.getUniqueId(), EssenceType.SLAYER)),
                "mining", EssenceManager.format(essences.balance(target.getUniqueId(), EssenceType.MINING)),
                "farming", EssenceManager.format(essences.balance(target.getUniqueId(), EssenceType.FARMING)),
                "kills", EssenceManager.format(essences.kills(target.getUniqueId())));
        messages.sendPrefixed(sender, other ? "essence.balance-other" : "essence.balance", placeholders);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (args.length == 1) {
            final List<String> subs = new java.util.ArrayList<>(List.of("balance"));
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                subs.addAll(List.of("give", "take", "set"));
            }
            return subs;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take")
                || args[0].equalsIgnoreCase("set"))) {
            return null; // player names
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take")
                || args[0].equalsIgnoreCase("set"))) {
            return List.of("slayer", "mining", "farming", "kills");
        }
        return List.of();
    }
}
