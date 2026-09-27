package com.coremc.core.collections;

import com.coremc.core.config.MessageService;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /collections} (aliases {@code /collection}, {@code /coll}) —
 * the player-facing Collection command. No arguments opens the GUI;
 * the subcommands exist so everything the menu can do is also
 * scriptable and testable from chat.
 */
public final class CollectionsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("menu", "list", "info", "claim", "recipes", "held", "help");

    private final CollectionConfig config;
    private final CollectionService collections;
    private final CollectionsGui gui;
    private final MessageService messages;

    public CollectionsCommand(final CollectionConfig config, final CollectionService collections,
                              final CollectionsGui gui, final MessageService messages) {
        this.config = config;
        this.collections = collections;
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!config.enabled()) {
            messages.sendPrefixed(sender, "collections.disabled");
            return true;
        }
        if (args.length == 0) {
            open(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu", "gui" -> open(sender);
            case "help" -> messages.sendList(sender, "collections.help");
            case "list" -> list(sender);
            case "recipes" -> {
                if (requirePlayer(sender)) {
                    gui.openRecipes((Player) sender, 0);
                }
            }
            case "held", "pending" -> {
                if (requirePlayer(sender)) {
                    gui.openPending((Player) sender, 0);
                }
            }
            case "info" -> {
                if (requirePlayer(sender)) {
                    info((Player) sender, args.length > 1 ? args[1] : null);
                }
            }
            case "claim" -> {
                if (requirePlayer(sender)) {
                    claim((Player) sender, args);
                }
            }
            default -> messages.sendPrefixed(sender, "collections.unknown-subcommand");
        }
        return true;
    }

    private void open(final CommandSender sender) {
        if (requirePlayer(sender)) {
            gui.openRoot((Player) sender);
        }
    }

    private void list(final CommandSender sender) {
        messages.sendPrefixed(sender, "collections.list-header");
        for (final CollectionCategory category : config.categories()) {
            final int entries = config.byCategory(category).size();
            final String line = category.color() + "  " + GuiText.caps(category.display())
                    + " &8- &f" + entries + " &7"
                    + GuiText.caps(entries == 1 ? "collection" : "collections");
            sender.sendMessage(ColorUtil.colorize(sender instanceof Player player
                    ? line + " &8- &b"
                            + collections.categoryPercent(player.getUniqueId(), category) + "%"
                    : line));
        }
    }

    private void info(final Player player, final String rawId) {
        if (rawId == null) {
            messages.sendPrefixed(player, "collections.summary", Map.of(
                    "percent", String.valueOf(collections.totalPercent(player.getUniqueId())),
                    "complete", String.valueOf(collections.completedTotal(player.getUniqueId())),
                    "total", String.valueOf(config.all().size()),
                    "claimable", String.valueOf(collections.claimableCount(player.getUniqueId()))));
            return;
        }
        final CollectionEntry entry = config.byId(rawId);
        if (entry == null) {
            messages.sendPrefixed(player, "collections.unknown-collection", Map.of("id", rawId));
            return;
        }
        if (!collections.discovered(player.getUniqueId(), entry)) {
            messages.sendPrefixed(player, "collections.hidden");
            return;
        }
        final long amount = collections.amount(player.getUniqueId(), entry.id());
        messages.sendPrefixed(player, "collections.info", Map.of(
                "collection", entry.display(),
                "amount", GuiText.number(amount),
                "tier", String.valueOf(collections.tier(player.getUniqueId(), entry)),
                "tiers", String.valueOf(entry.tiers()),
                "percent", String.valueOf(CollectionProgress.percent(
                        CollectionProgress.entryCompletion(amount, entry)))));
    }

    private void claim(final Player player, final String[] args) {
        if (args.length < 3) {
            messages.sendPrefixed(player, "collections.usage-claim");
            return;
        }
        final int tier;
        try {
            tier = Integer.parseInt(args[2]);
        } catch (final NumberFormatException badNumber) {
            messages.sendPrefixed(player, "collections.usage-claim");
            return;
        }
        final CollectionEntry entry = config.byId(args[1]);
        if (entry == null) {
            messages.sendPrefixed(player, "collections.unknown-collection", Map.of("id", args[1]));
            return;
        }
        final ClaimResult result = collections.claim(player.getUniqueId(), entry.id(), tier);
        final Map<String, String> placeholders = Map.of(
                "collection", entry.display(), "tier", String.valueOf(tier));
        switch (result) {
            case CLAIMED -> messages.sendPrefixed(player, "collections.claimed", placeholders);
            case PENDING -> messages.sendPrefixed(player, "collections.claim-held", placeholders);
            case ALREADY_CLAIMED ->
                    messages.sendPrefixed(player, "collections.claim-already", placeholders);
            case NOT_REACHED ->
                    messages.sendPrefixed(player, "collections.claim-locked", placeholders);
            case NOTHING_TO_CLAIM ->
                    messages.sendPrefixed(player, "collections.claim-nothing", placeholders);
            default -> messages.sendPrefixed(player, "collections.unknown-collection",
                    Map.of("id", entry.id()));
        }
    }

    private boolean requirePlayer(final CommandSender sender) {
        if (sender instanceof Player) {
            return true;
        }
        messages.sendPrefixed(sender, "collections.only-players");
        return false;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        if (args.length == 2 && ("info".equalsIgnoreCase(args[0])
                || "claim".equalsIgnoreCase(args[0]))) {
            final List<String> ids = new ArrayList<>();
            for (final CollectionEntry entry : config.all()) {
                if (!(sender instanceof Player player)
                        || collections.discovered(player.getUniqueId(), entry)) {
                    ids.add(entry.id());
                }
            }
            return prefixed(ids, args[1]);
        }
        if (args.length == 3 && "claim".equalsIgnoreCase(args[0])) {
            final CollectionEntry entry = config.byId(args[1]);
            final List<String> tiers = new ArrayList<>();
            if (entry != null) {
                for (int tier = 1; tier <= entry.tiers(); tier++) {
                    tiers.add(String.valueOf(tier));
                }
            }
            return prefixed(tiers, args[2]);
        }
        return List.of();
    }

    private static List<String> prefixed(final List<String> options, final String typed) {
        final String needle = typed.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(option);
            }
        }
        return out;
    }
}
