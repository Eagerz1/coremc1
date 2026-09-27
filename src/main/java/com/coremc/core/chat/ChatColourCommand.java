package com.coremc.core.chat;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.ColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /chatcolour} (alias {@code /chatcolor}) — message colour picker.
 *
 * Players:
 *   /chatcolour                open the GUI
 *   /chatcolour set <id>       pick a solid colour or gradient you own
 *   /chatcolour bold           toggle bold
 *   /chatcolour reset          back to the default
 *   /chatcolour list           list styles and your unlock state
 *
 * Staff ({@code coremc.admin.chatcolour}):
 *   /chatcolour grant  <player> <id>
 *   /chatcolour revoke <player> <id>
 *   /chatcolour check  <player>
 *   /chatcolour reset  <player>
 */
public final class ChatColourCommand implements CommandExecutor, TabCompleter {

    /** Staff permission for grant/revoke/check/reset-others. */
    public static final String ADMIN_PERMISSION = "coremc.admin.chatcolour";

    private static final List<String> PLAYER_SUBS = List.of("set", "bold", "reset", "list");
    private static final List<String> ADMIN_SUBS = List.of("grant", "revoke", "check");

    private final CoreMCPlugin plugin;
    private final MessageService messages;

    public ChatColourCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.sendPrefixed(sender, "player-only", Map.of());
                return true;
            }
            plugin.gui().open(player, new ChatColourGui(plugin));
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "set", "select" -> set(sender, args);
            case "bold" -> bold(sender);
            case "reset", "clear", "none" -> reset(sender, args);
            case "list" -> list(sender);
            case "grant", "give", "unlock" -> grant(sender, args);
            case "revoke", "take", "remove" -> revoke(sender, args);
            case "check", "info" -> check(sender, args);
            default -> {
                messages.sendPrefixed(sender, "chatcolour.usage", Map.of());
                yield true;
            }
        };
    }

    private boolean set(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        if (args.length < 2) {
            messages.sendPrefixed(player, "chatcolour.usage", Map.of());
            return true;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            messages.sendPrefixed(player, "island.starting", Map.of());
            return true;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        final CosmeticAccess.Result result = plugin.chatStyles().select(player, profile, id);
        final String display = plugin.chatStyles().byId(id)
                .map(style -> ColorUtil.colorize(style.display())).orElse(id);
        switch (result) {
            case SELECTED -> messages.sendPrefixed(player, "chatcolour.selected", Map.of("style", display));
            case CLEARED -> messages.sendPrefixed(player, "chatcolour.reset", Map.of());
            case LOCKED -> messages.sendPrefixed(player, "chatcolour.locked", Map.of("style", display));
            case UNKNOWN -> messages.sendPrefixed(player, "chatcolour.unknown", Map.of("style", args[1]));
            case UNCHANGED -> messages.sendPrefixed(player, "chatcolour.already", Map.of("style", display));
            default -> { }
        }
        return true;
    }

    private boolean bold(final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            messages.sendPrefixed(player, "island.starting", Map.of());
            return true;
        }
        if (!plugin.chatStyles().boldAllowed(player)) {
            messages.sendPrefixed(player, "chatcolour.bold-locked", Map.of());
            return true;
        }
        final boolean state = plugin.chatStyles().toggleBold(player, profile);
        messages.sendPrefixed(player, "chatcolour.bold", Map.of("state", state ? "&aON" : "&cOFF"));
        return true;
    }

    private boolean reset(final CommandSender sender, final String[] args) {
        if (args.length >= 2) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                messages.sendPrefixed(sender, "no-permission", Map.of());
                return true;
            }
            final Optional<UUID> target = resolve(args[1]);
            if (target.isEmpty()) {
                messages.sendPrefixed(sender, "tags.player-unknown", Map.of("player", args[1]));
                return true;
            }
            plugin.chatStyles().resetAsync(target.get(), changed -> messages.sendPrefixed(
                    sender, changed ? "chatcolour.admin-reset" : "chatcolour.admin-nothing",
                    Map.of("player", args[1])));
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            messages.sendPrefixed(player, "island.starting", Map.of());
            return true;
        }
        plugin.chatStyles().reset(profile);
        messages.sendPrefixed(player, "chatcolour.reset", Map.of());
        return true;
    }

    private boolean list(final CommandSender sender) {
        final Player player = sender instanceof Player p ? p : null;
        final PlayerProfile profile =
                player == null ? null : plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        final ChatStyleService styles = plugin.chatStyles();
        messages.send(sender, "chatcolour.list-header", Map.of(
                "solids", String.valueOf(styles.catalog().solids().size()),
                "gradients", String.valueOf(styles.catalog().gradients().size())));
        for (final ChatStyle style : styles.catalog().all()) {
            final boolean owned = styles.owns(player, profile, style);
            final boolean selected = profile != null && style.id().equals(profile.chatColor());
            messages.send(sender, "chatcolour.list-line", Map.of(
                    "id", style.id(),
                    "kind", style.gradient() ? "gradient" : "colour",
                    "style", ColorUtil.colorize(style.display()),
                    "state", selected ? "&a(selected)" : owned ? "&a(unlocked)" : "&c(locked)"));
        }
        return true;
    }

    private boolean grant(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length < 3) {
            messages.sendPrefixed(sender, "chatcolour.admin-usage", Map.of());
            return true;
        }
        final String id = args[2].toLowerCase(Locale.ROOT);
        if (plugin.chatStyles().byId(id).isEmpty()) {
            messages.sendPrefixed(sender, "chatcolour.unknown", Map.of("style", args[2]));
            return true;
        }
        final Optional<UUID> target = resolve(args[1]);
        if (target.isEmpty()) {
            messages.sendPrefixed(sender, "tags.player-unknown", Map.of("player", args[1]));
            return true;
        }
        plugin.chatStyles().grantAsync(target.get(), id, granted -> messages.sendPrefixed(
                sender, granted ? "chatcolour.admin-granted" : "chatcolour.admin-already",
                Map.of("player", args[1], "style", id)));
        return true;
    }

    private boolean revoke(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length < 3) {
            messages.sendPrefixed(sender, "chatcolour.admin-usage", Map.of());
            return true;
        }
        final Optional<UUID> target = resolve(args[1]);
        if (target.isEmpty()) {
            messages.sendPrefixed(sender, "tags.player-unknown", Map.of("player", args[1]));
            return true;
        }
        plugin.chatStyles().revokeAsync(target.get(), args[2].toLowerCase(Locale.ROOT),
                revoked -> messages.sendPrefixed(
                        sender, revoked ? "chatcolour.admin-revoked" : "chatcolour.admin-nothing",
                        Map.of("player", args[1], "style", args[2])));
        return true;
    }

    private boolean check(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length < 2) {
            messages.sendPrefixed(sender, "chatcolour.admin-usage", Map.of());
            return true;
        }
        final Optional<UUID> target = resolve(args[1]);
        if (target.isEmpty()) {
            messages.sendPrefixed(sender, "tags.player-unknown", Map.of("player", args[1]));
            return true;
        }
        final UUID uuid = target.get();
        plugin.playerData().ioExecute(() -> {
            final PlayerProfile profile = plugin.playerData().cachedOrLoad(uuid).orElse(null);
            final String owned = profile == null || profile.ownedChatStyles().isEmpty()
                    ? "none" : String.join(", ", new java.util.TreeSet<>(profile.ownedChatStyles()));
            final String selected = profile == null ? "none" : profile.chatColor();
            final String bold = profile != null && profile.chatBold() ? "on" : "off";
            plugin.tasks().runLater(() -> messages.sendPrefixed(sender, "chatcolour.admin-check", Map.of(
                    "player", args[1], "owned", owned, "selected", selected, "bold", bold)), 1L);
        });
        return true;
    }

    private Optional<UUID> resolve(final String name) {
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        return plugin.playerData().resolveUuid(name);
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            final String partial = args[0].toLowerCase(Locale.ROOT);
            for (final String sub : PLAYER_SUBS) {
                if (sub.startsWith(partial)) {
                    out.add(sub);
                }
            }
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                for (final String sub : ADMIN_SUBS) {
                    if (sub.startsWith(partial)) {
                        out.add(sub);
                    }
                }
            }
            return out;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            final String partial = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("set") || sub.equals("select")) {
                for (final String id : plugin.chatStyles().catalog().ids()) {
                    if (id.startsWith(partial)) {
                        out.add(id);
                    }
                }
                return out;
            }
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                for (final Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                        out.add(online.getName());
                    }
                }
            }
            return out;
        }
        if (args.length == 3 && sender.hasPermission(ADMIN_PERMISSION)
                && List.of("grant", "give", "unlock", "revoke", "take", "remove").contains(sub)) {
            final String partial = args[2].toLowerCase(Locale.ROOT);
            for (final String id : plugin.chatStyles().catalog().ids()) {
                if (id.startsWith(partial)) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
