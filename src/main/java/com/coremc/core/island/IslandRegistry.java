package com.coremc.core.island;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** In-memory owner, member, ID, and grid-cell indexes for loaded islands. */
public final class IslandRegistry {

    private final Map<UUID, Island> byOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Island> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Island> byMember = new ConcurrentHashMap<>();
    private final Map<String, Island> byCell = new ConcurrentHashMap<>();
    private final Set<String> usedCells = ConcurrentHashMap.newKeySet();

    public Optional<Island> ownedIsland(final UUID owner) {
        return Optional.ofNullable(byOwner.get(owner));
    }

    public Optional<Island> islandOf(final UUID player) {
        final Island owned = byOwner.get(player);
        return Optional.ofNullable(owned == null ? byMember.get(player) : owned);
    }

    public Optional<Island> islandById(final UUID islandId) {
        return Optional.ofNullable(byId.get(islandId));
    }

    public Optional<Island> islandAt(
            final String worldName,
            final int x,
            final int z,
            final int spacing,
            final Predicate<Island> containsBlock) {
        for (final int[] cell : IslandService.candidateCells(x, z, spacing)) {
            final Island island = byCell.get(GridAssigner.key(cell[0], cell[1], worldName));
            if (island != null && containsBlock.test(island)) {
                return Optional.of(island);
            }
        }
        return Optional.empty();
    }

    public int size() {
        return byOwner.size();
    }

    public List<Island> allIslands() {
        return List.copyOf(byOwner.values());
    }

    public List<Island> allById() {
        return List.copyOf(byId.values());
    }

    public Set<String> occupiedCells() {
        return Set.copyOf(usedCells);
    }

    public void register(final Island island, final int spacing) {
        byOwner.put(island.owner(), island);
        byId.put(island.islandId(), island);
        for (final UUID member : island.members()) {
            byMember.put(member, island);
        }
        final int[] cell = island.gridCell(spacing);
        final String key = GridAssigner.key(cell[0], cell[1], island.worldName());
        usedCells.add(key);
        byCell.put(key, island);
    }

    /** Removes an island after its deletion tombstone has been made durable. */
    public boolean remove(final Island island, final int spacing) {
        if (!byOwner.remove(island.owner(), island)) {
            return false;
        }
        final int[] cell = island.gridCell(spacing);
        final String key = GridAssigner.key(cell[0], cell[1], island.worldName());
        usedCells.remove(key);
        byCell.remove(key, island);
        byId.remove(island.islandId(), island);
        for (final UUID member : island.members()) {
            byMember.remove(member, island);
        }
        return true;
    }

    public void putMember(final UUID player, final Island island) {
        byMember.put(player, island);
    }

    public void removeMember(final UUID player, final Island island) {
        byMember.remove(player, island);
    }

    public void clear() {
        byOwner.clear();
        byId.clear();
        byMember.clear();
        byCell.clear();
        usedCells.clear();
    }
}
