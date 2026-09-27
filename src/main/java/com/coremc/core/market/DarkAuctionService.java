package com.coremc.core.market;

import com.coremc.core.config.MessageService;
import com.coremc.core.reward.PendingRewards;
import com.coremc.core.reward.RewardDeliverer;
import com.coremc.core.reward.RewardGrant;
import com.coremc.core.shop.EconomyService;
import com.coremc.core.store.TransactionLog;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiText;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Dark Auction: a rarer, scheduled session inside the Black
 * Market that auctions a handful of configured lots sequentially.
 *
 * <p>Bidding never reserves funds (the EconomyService cannot
 * escrow): every bid re-validates affordability, settlement
 * re-validates it again and walks down the stored valid bids when
 * the leader can no longer pay — so non-winners are never charged,
 * balances never go negative and a cancelled lot debits nobody.
 * Settlement per lot runs exactly once ({@link AuctionEngine}).</p>
 *
 * <p>Restart mid-session uses the explicit safe strategy: nothing
 * about a live lot is persisted, so the unresolved session simply
 * cancels — no funds were reserved, the boss bar dies with the
 * server, and no uncertain settlement is ever reconstructed. The
 * per-day session key IS persisted, so a restart cannot re-run a
 * session that already started.</p>
 */
public final class DarkAuctionService {

    private final BlackMarketConfig config;
    private final MarketState state;
    private final EconomyService economy;
    private final PendingRewards pending;
    private final RewardDeliverer deliverer;
    private final TransactionLog transactions;
    private final MessageService messages;
    private final MarketAnnouncer announcer;
    private final MarketEvents events;
    private final Random random;
    private final LongSupplier clock;
    private final Logger logger;
    private final JavaPlugin plugin;

    private String sessionId;
    private List<AuctionLotDef> queue = List.of();
    private int lotIndex = -1;
    private AuctionEngine engine;
    private long breakUntil;
    private BossBar bossBar;

    public DarkAuctionService(final JavaPlugin plugin, final BlackMarketConfig config,
                              final MarketState state, final EconomyService economy,
                              final PendingRewards pending, final RewardDeliverer deliverer,
                              final TransactionLog transactions, final MessageService messages,
                              final MarketAnnouncer announcer, final MarketEvents events,
                              final Random random, final LongSupplier clock,
                              final Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.state = state;
        this.economy = economy;
        this.pending = pending;
        this.deliverer = deliverer;
        this.transactions = transactions;
        this.messages = messages;
        this.announcer = announcer;
        this.events = events;
        this.random = random;
        this.clock = clock;
        this.logger = logger;
    }

    // ------------------------------------------------------------------
    // schedule
    // ------------------------------------------------------------------

    /**
     * The next configured session time at/after {@code now} as
     * ({@code key}, epochMillis). Keys are {@code yyyy-MM-dd HH:mm}
     * in the configured zone — the persisted last-session key makes
     * a restart skip a session that already started.
     */
    public Map.Entry<String, Long> nextSession(final long now) {
        Map.Entry<String, Long> best = null;
        for (final String time : config.auctionTimes()) {
            final LocalTime local = LocalTime.parse(time);
            for (int dayOffset = 0; dayOffset <= 1; dayOffset++) {
                final LocalDate day = ZonedDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(now), config.zone())
                        .toLocalDate().plusDays(dayOffset);
                final long at = LocalDateTime.of(day, local).atZone(config.zone())
                        .toInstant().toEpochMilli();
                final String key = day + " " + time;
                if (at >= now - 60_000 && !key.equals(state.lastAuctionSession())
                        && (best == null || at < best.getValue())) {
                    best = Map.entry(key, at);
                }
            }
        }
        return best;
    }

    /** Driven by the market's single 1s heartbeat. */
    public void tick(final long now) {
        if (!config.enabled()) {
            return;
        }
        if (!active()) {
            final Map.Entry<String, Long> next = nextSession(now);
            if (next != null && now >= next.getValue()) {
                start(next.getKey(), "schedule");
            }
            return;
        }
        if (engine == null) {
            if (now >= breakUntil) {
                advance(now);
            }
            return;
        }
        updateBossBar(now);
        if (engine.ended(now)) {
            settleCurrentLot(now);
        }
    }

    // ------------------------------------------------------------------
    // session lifecycle
    // ------------------------------------------------------------------

    public boolean active() {
        return sessionId != null;
    }

    /** The live lot's engine (null between lots / outside sessions). */
    public AuctionEngine engine() {
        return engine;
    }

    public AuctionLotDef currentLot() {
        return engine == null ? null : engine.lot();
    }

    public String sessionId() {
        return sessionId;
    }

    /** Lots remaining after the current one. */
    public int lotsRemaining() {
        return queue.isEmpty() ? 0 : Math.max(0, queue.size() - lotIndex - 1);
    }

    /** Starts a session; refuses to overlap a running one. */
    public boolean start(final String sessionKey, final String trigger) {
        if (active() || config.lots().isEmpty()) {
            return false;
        }
        final long now = clock.getAsLong();
        final int count = config.lotsMin() + (config.lotsMax() > config.lotsMin()
                ? random.nextInt(config.lotsMax() - config.lotsMin() + 1) : 0);
        queue = pickLots(count);
        if (queue.isEmpty()) {
            return false;
        }
        sessionId = "auc-" + now;
        lotIndex = -1;
        state.lastAuctionSession(sessionKey == null ? "manual " + now : sessionKey);
        announcer.announce("market.auction-start", Map.of(
                "lots", String.valueOf(queue.size())));
        logger.info("Dark Auction session " + sessionId + " (" + trigger + "): "
                + queue.size() + " lots.");
        breakUntil = now + 3_000; // short call to gather before lot 1
        engine = null;
        return true;
    }

    /** Picks distinct lots, avoiding the most recent session's lots. */
    private List<AuctionLotDef> pickLots(final int count) {
        final Set<String> recent = state.recentLotIds(config.lotsMax());
        final List<AuctionLotDef> fresh = new ArrayList<>();
        final List<AuctionLotDef> repeats = new ArrayList<>();
        for (final AuctionLotDef lot : config.lots()) {
            (recent.contains(lot.id()) ? repeats : fresh).add(lot);
        }
        Collections.shuffle(fresh, random);
        Collections.shuffle(repeats, random);
        final List<AuctionLotDef> ordered = new ArrayList<>(fresh);
        ordered.addAll(repeats);
        return List.copyOf(ordered.subList(0, Math.min(count, ordered.size())));
    }

    private void advance(final long now) {
        lotIndex++;
        if (lotIndex >= queue.size()) {
            end("all lots settled");
            return;
        }
        final AuctionLotDef lot = queue.get(lotIndex);
        engine = new AuctionEngine(lot, now, config.lotDurationMillis(),
                config.antiSnipeThresholdMillis(), config.antiSnipeExtensionMillis(),
                config.antiSnipeCapMillis());
        announcer.announce("market.auction-lot", Map.of(
                "item", MarketService.plain(lot.reward().display()),
                "rarity", lot.rarity().display(),
                "bid", GuiText.money(lot.startingBid()),
                "seconds", String.valueOf(config.lotDurationMillis() / 1000)));
        showBossBar();
        updateBossBar(now);
    }

    private void settleCurrentLot(final long now) {
        final AuctionEngine current = engine;
        engine = null;
        final AuctionLotDef lot = current.lot();
        // settlement re-validates the balance; withdraw refuses
        // atomically, so a broke leader falls through to earlier bids
        final AuctionEngine.Settlement result = current.settle(
                (bidder, amount) -> economy.withdraw(bidder, amount));
        final String txnId = UUID.randomUUID().toString();
        if (result.sold()) {
            final RewardGrant grant = RewardGrant.of(lot.reward(),
                    lot.reward().rollAmount(random.nextDouble()));
            pending.add(result.winner(), txnId, "auction:" + lot.id(), List.of(grant));
            deliverIfOnline(result.winner());
            state.recordLot(new MarketState.LotResult(sessionId, lot.id(), result.winner(),
                    result.amount(), now));
            transactions.record(txnId, result.winner(), "AUCTION_WIN", "session=" + sessionId
                    + " lot=" + lot.id() + " bid=" + result.amount());
            events.win(result.winner(), sessionId, lot, result.amount());
            announcer.announce("market.auction-sold", Map.of(
                    "item", MarketService.plain(lot.reward().display()),
                    "player", nameOf(result.winner()),
                    "bid", GuiText.money(result.amount())));
        } else {
            state.recordLot(new MarketState.LotResult(sessionId, lot.id(), null, 0, now));
            transactions.record(txnId, null, "AUCTION_PASS", "session=" + sessionId
                    + " lot=" + lot.id() + " bids=" + current.bids().size());
            announcer.announce("market.auction-unsold", Map.of(
                    "item", MarketService.plain(lot.reward().display())));
        }
        breakUntil = now + config.lotBreakMillis();
    }

    /** Ends the session cleanly (also the admin stop path). */
    public void end(final String reason) {
        if (!active()) {
            return;
        }
        // an unresolved live lot cancels: nobody was charged for
        // bidding, so cancelling debits nobody
        if (engine != null && !engine.settled()) {
            state.recordLot(new MarketState.LotResult(sessionId, engine.lot().id(), null, 0,
                    clock.getAsLong()));
            transactions.record(UUID.randomUUID().toString(), null, "AUCTION_CANCELLED",
                    "session=" + sessionId + " lot=" + engine.lot().id() + " reason=" + reason);
        }
        logger.info("Dark Auction session " + sessionId + " ended: " + reason);
        sessionId = null;
        queue = List.of();
        lotIndex = -1;
        engine = null;
        hideBossBar();
        announcer.announce("market.auction-end", Map.of());
    }

    // ------------------------------------------------------------------
    // bidding
    // ------------------------------------------------------------------

    /** Core bid path (headless-testable); the wrapper adds messages. */
    public AuctionEngine.BidResult bid(final UUID bidder, final long amount) {
        if (engine == null) {
            return AuctionEngine.BidResult.ENDED;
        }
        final AuctionEngine.BidResult result = engine.placeBid(bidder, amount,
                clock.getAsLong(), (who, bid) -> economy.has(who, bid));
        if (result == AuctionEngine.BidResult.OK) {
            events.bid(bidder, sessionId, engine.lot().id(), amount);
        }
        return result;
    }

    /** Player-facing bid with messages and sounds. */
    public void bid(final Player player, final long amount) {
        if (engine == null) {
            messages.sendPrefixed(player, "market.no-auction");
            return;
        }
        final long minNext = engine.minNextBid();
        switch (bid(player.getUniqueId(), amount)) {
            case OK -> {
                messages.sendPrefixed(player, "market.bid-placed", Map.of(
                        "bid", GuiText.money(amount)));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.4f);
            }
            case TOO_LOW -> {
                messages.sendPrefixed(player, "market.bid-too-low", Map.of(
                        "minimum", GuiText.money(minNext)));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
            }
            case CANNOT_AFFORD -> {
                messages.sendPrefixed(player, "market.bid-cannot-afford", Map.of(
                        "bid", GuiText.money(amount)));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
            }
            case ENDED -> messages.sendPrefixed(player, "market.no-auction");
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // boss bar (runtime only)
    // ------------------------------------------------------------------

    private void showBossBar() {
        if (plugin == null) {
            return;
        }
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID);
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(player);
        }
        bossBar.setVisible(true);
    }

    /** New joiners see the live auction bar too. */
    public void onJoin(final Player player) {
        if (bossBar != null && engine != null) {
            bossBar.addPlayer(player);
        }
    }

    private void updateBossBar(final long now) {
        if (plugin == null || bossBar == null || engine == null) {
            return;
        }
        final AuctionLotDef lot = engine.lot();
        final long bid = engine.currentBid() == 0 ? lot.startingBid() : engine.currentBid();
        bossBar.setTitle(ColorUtil.colorize(lot.rarity().color() + "&l"
                + GuiText.caps(MarketService.plain(lot.reward().display()))
                + " &7\u2022 &f" + GuiText.money(bid)
                + " &7\u2022 &e" + (engine.remainingMillis(now) / 1000) + "s"));
        final double progress = engine.remainingMillis(now)
                / (double) Math.max(1, config.lotDurationMillis() + engine.extendedTotalMillis());
        bossBar.setProgress(Math.min(1.0, Math.max(0.0, progress)));
    }

    private void hideBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
        }
    }

    /** onDisable: cancel the unresolved session, clear the bar. */
    public void shutdown() {
        if (active()) {
            end("server shutting down — unresolved lot cancelled, nobody charged");
        }
        hideBossBar();
    }

    private void deliverIfOnline(final UUID winner) {
        if (plugin == null) {
            return;
        }
        final Player online = Bukkit.getPlayer(winner);
        if (online != null) {
            pending.deliver(winner, grant -> deliverer.deliver(online, grant));
        }
    }

    private String nameOf(final UUID player) {
        if (plugin == null) {
            return player.toString().substring(0, 8);
        }
        final String name = Bukkit.getOfflinePlayer(player).getName();
        return name == null ? player.toString().substring(0, 8) : name;
    }
}
