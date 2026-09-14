package com.coremc.core.island;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.config.MessageService;
import com.coremc.core.world.IslandWorldService;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The island registry and every island gameplay operation: create, go,
 * leave, delete, invite and join, plus the per-player world border.
 *
 * One island per player: a player either owns an island or is a member
 * of (at most) one. All state changes are persisted immediately through
 * the {@link YamlIslandDataStore}; everything runs on the main thread.
 */
public final class IslandService {

    private final JavaPlugin plugin;
    private final CoreConfig config;
    private final MessageService messages;
    private final IslandWorldService worlds;
    private final SchematicService schematics;
    private final YamlIslandDataStore store;
    private final InviteLedger invites = new InviteLedger();
    private final Map<UUID, Island> byOwner = new HashMap<>();
    private final Map<UUID, Island> byMember = new HashMap<>();
    private final Map<UUID, Long> pendingDeletes = new HashMap<>();
    /** Slot high-water mark — slots are never reused, so this only grows. */
    private long nextSlot;

    public IslandService(final JavaPlugin plugin, final CoreConfig config, final MessageService messages,
                         final IslandWorldService worlds, final SchematicService schematics) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.worlds = worlds;
        this.schematics = schematics;
        this.store = new YamlIslandDataStore(
                new File(plugin.getDataFolder(), "islands").toPath(), plugin.getLogger());
    }

    /** Loads every stored island at startup. */
    public void load() {
        int highestStoredSlot = -1;
        try {
            for (final Island island : store.loadAll()) {
                register(island);
                highestStoredSlot = Math.max(highestStoredSlot, island.slot());
            }
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to load islands: " + exception.getMessage(), exception);
        }
        // A persisted counter survives deletions; the highest stored island
        // slot is the floor in case the counter file was lost.
        this.nextSlot = Math.max(store.loadNextSlot(), highestStoredSlot + 1L);
        plugin.getLogger().info("Loaded " + count() + " island(s), next slot " + nextSlot + ".");
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    /** The island this player owns or is a member of (or null). */
    public Island islandOf(final UUID player) {
        final Island owned = byOwner.get(player);
        if (owned != null) {
            return owned;
        }
        return byMember.get(player);
    }

    /** The island whose border claim contains this block column (or null). */
    public Island islandAt(final World world, final int x, final int z) {
        for (final Island island : byOwner.values()) {
            if (island.contains(world.getName(), x, z)) {
                return island;
            }
        }
        return null;
    }

    public int count() {
        return byOwner.size();
    }

    // ------------------------------------------------------------------
    // /is create
    // ------------------------------------------------------------------

    public void create(final Player player) {
        if (islandOf(player.getUniqueId()) != null) {
            messages.sendPrefixed(player, "island.already-have");
            return;
        }
        final Schematic schematic;
        try {
            schematic = schematics.load(config.islandSchematic());
        } catch (final IllegalArgumentException exception) {
            plugin.getLogger().severe("Cannot load island schematic: " + exception.getMessage());
            messages.sendPrefixed(player, "island.schematic-failed",
                    Map.of("error", exception.getMessage()));
            return;
        }

        final int slot = (int) nextSlot;
        nextSlot++;
        store.saveNextSlot(nextSlot);
        final int[] center = GridAssigner.centerForSlot(slot, config.islandSpacing());
        final World world = worlds.islandWorld();
        final int centerX = center[0];
        final int centerZ = center[1];
        final int baseY = config.islandYLevel();

        final Island island = new Island(
                UUID.randomUUID(),
                player.getUniqueId(),
                player.getName(),
                world.getName(),
                slot,
                centerX,
                baseY,
                centerZ,
                config.islandBorderSize(),
                config.islandSchematic(),
                System.currentTimeMillis(),
                centerX + schematic.spawnX(),
                baseY + schematic.spawnY(),
                centerZ + schematic.spawnZ(),
                0f,
                0f);

        try {
            store.save(island);
        } catch (final IOException exception) {
            plugin.getLogger().severe("Failed to save island for " + player.getName()
                    + ": " + exception.getMessage());
            messages.sendPrefixed(player, "island.schematic-failed",
                    Map.of("error", "island data could not be saved"));
            return;
        }

        final int pasted = schematics.paste(
                world, schematic, centerX, baseY, centerZ, island.borderSize(), config.chestItems());
        register(island);
        teleportHome(player, island);
        messages.sendPrefixed(player, "island.created");
        plugin.getLogger().info(player.getName() + " created island slot " + slot
                + " at (" + centerX + ", " + centerZ + ") — " + pasted + " blocks pasted.");
    }

    // ------------------------------------------------------------------
    // /is go
    // ------------------------------------------------------------------

    public void goHome(final Player player) {
        final Island island = islandOf(player.getUniqueId());
        if (island == null) {
            messages.sendPrefixed(player, "island.no-island");
            return;
        }
        teleportHome(player, island);
        messages.sendPrefixed(player, "island.teleported");
    }

    // ------------------------------------------------------------------
    // /is leave
    // ------------------------------------------------------------------

    public void leave(final Player player) {
        final Island island = islandOf(player.getUniqueId());
        if (island == null) {
            messages.sendPrefixed(player, "island.no-island");
            return;
        }
        if (island.isOwner(player.getUniqueId())) {
            messages.sendPrefixed(player, "island.owner-cannot-leave");
            return;
        }
        island.removeMember(player.getUniqueId());
        byMember.remove(player.getUniqueId(), island);
        saveQuietly(island);
        messages.sendPrefixed(player, "island.left", Map.of("owner", island.ownerName()));
    }

    // ------------------------------------------------------------------
    // /is delete
    // ------------------------------------------------------------------

    public void delete(final Player player, final boolean confirmArg) {
        final UUID playerId = player.getUniqueId();
        final Island island = byOwner.get(playerId);
        if (island == null) {
            if (byMember.containsKey(playerId)) {
                messages.sendPrefixed(player, "island.not-owner");
            } else {
                messages.sendPrefixed(player, "island.no-island");
            }
            return;
        }

        final long now = System.currentTimeMillis();
        final Long armedAt = pendingDeletes.get(playerId);
        final boolean armed = armedAt != null && now - armedAt < config.deleteConfirmSeconds() * 1000L;
        if (!confirmArg || !armed) {
            pendingDeletes.put(playerId, now);
            messages.sendPrefixed(player, "island.delete-confirm",
                    Map.of("seconds", String.valueOf(config.deleteConfirmSeconds())));
            return;
        }
        pendingDeletes.remove(playerId);

        evict(island);
        try {
            store.delete(island.owner());
        } catch (final IOException exception) {
            plugin.getLogger().severe("Failed to delete island file for " + island.ownerName()
                    + ": " + exception.getMessage());
        }
        unregister(island);
        plugin.getLogger().info(island.ownerName() + " deleted island slot " + island.slot() + ".");
        messages.sendPrefixed(player, "island.deleted");
    }

    /** Teleports every online island player to the main world spawn and clears their border. */
    private void evict(final Island island) {
        final World mainWorld = Bukkit.getWorlds().get(0);
        final Location fallback = mainWorld.getSpawnLocation();
        for (final UUID memberId : island.members()) {
            evictPlayer(Bukkit.getPlayer(memberId), island, fallback);
        }
        evictPlayer(Bukkit.getPlayer(island.owner()), island, fallback);
    }

    private void evictPlayer(final Player player, final Island island, final Location fallback) {
        if (player == null || !player.isOnline()) {
            return;
        }
        clearBorder(player);
        if (player.getWorld().getName().equals(island.worldName())) {
            player.teleport(fallback);
        }
        if (!player.getUniqueId().equals(island.owner())) {
            messages.sendPrefixed(player, "island.island-deleted-member",
                    Map.of("owner", island.ownerName()));
        }
    }

    // ------------------------------------------------------------------
    // /is invite + /is join
    // ------------------------------------------------------------------

    public void invite(final Player player, final String[] args) {
        final Island island = byOwner.get(player.getUniqueId());
        if (island == null) {
            if (byMember.containsKey(player.getUniqueId())) {
                messages.sendPrefixed(player, "island.not-owner");
            } else {
                messages.sendPrefixed(player, "island.no-island");
            }
            return;
        }
        if (args.length < 2) {
            messages.sendPrefixed(player, "island.invite-usage");
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || !target.isOnline()) {
            messages.sendPrefixed(player, "island.player-not-online");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            messages.sendPrefixed(player, "island.cannot-invite-self");
            return;
        }
        if (islandOf(target.getUniqueId()) != null) {
            messages.sendPrefixed(player, "island.target-has-island", Map.of("player", target.getName()));
            return;
        }
        invites.add(target.getUniqueId(), new InviteLedger.Entry(
                island.owner(), player.getName(),
                System.currentTimeMillis() + config.inviteExpirySeconds() * 1000L));
        messages.sendPrefixed(player, "island.invite-sent",
                Map.of("player", target.getName(), "seconds", String.valueOf(config.inviteExpirySeconds())));
        messages.sendPrefixed(target, "island.invite-received", Map.of("player", player.getName()));
    }

    public void join(final Player player) {
        final long now = System.currentTimeMillis();
        invites.purge(now);
        final UUID playerId = player.getUniqueId();
        final InviteLedger.Entry entry = invites.get(playerId);
        if (entry == null) {
            messages.sendPrefixed(player, "island.no-invite");
            return;
        }
        if (entry.expired(now)) {
            invites.remove(playerId);
            messages.sendPrefixed(player, "island.invite-expired");
            return;
        }
        if (islandOf(playerId) != null) {
            messages.sendPrefixed(player, "island.already-have");
            return;
        }
        final Island island = byOwner.get(entry.islandOwner());
        if (island == null) {
            invites.remove(playerId);
            messages.sendPrefixed(player, "island.invite-expired");
            return;
        }
        invites.remove(playerId);
        island.addMember(playerId);
        byMember.put(playerId, island);
        saveQuietly(island);
        messages.sendPrefixed(player, "island.joined", Map.of("owner", island.ownerName()));
        final Player owner = Bukkit.getPlayer(island.owner());
        if (owner != null && owner.isOnline()) {
            messages.sendPrefixed(owner, "island.member-joined", Map.of("player", player.getName()));
        }
        teleportHome(player, island);
    }

    // ------------------------------------------------------------------
    // border
    // ------------------------------------------------------------------

    /** Shows the player their island's border: a per-player vanilla world border. */
    public void applyBorder(final Player player, final Island island) {
        if (!config.borderVisual()) {
            return;
        }
        // Virtual borders are not bound to a world — the centre applies in
        // the player's current world, and this is always called right after
        // teleporting them into (or spawning them on) their island.
        final WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(island.centerX(), island.centerZ());
        border.setSize(island.borderSize());
        border.setWarningDistance(0);
        border.setDamageBuffer(0.0);
        border.setDamageAmount(0.0);
        player.setWorldBorder(border);
    }

    /** Resets the player to their current world's border. */
    public void clearBorder(final Player player) {
        player.setWorldBorder(player.getWorld().getWorldBorder());
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void teleportHome(final Player player, final Island island) {
        player.teleport(island.home(worlds.islandWorld()));
        applyBorder(player, island);
    }

    private void register(final Island island) {
        byOwner.put(island.owner(), island);
        for (final UUID memberId : island.members()) {
            byMember.put(memberId, island);
        }
    }

    private void unregister(final Island island) {
        byOwner.remove(island.owner());
        for (final UUID memberId : island.members()) {
            byMember.remove(memberId, island);
        }
        pendingDeletes.remove(island.owner());
        invites.removeAllFor(island.owner());
    }

    private void saveQuietly(final Island island) {
        try {
            store.save(island);
        } catch (final IOException exception) {
            plugin.getLogger().severe("Failed to save island " + island.id() + ": " + exception.getMessage());
        }
    }
}
