package com.coremc.core.collections;

import com.coremc.core.progress.ProgressEvent;
import com.coremc.core.progress.reward.Reward;
import com.coremc.core.progress.reward.RewardGrant;
import com.coremc.core.progress.reward.RewardService;
import com.coremc.core.progress.reward.RewardType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The permanent Collection system: counts authoritative progression
 * events, crosses milestones, applies permanent unlocks immediately,
 * parks hand-claimed rewards until the player claims them, and answers
 * every completion question the GUIs ask.
 *
 * <p>Deliberately free of Bukkit calls so all of it is unit-tested:
 * player-facing feedback goes through a {@link Notifier} the plugin
 * supplies, and rewards go through {@link RewardService}.</p>
 *
 * <p>Nothing in here is seasonal. A season reset changes ranks and
 * seasonal stats; {@code collections-data.yml} is untouched, which is
 * exactly the point of the system.</p>
 */
public final class CollectionService {

    /** Player-facing feedback, supplied by the plugin (messages, sounds, titles). */
    @FunctionalInterface
    public interface Notifier {
        void milestone(UUID player, MilestoneAward award);
    }

    private final CollectionConfig config;
    private final CollectionStore store;
    private final RewardService rewards;
    private final Logger logger;
    private final Map<UUID, CollectionProfile> profiles = new ConcurrentHashMap<>();

    private Notifier notifier;

    public CollectionService(final CollectionConfig config, final CollectionStore store,
                             final RewardService rewards, final Logger logger) {
        this.config = config;
        this.store = store;
        this.rewards = rewards;
        this.logger = logger;
    }

    /** Sets the feedback channel (null = silent, used by tests). */
    public void notifier(final Notifier newNotifier) {
        this.notifier = newNotifier;
    }

    public CollectionConfig config() {
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
            severe("Could not load collections data: " + failure.getMessage());
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
            severe("Could not save collections data: " + failure.getMessage());
        }
    }

    /** A player's profile, created on demand. */
    public CollectionProfile profile(final UUID player) {
        return profiles.computeIfAbsent(player, ignored -> new CollectionProfile());
    }

    /** Every known profile (read-only, for admin tooling). */
    public Map<UUID, CollectionProfile> profiles() {
        return Map.copyOf(profiles);
    }

    // ------------------------------------------------------------------
    // counting
    // ------------------------------------------------------------------

    /**
     * Counts one authoritative event into every Collection that
     * accepts it. Already deduplicated and guarded by the bus, so
     * every real action lands exactly once.
     */
    public List<MilestoneAward> accept(final ProgressEvent event) {
        if (event == null || event.empty() || !config.enabled()) {
            return List.of();
        }
        final List<CollectionEntry> candidates = config.byAction(event.action());
        if (candidates.isEmpty()) {
            return List.of();
        }
        final List<MilestoneAward> awards = new ArrayList<>();
        for (final CollectionEntry entry : candidates) {
            if (entry.accepts(event.action(), event.key(), event.source())) {
                awards.addAll(record(event.player(), entry, event.amount(), event.timestamp()));
            }
        }
        return awards;
    }

    /**
     * Adds to one entry and returns the milestones crossed by this
     * addition (usually none, occasionally one, more than one only
     * when a large amount arrives at once).
     */
    public List<MilestoneAward> record(final UUID player, final CollectionEntry entry,
                                       final long amount, final long now) {
        if (player == null || entry == null || amount <= 0) {
            return List.of();
        }
        final CollectionProfile profile = profile(player);
        final long before = profile.amount(entry.id());
        final long after = profile.add(entry.id(), amount);
        if (entry.discovery()) {
            profile.discover(entry.id(), amount, now);
        }
        final int reachedBefore = CollectionProgress.tiersReached(before, entry.milestones());
        final int reachedAfter = CollectionProgress.tiersReached(after, entry.milestones());
        final List<MilestoneAward> awards = new ArrayList<>();
        for (int index = reachedBefore; index < reachedAfter; index++) {
            awards.add(award(player, profile, entry, entry.milestones().get(index), after));
        }
        save();
        for (final MilestoneAward milestoneAward : awards) {
            if (notifier != null) {
                notifier.milestone(player, milestoneAward);
            }
        }
        return awards;
    }

    /** Applies the permanent half of a milestone and describes the rest. */
    private MilestoneAward award(final UUID player, final CollectionProfile profile,
                                 final CollectionEntry entry, final CollectionMilestone milestone,
                                 final long total) {
        final List<Reward> automatic = new ArrayList<>();
        final List<Reward> claimable = new ArrayList<>();
        for (final Reward reward : milestone.rewards()) {
            if (reward.type().permanent()) {
                if (profile.unlock(unlockKey(reward.type(), reward.id()))) {
                    automatic.add(reward);
                }
            } else {
                claimable.add(reward);
            }
        }
        return new MilestoneAward(entry, milestone, total, automatic, claimable);
    }

    /** Admin path: adds progress directly, with the same milestone handling. */
    public List<MilestoneAward> add(final UUID player, final String entryId, final long amount) {
        final CollectionEntry entry = config.byId(entryId);
        if (entry == null) {
            return List.of();
        }
        return record(player, entry, amount, System.currentTimeMillis());
    }

    /** Admin path: sets an exact total without granting anything. */
    public boolean set(final UUID player, final String entryId, final long total) {
        final CollectionEntry entry = config.byId(entryId);
        if (entry == null) {
            return false;
        }
        profile(player).set(entry.id(), Math.max(0, total));
        save();
        return true;
    }

    // ------------------------------------------------------------------
    // claiming
    // ------------------------------------------------------------------

    /**
     * Claims the hand-claimed rewards of one milestone. Exactly once:
     * the claim is recorded before delivery, and anything that cannot
     * be delivered is parked in pending storage rather than lost.
     */
    public ClaimResult claim(final UUID player, final String entryId, final int tier) {
        final CollectionEntry entry = config.byId(entryId);
        if (player == null || entry == null || tier < 1 || tier > entry.tiers()) {
            return ClaimResult.UNKNOWN;
        }
        final CollectionMilestone milestone = entry.milestones().get(tier - 1);
        final CollectionProfile profile = profile(player);
        if (profile.amount(entry.id()) < milestone.amount()) {
            return ClaimResult.NOT_REACHED;
        }
        if (!milestone.hasManualReward()) {
            return ClaimResult.NOTHING_TO_CLAIM;
        }
        if (profile.claimed(entry.id(), tier)) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        profile.claim(entry.id(), tier);
        save();
        boolean pending = false;
        for (final Reward reward : milestone.rewards()) {
            if (!reward.manual()) {
                continue;
            }
            final RewardGrant grant = rewards == null ? RewardGrant.PENDING
                    : rewards.deliver(player, reward, "collection:" + entry.id() + ":" + tier);
            if (grant == RewardGrant.PENDING) {
                pending = true;
            } else if (grant == RewardGrant.FAILED) {
                warn("Collection " + entry.id() + " tier " + tier + " has an undeliverable reward "
                        + reward.type().id() + " — check collections.yml");
            }
        }
        return pending ? ClaimResult.PENDING : ClaimResult.CLAIMED;
    }

    /** True when this milestone still has something to claim. */
    public boolean claimable(final UUID player, final CollectionEntry entry, final int tier) {
        if (entry == null || tier < 1 || tier > entry.tiers()) {
            return false;
        }
        final CollectionMilestone milestone = entry.milestones().get(tier - 1);
        return milestone.hasManualReward()
                && profile(player).amount(entry.id()) >= milestone.amount()
                && !profile(player).claimed(entry.id(), tier);
    }

    /** How many milestones across all Collections are waiting to be claimed. */
    public int claimableCount(final UUID player) {
        int count = 0;
        for (final CollectionEntry entry : config.all()) {
            for (int tier = 1; tier <= entry.tiers(); tier++) {
                if (claimable(player, entry, tier)) {
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    /** A player's total for one entry. */
    public long amount(final UUID player, final String entryId) {
        return profile(player).amount(entryId);
    }

    /** Tiers reached for an entry. */
    public int tier(final UUID player, final CollectionEntry entry) {
        return entry == null ? 0
                : CollectionProgress.tiersReached(amount(player, entry.id()), entry.milestones());
    }

    /** True once the player has found at least one of a hidden entry. */
    public boolean discovered(final UUID player, final CollectionEntry entry) {
        if (entry == null) {
            return false;
        }
        if (!entry.hidden()) {
            return true;
        }
        return amount(player, entry.id()) > 0 || profile(player).discovery(entry.id()).discovered();
    }

    /** Completion of one category as a whole percentage. */
    public int categoryPercent(final UUID player, final CollectionCategory category) {
        return CollectionProgress.percent(CollectionProgress.completion(
                config.byCategory(category), entry -> amount(player, entry.id())));
    }

    /** Completion of every Collection as a whole percentage. */
    public int totalPercent(final UUID player) {
        return CollectionProgress.percent(CollectionProgress.completion(
                config.all(), entry -> amount(player, entry.id())));
    }

    /** How many entries in a category are fully complete. */
    public int completedIn(final UUID player, final CollectionCategory category) {
        return CollectionProgress.completedCount(config.byCategory(category),
                entry -> amount(player, entry.id()));
    }

    /** How many Collections are fully complete. */
    public int completedTotal(final UUID player) {
        return CollectionProgress.completedCount(config.all(), entry -> amount(player, entry.id()));
    }

    // ------------------------------------------------------------------
    // unlocks
    // ------------------------------------------------------------------

    /** Storage key of a permanent unlock. */
    public static String unlockKey(final RewardType type, final String id) {
        return type.id() + ":" + (id == null ? "" : id.trim().toLowerCase(Locale.ROOT));
    }

    /** True when the player has a permanent unlock. */
    public boolean hasUnlock(final UUID player, final RewardType type, final String id) {
        return profile(player).unlocked(unlockKey(type, id));
    }

    /**
     * True when a Collection-locked recipe is unlocked. Derived from
     * the Collection tier as well as the stored unlock, so a recipe can
     * never be lost even if an unlock record goes missing.
     */
    public boolean recipeUnlocked(final UUID player, final String recipeId) {
        final UnlockableRecipe recipe = config.recipe(recipeId);
        if (recipe == null) {
            return false;
        }
        if (hasUnlock(player, RewardType.RECIPE, recipe.id())) {
            return true;
        }
        final CollectionEntry entry = config.byId(recipe.collectionId());
        return entry != null && tier(player, entry) >= recipe.tier();
    }

    /** Every unlock of one type the player owns (titles, cosmetics, …). */
    public Set<String> unlocksOfType(final UUID player, final RewardType type) {
        final String prefix = type.id() + ":";
        final Set<String> out = new LinkedHashSet<>();
        for (final String key : profile(player).unlocks()) {
            if (key.startsWith(prefix)) {
                out.add(key.substring(prefix.length()));
            }
        }
        return out;
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
