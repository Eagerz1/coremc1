package com.coremc.core.command;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.DateTimeUtil;
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
 * {@code /profile [player]} — shows the CoreMC player profile.
 *
 * Self-view is player-only (consoles have no profile). Viewing someone
 * else requires coremc.command.profile.others and the target must be
 * online (offline lookups would need a disk scan — kept out of scope).
 */
public final class ProfileCommand implements CommandExecutor, TabCompleter {

    private final MessageService messages;
    private final PlayerDataService playerData;

    public ProfileCommand(final CoreMCPlugin plugin) {
        this.messages = plugin.messages();
        this.playerData = plugin.playerData();
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.sendPrefixed(sender, "player-only", Map.of());
                return true;
            }
            showProfile(sender, player.getUniqueId());
            return true;
        }

        if (!sender.hasPermission("coremc.command.profile.others")) {
            messages.sendPrefixed(sender, "no-permission", Map.of());
            return true;
        }

        final Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.sendPrefixed(sender, "profile.not-found", Map.of("player", args[0]));
            return true;
        }
        showProfile(sender, target.getUniqueId());
        return true;
    }

    private void showProfile(final CommandSender viewer, final java.util.UUID uuid) {
        final PlayerProfile profile = playerData.profileOf(uuid).orElse(null);
        if (profile == null) {
            messages.sendPrefixed(viewer, "profile.not-found", Map.of("player", uuid.toString()));
            return;
        }

        final long now = System.currentTimeMillis();
        final String age = DateTimeUtil.formatAge(profile.lastSeenMillis(), now);
        final String lastSeen = DateTimeUtil.formatTimestamp(profile.lastSeenMillis())
                + ("just now".equals(age) ? " &7(just now)" : " &7(" + age + " ago)");

        viewer.sendMessage(messages.get("profile.header", Map.of("player", profile.username())));
        viewer.sendMessage(messages.get(
                "profile.first-join", Map.of("value", DateTimeUtil.formatTimestamp(profile.firstJoinMillis()))));
        viewer.sendMessage(
                messages.get("profile.total-logins", Map.of("value", String.valueOf(profile.totalLogins()))));
        viewer.sendMessage(messages.get("profile.last-seen", Map.of("value", lastSeen)));
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> completions = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("coremc.command.profile.others")) {
            final String partial = args[0].toLowerCase();
            for (final Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(partial)) {
                    completions.add(online.getName());
                }
            }
        }
        return completions;
    }
}
