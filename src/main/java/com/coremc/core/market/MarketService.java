package com.coremc.core.market;

import com.coremc.core.config.MessageService;
import com.coremc.core.reward.PendingRewards;
import com.coremc.core.reward.RewardDeliverer;
import com.coremc.core.reward.RewardGrant;
import com.coremc.core.store.TransactionLog;
import com.coremc.core.util.GuiText;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The limited-stock Black Market: wall-clock scheduled rotations,
 * warning announcements, and the one transactional purchase flow.
 *
 * <p>Scheduling never trusts uptime: a single once-per-second
 * heartbeat compares persisted timestamps against the clock, so a
 * restart lands exactly where the schedule says (an open window
 * keeps its persisted rotation — the grid-derived rotation id makes
 * re-opening the same window a no-op). Warnings fire when the clock
 * crosses their due time, so they cannot double-fire or replay after
 * a restart.</p>
 *
 * <p>The purchase flow (all on the main thread):
 * validate open/rotation/offer → requirements → affordability →
 * atomic stock+limit reservation → pending recorded → debit exactly
 * once (any refusal unwinds reservation and pending) → deliver via
 * the store's safe pending pipeline → history + audit log + domain
 * event. Two buyers can never share the last unit; a failed step
 * never costs anything.</p>
 */
public final class MarketService {

    /** Why a purchase attempt did not complete. */
    public enum PurchaseResult {
        OK, CLOSED, STALE_ROTATION, UNKNOWN_OFFER, OUT_OF_STOCK, LIMIT_REACHED,
        REQUIREMENTS, UNAFFORDABLE, IN_FLIGHT, DEBIT_FAILED
    }

    /** How close to a warning's due time the heartbeat may fire it. */
    private static final long WARNING_WINDOW_MILLIS = 3_000;

    private final BlackMarketConfig config;
    private final MarketState state;
    private final MarketSchedule schedule;
    private final MarketBank bank;
    private final MarketRequirements requirements;
    private final PendingRewards pending;
    private final RewardDeliverer deliverer;
    private final TransactionLog transactions;
    private final MessageService messages;
    private final MarketAnnouncer announcer;
    private final MarketEvents events;
    private final Random random;
    private final LongSupplier clock;
    private final Logger logger;

    private final Set<UUID> inFlight = new HashSet<>();
    private final Set<String> firedWarnings = new HashSet<>();
    private int taskId = -1;

    public MarketService(final BlackMarketConfig config, final MarketState state,
                         final MarketBank bank, final MarketRequirements requirements,
                         final PendingRewards pending, final RewardDeliverer deliverer,
                         final TransactionLog transactions, final MessageService messages,
                         final MarketAnnouncer announcer, final MarketEvents events,
                         final Random random, final LongSupplier clock, final Logger logger) {
        this.config = config;
        this.state = state;
        this.schedule = config.schedule();
        this.bank = bank;
        this.requirements = requirements;
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

    public BlackMarketConfig config() {
        return config;
    }

    public MarketState state() {
        return state;
    }

    public MarketRequirements requirements() {
        return requirements;
    }

    public MarketBank bank() {
        return bank;
    }

    // ------------------------------------------------------------------
    // scheduling
    // ------------------------------------------------------------------

    /** Starts the single 1s heartbeat (double starts are refused). */
    public void start(final JavaPlugin plugin, final DarkAuctionService auction) {
        if (taskId != -1) {
            return;
        }
        recover();
        taskId = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            final long now = clock.getAsLong();
            heartbeat(now);
            if (auction != null) {
                auction.tick(now);
            }
        }, 20L, 20L).getTaskId();
    }

    public void stop(final JavaPlugin plugin) {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /**
     * Boot recovery from persisted timestamps: a rotation whose
     * window already closed while the server was down closes now; an
     * open window keeps its stock and limits untouched.
     */
    public void recover() {
        final long now = clock.getAsLong();
        if (state.rotationId() != null && now >= state.closesAt()) {
            logger.info("Black Market: rotation " + state.rotationId()
                    + " expired while offline — closing it.");
            state.closeRotation();
        }
    }

    /** The scheduler's once-per-second pulse (public for tests). */
    public void heartbeat(final long now) {
        if (!config.enabled()) {
            return;
        }
        if (state.isOpen(now)) {
            for (final long offset : config.closingWarningsMillis()) {
                maybeAnnounce("market.closing-warning", state.closesAt() - offset, now,
                        "close-" + state.rotationId() + "-" + offset, Map.of(
                                "minutes", String.valueOf(offset / 60_000)));
            }
            return;
        }
        if (state.rotationId() != null) {
            closeNow("schedule");
        }
        final long nextOpen = schedule.dueOpenAt(now);
        if (config.openingWarningMillis() > 0) {
            maybeAnnounce("market.opening-warning", nextOpen - config.openingWarningMillis(),
                    now, "open-" + nextOpen, Map.of(
                            "minutes", String.valueOf(config.openingWarningMillis() / 60_000)));
        }
        if (schedule.isOpenWindow(now)) {
            final String rotationId = schedule.rotationId(schedule.currentSlotStart(now));
            if (!rotationId.equals(state.suppressedRotation())) {
                openRotation(rotationId, now, schedule.closeAt(now), "schedule");
            }
        }
    }

    private void maybeAnnounce(final String key, final long dueAt, final long now,
                               final String dedupKey, final Map<String, String> placeholders) {
        if (now < dueAt || now >= dueAt + WARNING_WINDOW_MILLIS) {
            return;
        }
        if (!firedWarnings.add(dedupKey)) {
            return;
        }
        announcer.announce(key, placeholders);
    }

    /** Opens a rotation (no-op when that exact rotation is live). */
    private boolean openRotation(final String rotationId, final long now, final long closesAt,
                                 final String trigger) {
        final List<RotationSelector.Selected> selected = RotationSelector.select(
                config.offers(), config.shape(), state.previousOfferIds(), random);
        if (selected.isEmpty()) {
            logger.warning("Black Market: rotation selection came up empty — check pools.");
            return false;
        }
        if (!state.openRotation(rotationId, now, closesAt, selected)) {
            return false;
        }
        state.suppressRotation("");
        announcer.announce("market.open", Map.of(
                "offers", String.valueOf(selected.size()),
                "minutes", String.valueOf((closesAt - now) / 60_000)));
        logger.info("Black Market open (" + trigger + "): rotation " + rotationId + " with "
                + selected.size() + " offers until " + closesAt + ".");
        return true;
    }

    /** Closes the live rotation and announces it. */
    private void closeNow(final String trigger) {
        final String closing = state.rotationId();
        state.closeRotation();
        announcer.announce("market.closed", Map.of());
        logger.info("Black Market closed (" + trigger + "): rotation " + closing + ".");
    }

    // ------------------------------------------------------------------
    // admin operations (they cooperate with the schedule)
    // ------------------------------------------------------------------

    /** Admin: open now for the configured duration. */
    public boolean adminOpen() {
        final long now = clock.getAsLong();
        if (state.isOpen(now)) {
            return false;
        }
        final String rotationId = schedule.isOpenWindow(now)
                ? schedule.rotationId(schedule.currentSlotStart(now))
                : "manual-" + now;
        final long closesAt = schedule.isOpenWindow(now)
                ? schedule.closeAt(now) : now + schedule.openForMillis();
        return openRotation(rotationId, now, closesAt, "admin");
    }

    /** Admin: close now. A grid window closed early stays closed. */
    public boolean adminClose() {
        final long now = clock.getAsLong();
        if (!state.isOpen(now)) {
            return false;
        }
        final String rotationId = state.rotationId();
        closeNow("admin");
        if (rotationId != null && rotationId.startsWith("rot-")) {
            state.suppressRotation(rotationId);
        }
        return true;
    }

    /** Admin: roll a fresh rotation immediately (market must be open). */
    public boolean adminRotate() {
        final long now = clock.getAsLong();
        if (!state.isOpen(now)) {
            return false;
        }
        final long closesAt = state.closesAt();
        state.closeRotation();
        return openRotation("manual-" + now, now, closesAt, "admin rotate");
    }

    // ------------------------------------------------------------------
    // purchases
    // ------------------------------------------------------------------

    /**
     * The core transactional purchase. {@code player} may be null
     * (headless tests): the reward then simply stays pending. Always
     * called on the main thread.
     */
    public PurchaseResult purchase(final UUID buyer, final Player player,
                                   final String rotationId, final String offerId) {
        final long now = clock.getAsLong();
        if (!config.enabled() || !state.isOpen(now)) {
            return PurchaseResult.CLOSED;
        }
        if (!state.rotationId().equals(rotationId)) {
            return PurchaseResult.STALE_ROTATION;
        }
        final MarketOffer offer = config.offer(offerId);
        final MarketState.ActiveOffer active = state.offer(offerId);
        if (offer == null || active == null) {
            return PurchaseResult.UNKNOWN_OFFER;
        }
        if (state.purchased(offerId, buyer) >= offer.perPlayer()) {
            return PurchaseResult.LIMIT_REACHED;
        }
        if (active.stockLeft() <= 0) {
            return PurchaseResult.OUT_OF_STOCK;
        }
        if (!requirements.allMet(buyer, offer)) {
            return PurchaseResult.REQUIREMENTS;
        }
        if (!bank.canAfford(buyer, active.price())) {
            return PurchaseResult.UNAFFORDABLE;
        }
        if (!inFlight.add(buyer)) {
            return PurchaseResult.IN_FLIGHT;
        }
        try {
            // 1. atomic reservation: stock + per-player count, persisted
            if (!state.reserve(rotationId, offerId, buyer, offer.perPlayer(), now)) {
                return PurchaseResult.OUT_OF_STOCK;
            }
            // 2. what is owed, persisted BEFORE the debit
            final String txnId = UUID.randomUUID().toString();
            final RewardGrant grant = RewardGrant.of(offer.reward(),
                    offer.reward().rollAmount(random.nextDouble()));
            pending.add(buyer, txnId, "blackmarket:" + offerId, List.of(grant));
            // 3. debit exactly once — any refusal unwinds everything
            if (!bank.debit(buyer, active.price(), "black market " + offerId)) {
                pending.remove(buyer, txnId);
                state.rollbackReservation(offerId, buyer);
                return PurchaseResult.DEBIT_FAILED;
            }
            // 4. deliver what fits; the rest stays pending for /rewards
            if (player != null) {
                pending.deliver(buyer, owed -> deliverer.deliver(player, owed));
            }
            final boolean delivered = !pending.has(buyer, txnId);
            // 5. history + audit + domain event
            state.recordSale(new MarketState.Sale(txnId, rotationId, offerId, buyer,
                    active.price(), now));
            transactions.record(txnId, buyer, "MARKET_BUY", "rotation=" + rotationId
                    + " offer=" + offerId + " price=" + active.price().serialize()
                    + " delivered=" + (delivered ? "full" : "pending"));
            events.purchase(buyer, rotationId, offer, active.price());
            return PurchaseResult.OK;
        } finally {
            inFlight.remove(buyer);
        }
    }

    /** The player-facing wrapper: messages + sounds per outcome. */
    public void purchase(final Player player, final String rotationId, final String offerId) {
        final PurchaseResult result = purchase(player.getUniqueId(), player, rotationId, offerId);
        final MarketState.ActiveOffer active = state.offer(offerId);
        final MarketOffer offer = config.offer(offerId);
        switch (result) {
            case OK -> {
                messages.sendPrefixed(player, "market.purchased", Map.of(
                        "item", plain(offer.reward().display()),
                        "price", active.price().text()));
                if (pending.grantCount(player.getUniqueId()) > 0) {
                    messages.sendPrefixed(player, "store.delivery-pending");
                }
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            }
            case CLOSED, STALE_ROTATION -> fail(player, "market.closed-now", Map.of());
            case UNKNOWN_OFFER -> fail(player, "market.offer-gone", Map.of());
            case OUT_OF_STOCK -> fail(player, "market.sold-out", Map.of());
            case LIMIT_REACHED -> fail(player, "market.limit-reached", Map.of(
                    "limit", offer == null ? "?" : String.valueOf(offer.perPlayer())));
            case REQUIREMENTS -> fail(player, "market.requirements", Map.of());
            case UNAFFORDABLE, DEBIT_FAILED -> fail(player, "market.cannot-afford", Map.of(
                    "price", active == null ? "?" : active.price().text()));
            case IN_FLIGHT -> {
                // the first click is still processing — stay silent
            }
            default -> {
            }
        }
    }

    private void fail(final Player player, final String key, final Map<String, String> ph) {
        messages.sendPrefixed(player, key, ph);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
    }

    // ------------------------------------------------------------------
    // status text
    // ------------------------------------------------------------------

    /** Seconds until the next scheduled opening (0 while open). */
    public long secondsUntilOpen(final long now) {
        return state.isOpen(now) ? 0
                : Math.max(0, (schedule.dueOpenAt(now) - now) / 1000);
    }

    /** Seconds until the live rotation closes (0 while closed). */
    public long secondsUntilClose(final long now) {
        return state.isOpen(now) ? Math.max(0, (state.closesAt() - now) / 1000) : 0;
    }

    /** One-line status for /coremarket status and the closed GUI. */
    public String statusLine(final long now) {
        if (!config.enabled()) {
            return "disabled";
        }
        return state.isOpen(now)
                ? "open, rotation " + state.rotationId() + ", closes in "
                        + GuiText.seconds(secondsUntilClose(now))
                : "closed, next opening in " + GuiText.seconds(secondsUntilOpen(now));
    }

    static String plain(final String colored) {
        final StringBuilder out = new StringBuilder(colored.length());
        for (int index = 0; index < colored.length(); index++) {
            if (colored.charAt(index) == '&' && index + 1 < colored.length()) {
                index++;
                continue;
            }
            out.append(colored.charAt(index));
        }
        return out.toString();
    }
}
