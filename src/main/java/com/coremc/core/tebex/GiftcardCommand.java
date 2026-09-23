package com.coremc.core.tebex;

import com.coremc.core.config.MessageService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /giftcard} ({@code /gc}) — your Tebex webstore gift card.
 *
 * <ul>
 *   <li>{@code /gc} — shows your gift card number (click it to copy)
 *       with its live balance beside it,</li>
 *   <li>{@code /gc link <code>} — validates the code against the Tebex
 *       Plugin API and links it to you.</li>
 * </ul>
 *
 * The number line is a component (click-to-copy needs one); everything
 * else goes through MessageService with the standard prefix.
 */
public final class GiftcardCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("link");

    private final JavaPlugin plugin;
    private final TebexConfig config;
    private final TebexClient tebex;
    private final GiftcardStore store;
    private final MessageService messages;

    public GiftcardCommand(final JavaPlugin plugin, final TebexConfig config,
                           final TebexClient tebex, final GiftcardStore store,
                           final MessageService messages) {
        this.plugin = plugin;
        this.config = config;
        this.tebex = tebex;
        this.store = store;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "tebex.only-players");
            return true;
        }
        if (!config.enabled()) {
            messages.sendPrefixed(player, "tebex.disabled");
            return true;
        }
        if (args.length >= 1 && "link".equalsIgnoreCase(args[0])) {
            if (args.length < 2 || args[1].isBlank()) {
                messages.sendPrefixed(player, "tebex.link-usage");
                return true;
            }
            link(player, args[1].trim());
            return true;
        }
        show(player);
        return true;
    }

    // ------------------------------------------------------------------
    // /gc — display the linked card
    // ------------------------------------------------------------------

    private void show(final Player player) {
        final UUID playerId = player.getUniqueId();
        final String code = store.codeOf(playerId);
        if (code == null) {
            messages.sendPrefixed(player, "tebex.not-linked");
            return;
        }
        messages.sendPrefixed(player, "tebex.card-header");
        tebex.lookup(code).thenAccept(lookup ->
                plugin.getServer().getScheduler().runTask(
                        plugin, () -> renderCard(player, code, lookup)));
    }

    private void renderCard(final Player player, final String code, final TebexClient.Lookup lookup) {
        if (!player.isOnline()) {
            return;
        }
        final Component prefix = LegacyComponentSerializer.legacySection()
                .deserialize(messages.prefix());
        final Component line;
        if (lookup.status() == TebexClient.LookupStatus.FOUND) {
            line = GiftcardLine.cardLine(messages.prefix(), code,
                    lookup.giftcard().formatted());
        } else if (lookup.status() == TebexClient.LookupStatus.NOT_FOUND) {
            line = Component.empty()
                    .append(prefix)
                    .append(Component.text("Giftcard: ", NamedTextColor.GRAY))
                    .append(GiftcardLine.number(code))
                    .append(Component.text("  Balance: ", NamedTextColor.GRAY))
                    .append(Component.text("no longer valid", NamedTextColor.RED));
        } else {
            line = Component.empty()
                    .append(prefix)
                    .append(Component.text("Giftcard: ", NamedTextColor.GRAY))
                    .append(GiftcardLine.number(code))
                    .append(Component.text("  Balance: ", NamedTextColor.GRAY))
                    .append(Component.text("unavailable (Tebex unreachable)",
                            NamedTextColor.RED));
        }
        player.sendMessage(line);
    }

    // ------------------------------------------------------------------
    // /gc link <code>
    // ------------------------------------------------------------------

    private void link(final Player player, final String code) {
        messages.sendPrefixed(player, "tebex.checking");
        tebex.lookup(code).thenAccept(lookup ->
                plugin.getServer().getScheduler().runTask(
                        plugin, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (lookup.status() != TebexClient.LookupStatus.FOUND) {
                                messages.sendPrefixed(player,
                                        lookup.status() == TebexClient.LookupStatus.NOT_FOUND
                                                ? "tebex.invalid-code" : "tebex.unreachable");
                                return;
                            }
                            try {
                                store.link(player.getUniqueId(), lookup.giftcard().code());
                            } catch (final java.io.IOException exception) {
                                messages.sendPrefixed(player, "tebex.store-failed");
                                return;
                            }
                            messages.sendPrefixed(player, "tebex.linked",
                                    Map.of("code", lookup.giftcard().code()));
                            renderCard(player, lookup.giftcard().code(), lookup);
                        }));
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase(java.util.Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
