package com.coremc.core.achievements;

import com.coremc.core.collections.ClaimResult;
import com.coremc.core.progress.ProgressEvent;
import com.coremc.core.progress.reward.Reward;
import com.coremc.core.progress.reward.RewardGrant;
import com.coremc.core.progress.reward.RewardService;
import com.coremc.core.progress.reward.RewardType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

/**
 * The permanent Achievement system: counts authoritative events,
 * awards Achievements exactly once, stamps seasonal ones with the
 * season they were earned in, applies permanent unlocks instantly and
 * keeps hand-claimed rewards safe until the player claims them.
 *
 * <p>Achievement Points are prestige only — they are summed from
 * earned Achievements and can never be spent, so they cannot inflate
 * the economy.</p>
 *
 * <p>No Bukkit calls: feedback goes through a {@link Notifier} and the
 * current season through an {@link IntSupplier}, which keeps the whole
 * class unit-testable.</p>
 */
public final class AchievementService {

    /** Player-facing feedback, supplied by the plugin. */
    @FunctionalInterface
    public interface Notifier {
        void earned(UUID player, Achievement achievement, boolean hasClaimable);
    }

    private final AchievementConfig config;
    private final AchievementStore store;
    private final RewardService rewards;
    private final Logger logger;
    private final Map<UUID, AchievementProfile> profiles = new ConcurrentHashMap<>();

    private IntSupplier season = () -> -1;
    private Notifier notifier;

    public AchievementService(final AchievementConfig config, final AchievementStore store,
                              final RewardService rewards, final Logger logger) {
        this.config = config;
        this.store = store;
        this.rewards = rewards;
        this.logger = logger;
    }

    /** Where the current season number comes from (RankService on the live server). */
    public void seasonSupplier(final IntSupplier supplier) {
        this.season = supplier == null ? () -> -1 : supplier;
    }

    /** Sets the feedback channel (null = silent, used by tests). */
    public void notifier(final Notifier newNotifier) {
        this.notifier = newNotifier;
    }

    public AchievementConfig config() {
        return config;
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    /** Loads permanent profiles from disk. */
    public void load() {
        profiles.clear();
        if (store == null) {
            return;
        }
        try {
            profiles.putAll(store.load());
        } catch (final IOException failure) {
            severe("Could not load achievements data: " + failure.getMessage());
        }
    }

    /** Writes permanent profiles to disk. */
    public void save() {
        if (store == null) {
            return;
        }
        try {
            store.save(new LinkedHashMap<>(profiles));
        } catch (final IOException failure) {
            severe("Could not save achievements data: " + failure.getMessage());
        }
    }

    /** A player's profile, created on demand. */
    public AchievementProfile profile(final UUID player) {
        return profiles.computeIfAbsent(player, ignored -> new AchievementProfile());
    }

    /** Every known profile (read-only, for admin tooling). */
    public Map<UUID, AchievementProfile> profiles() {
        return Map.copyOf(profiles);
    }

    // ------------------------------------------------------------------
    // counting
    // ------------------------------------------------------------------

    /** Counts one authoritative event into every Achievement listening to it. */
    public List<Achievement> accept(final ProgressEvent event) {
        if (event == null || event.empty() || !config.enabled()) {
            return List.of();
        }
        final List<Achievement> listening = config.byAction(event.action());
        if (listening.isEmpty()) {
            return List.of();
        }
        final AchievementProfile profile = profile(event.player());
        final List<Achievement> earned = new ArrayList<>();
        for (final Achievement achievement : listening) {
            if (!achievement.accepts(event.action(), event.key(), event.source())) {
                continue;
            }
            if (profile.earned(achievement.id())) {
                continue;
            }
            final long progress = switch (achievement.mode()) {
                case TOTAL -> profile.add(achievement.id(), event.amount());
                case MAX -> profile.raise(achievement.id(), event.amount());
                case UNIQUE -> profile.addUnique(achievement.id(), event.key());
            };
            if (progress >= achievement.requirement()
                    && grant(event.player(), profile, achievement, event.timestamp())) {
                earned.add(achievement);
            }
        }
        if (!earned.isEmpty() || !listening.isEmpty()) {
            save();
        }
        for (final Achievement achievement : earned) {
            if (notifier != null) {
                notifier.earned(event.player(), achievement, achievement.hasManualReward());
            }
        }
        return earned;
    }

    /** Records the earn and applies the permanent rewards; false when already earned. */
    private boolean grant(final UUID player, final AchievementProfile profile,
                          final Achievement achievement, final long when) {
        final int stamp = achievement.seasonal() ? Math.max(0, season.getAsInt()) : -1;
        if (!profile.earn(achievement.id(), when <= 0 ? System.currentTimeMillis() : when, stamp)) {
            return false;
        }
        for (final Reward reward : achievement.rewards()) {
            if (reward.type().permanent()) {
                profile.unlock(unlockKey(reward.type(), reward.id()));
            }
        }
        return true;
    }

    /** Admin path: grants an Achievement outright (still exactly once). */
    public boolean grant(final UUID player, final String achievementId) {
        final Achievement achievement = config.byId(achievementId);
        if (player == null || achievement == null) {
            return false;
        }
        final AchievementProfile profile = profile(player);
        profile.set(achievement.id(), achievement.requirement());
        if (!grant(player, profile, achievement, System.currentTimeMillis())) {
            return false;
        }
        save();
        if (notifier != null) {
            notifier.earned(player, achievement, achievement.hasManualReward());
        }
        return true;
    }

    /** Admin path: revokes an Achievement is deliberately not offered — see the docs. */
    public boolean earned(final UUID player, final String achievementId) {
        final Achievement achievement = config.byId(achievementId);
        return achievement != null && profile(player).earned(achievement.id());
    }

    // ------------------------------------------------------------------
    // claiming
    // ------------------------------------------------------------------

    /** Claims the hand-claimed rewards of an earned Achievement, exactly once. */
    public ClaimResult claim(final UUID player, final String achievementId) {
        final Achievement achievement = config.byId(achievementId);
        if (player == null || achievement == null) {
            return ClaimResult.UNKNOWN;
        }
        final AchievementProfile profile = profile(player);
        if (!profile.earned(achievement.id())) {
            return ClaimResult.NOT_REACHED;
        }
        if (!achievement.hasManualReward()) {
            return ClaimResult.NOTHING_TO_CLAIM;
        }
        if (profile.claimed(achievement.id())) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        profile.claim(achievement.id());
        save();
        boolean pending = false;
        for (final Reward reward : achievement.rewards()) {
            if (!reward.manual()) {
                continue;
            }
            final RewardGrant result = rewards == null ? RewardGrant.PENDING
                    : rewards.deliver(player, reward, "achievement:" + achievement.id());
            if (result == RewardGrant.PENDING) {
                pending = true;
            } else if (result == RewardGrant.FAILED) {
                warn("Achievement " + achievement.id() + " has an undeliverable reward "
                        + reward.type().id() + " — check achievements.yml");
            }
        }
        return pending ? ClaimResult.PENDING : ClaimResult.CLAIMED;
    }

    /** True when this Achievement is earned and still has something to claim. */
    public boolean claimable(final UUID player, final Achievement achievement) {
        return achievement != null && achievement.hasManualReward()
                && profile(player).earned(achievement.id())
                && !profile(player).claimed(achievement.id());
    }

    /** How many Achievements are waiting to be claimed. */
    public int claimableCount(final UUID player) {
        int count = 0;
        for (final Achievement achievement : config.all()) {
            if (claimable(player, achievement)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    /** Progress towards an Achievement, capped at its requirement. */
    public long progress(final UUID player, final Achievement achievement) {
        if (achievement == null) {
            return 0;
        }
        final AchievementProfile profile = profile(player);
        if (profile.earned(achievement.id())) {
            return achievement.requirement();
        }
        return Math.min(achievement.requirement(), profile.counter(achievement.id()));
    }

    /** Achievement Points earned (prestige only — never spendable). */
    public int points(final UUID player) {
        int total = 0;
        final AchievementProfile profile = profile(player);
        for (final Achievement achievement : config.all()) {
            if (profile.earned(achievement.id())) {
                total += achievement.points();
            }
        }
        return total;
    }

    /** How many Achievements have been earned. */
    public int earnedCount(final UUID player) {
        int count = 0;
        final AchievementProfile profile = profile(player);
        for (final Achievement achievement : config.all()) {
            if (profile.earned(achievement.id())) {
                count++;
            }
        }
        return count;
    }

    /** How many Achievements of a category have been earned. */
    public int earnedIn(final UUID player, final AchievementCategory category) {
        int count = 0;
        final AchievementProfile profile = profile(player);
        for (final Achievement achievement : config.byCategory(category)) {
            if (profile.earned(achievement.id())) {
                count++;
            }
        }
        return count;
    }

    /** Completion of everything as a whole percentage (by points, floored). */
    public int percent(final UUID player) {
        final int max = config.maxPoints();
        if (max <= 0) {
            return 0;
        }
        return (int) Math.floor(100.0 * Math.min(points(player), max) / max);
    }

    /**
     * True when a secret Achievement should still be hidden: secrets
     * show as "???" with no description until they are earned.
     */
    public boolean hidden(final UUID player, final Achievement achievement) {
        return achievement != null && achievement.secret()
                && !profile(player).earned(achievement.id());
    }

    /** The season a seasonal Achievement was earned in, or -1. */
    public int earnedSeason(final UUID player, final Achievement achievement) {
        return achievement == null ? -1 : profile(player).earnedSeason(achievement.id());
    }

    /** True when the player owns a permanent unlock. */
    public boolean hasUnlock(final UUID player, final RewardType type, final String id) {
        return profile(player).unlocked(unlockKey(type, id));
    }

    /** Storage key of a permanent unlock. */
    public static String unlockKey(final RewardType type, final String id) {
        return type.id() + ":" + (id == null ? "" : id.trim().toLowerCase(Locale.ROOT));
    }

    private void warn(final String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }

    private void severe(final String message) {
        if (logger != null) {
            logger.severe(message);
        }
    }
}
