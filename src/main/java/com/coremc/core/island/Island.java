package com.coremc.core.island;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A CoreMC Skyblock island (schema version 2).
 *
 * Ownership & membership: exactly one owner plus a bounded member set.
 * The island FILE is authoritative for membership; player profiles only
 * carry a fast-lookup association pointer.
 *
 * Geometry: the grid cell is derived from the absolute centre and the
 * configured spacing (stored absolutely so spacing changes never
 * corrupt islands). The BORDER is the protected square centred on the
 * island: new islands start at 50x50 and upgrades can grow it (never
 * beyond {@code spacing/2}, enforced by config validation, so islands
 * on adjacent cells can never overlap).
 *
 * Levels & upgrades: {@link #level} is the island prestige level;
 * {@link #upgrades} is a map of upgrade-id -&gt; purchased tier that
 * future CoreMC upgrade systems extend without schema changes.
 */
public final class Island {

    /** Default border width for brand-new islands (brief requirement). */
    public static final int DEFAULT_BORDER_SIZE = 50;
    public static final int DEFAULT_LEVEL = 1;

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
        map.put("upgrades", new LinkedHashMap<>(upgrades));
        map.put("created-millis", createdMillis);
        return map;
    }

    /** Tolerant load: v1 files (no members/border/level/upgrades) get defaults. */
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

    public Map<String, Integer> upgrades() {
        return java.util.Collections.unmodifiableMap(upgrades);
    }

    public void setUpgradeTier(final String upgradeId, final int tier) {
        upgrades.put(Objects.requireNonNull(upgradeId, "upgradeId"), Math.max(0, tier));
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
