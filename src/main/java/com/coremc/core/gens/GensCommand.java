package com.coremc.core.gens;

import com.coremc.core.config.MessageService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
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
 * {@code /gens} (aliases {@code /gen}, {@code /generators}) — the
 * generator progression command. No arguments (or "menu") opens the
 * /gens GUI; otherwise: list, buy, info, plus the admin give tool.
 */
public final class GensCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "coremc.gens.admin";
    private static final List<String> PLAYER_SUBS = List.of("menu", "list", "buy", "info", "help");
    private static final List<String> ADMIN_SUBS = List.of("menu", "list", "buy", "info", "help", "give");

    private final GeneratorConfig config;
    private final GeneratorService generators;
    private final GensMenuGui menu;
    private final GeneratorManageGui manageGui;
    private final MessageService messages;

    public GensCommand(final GeneratorConfig config, final GeneratorService generators,
                       final GensMenuGui menu, final GeneratorManageGui manageGui,
                       final MessageService messages) {
        this.config = config;
        this.generators = generators;
        this.menu = menu;
        this.manageGui = manageGui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!config.enabled()) {
            messages.sendPrefixed(sender, "gens.disabled");
            return true;
        }
        if (args.length == 0) {
            openMenu(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu", "gui" -> openMenu(sender);
            case "list" -> list(sender);
            case "help" -> messages.sendList(sender, "gens.help");
            case "buy" -> {
                if (!requirePlayer(sender)) {
                    break;
                }
                if (args.length < 2) {
                    messages.sendPrefixed(sender, "gens.usage-buy");
                    break;
                }
                int amount = 1;
                if (args.length >= 3) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
                    } catch (final NumberFormatException exception) {
                        messages.sendPrefixed(sender, "gens.usage-buy");
                        break;
                    }
                }
                generators.buy((Player) sender, args[1].toLowerCase(Locale.ROOT), amount);
            }
            case "info" -> {
                if (requirePlayer(sender)) {
                    info((Player) sender);
                }
            }
            case "give" -> give(sender, args);
            default -> messages.sendPrefixed(sender, "gens.unknown-subcommand");
        }
        return true;
    }

    private void openMenu(final CommandSender sender) {
        if (requirePlayer(sender)) {
            menu.open((Player) sender);
        }
    }

    /** {@code /gens list} — the whole progression, in chat. */
    private void list(final CommandSender sender) {
        messages.sendPrefixed(sender, "gens.list-header");
        for (final GeneratorTier tier : config.all()) {
            sender.sendMessage(ColorUtil.colorize("&8• " + tier.coloredName()
                    + " &8(&7" + tier.id() + "&8) "
                    + "&7" + GuiText.caps("tier") + " &f" + tier.tierNumeral()
                    + " &8| &7" + GuiText.money(tier.price())
                    + " &8| &a" + GuiText.money(tier.value())
                    + " &7/ &f" + GuiText.seconds(tier.intervalSeconds())));
        }
    }

    /** {@code /gens info} — the generator the player is looking at. */
    private void info(final Player player) {
        final org.bukkit.block.Block block = player.getTargetBlockExact(6);
        final GeneratorEntry entry = generators.entryAt(block);
        if (entry == null) {
            messages.sendPrefixed(player, "gens.look-at");
            return;
        }
        manageGui.open(player, entry);
    }

    /** {@code /gens give <player> <id> [amount]} — admin tool. */
    private void give(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "gens.no-permission");
            return;
        }
        if (args.length < 3) {
            messages.sendPrefixed(sender, "gens.usage-give");
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.sendPrefixed(sender, "gens.player-not-found", Map.of("player", args[1]));
            return;
        }
        final GeneratorTier tier = config.byId(args[2]);
        if (tier == null) {
            messages.sendPrefixed(sender, "gens.unknown-generator", Map.of("id", args[2]));
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (final NumberFormatException exception) {
                messages.sendPrefixed(sender, "gens.usage-give");
                return;
            }
        }
        generators.give(target, tier, amount);
    }

    private boolean requirePlayer(final CommandSender sender) {
        if (sender instanceof Player) {
            return true;
        }
        messages.sendPrefixed(sender, "gens.only-players");
        return false;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            final List<String> subs = sender.hasPermission(ADMIN_PERMISSION) ? ADMIN_SUBS : PLAYER_SUBS;
            for (final String sub : subs) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            return generatorIds(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (final Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT)
                        .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(online.getName());
                }
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return generatorIds(args[2]);
        }
        return out;
    }

    private List<String> generatorIds(final String prefix) {
        final List<String> out = new ArrayList<>();
        for (final GeneratorTier tier : config.all()) {
            if (tier.id().startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(tier.id());
            }
        }
        return out;
    }
}
