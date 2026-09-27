package com.coremc.core.chat;

import com.coremc.core.CoreMCPlugin;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.util.RawYaml;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Resolves the rank prefix rendered at the START of a chat line.
 *
 * CoreMC has no hard dependency on a permissions plugin: ranks are
 * declared in {@code chat.yml} as (id, permission, prefix, weight) and
 * resolved in weight order. The player's persisted store rank
 * ({@code PlayerProfile#rankId}) is honoured as a fallback so purchased
 * ranks show up even before a permission node exists. When LuckPerms or
 * any other permissions plugin is installed it simply drives the
 * permission checks, which is the standard integration point.
 *
 * Threading: chat rendering happens off the main thread, so prefixes are
 * resolved on the MAIN thread (join + a periodic refresh + explicit
 * refresh calls) and cached in a concurrent map. The async path only ever
 * reads the cache, or falls back to the pure profile-rank mapping — no
 * Bukkit state is touched off-thread.
 */
public final class RankService {

    /** One configured rank. */
    public record RankEntry(String id, String permission, String prefix, int weight) {
    }

    private final CoreMCPlugin plugin;

    private volatile List<RankEntry> ranks = List.of();
    private volatile Map<String, RankEntry> byId = Map.of();
    private volatile String defaultPrefix = "";
    private volatile boolean useProfileRank = true;
    private volatile long refreshSeconds = 60L;

    private final ConcurrentHashMap<UUID, String> prefixCache = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask refreshTask;

    public RankService(final CoreMCPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads the rank table from chat.yml; returns the rank count. */
    public int load() {
        Map<String, Object> root;
        try {
            root = RawYaml.loadMap(new File(plugin.getDataFolder(), "chat.yml"));
        } catch (final RuntimeException exception) {
            root = RawYaml.loadResource(plugin, "chat.yml");
        }
        final Map<String, Object> chat = ChatStyleService.ChatConfigs.section(root, "chat");
        final Map<String, Object> rankSection = ChatStyleService.ChatConfigs.section(chat, "rank");
        this.defaultPrefix = ChatStyleService.ChatConfigs.str(rankSection.get("default-prefix"), "");
        this.useProfileRank =
                !"false".equalsIgnoreCase(ChatStyleService.ChatConfigs.str(rankSection.get("use-profile-rank"), "true"));
        this.refreshSeconds = Math.max(5L,
                ChatStyleService.ChatConfigs.num(rankSection.get("refresh-seconds"), 60L));

        final List<RankEntry> parsed = new ArrayList<>();
        final Map<String, Object> raw = ChatStyleService.ChatConfigs.section(chat, "ranks");
        for (final Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                plugin.getLogger().warning("[chat] rank '" + entry.getKey() + "' must be a map — skipped.");
                continue;
            }
            final String id = entry.getKey().toLowerCase(Locale.ROOT).trim();
            parsed.add(new RankEntry(
                    id,
                    ChatStyleService.ChatConfigs.str(body.get("permission"), ""),
                    ChatStyleService.ChatConfigs.str(body.get("prefix"), ""),
                    (int) ChatStyleService.ChatConfigs.num(body.get("weight"), 0L)));
        }
        parsed.sort(Comparator.comparingInt(RankEntry::weight).reversed());
        this.ranks = List.copyOf(parsed);
        final Map<String, RankEntry> index = new LinkedHashMap<>();
        for (final RankEntry rank : parsed) {
            index.put(rank.id(), rank);
        }
        this.byId = Map.copyOf(index);
        prefixCache.clear();
        refreshAllOnline();
        return parsed.size();
    }

    /** Starts the periodic main-thread refresh of cached prefixes. */
    public void startRefreshTask() {
        if (refreshTask != null) {
            plugin.tasks().cancel(refreshTask);
        }
        final long ticks = refreshSeconds * 20L;
        this.refreshTask = plugin.tasks().runTimer(this::refreshAllOnline, ticks, ticks);
    }

    /** Recomputes cached prefixes for every online player (MAIN thread). */
    public void refreshAllOnline() {
        if (!Bukkit.isPrimaryThread()) {
            plugin.tasks().run(this::refreshAllOnline);
            return;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    /** Recomputes and caches the prefix for one player (MAIN thread). */
    public void refresh(final Player player) {
        if (player == null) {
            return;
        }
        final PlayerProfile profile = plugin.playerData().profileOf(player.getUniqueId()).orElse(null);
        prefixCache.put(player.getUniqueId(), resolve(player, profile));
    }

    /** Drops the cached prefix of a leaving player. */
    public void forget(final UUID uuid) {
        prefixCache.remove(uuid);
    }

    /**
     * Prefix for a player — cache first (async-safe). Falls back to the
     * pure profile-rank mapping when the cache has no entry yet.
     */
    public String prefixOf(final Player player, final PlayerProfile profile) {
        if (player != null) {
            final String cached = prefixCache.get(player.getUniqueId());
            if (cached != null) {
                return cached;
            }
        }
        return profilePrefix(profile);
    }

    /** Full resolution (permission checks + profile rank). MAIN thread. */
    private String resolve(final Player player, final PlayerProfile profile) {
        for (final RankEntry rank : ranks) {
            final String permission = rank.permission();
            if (permission != null && !permission.isBlank() && player.hasPermission(permission)) {
                return rank.prefix();
            }
        }
        return profilePrefix(profile);
    }

    /** Pure fallback: the persisted store rank, else the configured default. */
    private String profilePrefix(final PlayerProfile profile) {
        if (useProfileRank && profile != null) {
            final RankEntry rank = byId.get(profile.rankId() == null
                    ? "" : profile.rankId().toLowerCase(Locale.ROOT));
            if (rank != null) {
                return rank.prefix();
            }
        }
        return defaultPrefix;
    }

    /** Configured ranks, highest weight first. */
    public List<RankEntry> ranks() {
        return ranks;
    }
}
