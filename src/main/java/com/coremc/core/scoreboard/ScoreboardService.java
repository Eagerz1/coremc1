package com.coremc.core.scoreboard;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.role.Role;
import com.coremc.core.role.RoleService;
import com.coremc.core.util.ColorUtil;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

/** Live player sidebar for CoreMC currencies, progression, island and event state. */
public final class ScoreboardService implements Listener {

    private final CoreMCPlugin plugin;
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.US);

    public ScoreboardService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        final long period = Math.max(20L, plugin.getConfig().getLong("scoreboard.update-ticks", 40L));
        plugin.tasks().runTimer(this::refreshOnline, period, period);
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        if (enabled()) plugin.tasks().runLater(() -> show(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        hidden.remove(event.getPlayer().getUniqueId());
        boards.remove(event.getPlayer().getUniqueId());
        event.getPlayer().setScoreboard(mainBoard());
    }

    public void shutdown() {
        for (final Player player : Bukkit.getOnlinePlayers()) player.setScoreboard(mainBoard());
        hidden.clear();
        boards.clear();
    }

    /** Rebuilds online sidebars after a configuration reload. */
    public void refreshNow() {
        if (!enabled()) {
            shutdown();
            return;
        }
        refreshOnline();
    }

    public boolean toggle(final Player player) {
        if (!enabled()) {
            hidden.add(player.getUniqueId());
            player.setScoreboard(mainBoard());
            return false;
        }
        final UUID id = player.getUniqueId();
        if (hidden.remove(id)) {
            show(player);
            return true;
        }
        hidden.add(id);
        player.setScoreboard(mainBoard());
        return false;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("scoreboard.enabled", true);
    }

    private void refreshOnline() {
        if (!enabled()) return;
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (!hidden.contains(player.getUniqueId())) show(player);
        }
    }

    private void show(final Player player) {
        if (!player.isOnline() || hidden.contains(player.getUniqueId())) return;
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) return;
        final ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        final UUID id = player.getUniqueId();
        Scoreboard board = boards.get(id);
        if (board == null) {
            board = manager.getNewScoreboard();
            boards.put(id, board);
        }
        Objective objective = board.getObjective("coremc");
        if (objective == null) {
            objective = board.registerNewObjective(
                    "coremc", "dummy", ColorUtil.colorize(plugin.getConfig().getString(
                            "scoreboard.title", "&b&lCOREMC")));
        } else {
            objective.setDisplayName(ColorUtil.colorize(plugin.getConfig().getString(
                    "scoreboard.title", "&b&lCOREMC")));
        }
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        final List<String> lines = lines(player, profile);
        for (final String oldEntry : board.getEntries()) board.resetScores(oldEntry);
        int score = lines.size();
        for (final String line : lines) {
            objective.getScore(line).setScore(score--);
        }
        player.setScoreboard(board);
    }

    private List<String> lines(final Player player, final PlayerProfile profile) {
        final List<String> lines = new ArrayList<>();
        lines.add(ColorUtil.colorize("&7" + player.getName()));
        lines.add(ColorUtil.colorize("&6$ &f" + numbers.format(profile.money())));
        lines.add(ColorUtil.colorize("&bCredits &f" + numbers.format(profile.credits())));
        lines.add(ColorUtil.colorize("&dTokens &f" + numbers.format(profile.skyTokens())));

        final RoleService roles = plugin.roles();
        final Role role = roles.roleOf(profile).orElse(null);
        if (role == null) {
            lines.add(ColorUtil.colorize("&7Role &fChoose with /role"));
        } else {
            lines.add(ColorUtil.colorize("&7Role &f" + role.display()));
            lines.add(ColorUtil.colorize("&7Role Level &f" + roles.roleView(profile, role).level()));
            lines.add(ColorUtil.colorize("&7Omni-Tool &fLv. " + roles.toolView(profile).level()));
        }

        final var island = plugin.islands().islandOf(player.getUniqueId());
        if (island.isPresent()) {
            lines.add(ColorUtil.colorize("&aIsland &fLv. " + island.get().level()));
        } else {
            lines.add(ColorUtil.colorize("&aIsland &f/is create"));
        }
        lines.add(ColorUtil.colorize(plugin.events().active()
                ? "&bCore Hour &aLIVE " + com.coremc.core.event.EventService.formatDuration(
                        plugin.events().remainingMillis())
                : "&bCore Hour &f" + com.coremc.core.event.EventService.formatDuration(
                        plugin.events().remainingMillis())));
        lines.add(ColorUtil.colorize("&eQuests &f/quests"));
        return lines;
    }

    private Scoreboard mainBoard() {
        final ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) throw new IllegalStateException("Bukkit scoreboard manager is unavailable");
        return manager.getMainScoreboard();
    }
}
