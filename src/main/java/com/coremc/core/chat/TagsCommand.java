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
 * {@code /tags} — one command for the whole tag system.
 *
 * Players:
 *   /tags                  open the tag GUI
 *   /tags select <id>      equip a tag you own
 *   /tags clear            remove your tag
 *   /tags list             list every tag and your unlock state
 *
 * Staff ({@code coremc.admin.tags}):
 *   /tags grant  <player> <id>
 *   /tags revoke <player> <id>
 *   /tags check  <player>
 *   /tags clear  <player>
 *   /tags reload
 *
 * Deliberately ONE command with subcommands: no per-tag commands, no
 * command-tree spam, and one permission to audit.
 */
public final class TagsCommand implements CommandExecutor, TabCompleter {

    /** Staff permission for grant/revoke/check/clear-others/reload. */
    public static final String ADMIN_PERMISSION = "coremc.admin.tags";

    private static final List<String> PLAYER_SUBS = List.of("select", "clear", "list");
    private static final List<String> ADMIN_SUBS = List.of("grant", "revoke", "check", "reload");

    private final CoreMCPlugin plugin;
    private final MessageService messages;

    public TagsCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0) {
            return openGui(sender);
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "select", "set", "equip" -> select(sender, args);
            case "clear", "unequip", "none" -> clear(sender, args);
            case "list" -> list(sender);
            case "grant", "give", "unlock" -> grant(sender, args);
            case "revoke", "take", "remove" -> revoke(sender, args);
            case "check", "info" -> check(sender, args);
            case "reload" -> reload(sender);
            default -> {
                messages.sendPrefixed(sender, "tags.usage", Map.of());
                yield true;
            }
        };
    }

    private boolean openGui(final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        if (plugin.tags().all().isEmpty()) {
            messages.sendPrefixed(player, "tags.none-configured", Map.of());
            return true;
        }
        plugin.gui().open(player, new TagsGui(plugin));
        return true;
    }

    private boolean select(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        if (args.length < 2) {
            messages.sendPrefixed(player, "tags.usage", Map.of());
            return true;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            messages.sendPrefixed(player, "island.starting", Map.of());
            return true;
        }
        final String id = args[1].toLowerCase(Locale.ROOT);
        final CosmeticAccess.Result result = plugin.tags().select(player, profile, id);
        final String display = plugin.tags().byId(id).map(tag -> ColorUtil.colorize(tag.display())).orElse(id);
        switch (result) {
            case SELECTED -> messages.sendPrefixed(player, "tags.selected", Map.of("tag", display));
            case CLEARED -> messages.sendPrefixed(player, "tags.cleared", Map.of());
            case LOCKED -> messages.sendPrefixed(player, "tags.locked", Map.of("tag", display));
            case UNKNOWN -> messages.sendPrefixed(player, "tags.unknown", Map.of("tag", args[1]));
            case UNCHANGED -> messages.sendPrefixed(player, "tags.already", Map.of("tag", display));
            default -> { }
        }
        return true;
    }

    private boolean clear(final CommandSender sender, final String[] args) {
        if (args.length >= 2) { // staff: clear someone else's tag
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                messages.sendPrefixed(sender, "no-permission", Map.of());
                return true;
            }
            final Optional<UUID> target = resolve(args[1]);
            if (target.isEmpty()) {
                messages.sendPrefixed(sender, "tags.player-unknown", Map.of("player", args[1]));
                return true;
            }
            plugin.tags().clearAsync(target.get(), changed -> messages.sendPrefixed(
                    sender, changed ? "tags.admin-cleared" : "tags.admin-nothing",
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
        plugin.tags().clear(profile);
        messages.sendPrefixed(player, "tags.cleared", Map.of());
        return true;
    }

    private boolean list(final CommandSender sender) {
        if (plugin.tags().all().isEmpty()) {
            messages.sendPrefixed(sender, "tags.none-configured", Map.of());
            return true;
        }
        final Player player = sender instanceof Player p ? p : null;
        final PlayerProfile profile =
                player == null ? null : plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        messages.send(sender, "tags.list-header",
                Map.of("count", String.valueOf(plugin.tags().all().size())));
        for (final TagDefinition tag : plugin.tags().all()) {
            final boolean owned = plugin.tags().owns(player, profile, tag);
            final boolean selected = profile != null && tag.id().equals(profile.equippedTag());
            messages.send(sender, "tags.list-line", Map.of(
                    "id", tag.id(),
                    "tag", ColorUtil.colorize(tag.display()),
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
            messages.sendPrefixed(sender, "tags.admin-usage", Map.of());
            return true;
        }
        final String tagId = args[2].toLowerCase(Locale.ROOT);
        if (plugin.tags().byId(tagId).isEmpty()) {
            messages.sendPrefixed(sender, "tags.unknown", Map.of("tag", args[2]));
            return true;
        }
        final Optional<UUID> target = resolve(args[1]);
        if (target.isEmpty()) {
            messages.sendPrefixed(sender, "tags.player-unknown", Map.of("player", args[1]));
            return true;
        }
        plugin.tags().grantAsync(target.get(), tagId, granted -> {
            messages.sendPrefixed(sender, granted ? "tags.admin-granted" : "tags.admin-already",
                    Map.of("player", args[1], "tag", tagId));
            if (granted) {
                final Player online = Bukkit.getPlayer(target.get());
                if (online != null) {
                    messages.sendPrefixed(online, "tags.unlocked", Map.of("tag",
                            plugin.tags().byId(tagId).map(t -> ColorUtil.colorize(t.display())).orElse(tagId)));
                }
            }
        });
        return true;
    }

    private boolean revoke(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length < 3) {
            messages.sendPrefixed(sender, "tags.admin-usage", Map.of());
            return true;
        }
        final String tagId = args[2].toLowerCase(Locale.ROOT);
        final Optional<UUID> target = resolve(args[1]);
        if (target.isEmpty()) {
            messages.sendPrefixed(sender, "tags.player-unknown", Map.of("player", args[1]));
            return true;
        }
        plugin.tags().revokeAsync(target.get(), tagId, revoked -> messages.sendPrefixed(
                sender, revoked ? "tags.admin-revoked" : "tags.admin-nothing",
                Map.of("player", args[1], "tag", tagId)));
        return true;
    }

    private boolean check(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length < 2) {
            messages.sendPrefixed(sender, "tags.admin-usage", Map.of());
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
            final String owned = profile == null || profile.ownedTags().isEmpty()
                    ? "none" : String.join(", ", new java.util.TreeSet<>(profile.ownedTags()));
            final String equipped = profile == null ? "none" : profile.equippedTag();
            plugin.tasks().runLater(() -> messages.sendPrefixed(sender, "tags.admin-check", Map.of(
                    "player", args[1], "owned", owned, "selected", equipped)), 1L);
        });
        return true;
    }

    private boolean reload(final CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }
        final int tags = plugin.tags().load();
        final int styles = plugin.chatStyles().load();
        plugin.chatFormat().load();
        plugin.ranks().load();
        messages.sendPrefixed(sender, "tags.reloaded",
                Map.of("tags", String.valueOf(tags), "styles", String.valueOf(styles)));
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
            if (sub.equals("select") || sub.equals("set") || sub.equals("equip")) {
                for (final String id : plugin.tags().catalog().ids()) {
                    if (id.startsWith(partial)) {
                        out.add(id);
                    }
                }
                return out;
            }
            if (sender.hasPermission(ADMIN_PERMISSION)
                    && List.of("grant", "give", "unlock", "revoke", "take", "remove", "check", "info", "clear")
                            .contains(sub)) {
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
            for (final String id : plugin.tags().catalog().ids()) {
                if (id.startsWith(partial)) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
