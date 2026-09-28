package com.coremc.core.cosmetic;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.Role;
import com.coremc.core.util.ColorUtil;
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
 * {@code /skins} — the animated skins menu plus permission-gated staff
 * management (the same hooks crates/events/store call internally).
 *
 *   /skins                    opens the Tool Skins + Hats menus
 *   /skins help               command overview
 *   /skins list [player]      every skin with owned/locked markers
 *
 * staff (coremc.skins.admin):
 *   /skins grant <player> <skinId>    grant ownership (crate/event/store hook)
 *   /skins revoke <player> <skinId>   revoke ownership (unequips it too)
 *   /skins set <player> <skinId>      force-equip a skin the player owns
 *   /skins clear <player> <tool|hat|all>
 *   /skins resetseason <player>       apply the skins.yml season policy
 *   /skins reload                     re-read skins.yml (merge-safe)
 */
public final class SkinsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> STAFF_SUBS =
            List.of("grant", "revoke", "set", "clear", "resetseason", "reload");
    private static final List<String> PLAYER_SUBS = List.of("help", "list");

    private final CoreMCPlugin plugin;
    private final SkinService skins;
    private final MessageService messages;

    public SkinsCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.skins = plugin.skins();
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
            final String label, final String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.sendPrefixed(sender, "player-only", Map.of());
                return true;
            }
            plugin.gui().open(player, new SkinsGui(plugin));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> sendHelp(sender);
            case "list" -> listSkins(sender, args.length > 1 ? args[1] : null);
            case "grant", "revoke", "set", "clear", "resetseason", "reload" -> {
                if (!sender.hasPermission("coremc.skins.admin")) {
                    messages.sendPrefixed(sender, "no-permission", Map.of());
                    return true;
                }
                handleStaff(sender, args);
            }
            default -> messages.sendPrefixed(sender, "skins.unknown-sub", Map.of());
        }
        return true;
    }

    private void sendHelp(final CommandSender sender) {
        sender.sendMessage(ColorUtil.colorize("&b&lCOREMC &8» &7Skins"));
        sender.sendMessage(ColorUtil.colorize("&b/skins &8- &7open the skins menu"));
        sender.sendMessage(ColorUtil.colorize("&b/skins list [player] &8- &7show every skin"));
        if (sender.hasPermission("coremc.skins.admin")) {
            sender.sendMessage(ColorUtil.colorize("&b/skins grant <player> <skin> &8- &7grant ownership"));
            sender.sendMessage(ColorUtil.colorize("&b/skins revoke <player> <skin> &8- &7revoke ownership"));
            sender.sendMessage(ColorUtil.colorize("&b/skins set <player> <skin> &8- &7force-equip an owned skin"));
            sender.sendMessage(ColorUtil.colorize("&b/skins clear <player> <tool|hat|all> &8- &7clear selections"));
            sender.sendMessage(ColorUtil.colorize("&b/skins resetseason <player> &8- &7apply the season policy"));
            sender.sendMessage(ColorUtil.colorize("&b/skins reload &8- &7re-read skins.yml"));
        }
    }

    private void listSkins(final CommandSender sender, final String targetName) {
        if (targetName != null) {
            if (!sender.hasPermission("coremc.skins.admin")) {
                messages.sendPrefixed(sender, "no-permission", Map.of());
                return;
            }
            final var uuid = plugin.playerData().resolveUuid(targetName);
            if (uuid.isEmpty()) {
                messages.sendPrefixed(sender, "skins.unknown-player", Map.of("player", targetName));
                return;
            }
            final PlayerProfile loaded = plugin.playerData().cachedOrLoad(uuid.get()).orElse(null);
            listProfile(sender, loaded, targetName);
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return;
        }
        listProfile(sender, plugin.playerData().profileOf(player.getUniqueId()).orElse(null),
                sender.getName());
    }

    private void listProfile(final CommandSender sender, final PlayerProfile profile, final String name) {
        if (profile == null) {
            messages.sendPrefixed(sender, "skins.unknown-player", Map.of("player", name));
            return;
        }
        sender.sendMessage(ColorUtil.colorize(
                "&b&lCOREMC &8» &7Skins of &f" + name + " &8(&7owned "
                + countOwned(profile) + "/" + skins.catalog().all().size() + "&8)"));
        for (final SkinCollection collection : skins.catalog().collections()) {
            // plain-text ownership markers (✔/✘) survive colour stripping
            // so the journey harness can assert against /skins list output
            final StringBuilder line = new StringBuilder();
            line.append("&f").append(collection.display()).append("&8: ");
            for (final Skin skin : collection.toolSkins()) {
                line.append(profile.ownsSkin(skin.id()) ? "&a✔" : "&c✘")
                        .append(skin.role().key()).append(" ");
            }
            sender.sendMessage(ColorUtil.colorize(line.toString()));
        }
        final StringBuilder hats = new StringBuilder("&dHats&8: ");
        for (final Skin skin : skins.hats()) {
            hats.append(profile.ownsSkin(skin.id()) ? "&a✔" : "&c✘")
                    .append(skin.id()).append(" ");
        }
        sender.sendMessage(ColorUtil.colorize(hats.toString()));
    }

    private int countOwned(final PlayerProfile profile) {
        int owned = 0;
        for (final Skin skin : skins.catalog().all()) {
            if (profile.ownsSkin(skin.id())) {
                owned++;
            }
        }
        return owned;
    }

    private void handleStaff(final CommandSender sender, final String[] args) {
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                final int count = skins.load();
                messages.sendPrefixed(sender, "skins.reloaded", Map.of("count", String.valueOf(count)));
            }
            case "resetseason" -> {
                if (args.length < 2) {
                    usage(sender, "/skins resetseason <player>");
                    return;
                }
                final var uuid = plugin.playerData().resolveUuid(args[1]);
                if (uuid.isEmpty()) {
                    messages.sendPrefixed(sender, "skins.unknown-player", Map.of("player", args[1]));
                    return;
                }
                // may touch disk for offline players: run off the main thread
                final var targetUuid = uuid.get();
                plugin.playerData().ioExecute(() -> {
                    final List<String> removed = skins.applySeasonReset(targetUuid);
                    final String names = String.join(", ", removed);
                    sender.sendMessage(ColorUtil.colorize("&a[skins] Season reset for &f"
                            + args[1] + "&a: removed " + removed.size()
                            + (removed.isEmpty() ? " skins." : " skin(s): &f" + names)));
                });
            }
            case "grant", "revoke" -> {
                if (args.length < 3) {
                    usage(sender, "/skins " + args[0] + " <player> <skinId>");
                    return;
                }
                final var uuid = plugin.playerData().resolveUuid(args[1]);
                final var skin = skins.skin(args[2]);
                if (uuid.isEmpty()) {
                    messages.sendPrefixed(sender, "skins.unknown-player", Map.of("player", args[1]));
                    return;
                }
                if (skin.isEmpty()) {
                    messages.sendPrefixed(sender, "skins.unknown-skin", Map.of("skin", args[2]));
                    return;
                }
                final var targetUuid = uuid.get();
                final var target = skin.get();
                final boolean grant = args[0].equalsIgnoreCase("grant");
                plugin.playerData().ioExecute(() -> {
                    final var result = grant
                            ? skins.grant(targetUuid, target.id())
                            : skins.revoke(targetUuid, target.id());
                    if (result.isEmpty()) {
                        messages.sendPrefixed(sender, "skins.unknown-player",
                                Map.of("player", args[1]));
                        return;
                    }
                    final var hook = result.get();
                    final String key = !hook.success() ? "skins.unknown-player"
                            : grant ? (hook.already() ? "skins.grant-already" : "skins.granted-admin")
                            : (hook.already() ? "skins.revoke-not-owned" : "skins.revoked-admin");
                    messages.sendPrefixed(sender, key, Map.of(
                            "player", hook.username(),
                            "skin", ColorUtil.colorize(target.display())));
                });
            }
            case "set" -> {
                if (args.length < 3) {
                    usage(sender, "/skins set <player> <skinId>");
                    return;
                }
                final Player target = Bukkit.getPlayerExact(args[1]);
                final var skin = skins.skin(args[2]);
                if (target == null) {
                    messages.sendPrefixed(sender, "skins.player-offline", Map.of("player", args[1]));
                    return;
                }
                if (skin.isEmpty()) {
                    messages.sendPrefixed(sender, "skins.unknown-skin", Map.of("skin", args[2]));
                    return;
                }
                final PlayerProfile profile = plugin.playerData()
                        .profileOf(target.getUniqueId()).orElse(null);
                if (profile == null) {
                    messages.sendPrefixed(sender, "skins.player-offline", Map.of("player", args[1]));
                    return;
                }
                final Skin selected = skin.get();
                if (selected.type() == SkinType.HAT) {
                    profile.equipHat(selected.id());
                    plugin.hatOverlay().syncWithProfile(target, profile);
                } else {
                    profile.equipToolSkin(selected.role().key(), selected.id());
                    plugin.omniTool().refreshHeldTools(target, profile);
                }
                plugin.playerData().persistImportant(profile);
                messages.sendPrefixed(sender, "skins.set", Map.of(
                        "player", target.getName(),
                        "skin", ColorUtil.colorize(selected.display())));
            }
            case "clear" -> {
                if (args.length < 3) {
                    usage(sender, "/skins clear <player> <tool|hat|all>");
                    return;
                }
                final Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    messages.sendPrefixed(sender, "skins.player-offline", Map.of("player", args[1]));
                    return;
                }
                final PlayerProfile profile = plugin.playerData()
                        .profileOf(target.getUniqueId()).orElse(null);
                if (profile == null) {
                    messages.sendPrefixed(sender, "skins.player-offline", Map.of("player", args[1]));
                    return;
                }
                final String scope = args[2].toLowerCase(Locale.ROOT);
                if (scope.equals("hat") || scope.equals("all")) {
                    profile.equipHat(null);
                    plugin.hatOverlay().unequip(target);
                }
                if (scope.equals("tool") || scope.equals("all")) {
                    for (final Role role : Role.values()) {
                        profile.equipToolSkin(role.key(), null);
                    }
                    plugin.omniTool().refreshHeldTools(target, profile);
                }
                plugin.playerData().persistImportant(profile);
                messages.sendPrefixed(sender, "skins.cleared-admin", Map.of("player", target.getName()));
            }
            default -> usage(sender, "/skins help");
        }
    }

    private void usage(final CommandSender sender, final String usage) {
        sender.sendMessage(ColorUtil.colorize("&cUsage: " + usage));
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
            final String label, final String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(prefixMatch(args[0], PLAYER_SUBS));
            if (sender.hasPermission("coremc.skins.admin")) {
                out.addAll(prefixMatch(args[0], STAFF_SUBS));
            }
            return out;
        }
        if (args.length == 2) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "list", "grant", "revoke", "set", "clear", "resetseason" -> {
                    if (args[0].equalsIgnoreCase("list") && !sender.hasPermission("coremc.skins.admin")) {
                        return out;
                    }
                    if (!args[0].equalsIgnoreCase("list") && !sender.hasPermission("coremc.skins.admin")) {
                        return out;
                    }
                    for (final Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getName().toLowerCase(Locale.ROOT)
                                .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                            out.add(player.getName());
                        }
                    }
                }
                default -> {
                }
            }
            return out;
        }
        if (args.length == 3) {
            final boolean staff = sender.hasPermission("coremc.skins.admin");
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "grant", "revoke", "set" -> {
                    if (staff) {
                        final List<String> ids = new ArrayList<>();
                        for (final Skin skin : skins.catalog().all()) {
                            ids.add(skin.id());
                        }
                        out.addAll(prefixMatch(args[2], ids));
                    }
                }
                case "clear" -> {
                    if (staff) {
                        out.addAll(prefixMatch(args[2], List.of("tool", "hat", "all")));
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }

    private static List<String> prefixMatch(final String prefix, final List<String> options) {
        final List<String> out = new ArrayList<>();
        final String lower = prefix.toLowerCase(Locale.ROOT);
        for (final String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
