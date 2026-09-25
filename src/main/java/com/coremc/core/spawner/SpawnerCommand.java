package com.coremc.core.spawner;

import com.coremc.core.config.MessageService;
import com.coremc.core.shop.Money;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /spawner} (alias {@code /sp}) — the spawner progression
 * command. No arguments (or "menu") opens the spawner menu GUI;
 * otherwise: list, buy, info, upgrade, luck and luck upgrade, plus
 * the admin tools give, giveitem and setluck.
 */
public final class SpawnerCommand implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_SUBS = List.of("menu", "list", "buy", "info", "upgrade", "luck");
    private static final List<String> ADMIN_SUBS = List.of("menu", "list", "buy", "info", "upgrade", "luck",
            "give", "giveitem", "setluck");

    private final SpawnerConfig config;
    private final SpawnerService spawners;
    private final MessageService messages;
    private final SpawnerMenuGui menu;
    private final SpawnerUpgradeGui upgradeGui;

    public SpawnerCommand(final SpawnerConfig config, final SpawnerService spawners,
                          final MessageService messages, final SpawnerMenuGui menu,
                          final SpawnerUpgradeGui upgradeGui) {
        this.config = config;
        this.spawners = spawners;
        this.messages = messages;
        this.menu = menu;
        this.upgradeGui = upgradeGui;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (args.length == 0) {
            openMenu(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "menu", "gui" -> openMenu(sender);
            case "list" -> list(sender);
            case "buy" -> {
                if (requirePlayer(sender) && args.length >= 2) {
                    spawners.buy((Player) sender, args[1].toLowerCase());
                } else if (args.length < 2) {
                    messages.sendPrefixed(sender, "spawner.usage-buy");
                }
            }
            case "info" -> {
                if (requirePlayer(sender)) {
                    spawners.info((Player) sender);
                }
            }
            case "upgrade" -> {
                if (requirePlayer(sender)) {
                    final var context = spawners.upgradeContext((Player) sender);
                    if (context != null) {
                        upgradeGui.open((Player) sender, context);
                    }
                }
            }
            case "luck" -> {
                if (!requirePlayer(sender)) {
                    break;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("upgrade")) {
                    spawners.luckUpgrade((Player) sender);
                } else {
                    spawners.luckShow((Player) sender);
                }
            }
            case "give" -> {
                if (requireAdmin(sender) && args.length >= 3) {
                    final Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        messages.sendPrefixed(sender, "spawner.player-not-found", Map.of("player", args[1]));
                        break;
                    }
                    final SpawnerVariant variant = args.length >= 4
                            ? SpawnerVariant.of(args[3]) : SpawnerVariant.NORMAL;
                    spawners.give(target, args[2].toLowerCase(), variant);
                } else if (requireAdmin(sender)) {
                    messages.sendPrefixed(sender, "spawner.usage-give");
                }
            }
            case "giveitem" -> {
                if (requireAdmin(sender) && args.length >= 4) {
                    final Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        messages.sendPrefixed(sender, "spawner.player-not-found", Map.of("player", args[1]));
                        break;
                    }
                    final int amount;
                    try {
                        amount = args.length >= 5 ? Math.max(1, Math.min(2304, Integer.parseInt(args[4]))) : 1;
                    } catch (final NumberFormatException exception) {
                        messages.sendPrefixed(sender, "spawner.usage-giveitem");
                        break;
                    }
                    spawners.giveSystemItem(target, args[2].toLowerCase(), args[3].toLowerCase(), amount);
                } else if (requireAdmin(sender)) {
                    messages.sendPrefixed(sender, "spawner.usage-giveitem");
                }
            }
            case "setluck" -> {
                if (requireAdmin(sender) && args.length >= 3) {
                    final Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        messages.sendPrefixed(sender, "spawner.player-not-found", Map.of("player", args[1]));
                        break;
                    }
                    final int level;
                    try {
                        level = Integer.parseInt(args[2]);
                    } catch (final NumberFormatException exception) {
                        messages.sendPrefixed(sender, "spawner.usage-setluck");
                        break;
                    }
                    spawners.setLuck(target, level);
                    messages.sendPrefixed(sender, "spawner.set-luck-admin", Map.of(
                            "player", target.getName(), "level", String.valueOf(level)));
                } else if (requireAdmin(sender)) {
                    messages.sendPrefixed(sender, "spawner.usage-setluck");
                }
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    /** Opens the spawner menu GUI for players (console gets the help text). */
    private void openMenu(final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendHelp(sender);
            return;
        }
        menu.open(player);
    }

    private void list(final CommandSender sender) {
        messages.sendPrefixed(sender, "spawner.list-header");
        for (final SpawnerGroup group : config.groups()) {
            final StringBuilder line = new StringBuilder();
            for (final SpawnerMob mob : group.mobs()) {
                if (!line.isEmpty()) {
                    line.append(ColorUtil.colorize(" &8-> "));
                }
                line.append(ColorUtil.colorize("&f")).append(mob.name())
                        .append(ColorUtil.colorize(" &7(")).append(Money.format(mob.spawnerCost(), "$"))
                        .append(ColorUtil.colorize(")"));
            }
            sender.sendMessage(messages.prefix() + ColorUtil.colorize(
                    "&b" + group.name() + "&7: ") + line);
        }
    }

    private void sendHelp(final CommandSender sender) {
        messages.sendList(sender, "spawner.help");
        if (sender.hasPermission("coremc.spawner.admin")) {
            messages.sendList(sender, "spawner.help-admin");
        }
    }

    private boolean requirePlayer(final CommandSender sender) {
        if (sender instanceof Player) {
            return true;
        }
        messages.sendPrefixed(sender, "spawner.only-players");
        return false;
    }

    private boolean requireAdmin(final CommandSender sender) {
        if (!sender.hasPermission("coremc.spawner.admin")) {
            messages.sendPrefixed(sender, "spawner.no-permission");
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        final List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(sender.hasPermission("coremc.spawner.admin") ? ADMIN_SUBS : PLAYER_SUBS);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("buy"))) {
            for (final SpawnerGroup group : config.groups()) {
                for (final SpawnerMob mob : group.mobs()) {
                    options.add(mob.id());
                }
            }
        } else if (args.length == 2 && adminSub(args[0]) && sender.hasPermission("coremc.spawner.admin")) {
            Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")
                && sender.hasPermission("coremc.spawner.admin")) {
            for (final SpawnerGroup group : config.groups()) {
                for (final SpawnerMob mob : group.mobs()) {
                    options.add(mob.id());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("giveitem")
                && sender.hasPermission("coremc.spawner.admin")) {
            options.add("drop");
            options.add("relic");
        } else if (args.length == 4 && args[0].equalsIgnoreCase("giveitem")
                && sender.hasPermission("coremc.spawner.admin")) {
            final String kind = args[2].toLowerCase();
            for (final SpawnerGroup group : config.groups()) {
                if (kind.equals("relic")) {
                    options.add(group.id());
                } else if (kind.equals("drop")) {
                    group.mobs().forEach(mob -> options.add(mob.id()));
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")
                && sender.hasPermission("coremc.spawner.admin")) {
            for (final SpawnerVariant variant : SpawnerVariant.values()) {
                options.add(variant.name().toLowerCase());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setluck")
                && sender.hasPermission("coremc.spawner.admin")) {
            for (int level = 0; level <= config.luckMaxLevel(); level++) {
                options.add(String.valueOf(level));
            }
        }
        return filter(options, args.length - 1 >= 0 ? args[args.length - 1] : "");
    }

    private boolean adminSub(final String sub) {
        return sub.equalsIgnoreCase("give") || sub.equalsIgnoreCase("giveitem")
                || sub.equalsIgnoreCase("setluck");
    }

    private List<String> filter(final List<String> options, final String token) {
        final List<String> matches = new ArrayList<>();
        for (final String option : options) {
            if (option.toLowerCase().startsWith(token.toLowerCase())) {
                matches.add(option);
            }
        }
        return matches;
    }
}
