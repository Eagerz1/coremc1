package com.coremc.core.island;

import com.coremc.core.config.CoreConfig;
import com.coremc.core.player.PlayerDataService;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Coordinates island invites and membership changes with durable island/profile writes. */
public final class IslandMembershipCoordinator {

    private final CoreConfig config;
    private final IslandRegistry registry;
    private final IslandPersistence persistence;
    private final PlayerDataService playerData;
    private final Logger logger;
    private final IslandMembershipHost host;
    private final MemberInviteLedger invites = new MemberInviteLedger();
    private final Set<UUID> pendingKicks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingLeaves = ConcurrentHashMap.newKeySet();

    public IslandMembershipCoordinator(
            final CoreConfig config,
            final IslandRegistry registry,
            final IslandPersistence persistence,
            final PlayerDataService playerData,
            final Logger logger,
            final IslandMembershipHost host) {
        this.config = config;
        this.registry = registry;
        this.persistence = persistence;
        this.playerData = playerData;
        this.logger = logger;
        this.host = host;
    }

    public IslandService.InviteResult invite(final Player inviter, final Player target) {
        final Optional<Island> island = host.ownedIsland(inviter.getUniqueId());
        if (island.isEmpty()) return IslandService.InviteResult.SENDER_NOT_OWNER;
        if (registry.islandOf(target.getUniqueId()).isPresent()) {
            return IslandService.InviteResult.TARGET_HAS_ISLAND;
        }
        if (island.get().members().size() >= host.memberCapacity(island.get())) {
            return IslandService.InviteResult.TEAM_FULL;
        }
        final long expiresAt = System.currentTimeMillis() + config.islandInviteExpirySeconds() * 1000L;
        invites.invite(target.getUniqueId(), island.get().islandId(), inviter.getUniqueId(), expiresAt);
        return IslandService.InviteResult.SENT;
    }

    public IslandService.AcceptResult acceptInvite(final Player player) {
        final MemberInviteLedger.Pending invite = invites.take(player.getUniqueId());
        if (invite == null) return IslandService.AcceptResult.NO_INVITE;
        if (invite.expiresAtMillis() < System.currentTimeMillis()) return IslandService.AcceptResult.EXPIRED;
        final Island island = registry.islandById(invite.islandId()).orElse(null);
        if (island == null) return IslandService.AcceptResult.ISLAND_GONE;
        if (hasPendingWrite(player.getUniqueId()) || registry.islandOf(player.getUniqueId()).isPresent()) {
            return IslandService.AcceptResult.ALREADY_ISLAND;
        }
        if (island.members().size() >= host.memberCapacity(island)) return IslandService.AcceptResult.TEAM_FULL;

        island.addMember(player.getUniqueId());
        registry.putMember(player.getUniqueId(), island);
        host.setAssociation(player.getUniqueId(), island.islandId());
        flush(island);
        return IslandService.AcceptResult.ACCEPTED;
    }

    public boolean hasPendingInvite(final UUID player) {
        final MemberInviteLedger.Pending invite = invites.peek(player);
        return invite != null && invite.expiresAtMillis() >= System.currentTimeMillis();
    }

    public boolean hasPendingWrite(final UUID player) {
        return pendingKicks.contains(player) || pendingLeaves.contains(player);
    }

    public void purgeIsland(final UUID islandId) {
        invites.purgeIsland(islandId);
    }

    public IslandService.LeaveResult leave(
            final Player player, final Consumer<IslandService.LeaveResult> completion) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Island leave must run on the main thread");
        final UUID playerId = player.getUniqueId();
        final Island island = registry.islandOf(playerId).orElse(null);
        if (island == null || island.roleOf(playerId) != IslandRole.MEMBER) {
            return IslandService.LeaveResult.NOT_A_MEMBER;
        }
        if (!pendingLeaves.add(playerId)) return IslandService.LeaveResult.PENDING;
        if (!island.removeMember(playerId)) {
            pendingLeaves.remove(playerId);
            return IslandService.LeaveResult.NOT_A_MEMBER;
        }
        registry.removeMember(playerId, island);
        final Map<String, Object> snapshot = island.toMap();
        try {
            persistence.execute(() -> {
                Throwable failure = null;
                try {
                    persistence.saveSnapshot(snapshot);
                    playerData.clearIslandAssociationIfMatches(playerId, island.islandId());
                } catch (IOException | RuntimeException exception) {
                    failure = exception;
                }
                final Throwable result = failure;
                host.scheduleMembershipCallback(() -> {
                    pendingLeaves.remove(playerId);
                    if (result == null) {
                        host.setAssociation(playerId, null);
                        final Player online = Bukkit.getPlayer(playerId);
                        if (online != null) host.rescueIfStranded(online, island);
                        completion.accept(IslandService.LeaveResult.LEFT);
                    } else {
                        if (host.isCurrentIsland(island)) {
                            island.addMember(playerId);
                            registry.putMember(playerId, island);
                            host.markDirty(island);
                        }
                        logger.log(Level.SEVERE, "Failed to persist island leave for " + playerId, result);
                        completion.accept(IslandService.LeaveResult.SAVE_FAILED);
                    }
                }, "finish island leave");
            });
        } catch (RejectedExecutionException exception) {
            pendingLeaves.remove(playerId);
            island.addMember(playerId);
            registry.putMember(playerId, island);
            return IslandService.LeaveResult.SAVE_FAILED;
        }
        return IslandService.LeaveResult.PENDING;
    }

    public IslandService.KickResult kick(
            final Player ownerActor,
            final UUID target,
            final Consumer<IslandService.KickResult> completion) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Island kick must run on the main thread");
        final Optional<Island> island = host.ownedIsland(ownerActor.getUniqueId());
        if (island.isEmpty()) return IslandService.KickResult.NOT_OWNER;
        if (target.equals(ownerActor.getUniqueId())) return IslandService.KickResult.CANNOT_KICK_OWNER;
        final Island team = island.get();
        if (team.roleOf(target) != IslandRole.MEMBER) return IslandService.KickResult.TARGET_NOT_MEMBER;
        if (!pendingKicks.add(target)) return IslandService.KickResult.PENDING;
        if (!team.removeMember(target)) {
            pendingKicks.remove(target);
            return IslandService.KickResult.TARGET_NOT_MEMBER;
        }
        registry.removeMember(target, team);
        final Map<String, Object> snapshot = team.toMap();
        try {
            persistence.execute(() -> {
                Throwable failure = null;
                try {
                    persistence.saveSnapshot(snapshot);
                    playerData.clearIslandAssociationIfMatches(target, team.islandId());
                } catch (IOException | RuntimeException exception) {
                    failure = exception;
                }
                final Throwable result = failure;
                host.scheduleMembershipCallback(() -> {
                    pendingKicks.remove(target);
                    if (result == null) {
                        host.setAssociation(target, null);
                        final Player kicked = Bukkit.getPlayer(target);
                        if (kicked != null) host.rescueIfStranded(kicked, team);
                        completion.accept(IslandService.KickResult.KICKED);
                    } else {
                        if (host.isCurrentIsland(team)) {
                            team.addMember(target);
                            registry.putMember(target, team);
                            host.markDirty(team);
                        }
                        logger.log(Level.SEVERE, "Failed to persist island kick for " + target, result);
                        completion.accept(IslandService.KickResult.SAVE_FAILED);
                    }
                }, "finish island kick");
            });
        } catch (RejectedExecutionException exception) {
            pendingKicks.remove(target);
            team.addMember(target);
            registry.putMember(target, team);
            return IslandService.KickResult.SAVE_FAILED;
        }
        return IslandService.KickResult.PENDING;
    }

    public void clearTransient() {
        pendingKicks.clear();
        pendingLeaves.clear();
        invites.clear();
    }

    private void flush(final Island island) {
        final Map<String, Object> snapshot = island.toMap();
        try {
            persistence.execute(() -> {
                try {
                    persistence.saveSnapshot(snapshot);
                } catch (IOException exception) {
                    if (host.isCurrentIsland(island)) host.markDirty(island);
                    logger.log(Level.SEVERE,
                            "Failed to save island after membership change " + island.islandId(), exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            if (host.isCurrentIsland(island)) host.markDirty(island);
            logger.log(Level.SEVERE,
                    "Could not queue island save after membership change " + island.islandId(), exception);
        }
    }
}
