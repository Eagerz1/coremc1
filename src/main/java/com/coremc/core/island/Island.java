package com.coremc.core.island;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A CoreMC Skyblock island.
 *
 * Islands currently belong to exactly one owner (teams land in a later
 * iteration). The grid coordinates are derived from the centre and the
 * configured spacing; the store persists absolute centre coordinates so
 * spacing changes never corrupt existing islands.
 */
public final class Island {

    private final UUID islandId;
    private final UUID owner;
    private final String worldName;
    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final long createdMillis;

    public Island(
            final UUID islandId,
            final UUID owner,
            final String worldName,
            final int centerX,
            final int centerY,
            final int centerZ,
            final long createdMillis) {
        this.islandId = Objects.requireNonNull(islandId);
        this.owner = Objects.requireNonNull(owner);
        this.worldName = Objects.requireNonNull(worldName);
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
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

    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("island-id", islandId.toString());
        map.put("owner", owner.toString());
        map.put("world", worldName);
        map.put("center-x", centerX);
        map.put("center-y", centerY);
        map.put("center-z", centerZ);
        map.put("created-millis", createdMillis);
        return map;
    }

    public static Island fromMap(final Map<String, Object> map) {
        return new Island(
                UUID.fromString(String.valueOf(map.get("island-id"))),
                UUID.fromString(String.valueOf(map.get("owner"))),
                String.valueOf(map.get("world")),
                asInt(map.get("center-x")),
                asInt(map.get("center-y")),
                asInt(map.get("center-z")),
                asLong(map.get("created-millis")));
    }

    public UUID islandId() {
        return islandId;
    }

    public UUID owner() {
        return owner;
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

    public long createdMillis() {
        return createdMillis;
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
        return "Island{id=" + islandId + ", owner=" + owner + ", centre=" + centerX + "," + centerY + "," + centerZ
                + " @ " + worldName + "}";
    }
}
