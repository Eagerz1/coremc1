package com.coremc.core.island;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A CoreMC Skyblock island (schema version 5).
 *
 * Ownership & membership: exactly one owner plus a bounded member set.
 * The island FILE is authoritative for membership; player profiles only
 * carry a fast-lookup association pointer.
 *
 * Geometry: the grid cell is derived from the absolute centre and the
 * configured spacing (stored absolutely so spacing changes never
 * corrupt islands). The BORDER is the protected square centred on the
 * island: new islands start at 50x50 and upgrades can grow it (never
 * beyond {@code spacing}, enforced by config validation, so islands
 * on adjacent cells can never overlap).
 *
 * Levels & upgrades: {@link #level} is the island level, recomputed by
 * the island level service from {@link #xp}; {@link #upgrades} is a map
 * of upgrade-id -&gt; purchased tier that future CoreMC upgrade systems
 * extend without schema changes.
 *
 * Theme & settings (schema v3): the {@link #theme} key records which
 * theme the island was generated with (defaults to \"plains\" for
 * islands created before themes existed); {@link #settings} holds
 * owner-controlled switches (Settings/Permissions GUI) as
 * boolean-by-string keys that enums guard on read.
 *
 * Progression (schema v4): {@link #xp} is the island's lifetime
 * progression score and {@link #stats} its lifetime counters
 * (blocks-mined, crops-harvested, ...). Both feed the island level
 * calculation and the island leaderboard.
 *
 * Buffs (schema v5): {@link #buffs} maps buff-id -&gt; purchased level
 * for the 12 island buffs. Older files simply have no {@code buffs}
 * key and load with every buff at 0 — no migration step needed.
 */
public final class Island {

    /** Default border width for brand-new islands (brief requirement). */
    public static final int DEFAULT_BORDER_SIZE = 50;
    public static final int DEFAULT_LEVEL = 1;
    /** Theme assigned to islands that predate the theme system. */
    public static final String DEFAULT_THEME = "plains";

    /** Toggleable island settings keys (Settings + Permissions GUIs). */
    public enum Setting {
        /** Settings: natural mob spawning inside the border (default ON). */
        MOB_SPAWNING("mob-spawning", true),
        /**
         * Settings: non-team players may interact (doors/buttons/containers)
         * inside the border. Default OFF — matches the legacy strict posture
         * where outsiders could never touch anything; owners opt in.
         */
        VISITORS("visitors", false),
        /** Permissions: members may break/place blocks (default ON). */
        MEMBERS_BUILD("members-build", true),
        /** Permissions: members may open containers and use doors/buttons (default ON). */
        MEMBERS_CONTAINERS("members-containers", true);

        private final String key;
        private final boolean defaultValue;

        Setting(final String key, final boolean defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public String key() {
            return key;
        }

        public boolean defaultValue() {
            return defaultValue;
        }
    }

    private final UUID islandId;
    private final UUID owner;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final String worldName;
    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final int borderSize;
    private final long createdMillis;

    private int level = DEFAULT_LEVEL;
    private final Map<String, Integer> upgrades = new LinkedHashMap<>();
    private final Map<String, Integer> buffs = new LinkedHashMap<>();
    private String theme = DEFAULT_THEME;
    private final Map<String, Boolean> settings = new LinkedHashMap<>();
    private long xp;
    private final Map<String, Long> stats = new LinkedHashMap<>();

    public Island(
            final UUID islandId,
            final UUID owner,
            final String worldName,
            final int centerX,
            final int centerY,
            final int centerZ,
            final int borderSize,
            final long createdMillis) {
        this.islandId = Objects.requireNonNull(islandId);
        this.owner = Objects.requireNonNull(owner);
        this.worldName = Objects.requireNonNull(worldName);
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.borderSize = borderSize;
        this.createdMillis = createdMillis;
    }

    /** Grid cell of this island for a given spacing. */
    public int[] gridCell(final int spacing) {
        return new int[] {Math.floorDiv(centerX, spacing), Math.floorDiv(centerZ, spacing)};
    }

    /** The block the player stands on after teleporting (island home). */
    public double homeX() {
        return centerX + 0.5;
    }

    public double homeY() {
        return centerY + 1.0;
    }

    public double homeZ() {
        return centerZ + 0.5;
    }

    /**
     * Whether (x,z) is inside the protected border square
     * [cx-border/2, cx+border/2) x [cz-border/2, cz+border/2). All Y levels.
     */
    public boolean containsBlock(final int x, final int z) {
        final int half = borderSize / 2;
        return x >= centerX - half && x < centerX + half && z >= centerZ - half && z < centerZ + half;
    }

    /** OWNER or MEMBER or null. */
    public IslandRole roleOf(final UUID player) {
        if (owner.equals(player)) {
            return IslandRole.OWNER;
        }
        if (members.contains(player)) {
            return IslandRole.MEMBER;
        }
        return null;
    }

    public boolean addMember(final UUID player) {
        return members.add(player);
    }

    public boolean removeMember(final UUID player) {
        return members.remove(player);
    }

    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("island-id", islandId.toString());
        map.put("owner", owner.toString());
        final java.util.List<String> memberList = new java.util.ArrayList<>(members.size());
        for (final UUID member : members) {
            memberList.add(member.toString());
        }
        map.put("members", memberList);
        map.put("world", worldName);
        map.put("center-x", centerX);
        map.put("center-y", centerY);
        map.put("center-z", centerZ);
        map.put("border-size", borderSize);
        map.put("level", level);
        map.put("xp", xp);
        map.put("stats", new LinkedHashMap<>(stats));
        map.put("upgrades", new LinkedHashMap<>(upgrades));
        map.put("buffs", new LinkedHashMap<>(buffs));
        map.put("theme", theme);
        map.put("settings", new LinkedHashMap<>(settings));
        map.put("created-millis", createdMillis);
        return map;
    }

    /** Tolerant load: v1/v2 files (no members/border/level/upgrades/theme/settings) get defaults. */
    public static Island fromMap(final Map<String, Object> map) {
        final int border = map.containsKey("border-size") ? asInt(map.get("border-size")) : DEFAULT_BORDER_SIZE;
        final Island island = new Island(
                UUID.fromString(String.valueOf(map.get("island-id"))),
                UUID.fromString(String.valueOf(map.get("owner"))),
                String.valueOf(map.get("world")),
                asInt(map.get("center-x")),
                asInt(map.get("center-y")),
                asInt(map.get("center-z")),
                border,
                asLong(map.get("created-millis")));
        if (map.get("level") instanceof Number level) {
            island.level = Math.max(1, level.intValue());
        }
        if (map.get("xp") instanceof Number xp) {
            island.xp = Math.max(0L, xp.longValue());
        }
        final Object statsObject = map.get("stats");
        if (statsObject instanceof Map<?, ?> rawStats) {
            for (final Map.Entry<?, ?> entry : rawStats.entrySet()) {
                if (entry.getValue() instanceof Number amount) {
                    island.stats.put(String.valueOf(entry.getKey()), Math.max(0L, amount.longValue()));
                }
            }
        }
        final Object memberObject = map.get("members");
        if (memberObject instanceof java.util.List<?> list) {
            for (final Object entry : list) {
                island.members.add(UUID.fromString(String.valueOf(entry)));
            }
        }
        final Object upgradeObject = map.get("upgrades");
        if (upgradeObject instanceof Map<?, ?> raw) {
            for (final Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getValue() instanceof Number tier) {
                    island.upgrades.put(String.valueOf(entry.getKey()), tier.intValue());
                }
            }
        }
        final Object buffObject = map.get("buffs");
        if (buffObject instanceof Map<?, ?> rawBuffs) {
            for (final Map.Entry<?, ?> entry : rawBuffs.entrySet()) {
                if (entry.getValue() instanceof Number tier) {
                    island.buffs.put(String.valueOf(entry.getKey()), Math.max(0, tier.intValue()));
                }
            }
        }
        final Object themeObject = map.get("theme");
        if (themeObject != null && !String.valueOf(themeObject).isBlank()) {
            island.theme = String.valueOf(themeObject);
        }
        final Object settingsObject = map.get("settings");
        if (settingsObject instanceof Map<?, ?> raw) {
            for (final Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getValue() instanceof Boolean on) {
                    island.settings.put(String.valueOf(entry.getKey()), on);
                }
            }
        }
        return island;
    }

    public UUID islandId() {
        return islandId;
    }

    public UUID owner() {
        return owner;
    }

    public Set<UUID> members() {
        return java.util.Collections.unmodifiableSet(members);
    }

    public String worldName() {
        return worldName;
    }

    public int centerX() {
        return centerX;
    }

    public int centerY() {
        return centerY;
    }

    public int centerZ() {
        return centerZ;
    }

    public int borderSize() {
        return borderSize;
    }

    public long createdMillis() {
        return createdMillis;
    }

    public int level() {
        return level;
    }

    public void level(final int level) {
        this.level = Math.max(1, level);
    }

    /** Lifetime island progression score (feeds the level calculation). */
    public long xp() {
        return xp;
    }

    /** Adds island XP (non-positive amounts are ignored). */
    public void addXp(final long amount) {
        if (amount > 0L) {
            xp += amount;
        }
    }

    /** Lifetime island counter for {@code statKey} (0 when never tracked). */
    public long statOf(final String statKey) {
        return stats.getOrDefault(statKey, 0L);
    }

    /** Adds to a lifetime island counter (non-positive amounts are ignored). */
    public void addStat(final String statKey, final long amount) {
        if (amount > 0L) {
            stats.merge(statKey, amount, Long::sum);
        }
    }

    public Map<String, Long> stats() {
        return java.util.Collections.unmodifiableMap(stats);
    }

    public Map<String, Integer> upgrades() {
        return java.util.Collections.unmodifiableMap(upgrades);
    }

    public void setUpgradeTier(final String upgradeId, final int tier) {
        upgrades.put(Objects.requireNonNull(upgradeId, "upgradeId"), Math.max(0, tier));
    }

    /** Buff-id -&gt; purchased level for the 12 island buffs (missing = 0). */
    public Map<String, Integer> buffs() {
        return java.util.Collections.unmodifiableMap(buffs);
    }

    public void setBuffTier(final String buffId, final int tier) {
        buffs.put(Objects.requireNonNull(buffId, "buffId"), Math.max(0, tier));
    }

    /** The theme key this island was generated with (never null). */
    public String theme() {
        return theme;
    }

    public void theme(final String theme) {
        if (theme != null && !theme.isBlank()) {
            this.theme = theme;
        }
    }

    /** Effective value of a toggleable setting (stored override or its default). */
    public boolean setting(final Setting setting) {
        return settings.getOrDefault(setting.key(), setting.defaultValue());
    }

    public void setting(final Setting setting, final boolean value) {
        settings.put(setting.key(), value);
    }

    private static int asInt(final Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Expected numeric island field, got: " + value);
    }

    private static long asLong(final Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Expected numeric island field, got: " + value);
    }

    @Override
    public String toString() {
        return "Island{id=" + islandId + ", owner=" + owner + ", members=" + members.size() + ", centre=" + centerX
                + "," + centerY + "," + centerZ + ", border=" + borderSize + ", level=" + level + " @ " + worldName
                + "}";
    }
}
