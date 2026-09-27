package com.coremc.core.season;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent per-player Season Journey state plus historical summaries. */
public final class SeasonJourneyProfile {

    public record SeasonSummary(String seasonId, String name, long xp, int highestLevel,
                                boolean completed, long completedAt, long archivedAt) {
    }

    public record PendingReward(String seasonId, String track, int level,
                                String rewardKey, String type, long amount, String item, String display) {
        public String id() {
            return seasonId + ":" + track + ":" + level + ":" + rewardKey;
        }
    }

    private String seasonId = "";
    private long xp;
    private int highestLevel = 1;
    private boolean completed;
    private long completedAt;
    private final Set<Integer> claimedFree = new LinkedHashSet<>();
    private final Set<Integer> claimedPremium = new LinkedHashSet<>();
    private final Set<String> xpAwardKeys = new LinkedHashSet<>();
    private final Set<String> deliveredRewardKeys = new LinkedHashSet<>();
    private final List<PendingReward> pendingRewards = new ArrayList<>();
    private final Map<String, SeasonSummary> history = new LinkedHashMap<>();

    public String seasonId() { return seasonId; }
    public void setSeasonId(final String seasonId) { this.seasonId = seasonId == null ? "" : seasonId; }
    public long xp() { return xp; }
    public void setXp(final long xp) { this.xp = Math.max(0L, xp); }
    public void addXp(final long amount, final long max) { this.xp = Math.min(Math.max(0L, max), this.xp + Math.max(0L, amount)); }
    public int highestLevel() { return highestLevel; }
    public void setHighestLevel(final int highestLevel) { this.highestLevel = Math.max(1, highestLevel); }
    public boolean completed() { return completed; }
    public void setCompleted(final boolean completed) { this.completed = completed; }
    public long completedAt() { return completedAt; }
    public void setCompletedAt(final long completedAt) { this.completedAt = Math.max(0L, completedAt); }
    public Set<Integer> claimedFree() { return claimedFree; }
    public Set<Integer> claimedPremium() { return claimedPremium; }
    public Set<String> xpAwardKeys() { return xpAwardKeys; }
    public Set<String> deliveredRewardKeys() { return deliveredRewardKeys; }
    public List<PendingReward> pendingRewards() { return pendingRewards; }
    public Map<String, SeasonSummary> history() { return history; }

    public boolean claimed(final String track, final int level) {
        return "premium".equals(track) ? claimedPremium.contains(level) : claimedFree.contains(level);
    }

    public void markClaimed(final String track, final int level) {
        if ("premium".equals(track)) {
            claimedPremium.add(level);
        } else {
            claimedFree.add(level);
        }
    }

    public void archiveCurrent(final String name, final long now) {
        if (seasonId == null || seasonId.isBlank() || history.containsKey(seasonId)) {
            return;
        }
        history.put(seasonId, new SeasonSummary(seasonId, name, xp, highestLevel, completed, completedAt, now));
    }

    public void beginSeason(final String newSeasonId) {
        this.seasonId = newSeasonId == null ? "" : newSeasonId;
        this.xp = 0L;
        this.highestLevel = 1;
        this.completed = false;
        this.completedAt = 0L;
        this.claimedFree.clear();
        this.claimedPremium.clear();
        this.xpAwardKeys.clear();
        this.deliveredRewardKeys.clear();
    }

    public Map<String, SeasonSummary> historyView() {
        return Collections.unmodifiableMap(history);
    }
}
