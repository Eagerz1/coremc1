package com.coremc.core.island;

import com.coremc.core.config.MessageService;
import com.coremc.core.tebex.GiftcardLine;
import com.coremc.core.tebex.GiftcardStore;
import com.coremc.core.tebex.TebexClient;
import com.coremc.core.tebex.TebexConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Island top season rewards: when a new season starts, the leaders of
 * each leaderboard are paid in webstore gift cards (GC) — the card is
 * created through the Tebex Plugin API, linked to the island owner and
 * shown to them if they are online. Rewards always go to the island
 * owner, whatever the team size.
 *
 * <p>Each season is only ever paid once ({@code island-rewards.yml}
 * remembers the last paid season), and a payout needs the Tebex
 * integration to be configured — without it the season is marked
 * unpaid and the admin sees a loud log line, never silently lost
 * rewards.</p>
 */
public class IslandTopRewards {

    /** One payable prize: the island, its leaderboard, place and GC amount. */
    public record Reward(Island island, IslandTop.Category category, int rank, double amount) {
    }

    private final JavaPlugin plugin;
    private final Supplier<List<Island>> standings;
    private final IslandPointsService points;
    private final TebexConfig tebexConfig;
    private final TebexClient tebex;
    private final GiftcardStore giftcards;
    private final MessageService messages;
    private final Logger logger;
    private final Path stateFile;

    private int lastPaidSeason;
    private CompletableFuture<Void> payoutChain = CompletableFuture.completedFuture(null);

    public IslandTopRewards(final JavaPlugin plugin, final Supplier<List<Island>> standings,
                            final IslandPointsService points, final TebexConfig tebexConfig,
                            final TebexClient tebex, final GiftcardStore giftcards,
                            final MessageService messages, final Logger logger,
                            final Path stateFile) {
        this.plugin = plugin;
        this.standings = standings;
        this.points = points;
        this.tebexConfig = tebexConfig;
        this.tebex = tebex;
        this.giftcards = giftcards;
        this.messages = messages;
        this.logger = logger;
        this.stateFile = stateFile;
        this.lastPaidSeason = 0;
    }

    /** Loads the last paid season so restarts never double-pay. */
    public void load() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            final List<String> lines = Files.readAllLines(stateFile, StandardCharsets.UTF_8);
            for (final String line : lines) {
                final String trimmed = line.trim();
                if (trimmed.startsWith("last-paid-season:")) {
                    final String value = trimmed.substring("last-paid-season:".length()).trim();
                    try {
                        lastPaidSeason = Math.max(0, Integer.parseInt(value));
                    } catch (final NumberFormatException exception) {
                        logger.warning("Corrupt last-paid-season '" + value
                                + "' in " + stateFile + " — assuming nothing was paid yet.");
                    }
                    return;
                }
            }
        } catch (final IOException exception) {
            logger.warning("Could not read island rewards state: " + exception.getMessage());
        }
    }

    private void persist() {
        try {
            final String yaml = "last-paid-season: " + lastPaidSeason + "\n";
            if (stateFile.getParent() != null) {
                Files.createDirectories(stateFile.getParent());
            }
            final Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(temp, yaml, StandardCharsets.UTF_8);
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException exception) {
            logger.warning("Could not save island rewards state: " + exception.getMessage());
        }
    }

    /**
     * Pure payout table for the given standings: one reward per
     * rewarded place of each category, in category then rank order.
     */
    public static List<Reward> winners(final List<Island> islands,
                                       final ToDoubleFunction<Island> points) {
        final List<Reward> table = new ArrayList<>();
        for (final IslandTop.Category category : IslandTop.Category.values()) {
            final List<Double> rewards = IsTopLayout.rewards(category);
            final List<Island> top = IslandTop.top(islands, points, category, rewards.size());
            for (int index = 0; index < top.size(); index++) {
                table.add(new Reward(top.get(index), category, index + 1, rewards.get(index)));
            }
        }
        return table;
    }

    /** The last season that was paid out (0 = none yet). */
    public int lastPaidSeason() {
        return lastPaidSeason;
    }

    /** Completion of the most recent payout (resolves when all cards are handled). */
    public CompletableFuture<Void> currentPayout() {
        return payoutChain;
    }

    /**
     * Pays the island top rewards for {@code season}. No-op when that
     * season (or a later one) was already paid; skipped (unpaid, with
     * a warning) when Tebex is not configured.
     */
    public void payOut(final int season) {
        if (season <= lastPaidSeason) {
            return;
        }
        if (!tebexConfig.enabled()) {
            logger.warning("Island top rewards for season " + season
                    + " NOT paid — Tebex is not configured (tebex.yml). "
                    + "The season stays unpaid; configure Tebex and run /season set "
                    + season + " again.");
            return;
        }
        lastPaidSeason = season;
        persist();
        final List<Reward> table = winners(standings.get(), points::points);
        payoutChain = new CompletableFuture<>();
        if (table.isEmpty()) {
            logger.info("Island top rewards: nobody ranked yet — nothing to pay for season "
                    + season + ".");
            payoutChain.complete(null);
            return;
        }
        logger.info("Island top rewards: paying " + table.size()
                + " place(s) for season " + season + ".");
        payNext(new ArrayList<>(table), 0, season);
    }

    /** Hands out the rewards one by one so the API is never hammered. */
    private void payNext(final List<Reward> table, final int index, final int season) {
        if (index >= table.size()) {
            payoutChain.complete(null);
            return;
        }
        final Reward reward = table.get(index);
        final String note = "Island top #" + reward.rank() + " " + reward.category().display()
                + " — season " + season;
        tebex.createGiftcard(reward.amount(), note).thenAccept(created ->
                hopToMain(() -> {
                    if (created.status() != TebexClient.LookupStatus.FOUND) {
                        logger.severe("Island top reward failed for " + reward.island().ownerName()
                                + " (#" + reward.rank() + " " + reward.category().display()
                                + ", " + reward.amount() + " GC) — Tebex did not create the card.");
                    } else {
                        deliver(reward, created.giftcard());
                    }
                    payNext(table, index + 1, season);
                }));
    }

    private void deliver(final Reward reward, final TebexClient.Giftcard card) {
        try {
            giftcards.link(reward.island().owner(), card.code());
        } catch (final IOException exception) {
            logger.severe("Could not link the reward gift card for "
                    + reward.island().ownerName() + ": " + exception.getMessage());
        }
        final Player owner = onlineOwner(reward.island().owner());
        if (owner != null && owner.isOnline()) {
            messages.sendPrefixed(owner, "island.top-reward", Map.of(
                    "rank", String.valueOf(reward.rank()),
                    "category", reward.category().display(),
                    "amount", String.format(Locale.US, "%.0f", reward.amount())));
            owner.sendMessage(GiftcardLine.cardLine(messages.prefix(), card.code(),
                    card.formatted()));
        } else {
            logger.info("Island top reward for " + reward.island().ownerName()
                    + ": gift card " + card.code() + " linked — they will see it with /gc.");
        }
    }

    /** Runs a task on the main thread (seam for headless tests). */
    protected void hopToMain(final Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /** The island owner when they are online right now (seam for headless tests). */
    protected Player onlineOwner(final UUID owner) {
        return org.bukkit.Bukkit.getPlayer(owner);
    }
}
