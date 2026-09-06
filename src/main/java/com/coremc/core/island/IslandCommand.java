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
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /island} — Skyblock island commands.
 *
 *   create          create your island and teleport to it (one per player)
 *   teleport|home   teleport back to your island
 *   info            show your island's position and age
 *   delete          delete your island (requires a confirmation repeat)
 *   help            this summary
 */
public final class IslandCommand implements CommandExecutor, TabCompleter {

    private final IslandService islands;
    private final MessageService messages;
    private final long confirmMillis;

    /** owner -> confirm-expiry epoch millis, lazily pruned; bounded by online players. */
    private final Map<UUID, Long> pendingDeletes = new ConcurrentHashMap<>();

    public IslandCommand(final CoreMCPlugin plugin) {
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

        final String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        switch (sub) {
            case "create" -> create(player);
            case "teleport", "tp", "home", "go" -> teleportHome(player);
            case "info" -> info(player);
            case "delete" -> delete(player);
            default -> help(player, label);
        }
        return true;
    }

    private void create(final Player player) {
        if (islands.islandOf(player.getUniqueId()).isPresent()) {
            messages.sendPrefixed(player, "island.already-have", Map.of());
            return;
        }
        final Optional<World> world = islands.islandWorld();
        if (world.isEmpty()) {
            messages.sendPrefixed(player, "island.world-missing", Map.of());
            return;
        }
        try {
            final Island island = islands.createIsland(player);
            messages.sendPrefixed(player, "island.created", Map.of());
            islands.teleportHome(player, island);
            messages.sendPrefixed(player, "island.teleported", Map.of());
        } catch (final IOException exception) {
            messages.sendPrefixed(player, "island.create-failed", Map.of());
            pluginLog(exception);
        }
    }

    private void teleportHome(final Player player) {
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

    private void info(final Player player) {
        final Optional<Island> island = islands.islandOf(player.getUniqueId());
        if (island.isEmpty()) {
            messages.sendPrefixed(player, "island.none", Map.of());
            return;
        }
        final Island i = island.get();
        final long now = System.currentTimeMillis();
        final String age = DateTimeUtil.formatAge(i.createdMillis(), now);
        final String created = DateTimeUtil.formatTimestamp(i.createdMillis())
                + ("just now".equals(age) ? " (just now)" : " (" + age + " ago)");
        player.sendMessage(messages.get("island.info-header", Map.of()));
        player.sendMessage(messages.get(
                "island.info-location",
                Map.of("world", i.worldName(), "x", "" + i.centerX(), "y", "" + i.centerY(), "z", "" + i.centerZ())));
        player.sendMessage(messages.get("island.info-created", Map.of("value", created)));
    }

    private void delete(final Player player) {
        final UUID owner = player.getUniqueId();
        if (islands.islandOf(owner).isEmpty()) {
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
            pluginLog(exception);
        }
    }

    private void help(final Player player, final String label) {
        player.sendMessage(messages.get("island.help-header", Map.of()));
        player.sendMessage(messages.get("island.help-create", Map.of("label", label)));
        player.sendMessage(messages.get("island.help-teleport", Map.of("label", label)));
        player.sendMessage(messages.get("island.help-info", Map.of("label", label)));
        player.sendMessage(messages.get("island.help-delete", Map.of("label", label)));
    }

    private void pluginLog(final Exception exception) {
        java.util.logging.Logger.getLogger("CoreMC").log(
                java.util.logging.Level.SEVERE, "Island command failed", exception);
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        final List<String> completions = new ArrayList<>();
        if (args.length == 1 && sender instanceof Player) {
            final String partial = args[0].toLowerCase();
            for (final String sub : List.of("create", "teleport", "home", "info", "delete", "help")) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
        }
        return completions;
    }
}
