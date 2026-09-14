package com.coremc.core.island;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * A CoreMC skyblock island: exactly one owner plus a member set, a grid
 * position (stored as absolute world coordinates, so later spacing
 * changes never corrupt existing islands) and a border claim — the
 * protected square centred on the island.
 */
public final class Island {

    private final UUID id;
    private final UUID owner;
    private final String ownerName;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final String worldName;
    private final int slot;
    private final int centerX;
    private final int baseY;
    private final int centerZ;
    private final int borderSize;
    private final String schematic;
    private final long createdAt;
    private final double homeX;
    private final double homeY;
    private final double homeZ;
    private final float homeYaw;
    private final float homePitch;

    public Island(
            final UUID id,
            final UUID owner,
            final String ownerName,
            final String worldName,
            final int slot,
            final int centerX,
            final int baseY,
            final int centerZ,
            final int borderSize,
            final String schematic,
            final long createdAt,
            final double homeX,
            final double homeY,
            final double homeZ,
            final float homeYaw,
            final float homePitch) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.worldName = worldName;
        this.slot = slot;
        this.centerX = centerX;
        this.baseY = baseY;
        this.centerZ = centerZ;
        this.borderSize = borderSize;
        this.schematic = schematic;
        this.createdAt = createdAt;
        this.homeX = homeX;
        this.homeY = homeY;
        this.homeZ = homeZ;
        this.homeYaw = homeYaw;
        this.homePitch = homePitch;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public String worldName() {
        return worldName;
    }

    public int slot() {
        return slot;
    }

    public int centerX() {
        return centerX;
    }

    public int baseY() {
        return baseY;
    }

    public int centerZ() {
        return centerZ;
    }

    public int borderSize() {
        return borderSize;
    }

    public String schematic() {
        return schematic;
    }

    public long createdAt() {
        return createdAt;
    }

    /** The island home point in the given (island) world. */
    public Location home(final World world) {
        return new Location(world, homeX, homeY, homeZ, homeYaw, homePitch);
    }

    public boolean isOwner(final UUID player) {
        return owner.equals(player);
    }

    /** True for the owner and every member. */
    public boolean isMember(final UUID player) {
        return owner.equals(player) || members.contains(player);
    }

    public boolean addMember(final UUID player) {
        if (owner.equals(player)) {
            return false;
        }
        return members.add(player);
    }

    public boolean removeMember(final UUID player) {
        return members.remove(player);
    }

    /** Unmodifiable view of the member UUIDs (owner not included). */
    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    /**
     * Whether the given block column lies inside the island's border
     * claim. The border square spans {@code borderSize} blocks centred
     * on the island centre, matching the visual world border exactly.
     */
    public boolean contains(final String world, final int x, final int z) {
        if (!worldName.equals(world)) {
            return false;
        }
        final int half = borderSize / 2;
        return Math.abs(x - centerX) <= half && Math.abs(z - centerZ) <= half;
    }

    /** Serialises the island to a flat map for YAML persistence. */
    public Map<String, Object> toMap() {
        final List<String> memberIds = new ArrayList<>();
        for (final UUID member : members) {
            memberIds.add(member.toString());
        }
        return Map.ofEntries(
                Map.entry("id", id.toString()),
                Map.entry("owner", owner.toString()),
                Map.entry("owner-name", ownerName),
                Map.entry("members", memberIds),
                Map.entry("world", worldName),
                Map.entry("slot", slot),
                Map.entry("center", Map.of("x", centerX, "y", baseY, "z", centerZ)),
                Map.entry("border-size", borderSize),
                Map.entry("schematic", schematic),
                Map.entry("created", createdAt),
                Map.entry("home", Map.of(
                        "x", homeX, "y", homeY, "z", homeZ,
                        "yaw", (double) homeYaw, "pitch", (double) homePitch)));
    }

    /** Rebuilds an island from {@link #toMap()} output. */
    public static Island fromMap(final Map<String, Object> map) {
        require(map, "id", "owner", "owner-name", "world", "slot", "center", "border-size",
                "schematic", "created", "home");
        final Map<String, Object> center = nested(map, "center");
        final Map<String, Object> home = nested(map, "home");
        final List<UUID> members = new ArrayList<>();
        final Object rawMembers = map.get("members");
        if (rawMembers instanceof List<?> list) {
            for (final Object entry : list) {
                members.add(UUID.fromString(String.valueOf(entry)));
            }
        }
        final Island island = new Island(
                UUID.fromString(String.valueOf(map.get("id"))),
                UUID.fromString(String.valueOf(map.get("owner"))),
                String.valueOf(map.get("owner-name")),
                String.valueOf(map.get("world")),
                intAt(map, "slot"),
                intAt(center, "x"),
                intAt(center, "y"),
                intAt(center, "z"),
                intAt(map, "border-size"),
                String.valueOf(map.get("schematic")),
                longAt(map, "created"),
                doubleAt(home, "x"),
                doubleAt(home, "y"),
                doubleAt(home, "z"),
                (float) doubleAt(home, "yaw"),
                (float) doubleAt(home, "pitch"));
        for (final UUID member : members) {
            island.addMember(member);
        }
        return island;
    }

    private static void require(final Map<String, Object> map, final String... keys) {
        for (final String key : keys) {
            if (!map.containsKey(key)) {
                throw new IllegalArgumentException("Island data missing key '" + key + "'");
            }
        }
    }

    private static Map<String, Object> nested(final Map<String, Object> map, final String key) {
        if (map.get(key) instanceof Map<?, ?> nested) {
            final Map<String, Object> result = new java.util.LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : nested.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        throw new IllegalArgumentException("Island data key '" + key + "' is not a section");
    }

    private static int intAt(final Map<String, Object> map, final String key) {
        if (map.get(key) instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Island data key '" + key + "' is not a number");
    }

    private static long longAt(final Map<String, Object> map, final String key) {
        if (map.get(key) instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Island data key '" + key + "' is not a number");
    }

    private static double doubleAt(final Map<String, Object> map, final String key) {
        if (map.get(key) instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("Island data key '" + key + "' is not a number");
    }
}
