package com.coremc.core.island;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.config.MessageService;
import com.coremc.core.util.DateTimeUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /is} — CoreMC Skyblock island commands.
 *
 *   (no args)         open the island GUI panel
 *   create            create your island and teleport to it
 *   home/tp           teleport to your island (owner or member)
 *   invite <player>   invite a player to join your island (owner)
 *   accept            accept a pending invite
 *   leave             leave the island you belong to (members)
 *   kick <player>     kick a member (owner)
 *   delete            permanently delete your island (two-step confirm)
 *   info              island position, border, level, members, creation
 *   upgrades          open the island upgrades panel
 *   help              this summary
 *
 * All island state lives in {@link IslandService}; this class only
 * routes to it and renders messages.
 */
public final class IslandCommand implements CommandExecutor, TabCompleter {

    private final CoreMCPlugin plugin;
    private final IslandService islands;
    private final MessageService messages;
    private final long confirmMillis;

    /** owner -> confirm-expiry epoch millis, lazily pruned; bounded by online players. */
    private final Map<UUID, Long> pendingDeletes = new ConcurrentHashMap<>();

    public IslandCommand(final CoreMCPlugin plugin) {
        this.plugin = plugin;
        this.islands = plugin.islands();
        this.messages = plugin.messages();
        this.confirmMillis = plugin.coreConfig().islandDeleteConfirmSeconds() * 1000L;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only", Map.of());
            return true;
        }
        if (!islands.isLoaded()) {
            messages.sendPrefixed(sender, "island.starting", Map.of());
            return true;
        }
        if (args.length == 0) {
            plugin.gui().open(player, new IslandMainGui(plugin));
            return true;
        }

        final String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> create(player, args);
            case "home", "tp", "teleport", "go" -> home(player);
            case "invite" -> invite(player, args);
            case "accept", "join" -> accept(player);
            case "leave" -> leave(player);
            case "kick" -> kick(player, args);
            case "delete" -> delete(player);
            case "info" -> info(player);
            case "upgrades" -> plugin.gui().open(player, new IslandUpgradesGui(plugin));
            default -> help(player, label);
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void create(final Player player, final String[] args) {
        IslandTheme theme = null;
        if (args.length >= 2) {
            final Optional<IslandTheme> resolved = plugin.themes().theme(args[1]);
            if (resolved.isEmpty()) {
                messages.sendPrefixed(player, "island.theme-unknown",
                        Map.of("themes", String.join(", ", plugin.themes().keys())));
                return;
            }
            theme = resolved.get();
        } else {
            theme = plugin.themes().defaultTheme().orElse(null);
        }
        final IslandTheme chosen = theme;
        final IslandService.CreateResult result = islands.createIsland(player, chosen, island -> {
            if (chosen != null) {
                messages.sendPrefixed(player, "island.created-themed",
                        Map.of("theme", com.coremc.core.util.ColorUtil.colorize(chosen.display())));
            } else {
                messages.sendPrefixed(player, "island.created", Map.of());
            }
            islands.teleportHome(player, island);
            messages.sendPrefixed(player, "island.teleported", Map.of());
        });
        switch (result) {
            case ALREADY_ISLAND -> messages.sendPrefixed(player, "island.already-have", Map.of());
            case WORLD_MISSING -> messages.sendPrefixed(player, "island.world-missing", Map.of());
            case CREATED -> { /* handled in the callback */ }
        }
    }

    private void home(final Player player) {
        final Optional<Island> island = islands.islandOf(player.getUniqueId());
        if (island.isEmpty()) {
            messages.sendPrefixed(player, "island.none", Map.of());
            return;
        }
        try {
            islands.teleportHome(player, island.get());
            messages.sendPrefixed(player, "island.teleported", Map.of());
        } catch (final IllegalStateException exception) {
            messages.sendPrefixed(player, "island.world-missing", Map.of());
        }
    }

    private void invite(final Player player, final String[] args) {
        if (args.length < 2) {
            messages.sendPrefixed(player, "island.invite-usage", Map.of());
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.sendPrefixed(player, "island.target-offline", Map.of("player", args[1]));
            return;
        }
        if (target.equals(player)) {
            messages.sendPrefixed(player, "island.invite-self", Map.of());
            return;
        }
        switch (islands.invite(player, target)) {
            case SENT -> {
                messages.sendPrefixed(player, "island.invite.sent", Map.of("player", target.getName()));
                messages.sendPrefixed(
                        target,
                        "island.invite.received",
                        Map.of(
                                "player", player.getName(),
                                "seconds",
                                String.valueOf(plugin.coreConfig().islandInviteExpirySeconds())));
            }
            case TARGET_HAS_ISLAND -> messages.sendPrefixed(player, "island.invite.target-busy", Map.of());
            case SENDER_NOT_OWNER -> messages.sendPrefixed(player, "island.not-owner", Map.of());
            case TARGET_BUSY, TEAM_FULL -> messages.sendPrefixed(player, "island.team-full", Map.of());
        }
    }

    private void accept(final Player player) {
        switch (islands.acceptInvite(player)) {
            case ACCEPTED -> {
                messages.sendPrefixed(player, "island.joined", Map.of());
                islands.islandOf(player.getUniqueId())
                        .ifPresent(island -> notifyOwner(island, player, "island.member-joined"));
            }
            case NO_INVITE -> messages.sendPrefixed(player, "island.invite.none", Map.of());
            case EXPIRED -> messages.sendPrefixed(player, "island.invite.expired", Map.of());
            case ISLAND_GONE -> messages.sendPrefixed(player, "island.invite.gone", Map.of());
            case TEAM_FULL -> messages.sendPrefixed(player, "island.team-full", Map.of());
            case ALREADY_ISLAND -> messages.sendPrefixed(player, "island.already-have", Map.of());
        }
    }

    private void leave(final Player player) {
        if (islands.ownedIsland(player.getUniqueId()).isPresent()) {
            messages.sendPrefixed(player, "island.leave-owner", Map.of());
            return;
        }
        switch (islands.leave(player)) {
            case LEFT -> messages.sendPrefixed(player, "island.left", Map.of());
            case NOT_A_MEMBER -> messages.sendPrefixed(player, "island.none", Map.of());
        }
    }

    private void kick(final Player player, final String[] args) {
        if (args.length < 2) {
            messages.sendPrefixed(player, "island.kick-usage", Map.of());
            return;
        }
        final IslandService.KickResult[] resultHolder = new IslandService.KickResult[1];
        plugin.playerData().resolveUuid(args[1]).ifPresentOrElse(
                uuid -> resultHolder[0] = islands.kick(player, uuid),
                () -> resultHolder[0] = null);
        final IslandService.KickResult result = resultHolder[0];
        if (result == null) {
            messages.sendPrefixed(player, "island.target-unknown", Map.of("player", args[1]));
            return;
        }
        switch (result) {
            case KICKED -> {
                messages.sendPrefixed(player, "island.kicked.owner", Map.of("player", args[1]));
                final Player target = Bukkit.getPlayerExact(args[1]);
                if (target != null) {
                    messages.sendPrefixed(target, "island.kicked.you", Map.of());
                }
            }
            case NOT_OWNER -> messages.sendPrefixed(player, "island.not-owner", Map.of());
            case TARGET_NOT_MEMBER -> messages.sendPrefixed(player, "island.kick-not-member", Map.of());
            case CANNOT_KICK_OWNER -> messages.sendPrefixed(player, "island.kick-self", Map.of());
        }
    }

    private void delete(final Player player) {
        final UUID owner = player.getUniqueId();
        if (islands.ownedIsland(owner).isEmpty()) {
            messages.sendPrefixed(player, "island.none", Map.of());
            return;
        }

        final long expiry = pendingDeletes.getOrDefault(owner, 0L);
        final long now = System.currentTimeMillis();
        if (expiry < now) {
            pendingDeletes.put(owner, now + confirmMillis);
            messages.sendPrefixed(
                    player, "island.delete-confirm", Map.of("seconds", String.valueOf(confirmMillis / 1000L)));
            return;
        }

        pendingDeletes.remove(owner);
        try {
            islands.deleteIsland(owner);
            messages.sendPrefixed(player, "island.deleted", Map.of());
        } catch (final IOException exception) {
            messages.sendPrefixed(player, "island.delete-failed", Map.of());
            plugin.getLogger().log(Level.SEVERE, "Failed to delete island of " + owner, exception);
        }
    }

    private void info(final Player player) {
        final Optional<Island> islandOpt = islands.islandOf(player.getUniqueId());
        if (islandOpt.isEmpty()) {
            messages.sendPrefixed(player, "island.none", Map.of());
            return;
        }
        final Island island = islandOpt.get();
        final String age = DateTimeUtil.formatAge(island.createdMillis(), System.currentTimeMillis());
        final String created = DateTimeUtil.formatTimestamp(island.createdMillis())
                + ("just now".equals(age) ? " (just now)" : " (" + age + " ago)");
        player.sendMessage(messages.get("island.info-header", Map.of()));
        player.sendMessage(messages.get(
                "island.info-owner", Map.of("owner", resolveName(island.owner()))));
        player.sendMessage(messages.get(
                "island.info-location",
                Map.of("world", island.worldName(), "x", "" + island.centerX(), "y", "" + island.centerY(), "z",
                        "" + island.centerZ())));
        player.sendMessage(messages.get(
                "island.info-border",
                Map.of("border",
                        plugin.islands().effectiveBorder(island) + "x"
                                + plugin.islands().effectiveBorder(island))));
        player.sendMessage(messages.get("island.info-level", Map.of("level", String.valueOf(island.level()))));
        player.sendMessage(messages.get("island.info-stats", Map.of("stats", formatStats(island))));
        player.sendMessage(messages.get(
                "island.info-members",
                Map.of(
                        "members", memberNames(island),
                        "current", String.valueOf(island.members().size()),
                        "max", String.valueOf(islands.memberCapacity(island)))));
        player.sendMessage(messages.get("island.info-created", Map.of("value", created)));
    }

    private String memberNames(final Island island) {
        if (island.members().isEmpty()) {
            return "-";
        }
        final List<String> names = new ArrayList<>();
        for (final UUID member : island.members()) {
            names.add(resolveName(member));
        }
        return String.join(", ", names);
    }

    private String resolveName(final UUID uuid) {
        final Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        final org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() != null ? offline.getName() : uuid.toString().substring(0, 8);
    }

    private void notifyOwner(final Island island, final Player actor, final String messageKey) {
        final Player ownerPlayer = Bukkit.getPlayer(island.owner());
        if (ownerPlayer != null && !ownerPlayer.equals(actor)) {
            messages.sendPrefixed(ownerPlayer, messageKey, Map.of("player", actor.getName()));
        }
    }

    private void help(final Player player, final String label) {
        player.sendMessage(messages.get("island.help-header", Map.of()));
        player.sendMessage(messages.get("island.help-create", Map.of("label", label)));
        player.sendMessage(messages.get("island.help-home", Map.of("label", label)));
        player.sendMessage(messages.get("island.help-invite", Map.of("label", label)));
        player.sendMessage(messages.get("island.help-team", Map.of("label", label)));
        player.sendMessage(messages.get("island.help-info", Map.of("label", label)));
        player.sendMessage(messages.get("island.help-upgrades", Map.of("label", label)));
        player.sendMessage(messages.get("island.help-delete", Map.of("label", label)));
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> completions = new ArrayList<>();
        if (!(sender instanceof Player player)) {
            return completions;
        }
        if (args.length == 1) {
            final String partial = args[0].toLowerCase();
            for (final String sub : List.of(
                    "create", "home", "invite", "accept", "leave", "kick", "delete", "info", "upgrades", "help")) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
            return completions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("invite")) {
            final String partial = args[1].toLowerCase();
            for (final Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player) && online.getName().toLowerCase().startsWith(partial)) {
                    completions.add(online.getName());
                }
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("kick")) {
            final String partial = args[1].toLowerCase();
            islands.ownedIsland(player.getUniqueId()).ifPresent(island -> {
                for (final UUID member : island.members()) {
                    final String name = resolveName(member);
                    if (name.toLowerCase().startsWith(partial)) {
                        completions.add(name);
                    }
                }
            });
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            final String partial = args[1].toLowerCase();
            for (final String key : plugin.themes().keys()) {
                if (key.startsWith(partial)) {
                    completions.add(key);
                }
            }
        }
        return completions;
    }
}
