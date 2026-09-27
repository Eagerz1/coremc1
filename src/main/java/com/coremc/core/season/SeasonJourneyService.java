package com.coremc.core.season;

import com.coremc.core.config.MessageService;
import com.coremc.core.island.Island;
import com.coremc.core.island.IslandService;
import com.coremc.core.progression.IslandProgressionService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Long-term Season Journey progression and reward claiming. All XP goes
 * through addXp/addConfiguredXpOnce so sources can be deduplicated centrally.
 */
public final class SeasonJourneyService {

    public enum ClaimResult {
        CLAIMED,
        PENDING,
        LOCKED,
        ALREADY_CLAIMED,
        PREMIUM_DISABLED,
        PREMIUM_LOCKED,
        MISSING
    }

    private final JavaPlugin plugin;
    private SeasonConfig config;
    private final YamlSeasonJourneyStore store;
    private final IslandService islands;
    private final IslandProgressionService progression;
    private final SeasonRewardRegistry rewards;
    private final SeasonPremiumAccess premiumAccess;
    private final MessageService messages;
    private final Logger logger;
    private final LongSupplier clock;
    private final Map<UUID, SeasonJourneyProfile> profiles = new LinkedHashMap<>();

    public SeasonJourneyService(final JavaPlugin plugin, final SeasonConfig config,
                                final YamlSeasonJourneyStore store, final IslandService islands,
                                final IslandProgressionService progression, final MessageService messages,
                                final Logger logger) {
        this(plugin, config, store, islands, progression, SeasonRewardRegistry.defaults(progression),
                SeasonPremiumAccess.none(), messages, logger, System::currentTimeMillis);
    }

    SeasonJourneyService(final JavaPlugin plugin, final SeasonConfig config,
                         final YamlSeasonJourneyStore store, final IslandService islands,
                         final IslandProgressionService progression, final SeasonRewardRegistry rewards,
                         final SeasonPremiumAccess premiumAccess,
                         final MessageService messages, final Logger logger, final LongSupplier clock) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.islands = islands;
        this.progression = progression;
        this.rewards = rewards == null ? SeasonRewardRegistry.defaults(progression) : rewards;
        this.premiumAccess = premiumAccess == null ? SeasonPremiumAccess.none() : premiumAccess;
        this.messages = messages;
        this.logger = logger;
        this.clock = clock;
    }

    public void load() {
        profiles.clear();
        try {
            profiles.putAll(store.loadAll());
        } catch (final IOException exception) {
            logger.warning("Season Journey data starts fresh — " + exception.getMessage());
        }
        if (plugin != null && config.enabled()) {
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::persist, 20L * 60L, 20L * 60L);
        }
    }

    public void shutdown() {
        persist();
    }

    public synchronized void reload(final SeasonConfig newConfig) {
        this.config = newConfig;
        persist();
    }

    public SeasonConfig config() {
        return config;
    }

    public SeasonConfig.SeasonDef season() {
        return config.season();
    }

    public synchronized SeasonJourneyProfile profile(final UUID playerId) {
        final SeasonJourneyProfile profile = profiles.computeIfAbsent(playerId, ignored -> new SeasonJourneyProfile());
        ensureCurrentSeason(profile);
        return profile;
    }

    public synchronized int level(final UUID playerId) {
        return config.levelForXp(profile(playerId).xp());
    }

    public synchronized long xp(final UUID playerId) {
        return profile(playerId).xp();
    }

    public synchronized long nextLevelXp(final UUID playerId) {
        return config.nextXpFor(profile(playerId).xp());
    }

    public synchronized List<SeasonConfig.Tier> claimableRewards(final UUID playerId, final String track) {
        final SeasonJourneyProfile profile = profile(playerId);
        final int reached = config.levelForXp(profile.xp());
        final String normalTrack = "premium".equalsIgnoreCase(track) ? "premium" : "free";
        return config.tiers().stream()
                .filter(tier -> tier.level() <= reached && !profile.claimed(normalTrack, tier.level()))
                .toList();
    }

    public synchronized Map<String, SeasonJourneyProfile.SeasonSummary> history(final UUID playerId) {
        return profile(playerId).historyView();
    }

    public long remainingMillis() {
        return Math.max(0L, config.season().endAt() - now());
    }

    public synchronized boolean addConfiguredXpOnce(final UUID playerId, final SeasonXpSource source,
                                                   final String achievementKey) {
        return addXp(playerId, config.sourceXp(source), source, achievementKey);
    }

    public synchronized boolean addXp(final UUID playerId, final long amount, final SeasonXpSource source) {
        return addXp(playerId, amount, source, "manual:" + source.key() + ":" + now());
    }

    public synchronized boolean addXp(final UUID playerId, final long amount, final SeasonXpSource source,
                                      final String achievementKey) {
        if (playerId == null || !config.enabled() || amount <= 0L || !config.season().active(now())) {
            return false;
        }
        final SeasonJourneyProfile profile = profile(playerId);
        final String key = config.season().id() + ":" + source.key() + ":" + SeasonConfig.normalise(achievementKey);
        if (!profile.xpAwardKeys().add(key)) {
            return false;
        }
        final int oldLevel = config.levelForXp(profile.xp());
        profile.addXp(amount, config.maxXp());
        final int newLevel = config.levelForXp(profile.xp());
        if (newLevel > profile.highestLevel()) {
            profile.setHighestLevel(newLevel);
        }
        if (newLevel >= config.maxLevel() && !profile.completed()) {
            profile.setCompleted(true);
            profile.setCompletedAt(now());
            profile.history().put(config.season().id(), new SeasonJourneyProfile.SeasonSummary(
                    config.season().id(), config.season().name(), profile.xp(), newLevel, true,
                    profile.completedAt(), now()));
        }
        persist();
        final Player player = plugin == null ? null : Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline() && messages != null) {
            if (newLevel > oldLevel) {
                messages.sendPrefixed(player, "journey.level-up", Map.of("level", String.valueOf(newLevel)));
            }
            messages.sendPrefixed(player, "journey.xp-gained", Map.of(
                    "amount", format(amount), "source", source.key().replace('-', ' ')));
        }
        return true;
    }

    public synchronized ClaimResult claim(final Player player, final int level, final String track) {
        if (player == null || !config.enabled()) {
            return ClaimResult.MISSING;
        }
        final String normalTrack = "premium".equalsIgnoreCase(track) ? "premium" : "free";
        if ("premium".equals(normalTrack) && !config.premiumEnabled()) {
            message(player, "journey.premium-disabled", Map.of());
            return ClaimResult.PREMIUM_DISABLED;
        }
        if ("premium".equals(normalTrack) && !premiumAccess.hasPremium(player)) {
            message(player, "journey.premium-locked", Map.of());
            return ClaimResult.PREMIUM_LOCKED;
        }
        final SeasonConfig.Tier tier = config.tier(level);
        if (tier == null) {
            return ClaimResult.MISSING;
        }
        final SeasonJourneyProfile profile = profile(player.getUniqueId());
        if (!config.season().id().equals(profile.seasonId())) {
            return ClaimResult.MISSING;
        }
        if (config.levelForXp(profile.xp()) < level) {
            message(player, "journey.claim-locked", Map.of("level", String.valueOf(level)));
            return ClaimResult.LOCKED;
        }
        if (profile.claimed(normalTrack, level)) {
            message(player, "journey.already-claimed", Map.of("level", String.valueOf(level), "track", normalTrack));
            return ClaimResult.ALREADY_CLAIMED;
        }
        final List<SeasonConfig.Reward> trackRewards = "premium".equals(normalTrack)
                ? tier.premiumRewards() : tier.freeRewards();
        final Island island = islands == null ? null : islands.islandOf(player.getUniqueId());
        boolean pending = false;
        for (final SeasonConfig.Reward reward : trackRewards) {
            final String deliveryKey = config.season().id() + ":" + normalTrack + ":" + level + ":" + reward.key();
            if (profile.deliveredRewardKeys().contains(deliveryKey)) {
                continue;
            }
            final SeasonRewardIntegration.Result result = rewards.deliver(player, island, reward);
            if (result == SeasonRewardIntegration.Result.DELIVERED) {
                profile.deliveredRewardKeys().add(deliveryKey);
            } else {
                pending = true;
                final SeasonJourneyProfile.PendingReward pendingReward = new SeasonJourneyProfile.PendingReward(
                        config.season().id(), normalTrack, level, reward.key(), reward.type(),
                        reward.amount(), reward.item(), reward.display());
                if (profile.pendingRewards().stream().noneMatch(existing -> existing.id().equals(pendingReward.id()))) {
                    profile.pendingRewards().add(pendingReward);
                }
            }
        }
        profile.markClaimed(normalTrack, level);
        persist();
        message(player, pending ? "journey.claim-pending" : "journey.claimed",
                Map.of("level", String.valueOf(level), "track", normalTrack));
        return pending ? ClaimResult.PENDING : ClaimResult.CLAIMED;
    }

    private void message(final Player player, final String key, final Map<String, String> placeholders) {
        if (messages != null && player != null) {
            messages.sendPrefixed(player, key, placeholders);
        }
    }

    public synchronized void transitionAllKnownProfiles() {
        for (final SeasonJourneyProfile profile : profiles.values()) {
            ensureCurrentSeason(profile);
        }
        persist();
    }

    private void ensureCurrentSeason(final SeasonJourneyProfile profile) {
        if (profile.seasonId().equals(config.season().id())) {
            return;
        }
        profile.archiveCurrent(config.season().name(), now());
        profile.beginSeason(config.season().id());
    }

    private void persist() {
        try {
            store.saveAll(profiles);
        } catch (final IOException exception) {
            logger.warning("Could not save Season Journey data: " + exception.getMessage());
        }
    }

    private long now() {
        return clock.getAsLong();
    }

    private String format(final long value) {
        return String.format(Locale.US, "%,d", value);
    }

}
