package com.coremc.core.island;

import com.coremc.core.economy.Currency;
import com.coremc.core.economy.EconomyService;
import com.coremc.core.player.PlayerDataService;
import com.coremc.core.player.PlayerProfile;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Executes and recovers journaled island upgrade and buff purchases. */
public final class IslandPurchaseCoordinator {

    private final IslandPersistence persistence;
    private final IslandPurchaseJournal journal;
    private final PlayerDataService playerData;
    private final EconomyService economy;
    private final Logger logger;
    private final IslandPurchaseHost host;
    private final AtomicBoolean inProgress = new AtomicBoolean();

    public IslandPurchaseCoordinator(
            final IslandPersistence persistence,
            final IslandPurchaseJournal journal,
            final PlayerDataService playerData,
            final EconomyService economy,
            final Logger logger,
            final IslandPurchaseHost host) {
        this.persistence = persistence;
        this.journal = journal;
        this.playerData = playerData;
        this.economy = economy;
        this.logger = logger;
        this.host = host;
    }

    public boolean isInProgress() {
        return inProgress.get();
    }

    public void recoverPending(final List<Island> persistedIslands) throws IOException {
        final Optional<IslandPurchaseJournal.Pending> pending = journal.load();
        if (pending.isEmpty()) return;
        final IslandPurchaseJournal.Pending transaction = pending.get();
        final Island island = persistedIslands.stream()
                .filter(candidate -> candidate.islandId().equals(transaction.islandId()))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "Pending island purchase refers to missing island " + transaction.islandId()));

        final PlayerProfile profile;
        try {
            profile = playerData.loadForPersistence(transaction.playerId()).get(10L, TimeUnit.SECONDS)
                    .orElseThrow(() -> new IOException(
                            "Cannot load profile for pending island purchase " + transaction.id()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading profile for island purchase " + transaction.id(), exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("Could not load profile for pending island purchase " + transaction.id(), exception);
        }

        if (!transaction.id().equals(profile.lastIslandPurchaseId())) {
            profile.lastIslandPurchaseId(transaction.id());
            economy.setBalance(profile, Currency.SKY_TOKENS, transaction.balanceAfter());
        }
        if (transaction.kind().equals("upgrade")) {
            final int current = island.upgrades().getOrDefault(transaction.key(), 0);
            if (current < transaction.tier()) island.setUpgradeTier(transaction.key(), transaction.tier());
        } else {
            final int current = island.buffs().getOrDefault(transaction.key(), 0);
            if (current < transaction.tier()) island.setBuffTier(transaction.key(), transaction.tier());
        }

        try {
            if (!playerData.persistAndWait(profile).get(10L, TimeUnit.SECONDS)) {
                throw new IOException("Profile save failed while recovering purchase " + transaction.id());
            }
            persistence.saveSnapshot(island.toMap());
            journal.clear();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while recovering island purchase " + transaction.id(), exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("Could not save profile while recovering island purchase " + transaction.id(), exception);
        }
        logger.info("Recovered interrupted island purchase " + transaction.id() + ".");
    }

    public boolean purchase(
            final Player player,
            final PlayerProfile profile,
            final Island island,
            final String kind,
            final String key,
            final int tier,
            final long price,
            final Runnable onCommitted) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Island purchases must start on the main thread");
        }
        if (!inProgress.compareAndSet(false, true)) {
            host.sendPurchaseMessage(player, "island.purchase-pending", Map.of());
            return false;
        }

        final String transactionId = UUID.randomUUID().toString();
        final long balanceAfter;
        final Map<String, Object> snapshot;
        synchronized (island) {
            synchronized (profile) {
                final long balance = profile.balanceOf(Currency.SKY_TOKENS);
                if (balance < price) {
                    inProgress.set(false);
                    host.sendPurchaseMessage(player,
                            kind.equals("upgrade") ? "island.upgrade.insufficient" : "island.buff.insufficient",
                            Map.of("price", String.valueOf(price)));
                    return false;
                }
                balanceAfter = balance - price;
                try {
                    journal.write(new IslandPurchaseJournal.Pending(transactionId, player.getUniqueId(),
                            island.islandId(), kind, key, tier, balanceAfter));
                } catch (IOException exception) {
                    inProgress.set(false);
                    logger.log(Level.SEVERE, "Could not write island purchase journal; purchase was not applied.", exception);
                    host.sendPurchaseMessage(player, "island.purchase-save-failed", Map.of());
                    return false;
                }
                profile.lastIslandPurchaseId(transactionId);
                economy.setBalance(profile, Currency.SKY_TOKENS, balanceAfter);
                if (kind.equals("upgrade")) island.setUpgradeTier(key, tier);
                else island.setBuffTier(key, tier);
                snapshot = island.toMap();
            }
        }

        host.sendPurchaseMessage(player, "island.purchase-pending", Map.of());
        try {
            persistence.execute(() -> {
                IOException lastFailure = null;
                boolean committed = false;
                for (int attempt = 1; attempt <= 3 && !committed; attempt++) {
                    try {
                        if (!playerData.persistAndWait(profile).get(10L, TimeUnit.SECONDS)) {
                            throw new IOException("Player profile write failed for island purchase " + transactionId);
                        }
                        persistence.saveSnapshot(snapshot);
                        journal.clear();
                        committed = true;
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        lastFailure = new IOException("Interrupted while saving island purchase " + transactionId, exception);
                        break;
                    } catch (ExecutionException | TimeoutException | IOException exception) {
                        lastFailure = exception instanceof IOException ioException ? ioException
                                : new IOException("Could not durably save island purchase " + transactionId, exception);
                    }
                }
                if (committed) {
                    inProgress.set(false);
                    host.schedulePurchaseCallback(onCommitted, "finish island purchase");
                } else {
                    logger.log(Level.SEVERE,
                            "Island purchase remains journaled and will be recovered at restart: " + transactionId,
                            lastFailure);
                    host.schedulePurchaseCallback(
                            () -> host.sendPurchaseMessage(player, "island.purchase-recovery-pending", Map.of()),
                            "report pending island purchase recovery");
                }
            });
        } catch (RejectedExecutionException exception) {
            logger.log(Level.SEVERE,
                    "Island purchase remains journaled and will be recovered at restart: " + transactionId,
                    exception);
            host.sendPurchaseMessage(player, "island.purchase-recovery-pending", Map.of());
        }
        return true;
    }
}
