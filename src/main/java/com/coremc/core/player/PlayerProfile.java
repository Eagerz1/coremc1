package com.coremc.core.player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent per-player data for CoreMC (schema version 6).
 *
 * Schema versions: 1 = join stats only; 2 = + currencies, single
 * role/omnitool slots, island association, placeholders; 3 = per-role
 * progress map (role switching never wipes your other roles' progress);
 * 4 = + spawner unlock kill counters;
 * 5 = + OmniTool purchased upgrade levels;
 * 6 = + custom enchant levels, souls, tags, companions, quests, playtime,
 * chat style, lifetime stats, subscription claims, store rank.
 * All loads are tolerant: unknown/missing fields become defaults.
 *
 * A profile is created the first time a player connects and survives
 * server restarts (YAML on disk). It is deliberately designed for
 * expansion: currencies, role progress, OmniTool progress, island
 * association and cosmetic/subscription placeholders all live here so
 * every CoreMC system reads/writes one authoritative record.
 *
 * Mutations happen on the main server thread (join/quit) or from
 * services holding the profile while the player is online; the YAML
 * snapshot is taken at save time. Offline-profile mutations (admin
 * commands) are loaded, mutated and flushed without being cached.
 *
 * Loading is forward/backward tolerant: unknown/missing fields fall
 * back to defaults, so v1 files seamlessly become v2.
 */
public final class PlayerProfile {

    /** Current on-disk schema version. */
    public static final int SCHEMA_VERSION = 6;

    private final UUID uuid;

    private String username;
    private long firstJoinMillis;
    private long lastSeenMillis;
    private long totalLogins;

    // --- currencies (long minor balance; negatives are impossible by contract) ---
    private long money;
    private long credits;
    private long skyTokens;

    // --- progression ---
    private String roleId = "none";
    private int roleLevel = 1;
    private long roleXp;
    private int omniToolLevel = 1;
    private long omniToolXp;
    /** roleKey -> {"level": int, "xp": long} — per-role, survives switches. */
    private final Map<String, Map<String, Object>> roleProgress = new LinkedHashMap<>();

    /** entityKey (lowercase) -> kills — spawner unlock progression, survives restarts. */
    private final Map<String, Long> killCounts = new LinkedHashMap<>();

    /** OmniTool purchased upgrade id -> level (0 = not owned). Schema v5. */
    private final Map<String, Integer> omniUpgrades = new LinkedHashMap<>();

    // --- island association (islandId or null; authoritative membership lives in the island file) ---
    private UUID islandId;

    // --- placeholders for future systems (persisted so nothing is lost later) ---
    private final Map<String, String> cosmetics = new LinkedHashMap<>();
    private String subscriptionTier = "none";
    private long subscriptionExpiresMillis;

    // --- schema v6: full CoreMC progression (all tolerant, all defaulted) ---

    /** Custom enchant id -> level (0 = not owned; never persisted). */
    private final Map<String, Integer> enchantLevels = new LinkedHashMap<>();
    /** Souls collected via Slayer Soul Collector (spendable progression). */
    private long souls;
    /** Owned cosmetic tag ids. */
    private final java.util.Set<String> ownedTags = new java.util.LinkedHashSet<>();
    /** Equipped tag id ("none" = hidden). */
    private String equippedTag = "none";
    /** Companion id -> {"rarity": String, "level": int, "xp": long}. */
    private final Map<String, Map<String, Object>> companions = new LinkedHashMap<>();
    /** Equipped companion id ("none" = no active companion). */
    private String equippedCompanion = "none";
    /** Day-key (yyyy-MM-dd) the current daily quests were assigned for. */
    private String questDay = "";
    /** Assigned daily quest ids for {@link #questDay}. */
    private final java.util.List<String> dailyQuests = new java.util.ArrayList<>();
    /** Quest id -> {"progress": long, "done": boolean, "claimed": boolean}. */
    private final Map<String, Map<String, Object>> questProgress = new LinkedHashMap<>();
    /** Lifetime active playtime in minutes (anti-AFK filtered by the service). */
    private long playtimeMinutes;
    /** Claimed playtime milestone ids. */
    private final java.util.Set<String> playtimeClaimed = new java.util.LinkedHashSet<>();
    /** Selected chat colour/gradient id ("none" = default). */
    private String chatColor = "none";
    /** Whether the chat colour applies bold. */
    private boolean chatBold;
    /** Generic lifetime stats (blocks-mined, crops-harvested, ...). */
    private final Map<String, Long> stats = new LinkedHashMap<>();
    /** Subscription tier -> last reward-claim day-key (claim-once guard). */
    private final Map<String, String> subscriptionClaims = new LinkedHashMap<>();
    /** Purchased store rank id ("member" = default). */
    private String rankId = "member";

    private PlayerProfile(final UUID uuid) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
    }

    /** Creates a brand-new profile for a player who has never joined. */
    public static PlayerProfile createNew(final UUID uuid, final String username, final long nowMillis) {
        final PlayerProfile profile = new PlayerProfile(uuid);
        profile.username = username;
        profile.firstJoinMillis = nowMillis;
        profile.lastSeenMillis = nowMillis;
        profile.totalLogins = 0L;
        return profile;
    }

    /** Rebuilds a profile from its YAML representation (tolerant of v1 files). */
    public static PlayerProfile fromMap(final UUID uuid, final Map<String, Object> map) {
        final PlayerProfile profile = new PlayerProfile(uuid);
        profile.username = String.valueOf(map.getOrDefault("username", "unknown"));
        profile.firstJoinMillis = asLong(map.get("first-join-millis"), 0L);
        profile.lastJoinDefaults(map, profile);
        return profile;
    }

    private void lastJoinDefaults(final Map<String, Object> map, final PlayerProfile profile) {
        profile.lastSeenMillis = asLong(map.get("last-seen-millis"), 0L);
        profile.totalLogins = asLong(map.get("total-logins"), 0L);
        profile.money = Math.max(0L, asLong(map.get("money"), 0L));
        profile.credits = Math.max(0L, asLong(map.get("credits"), 0L));
        profile.skyTokens = Math.max(0L, asLong(map.get("sky-tokens"), 0L));
        profile.roleId = String.valueOf(map.getOrDefault("role", "none"));
        profile.roleLevel = (int) Math.max(1L, asLong(map.get("role-level"), 1L));
        profile.roleXp = Math.max(0L, asLong(map.get("role-xp"), 0L));
        profile.omniToolLevel = (int) Math.max(1L, asLong(map.get("omnitool-level"), 1L));
        profile.omniToolXp = Math.max(0L, asLong(map.get("omnitool-xp"), 0L));
        final Object island = map.get("island-id");
        profile.islandId = island == null ? null : UUID.fromString(String.valueOf(island));
        profile.subscriptionTier = String.valueOf(map.getOrDefault("subscription-tier", "none"));
        profile.subscriptionExpiresMillis = Math.max(0L, asLong(map.get("subscription-expires-millis"), 0L));
        final Object progress = map.get("role-progress");
        if (progress instanceof Map<?, ?> raw) {
            for (final Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> data) {
                    final Map<String, Object> record = new LinkedHashMap<>();
                    record.put("level", Math.max(1L, asLong(data.get("level"), 1L)));
                    record.put("xp", Math.max(0L, asLong(data.get("xp"), 0L)));
                    profile.roleProgress.put(String.valueOf(entry.getKey()), record);
                }
            }
        } else if (!"none".equals(profile.roleId) && (profile.roleLevel > 1 || profile.roleXp > 0)) {
            // v2 migration: fold the single role-level fields into the progress map
            final Map<String, Object> record = new LinkedHashMap<>();
            record.put("level", (long) profile.roleLevel);
            record.put("xp", profile.roleXp);
            profile.roleProgress.put(profile.roleId, record);
        }
        final Object killMap = map.get("kill-counts");
        if (killMap instanceof Map<?, ?> raw) {
            for (final Map.Entry<?, ?> entry : raw.entrySet()) {
                profile.killCounts.put(String.valueOf(entry.getKey()), Math.max(0L, asLong(entry.getValue(), 0L)));
            }
        }
        final Object cosmeticsMap = map.get("cosmetics");
        if (cosmeticsMap instanceof Map<?, ?> raw) {
            for (final Map.Entry<?, ?> entry : raw.entrySet()) {
                profile.cosmetics.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        final Object upgradesMap = map.get("omni-upgrades");
        if (upgradesMap instanceof Map<?, ?> raw) {
            for (final Map.Entry<?, ?> entry : raw.entrySet()) {
                final long level = Math.max(0L, asLong(entry.getValue(), 0L));
                if (level > 0L) { // level 0 is the default; never persisted, never loaded
                    profile.omniUpgrades.put(
                            String.valueOf(entry.getKey()), (int) Math.min(level, Integer.MAX_VALUE));
                }
            }
        }
        // --- schema v6 (all tolerant: absent = default) ---
        final Object enchantMap = map.get("enchant-levels");
        if (enchantMap instanceof Map<?, ?> rawEnch) {
            for (final Map.Entry<?, ?> entry : rawEnch.entrySet()) {
                final long level = Math.max(0L, asLong(entry.getValue(), 0L));
                if (level > 0L) {
                    profile.enchantLevels.put(
                            String.valueOf(entry.getKey()), (int) Math.min(level, Integer.MAX_VALUE));
                }
            }
        }
        profile.souls = Math.max(0L, asLong(map.get("souls"), 0L));
        final Object tagsObject = map.get("owned-tags");
        if (tagsObject instanceof java.util.List<?> tagList) {
            for (final Object entry : tagList) {
                profile.ownedTags.add(String.valueOf(entry));
            }
        }
        profile.equippedTag = String.valueOf(map.getOrDefault("equipped-tag", "none"));
        loadStringObjectMap(map.get("companions"), profile.companions);
        profile.equippedCompanion = String.valueOf(map.getOrDefault("equipped-companion", "none"));
        profile.questDay = String.valueOf(map.getOrDefault("quest-day", ""));
        final Object dailiesObject = map.get("daily-quests");
        if (dailiesObject instanceof java.util.List<?> dailyList) {
            for (final Object entry : dailyList) {
                profile.dailyQuests.add(String.valueOf(entry));
            }
        }
        loadStringObjectMap(map.get("quest-progress"), profile.questProgress);
        profile.playtimeMinutes = Math.max(0L, asLong(map.get("playtime-minutes"), 0L));
        final Object claimedObject = map.get("playtime-claimed");
        if (claimedObject instanceof java.util.List<?> claimedList) {
            for (final Object entry : claimedList) {
                profile.playtimeClaimed.add(String.valueOf(entry));
            }
        }
        profile.chatColor = String.valueOf(map.getOrDefault("chat-color", "none"));
        profile.chatBold = Boolean.parseBoolean(String.valueOf(map.getOrDefault("chat-bold", "false")));
        final Object statsObject = map.get("stats");
        if (statsObject instanceof Map<?, ?> rawStats) {
            for (final Map.Entry<?, ?> entry : rawStats.entrySet()) {
                profile.stats.put(String.valueOf(entry.getKey()), Math.max(0L, asLong(entry.getValue(), 0L)));
            }
        }
        final Object claimsObject = map.get("subscription-claims");
        if (claimsObject instanceof Map<?, ?> rawClaims) {
            for (final Map.Entry<?, ?> entry : rawClaims.entrySet()) {
                profile.subscriptionClaims.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        profile.rankId = String.valueOf(map.getOrDefault("rank", "member"));
    }

    /** Tolerant loader for id -> flat string/object record maps (companions, quests). */
    private static void loadStringObjectMap(
            final Object raw, final Map<String, Map<String, Object>> target) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> data) {
                final Map<String, Object> record = new LinkedHashMap<>();
                for (final Map.Entry<?, ?> field : data.entrySet()) {
                    record.put(String.valueOf(field.getKey()), field.getValue());
                }
                target.put(String.valueOf(entry.getKey()), record);
            }
        }
    }

    /** Serialises this profile to a YAML-safe map. */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema-version", SCHEMA_VERSION);
        map.put("username", username);
        map.put("first-join-millis", firstJoinMillis);
        map.put("last-seen-millis", lastSeenMillis);
        map.put("total-logins", totalLogins);
        map.put("money", money);
        map.put("credits", credits);
        map.put("sky-tokens", skyTokens);
        map.put("role", roleId);
        map.put("role-level", roleLevel);
        map.put("role-xp", roleXp);
        map.put("role-progress", deepCopyProgress());
        map.put("omnitool-level", omniToolLevel);
        map.put("omnitool-xp", omniToolXp);
        map.put("island-id", islandId == null ? null : islandId.toString());
        map.put("subscription-tier", subscriptionTier);
        map.put("subscription-expires-millis", subscriptionExpiresMillis);
        map.put("cosmetics", new LinkedHashMap<>(cosmetics));
        map.put("kill-counts", new LinkedHashMap<>(killCounts));
        map.put("omni-upgrades", new LinkedHashMap<>(omniUpgrades));
        map.put("enchant-levels", new LinkedHashMap<>(enchantLevels));
        map.put("souls", souls);
        map.put("owned-tags", new java.util.ArrayList<>(ownedTags));
        map.put("equipped-tag", equippedTag);
        map.put("companions", deepCopyRecords(companions));
        map.put("equipped-companion", equippedCompanion);
        map.put("quest-day", questDay);
        map.put("daily-quests", new java.util.ArrayList<>(dailyQuests));
        map.put("quest-progress", deepCopyRecords(questProgress));
        map.put("playtime-minutes", playtimeMinutes);
        map.put("playtime-claimed", new java.util.ArrayList<>(playtimeClaimed));
        map.put("chat-color", chatColor);
        map.put("chat-bold", chatBold);
        map.put("stats", new LinkedHashMap<>(stats));
        map.put("subscription-claims", new LinkedHashMap<>(subscriptionClaims));
        map.put("rank", rankId);
        return map;
    }

    /** Records a successful login. Called on the main thread from the join listener. */
    public void recordLogin(final String currentName, final long nowMillis) {
        this.username = currentName;
        this.lastSeenMillis = nowMillis;
        this.totalLogins++;
    }

    /** Updates the last-seen timestamp (called when the player quits). */
    public void recordQuit(final long nowMillis) {
        this.lastSeenMillis = nowMillis;
    }

    /** Whether this login is the player's first ever. Call before/for {@link #recordLogin}. */
    public boolean isFirstLogin() {
        return totalLogins == 0L;
    }

    public UUID uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    public long firstJoinMillis() {
        return firstJoinMillis;
    }

    public long lastSeenMillis() {
        return lastSeenMillis;
    }

    public long totalLogins() {
        return totalLogins;
    }

    // --- currency accessors; the service layer enforces the no-negative/overflow contract ---

    public long money() {
        return money;
    }

    public long credits() {
        return credits;
    }

    public long skyTokens() {
        return skyTokens;
    }

    public long balanceOf(final com.coremc.core.economy.Currency currency) {
        return switch (currency) {
            case MONEY -> money;
            case CREDITS -> credits;
            case SKY_TOKENS -> skyTokens;
        };
    }

    /** Package-visible low-level setter — ALWAYS go through EconomyService. */
    public void setBalanceInternal(final com.coremc.core.economy.Currency currency, final long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("Balance cannot be negative: " + amount);
        }
        switch (currency) {
            case MONEY -> money = amount;
            case CREDITS -> credits = amount;
            case SKY_TOKENS -> skyTokens = amount;
        }
    }

    // --- progression accessors ---

    public String roleId() {
        return roleId;
    }

    public void roleId(final String roleId) {
        this.roleId = roleId;
    }

    public int roleLevel() {
        return (int) asLong(progressOf(roleId).get("level"), roleLevel);
    }

    public long roleXp() {
        return asLong(progressOf(roleId).get("xp"), roleXp);
    }

    /** Per-role progress view: level/xp for any role key (defaults 1/0). */
    public Map<String, Object> progressOf(final String roleKey) {
        return roleProgress.getOrDefault(roleKey, java.util.Map.of("level", 1, "xp", 0));
    }

    /** Writes progress for a role key (validated by the progression service). */
    public void setProgress(final String roleKey, final int level, final long xp) {
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("level", level);
        record.put("xp", xp);
        roleProgress.put(roleKey, record);
        if (roleKey.equals(roleId)) {
            this.roleLevel = level;
            this.roleXp = xp;
        }
    }

    public Map<String, Map<String, Object>> roleProgress() {
        return Map.copyOf(roleProgress);
    }

    private Map<String, Object> deepCopyProgress() {
        final Map<String, Object> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, Object>> entry : roleProgress.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }

    public int omniToolLevel() {
        return omniToolLevel;
    }

    public long omniToolXp() {
        return omniToolXp;
    }

    /** OmniTool progression — use RoleService/ProgressionService math. */
    public void setOmniToolProgress(final int level, final long xp) {
        this.omniToolLevel = Math.max(1, level);
        this.omniToolXp = Math.max(0L, xp);
    }

    // --- spawner kill progression ---

    /** Kills recorded for {@code entityKey} (lowercase entity name). */
    public long killCountOf(final String entityKey) {
        return killCounts.getOrDefault(entityKey, 0L);
    }

    /** Increments the kill counter for {@code entityKey} by one. */
    public void addKillCount(final String entityKey) {
        killCounts.merge(entityKey, 1L, Long::sum);
    }

    // --- OmniTool purchased upgrades (schema v5) ---

    /** Level of the OmniTool upgrade {@code upgradeId} (0 = not owned). */
    public int omniUpgrade(final String upgradeId) {
        return omniUpgrades.getOrDefault(upgradeId, 0);
    }

    /**
     * Sets the level of OmniTool upgrade {@code upgradeId}. Level 0 clears
     * the entry (defaults are never persisted). The max is enforced by the
     * OmniUpgradeCatalog, which owns the catalogue definition.
     */
    public void setOmniUpgrade(final String upgradeId, final int level) {
        if (level <= 0) {
            omniUpgrades.remove(upgradeId);
        } else {
            omniUpgrades.put(upgradeId, level);
        }
    }

    // --- custom enchant levels (schema v6) ---

    /** Level of custom enchant {@code enchantId} (0 = not owned). */
    public int enchantLevel(final String enchantId) {
        return enchantLevels.getOrDefault(enchantId, 0);
    }

    /**
     * Sets a custom enchant level (level {@code <= 0} clears the entry).
     * Max levels are enforced by the enchant registry, which owns the catalogue.
     */
    public void setEnchantLevel(final String enchantId, final int level) {
        if (level <= 0) {
            enchantLevels.remove(enchantId);
        } else {
            enchantLevels.put(enchantId, level);
        }
    }

    public Map<String, Integer> enchantLevels() {
        return Map.copyOf(enchantLevels);
    }

    // --- souls ---

    public long souls() {
        return souls;
    }

    public void souls(final long souls) {
        this.souls = Math.max(0L, souls);
    }

    // --- tags ---

    public java.util.Set<String> ownedTags() {
        return java.util.Set.copyOf(ownedTags);
    }

    public boolean addTag(final String tagId) {
        return ownedTags.add(tagId);
    }

    public String equippedTag() {
        return equippedTag;
    }

    public void equippedTag(final String tagId) {
        this.equippedTag = tagId == null || tagId.isBlank() ? "none" : tagId;
    }

    // --- companions ---

    public Map<String, Map<String, Object>> companions() {
        return deepCopyRecords(companions);
    }

    public Map<String, Object> companionOf(final String companionId) {
        return companions.getOrDefault(companionId, Map.of());
    }

    public void setCompanion(final String companionId, final Map<String, Object> record) {
        companions.put(companionId, new LinkedHashMap<>(record));
    }

    public boolean removeCompanion(final String companionId) {
        return companions.remove(companionId) != null;
    }

    public String equippedCompanion() {
        return equippedCompanion;
    }

    public void equippedCompanion(final String companionId) {
        this.equippedCompanion = companionId == null || companionId.isBlank() ? "none" : companionId;
    }

    // --- quests ---

    public String questDay() {
        return questDay;
    }

    public void questDay(final String questDay) {
        this.questDay = questDay == null ? "" : questDay;
    }

    public java.util.List<String> dailyQuests() {
        return java.util.List.copyOf(dailyQuests);
    }

    public void dailyQuests(final java.util.List<String> questIds) {
        dailyQuests.clear();
        dailyQuests.addAll(questIds);
    }

    public Map<String, Object> questProgressOf(final String questId) {
        return questProgress.getOrDefault(questId, Map.of());
    }

    public void setQuestProgress(final String questId, final Map<String, Object> record) {
        questProgress.put(questId, new LinkedHashMap<>(record));
    }

    public void clearQuestProgress() {
        questProgress.clear();
    }

    // --- playtime ---

    public long playtimeMinutes() {
        return playtimeMinutes;
    }

    public void addPlaytimeMinutes(final long minutes) {
        if (minutes > 0L) {
            playtimeMinutes += minutes;
        }
    }

    /** Claims a playtime milestone; returns false if already claimed. */
    public boolean claimPlaytimeMilestone(final String milestoneId) {
        return playtimeClaimed.add(milestoneId);
    }

    public boolean playtimeMilestoneClaimed(final String milestoneId) {
        return playtimeClaimed.contains(milestoneId);
    }

    // --- chat ---

    public String chatColor() {
        return chatColor;
    }

    public void chatColor(final String chatColor) {
        this.chatColor = chatColor == null || chatColor.isBlank() ? "none" : chatColor;
    }

    public boolean chatBold() {
        return chatBold;
    }

    public void chatBold(final boolean chatBold) {
        this.chatBold = chatBold;
    }

    // --- stats ---

    public long statOf(final String statKey) {
        return stats.getOrDefault(statKey, 0L);
    }

    public void addStat(final String statKey, final long amount) {
        if (amount > 0L) {
            stats.merge(statKey, amount, Long::sum);
        }
    }

    // --- subscriptions ---

    /** Day-key the {@code tier} rewards were last claimed ("" = never). */
    public String subscriptionClaimDay(final String tier) {
        return subscriptionClaims.getOrDefault(tier, "");
    }

    public void markSubscriptionClaimed(final String tier, final String dayKey) {
        subscriptionClaims.put(tier, dayKey);
    }

    // --- store rank ---

    public String rankId() {
        return rankId;
    }

    public void rankId(final String rankId) {
        this.rankId = rankId == null || rankId.isBlank() ? "member" : rankId;
    }

    private static Map<String, Map<String, Object>> deepCopyRecords(
            final Map<String, Map<String, Object>> source) {
        final Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }

    /** Island this player owns or belongs to (null = none). Fast redirect only;
     * team membership authority is the island file. */
    public UUID islandId() {
        return islandId;
    }

    public void islandId(final UUID islandId) {
        this.islandId = islandId;
    }

    public Map<String, String> cosmetics() {
        return Map.copyOf(cosmetics);
    }

    public String subscriptionTier() {
        return subscriptionTier;
    }

    public long subscriptionExpiresMillis() {
        return subscriptionExpiresMillis;
    }

    private static long asLong(final Object value, final long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @Override
    public String toString() {
        return "PlayerProfile{uuid=" + uuid + ", username=" + username + ", logins=" + totalLogins + ", credits="
                + credits + ", skyTokens=" + skyTokens + "}";
    }
}
