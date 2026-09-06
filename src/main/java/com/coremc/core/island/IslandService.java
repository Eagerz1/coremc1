package com.coremc.core.island;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerProfile;
import com.coremc.core.scheduler.TaskService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns islands: registry (by owner / member / id / grid cell), spiral
 * grid assignment, starter-island generation, team membership, invites,
 * border protection lookups and lifecycle.
 *
 * The full registry is loaded once at startup (async), then mutated on
 * the main thread. Island files save eagerly on every structural
 * change; disk I/O runs on a dedicated daemon executor so the main
 * thread never blocks on disk.
 *
 * Ghost-data guarantees (see deleteIsland):
 *  - member associations are scrubbed from every profile (online AND
 *    offline) before the island file is removed,
 *  - pending invites referencing the island are purged,
 *  - teleport paths resolve only the live registry, so a deleted island
 *    can never be teleported to,
 *  - profiles loaded later self-heal unknown associations at join.
 */
public final class IslandService {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final CoreConfig config;
    private final IslandDataStore store;
    private final PlayerDataService playerData;

    private final Map<UUID, Island> islandsByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Island> islandsById = new ConcurrentHashMap<>();
    /** member uuid -> island (rebuilt on load; owner is NOT in this map). */
    private final Map<UUID, Island> islandsByMember = new ConcurrentHashMap<>();
    private final Map<String, Island> islandsByCell = new ConcurrentHashMap<>();
    private final Set<String> usedCells = ConcurrentHashMap.newKeySet();
    private final MemberInviteLedger invites = new MemberInviteLedger();

    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "CoreMC-Islands");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean loaded = false;

    public IslandService(
            final JavaPlugin plugin,
            final CoreConfig config,
            final IslandDataStore store,
            final PlayerDataService playerData) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.store = store;
        this.playerData = playerData;
    }

    /** Loads the registry from disk on the I/O executor. */
    public void start() {
        io.execute(() -> {
            try {
                for (final Island island : store.loadAll()) {
                    register(island);
                }
                logger.info("Loaded " + islandsByOwner.size() + " island(s).");
            } catch (final IOException exception) {
                logger.log(Level.SEVERE, "Failed to load islands — island commands may misbehave.", exception);
            } finally {
                loaded = true;
            }
        });
    }

    /** Whether the registry finished loading (commands wait for this). */
    public boolean isLoaded() {
        return loaded;
    }

    // ------------------------------------------------------------------ lookups

    /** Island OWNED by this player (null if they only belong to someone else's). */
    public Optional<Island> ownedIsland(final UUID owner) {
        return Optional.ofNullable(islandsByOwner.get(owner));
    }

    /** Island this player owns OR belongs to. This is the /is home/info island. */
    public Optional<Island> islandOf(final UUID player) {
        final Island owned = islandsByOwner.get(player);
        if (owned != null) {
            return Optional.of(owned);
        }
        return Optional.ofNullable(islandsByMember.get(player));
    }

    public Optional<Island> islandById(final UUID islandId) {
        return Optional.ofNullable(islandsById.get(islandId));
    }

    /**
     * Island whose protected border contains (worldName, x, z), O(1).
     * Only callable for points inside an island cell; borders are smaller
     * than the cell by config validation so at most one island can match.
     */
    public Optional<Island> islandAt(final String worldName, final int x, final int z) {
        final int spacing = config.islandSpacing();
        final String cellKey = GridAssigner.key(Math.floorDiv(x, spacing), Math.floorDiv(z, spacing), worldName);
        final Island island = islandsByCell.get(cellKey);
        if (island != null && island.containsBlock(x, z)) {
            return Optional.of(island);
        }
        return Optional.empty();
    }

    public int islandCount() {
        return islandsByOwner.size();
    }

    /** The configured island world, or empty if it does not exist. */
    public Optional<World> islandWorld() {
        return Optional.ofNullable(Bukkit.getWorld(config.islandWorldName()));
    }

    /** Total member capacity for an island (base config + purchased member-slot upgrades). */
    public int memberCapacity(final Island island) {
        return config.islandMemberSlots() + island.upgrades().getOrDefault("member-slots", 0);
    }

    /** Island level placeholders (for future PlaceholderAPI binding; /is info uses them today). */
    public Map<String, String> placeholdersOf(final UUID player) {
        final Optional<Island> island = islandOf(player);
        if (island.isEmpty()) {
            return Map.of(
                    "island_level", "-", "island_border", "-", "island_members", "-", "island_owner", "-");
        }
        final Island value = island.get();
        return Map.of(
                "island_level", String.valueOf(value.level()),
                "island_border", value.borderSize() + "x" + value.borderSize(),
                "island_members", String.valueOf(value.members().size()),
                "island_owner", nameOf(value.owner()));
    }

    private String nameOf(final UUID uuid) {
        final Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        final org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() != null ? offline.getName() : uuid.toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Result tokens for create; keeps the command layer thin. */
    public enum CreateResult { CREATED, ALREADY_ISLAND, WORLD_MISSING }

    /**
     * Creates an island for {@code player}: assigns the next grid cell,
     * generates the starter platform, registers + persists the island
     * and records the profile association. Must be called on the main
     * thread.
     */
    public CreateResult createIsland(final Player player, final java.util.function.Consumer<Island> onCreated) {
        final UUID owner = player.getUniqueId();
        if (islandOf(owner).isPresent()) {
            return CreateResult.ALREADY_ISLAND;
        }
        final Optional<World> world = islandWorld();
        if (world.isEmpty()) {
            return CreateResult.WORLD_MISSING;
        }
        final World target = world.get();
        final int spacing = config.islandSpacing();
        final int[] cell = GridAssigner.nextFreeCell(usedCells, target.getName());
        final Island island = new Island(
                UUID.randomUUID(),
                owner,
                target.getName(),
                cell[0] * spacing,
                config.islandStartHeight(),
                cell[1] * spacing,
                config.islandBorderSize(),
                System.currentTimeMillis());

        generateStarterIsland(target, island);
        register(island);
        setAssociation(owner, island.islandId());
        flush(island);
        onCreated.accept(island);
        return CreateResult.CREATED;
    }

    /**
     * Deletes the island owned by {@code owner}. Ghost-proof ordering:
     *  1. un-register + purge invites referencing the island,
     *  2. scrub member/owner associations (online + offline profiles),
     *  3. delete the island file LAST.
     * Platform blocks stay in the world (documented).
     */
    public void deleteIsland(final UUID owner) throws IOException {
        final Island removed = islandsByOwner.remove(owner);
        if (removed == null) {
            throw new IllegalStateException("Owner has no island");
        }
        unregisterIndexes(removed);
        invites.purgeIsland(removed.islandId());
        final List<UUID> scrubTargets = new ArrayList<>();
        scrubTargets.add(owner);
        scrubTargets.addAll(removed.members());

        io.execute(() -> {
            for (final UUID player : scrubTargets) {
                playerData.clearIslandAssociationIfMatches(player, removed.islandId());
            }
            try {
                store.delete(owner);
                logger.info("Deleted island " + removed.islandId() + " of " + owner + " (" + scrubTargets.size()
                        + " association(s) scrubbed).");
            } catch (final IOException exception) {
                logger.log(Level.SEVERE, "Failed to delete island file of " + owner, exception);
            }
        });
    }

    // ------------------------------------------------------------------ teams

    public enum InviteResult { SENT, TARGET_HAS_ISLAND, SENDER_NOT_OWNER, TARGET_BUSY, TEAM_FULL }

    /** Owner invites a target. Invites expire per config; ledger is in-memory only. */
    public InviteResult invite(final Player inviter, final Player target) {
        final Optional<Island> island = ownedIsland(inviter.getUniqueId());
        if (island.isEmpty()) {
            return InviteResult.SENDER_NOT_OWNER;
        }
        if (islandOf(target.getUniqueId()).isPresent()) {
            return InviteResult.TARGET_HAS_ISLAND;
        }
        if (island.get().members().size() >= memberCapacity(island.get())) {
            return InviteResult.TEAM_FULL;
        }
        final long expiresAt = System.currentTimeMillis() + config.islandInviteExpirySeconds() * 1000L;
        invites.invite(target.getUniqueId(), island.get().islandId(), inviter.getUniqueId(), expiresAt);
        return InviteResult.SENT;
    }

    public enum AcceptResult { ACCEPTED, NO_INVITE, EXPIRED, ISLAND_GONE, TEAM_FULL, ALREADY_ISLAND }

    /** Target accepts their pending invite. All race-conditions re-checked. */
    public AcceptResult acceptInvite(final Player player) {
        final MemberInviteLedger.Pending invite = invites.take(player.getUniqueId());
        if (invite == null) {
            return AcceptResult.NO_INVITE;
        }
        if (invite.expiresAtMillis() < System.currentTimeMillis()) {
            return AcceptResult.EXPIRED;
        }
        final Island island = islandsById.get(invite.islandId());
        if (island == null) {
            return AcceptResult.ISLAND_GONE; // deleted while the invite was pending
        }
        if (islandOf(player.getUniqueId()).isPresent()) {
            return AcceptResult.ALREADY_ISLAND;
        }
        if (island.members().size() >= memberCapacity(island)) {
            return AcceptResult.TEAM_FULL;
        }
        island.addMember(player.getUniqueId());
        islandsByMember.put(player.getUniqueId(), island);
        setAssociation(player.getUniqueId(), island.islandId());
        flush(island);
        return AcceptResult.ACCEPTED;
    }

    public boolean hasPendingInvite(final UUID player) {
        final MemberInviteLedger.Pending invite = invites.peek(player);
        return invite != null && invite.expiresAtMillis() >= System.currentTimeMillis();
    }

    public enum LeaveResult { LEFT, NOT_A_MEMBER }

    /** Member leaves the island they belong to. */
    public LeaveResult leave(final Player player) {
        final Island island = islandsByMember.remove(player.getUniqueId());
        if (island == null) {
            return LeaveResult.NOT_A_MEMBER;
        }
        island.removeMember(player.getUniqueId());
        setAssociation(player.getUniqueId(), null);
        flush(island);
        return LeaveResult.LEFT;
    }

    public enum KickResult { KICKED, NOT_OWNER, TARGET_NOT_MEMBER, CANNOT_KICK_OWNER }

    /** Owner kicks a member. */
    public KickResult kick(final Player ownerActor, final UUID target) {
        final Optional<Island> island = ownedIsland(ownerActor.getUniqueId());
        if (island.isEmpty()) {
            return KickResult.NOT_OWNER;
        }
        if (target.equals(ownerActor.getUniqueId())) {
            return KickResult.CANNOT_KICK_OWNER;
        }
        final Island team = island.get();
        if (!team.removeMember(target)) {
            return KickResult.TARGET_NOT_MEMBER;
        }
        islandsByMember.remove(target, team);
        io.execute(() -> playerData.clearIslandAssociationIfMatches(target, team.islandId()));
        setAssociation(target, null); // no-op if offline; offline scrub above covers them
        flush(team);
        return KickResult.KICKED;
    }

    // ------------------------------------------------------------------ persistence & associations

    /** Saves an island asynchronously (structure changes flush eagerly). */
    public void flush(final Island island) {
        io.execute(() -> {
            try {
                store.save(island);
            } catch (final IOException exception) {
                logger.log(Level.SEVERE, "Failed to save island " + island.islandId(), exception);
            }
        });
    }

    /** Join-time self-heal: drop associations pointing at islands that no longer exist. */
    public void reconcileAssociation(final PlayerProfile profile) {
        if (profile.islandId() != null && !islandsById.containsKey(profile.islandId())) {
            logger.warning("Clearing stale island association on profile " + profile.uuid()
                    + " (island " + profile.islandId() + " no longer exists).");
            profile.islandId(null);
            playerData.persistAfterEconomyChange(profile, true);
        }
    }

    private void setAssociation(final UUID player, final UUID islandId) {
        playerData.profileOf(player).ifPresent(profile -> {
            profile.islandId(islandId);
            playerData.persistAfterEconomyChange(profile, true);
        });
    }

    // ------------------------------------------------------------------ teleport

    /**
     * Teleports the player to their island home synchronously (main
     * thread). The island instance must come from the live registry —
     * deleted islands are unreachable by design.
     */
    public void teleportHome(final Player player, final Island island) {
        final World world = Bukkit.getWorld(island.worldName());
        if (world == null) {
            throw new IllegalStateException("Island world '" + island.worldName() + "' is not loaded");
        }
        player.teleport(new Location(world, island.homeX(), island.homeY(), island.homeZ()));
    }

    /** Stops the I/O executor. Called on disable. */
    public void shutdown() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Island executor did not terminate in 10s.");
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while stopping island service", exception);
        }
        islandsByOwner.clear();
        islandsById.clear();
        islandsByMember.clear();
        islandsByCell.clear();
        usedCells.clear();
        invites.clear();
    }

    // ------------------------------------------------------------------ internals

    private void register(final Island island) {
        islandsByOwner.put(island.owner(), island);
        islandsById.put(island.islandId(), island);
        for (final UUID member : island.members()) {
            islandsByMember.put(member, island);
        }
        final int spacing = config.islandSpacing();
        final int[] cell = island.gridCell(spacing);
        final String key = GridAssigner.key(cell[0], cell[1], island.worldName());
        usedCells.add(key);
        islandsByCell.put(key, island);
    }

    private void unregisterIndexes(final Island island) {
        final int spacing = config.islandSpacing();
        final int[] cell = island.gridCell(spacing);
        final String key = GridAssigner.key(cell[0], cell[1], island.worldName());
        usedCells.remove(key);
        islandsByCell.remove(key);
        islandsById.remove(island.islandId());
        for (final UUID member : island.members()) {
            islandsByMember.remove(member, island);
        }
    }

    /**
     * Builds the classic starter island: a 5x5 grass platform on dirt,
     * one bedrock in the centre and an oak tree in the north-east corner.
     */
    private void generateStarterIsland(final World world, final Island island) {
        final int cx = island.centerX();
        final int cy = island.centerY();
        final int cz = island.centerZ();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                setBlock(world, cx + dx, cy - 1, cz + dz, Material.DIRT);
                setBlock(world, cx + dx, cy, cz + dz, Material.GRASS_BLOCK);
            }
        }
        setBlock(world, cx, cy - 1, cz, Material.BEDROCK);

        final Location sapling = new Location(world, cx + 2, cy + 1, cz + 2);
        setBlock(world, cx + 2, cy + 1, cz + 2, Material.OAK_SAPLING);
        if (!world.generateTree(sapling, TreeType.TREE)) {
            logger.fine("Tree generation reported failure at " + sapling + " — sapling remains.");
        }
    }

    private void setBlock(final World world, final int x, final int y, final int z, final Material material) {
        final Block block = world.getBlockAt(x, y, z);
        block.setType(material);
    }
}
