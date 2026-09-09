package com.coremc.core.role;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.player.PlayerProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /role} — role selection and info.
 *
 *   /role          opens the role selection panel
 *   /role <name>   selects a role directly (miner/logger/fisher/slayer/farmer/universal)
 *   /role info     prints your current role & progression summary
 */
public final class RoleCommand implements CommandExecutor, TabCompleter {

    private final CoreMCPlugin plugin;
    private final RoleService roles;
    private final MessageService messages;

    public RoleCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.roles = plugin.roles();
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            messages.sendPrefixed(player, "island.starting", Map.of());
            return true;
        }

        if (args.length == 0) {
            plugin.gui().open(player, new RoleSelectGui(plugin));
            return true;
        }
        final String arg = args[0].toLowerCase();
        if ("info".equals(arg)) {
            info(player, profile);
            return true;
        }
        final java.util.Optional<Role> role = Role.byKey(arg);
        if (role.isEmpty()) {
            messages.sendPrefixed(player, "role.unknown", Map.of("role", args[0]));
            return true;
        }
        if (roles.select(player, profile, role.get())) {
            messages.sendPrefixed(player, "role.selected", Map.of("role", role.get().display()));
        } else {
            messages.sendPrefixed(player, "role.already", Map.of("role", role.get().display()));
            // still ensure they own the tool (e.g. lost it somehow)
            plugin.omniTool().grantFresh(player, role.get(), profile);
        }
        return true;
    }

    private void info(final Player player, final PlayerProfile profile) {
        final java.util.Optional<Role> current = roles.roleOf(profile);
        player.sendMessage(messages.get("role.info-header", Map.of()));
        for (final Role role : Role.values()) {
            final RoleService.ProgressView view = roles.roleView(profile, role);
            player.sendMessage(messages.get(
                    "role.info-line",
                    Map.of(
                            "role", role.display(),
                            "level", String.valueOf(view.level()),
                            "xp", String.valueOf(view.xp()),
                            "next", view.maxed() ? "MAX" : String.valueOf(view.xpToNext()),
                            "state", current.map(r -> r == role ? " &a(selected)" : "").orElse(""))));
        }
        final RoleService.ProgressView tool = roles.toolView(profile);
        player.sendMessage(messages.get(
                "role.info-tool",
                Map.of(
                        "level", String.valueOf(tool.level()),
                        "xp", String.valueOf(tool.xp()),
                        "next", tool.maxed() ? "MAX" : String.valueOf(tool.xpToNext()))));
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            final String partial = args[0].toLowerCase();
            for (final Role role : Role.values()) {
                if (role.key().startsWith(partial)) {
                    completions.add(role.key());
                }
            }
            if ("info".startsWith(partial)) {
                completions.add("info");
            }
        }
        return completions;
    }
}
