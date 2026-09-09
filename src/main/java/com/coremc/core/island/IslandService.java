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
    /**
     * Cells a DELETED island once occupied. On top of {@link #usedCells} the
     * spiral never assigns them again, so a fresh island can never spawn on
     * top of the old island's leftover platform blocks (ghost-reuse guard).
     * Persisted in {@code islands/deleted-cells.txt}.
     */
    private final Set<String> retiredCells = ConcurrentHashMap.newKeySet();
    private final java.nio.file.Path retiredCellsFile;
    private final MemberInviteLedger invites = new MemberInviteLedger();
    /**
     * Islands with unflushed progression (xp/stats/level). Drained every
     * minute by the progress timer and on shutdown — per-action disk
     * writes would be a performance disaster.
     */
    private final Set<UUID> dirtyIslands = ConcurrentHashMap.newKeySet();

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
            final PlayerDataService playerData,
            final java.nio.file.Path islandFolder) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.store = store;
        this.playerData = playerData;
        this.retiredCellsFile = islandFolder.resolve("deleted-cells.txt");
    }

    /** Loads the registry from disk on the I/O executor. */
    public void start() {
        io.execute(() -> {
            try {
                loadRetiredCells();
                for (final Island island : store.loadAll()) {
                    register(island);
                }
                logger.info("Loaded " + islandsByOwner.size() + " island(s) (" + retiredCells.size()
                        + " retired cell(s) reserved).");
            } catch (final IOException exception) {
                logger.log(Level.SEVERE, "Failed to load islands — island commands may misbehave.", exception);
            } finally {
                loaded = true;
            }
        });
    }

    private void loadRetiredCells() {
        if (!java.nio.file.Files.exists(retiredCellsFile)) {
            return;
        }
        try {
            retiredCells.addAll(java.nio.file.Files.readAllLines(retiredCellsFile));
        } catch (final IOException exception) {
            logger.log(Level.WARNING, "Could not read deleted-cells.txt — ghost reuse guard in-memory only.",
                    exception);
        }
    }

    private void persistRetiredCells() {
        try {
            java.nio.file.Files.createDirectories(retiredCellsFile.getParent());
            java.nio.file.Files.write(retiredCellsFile, new java.util.ArrayList<>(retiredCells));
        } catch (final IOException exception) {
            logger.log(Level.WARNING, "Could not persist deleted-cells.txt — guard resets on restart.", exception);
        }
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
        if (island != null && containsBlock(island, x, z)) {
            return Optional.of(island);
        }
        return Optional.empty();
    }

    public int islandCount() {
        return islandsByOwner.size();
    }

    /** Snapshot of every live island (for timers that tick island-scoped effects). */
    public List<Island> allIslands() {
        return List.copyOf(islandsByOwner.values());
    }

    /** The configured island world, or empty if it does not exist. */
    public Optional<World> islandWorld() {
        return Optional.ofNullable(Bukkit.getWorld(config.islandWorldName()));
    }

    /** Total member capacity for an island (base config + purchased member-slot upgrades). */
    public int memberCapacity(final Island island) {
        return config.islandMemberSlots() + island.upgrades().getOrDefault("member-slots", 0);
    }

    /**
     * Protected border width including purchased border tiers: absolute
     * widths from the sizes list when configured (50 -> 200), else the
     * legacy base + tiers × step.
     */
    public int effectiveBorder(final Island island) {
        final int tier = island.upgrades().getOrDefault("border", 0);
        final java.util.List<Integer> sizes = config.upgradeBorderSizes();
        if (!sizes.isEmpty()) {
            return sizes.get(Math.min(tier, sizes.size() - 1));
        }
        return island.borderSize() + tier * config.upgradeBorderStepBlocks();
    }

    /** (x,z) containment against the island's EFFECTIVE (upgraded) border. */
    public boolean containsBlock(final Island island, final int x, final int z) {
        final int half = effectiveBorder(island) / 2;
        return x >= island.centerX() - half && x < island.centerX() + half
                && z >= island.centerZ() - half && z < island.centerZ() + half;
    }

    /**
     * Attempts to buy the next tier of {@code upgradeId} with Sky Tokens.
     * Failure modes are exact (maxed / insufficient) and the island file is
     * flushed immediately on success.
     */
    public boolean purchaseUpgrade(final org.bukkit.entity.Player player, final Island island, final String upgradeId) {
        final int tier = island.upgrades().getOrDefault(upgradeId, 0);
        final int maxTier = config.upgradeMaxTier(upgradeId);
        if (tier >= maxTier) {
            ((com.coremc.core.CoreMCPlugin) plugin).messages().sendPrefixed(player, "island.upgrade.maxed", Map.of());
            return false;
        }
        final var cost = config.upgradeCost(upgradeId, tier);
        if (cost.isEmpty()) {
            ((com.coremc.core.CoreMCPlugin) plugin).messages().sendPrefixed(player, "island.upgrade.maxed", Map.of());
            return false;
        }
        for (final Map.Entry<String, Integer> requirement
                : config.upgradeRequiresAt(upgradeId, tier + 1).entrySet()) {
            if (island.upgrades().getOrDefault(requirement.getKey(), 0) < requirement.getValue()) {
                ((com.coremc.core.CoreMCPlugin) plugin).messages().sendPrefixed(player, "island.upgrade.locked",
                        Map.of("track", UpgradeCatalog.displayOf(requirement.getKey()),
                                "tier", String.valueOf(requirement.getValue())));
                return false;
            }
        }
        final var profile = playerData.profileOf(player.getUniqueId()).orElse(null);
        if (profile == null) {
            return false;
        }
        // Level gates (spec: upgrades may need island/role levels): the
        // island's own level, and the buyer's best role level (any role).
        final int needIsland = config.upgradeRequiresIslandLevel(upgradeId);
        if (needIsland > 0 && island.level() < needIsland) {
            ((com.coremc.core.CoreMCPlugin) plugin).messages().sendPrefixed(player, "island.upgrade.locked-island",
                    Map.of("level", String.valueOf(needIsland), "yours", String.valueOf(island.level())));
            return false;
        }
        final int needRole = config.upgradeRequiresRoleLevel(upgradeId);
        if (needRole > 0) {
            final int haveRole = ((com.coremc.core.CoreMCPlugin) plugin).roles().maxRoleLevel(profile);
            if (haveRole < needRole) {
                ((com.coremc.core.CoreMCPlugin) plugin).messages().sendPrefixed(player, "island.upgrade.locked-role",
                        Map.of("level", String.valueOf(needRole), "yours", String.valueOf(haveRole)));
                return false;
            }
        }
        final long price = cost.getAsLong();
        if (price > 0L
                && !((com.coremc.core.CoreMCPlugin) plugin).economy()
                        .withdraw(profile, com.coremc.core.economy.Currency.SKY_TOKENS, price)) {
            ((com.coremc.core.CoreMCPlugin) plugin).messages().sendPrefixed(player, "island.upgrade.insufficient",
                    Map.of("price", String.valueOf(price)));
            return false;
        }
        island.setUpgradeTier(upgradeId, tier + 1);
        flush(island);
        ((com.coremc.core.CoreMCPlugin) plugin).upgradeEffects().onPurchased(island, upgradeId, tier + 1);
        ((com.coremc.core.CoreMCPlugin) plugin).messages().sendPrefixed(player, "island.upgrade.bought",
                Map.of("tier", String.valueOf(tier + 1), "max", String.valueOf(maxTier),
                        "price", String.valueOf(price)));
        return true;
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
                "island_border", effectiveBorder(value) + "x" + effectiveBorder(value),
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
     * Creates an island for {@code player} with the default theme. Convenience
     * overload of {@link #createIsland(Player, IslandTheme, java.util.function.Consumer)}.
     */
    public CreateResult createIsland(final Player player, final java.util.function.Consumer<Island> onCreated) {
        return createIsland(player, null, onCreated);
    }

    /**
     * Creates an island for {@code player}: assigns the next grid cell,
     * generates the themed starter platform, registers + persists the island
     * and records the profile association. Must be called on the main
     * thread. {@code theme} may be null → the default theme is used.
     */
    public CreateResult createIsland(
            final Player player,
            final IslandTheme theme,
            final java.util.function.Consumer<Island> onCreated) {
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
        final int[] cell = GridAssigner.nextFreeCell(occupiedCells(), target.getName());
        final Island island = new Island(
                UUID.randomUUID(),
                owner,
                target.getName(),
                cell[0] * spacing,
                config.islandStartHeight(),
                cell[1] * spacing,
                config.islandBorderSize(),
                System.currentTimeMillis());
        island.theme(theme == null ? Island.DEFAULT_THEME : theme.key());

        generateStarterIsland(target, island, theme);
        register(island);
        setAssociation(owner, island.islandId());
        flush(island);
        onCreated.accept(island);
        return CreateResult.CREATED;
    }

    /** Live + retired cells: the spiral never reuses either (ghost-reuse guard). */
    private Set<String> occupiedCells() {
        final Set<String> occupied = new java.util.HashSet<>(usedCells);
        occupied.addAll(retiredCells);
        return occupied;
    }

    /**
     * Deletes the island owned by {@code owner}. Ghost-proof ordering:
     *  1. un-register + purge invites referencing the island,
     *  2. RETIRE the grid cell so the spiral never reuses it (a fresh island
     *     can never spawn on the old platform's leftover blocks),
     *  3. scrub member/owner associations (online + offline profiles),
     *  4. delete the island file LAST.
     * Platform blocks stay in the world (documented).
     */
    public void deleteIsland(final UUID owner) throws IOException {
        final Island removed = islandsByOwner.remove(owner);
        if (removed == null) {
            throw new IllegalStateException("Owner has no island");
        }
        unregisterIndexes(removed);
        invites.purgeIsland(removed.islandId());
        ((com.coremc.core.CoreMCPlugin) plugin).islandActivity().purgeIsland(removed.islandId());
        final int spacing = config.islandSpacing();
        final int[] cell = removed.gridCell(spacing);
        retiredCells.add(GridAssigner.key(cell[0], cell[1], removed.worldName()));
        final List<UUID> scrubTargets = new ArrayList<>();
        scrubTargets.add(owner);
        scrubTargets.addAll(removed.members());

        io.execute(() -> {
            persistRetiredCells();
            for (final UUID player : scrubTargets) {
                playerData.clearIslandAssociationIfMatches(player, removed.islandId());
            }
            try {
                store.delete(owner);
                logger.info("Deleted island " + removed.islandId() + " of " + owner + " (" + scrubTargets.size()
                        + " association(s) scrubbed, cell retired).");
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

    /** Marks an island's progression dirty (batched flush, not immediate disk I/O). */
    public void markDirty(final Island island) {
        dirtyIslands.add(island.islandId());
    }

    /** Flushes every progression-dirty island (progress timer + shutdown). */
    public void flushDirty() {
        final List<UUID> pending = new ArrayList<>(dirtyIslands);
        dirtyIslands.removeAll(pending);
        for (final UUID islandId : pending) {
            final Island island = islandsById.get(islandId);
            if (island != null) {
                flush(island);
            }
        }
    }

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
        flushDirty(); // queued ahead of the executor stop, so saves land before termination
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

    // ------------------------------------------------------------------ themed generation

    /** Platform radius: a 7x7 starter island (r=3) — a proper base, not a tiny plot. */
    private static final int PLATFORM_RADIUS = 3;

    /**
     * Builds the themed starter island: a 7x7 two-layer platform (surface over
     * filler), one bedrock in the centre, the theme's decorations (a tree is
     * attempted for sapling entries on tree themes) and a starter chest with
     * the theme's contents.
     *
     * {@code theme} may be null (legacy callers): the classic plains look is
     * generated from hard defaults.
     */
    private void generateStarterIsland(final World world, final Island island, final IslandTheme theme) {
        final int cx = island.centerX();
        final int cy = island.centerY();
        final int cz = island.centerZ();
        final Material top = theme == null ? Material.GRASS_BLOCK : theme.top();
        final Material under = theme == null ? Material.DIRT : theme.under();

        for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
            for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                setBlock(world, cx + dx, cy - 2, cz + dz, under);
                setBlock(world, cx + dx, cy - 1, cz + dz, under);
                setBlock(world, cx + dx, cy, cz + dz, top);
            }
        }
        setBlock(world, cx, cy - 1, cz, Material.BEDROCK);

        final java.util.List<Location> saplings = new ArrayList<>();
        final java.util.List<String> decorations =
                theme == null ? List.of("2,1,2=OAK_SAPLING", "-2,0,-2=WATER", "-1,0,-2=WATER")
                        : theme.decorations();
        for (final String entry : decorations) {
            final Object[] parsed = ThemeService.parseDecoration(entry);
            if (parsed == null) {
                continue;
            }
            final int dx = (Integer) parsed[0];
            final int dy = (Integer) parsed[1];
            final int dz = (Integer) parsed[2];
            final Material material = (Material) parsed[3];
            if (Math.abs(dx) > PLATFORM_RADIUS || Math.abs(dz) > PLATFORM_RADIUS) {
                continue; // decoration outside the platform — skip safely
            }
            final Location at = new Location(world, cx + dx, cy + dy, cz + dz);
            if (material.name().endsWith("_SAPLING")) {
                saplings.add(at);
            }
            setBlock(world, at.getBlockX(), at.getBlockY(), at.getBlockZ(), material);
        }

        final boolean tree = theme == null || theme.tree();
        if (tree && !saplings.isEmpty()) {
            final Location sapling = saplings.get(0);
            if (!world.generateTree(sapling, TreeType.TREE)) {
                logger.fine("Tree generation reported failure at " + sapling + " — sapling remains.");
            }
        }

        placeStarterChest(world, island, theme == null
                ? List.of("ICE:2", "LAVA_BUCKET:1", "BREAD:4", "BONE_MEAL:3")
                : theme.chestContents());
    }

    /** Chest south of the centre with the theme's starter contents. */
    private void placeStarterChest(
            final World world, final Island island, final List<String> contents) {
        final int x = island.centerX();
        final int y = island.centerY() + 1;
        final int z = island.centerZ() + 2;
        setBlock(world, x, y, z, Material.CHEST);
        if (world.getBlockAt(x, y, z).getState() instanceof org.bukkit.block.Chest chest) {
            for (final String entry : contents) {
                final Object[] parsed = ThemeService.parseContent(entry);
                if (parsed == null) {
                    continue;
                }
                chest.getBlockInventory()
                        .addItem(new org.bukkit.inventory.ItemStack((Material) parsed[0], (Integer) parsed[1]));
            }
            chest.update(true);
        }
    }

    private void setBlock(final World world, final int x, final int y, final int z, final Material material) {
        final Block block = world.getBlockAt(x, y, z);
        block.setType(material);
    }
}
