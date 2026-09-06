package com.coremc.core.shop;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /shop} — the shop hub; {@code /shop <category>} jumps straight
 * into gear/food/end/nether; {@code /tokenshop} opens the exchange.
 */
public final class ShopCommand implements CommandExecutor, TabCompleter {

    private final CoreMCPlugin plugin;

    public ShopCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        final MessageService messages = plugin.messages();
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        if ("tokenshop".equalsIgnoreCase(command.getName())) {
            plugin.gui().open(player, new TokenShopGui(plugin));
            return true;
        }
        if (args.length == 0) {
            plugin.gui().open(player, new ShopMainGui(plugin));
            return true;
        }
        final ShopCategory category = ShopCategory.byKey(args[0]);
        if (category == null || category == ShopCategory.TOKENS) {
            messages.sendPrefixed(player, "shop.unknown-category", Map.of("input", args[0]));
            return true;
        }
        plugin.gui().open(player, new ShopCategoryGui(plugin, category));
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1 && !"tokenshop".equalsIgnoreCase(command.getName())) {
            final String partial = args[0].toLowerCase();
            for (final ShopCategory category : ShopCategory.values()) {
                if (category != ShopCategory.TOKENS && category.key().startsWith(partial)) {
                    out.add(category.key());
                }
            }
        }
        return out;
    }
}
