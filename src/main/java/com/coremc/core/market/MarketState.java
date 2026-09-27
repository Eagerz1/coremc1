package com.coremc.core.market;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The Black Market's persisted, transactional state
 * ({@code black-market-data.yml}): the live rotation (id, window,
 * offers with resolved prices and remaining GLOBAL stock),
 * per-player purchase counts, the previous rotation's offer ids (for
 * repeat avoidance), bounded sale/lot history and the last Dark
 * Auction session key.
 *
 * <p>All mutation happens on the main server thread; stock
 * reservation is a single check-and-decrement here, so two buyers
 * can never both take the last unit. Every mutation persists
 * immediately — a restart resets nothing.</p>
 */
public final class MarketState {

    /** One live offer in the current rotation. */
    public static final class ActiveOffer {
        private final String offerId;
        private final MarketCost price;
        private final int stockTotal;
        private int stockLeft;

        ActiveOffer(final String offerId, final MarketCost price, final int stockTotal,
                    final int stockLeft) {
            this.offerId = offerId;
            this.price = price;
            this.stockTotal = stockTotal;
            this.stockLeft = stockLeft;
        }

        public String offerId() {
            return offerId;
        }

        public MarketCost price() {
            return price;
        }

        public int stockTotal() {
            return stockTotal;
        }

        public int stockLeft() {
            return stockLeft;
        }
    }

    /** One recorded limited-stock sale. */
    public record Sale(String txnId, String rotationId, String offerId, UUID buyer,
                       MarketCost price, long timestamp) {

        String serialize() {
            return txnId + "|" + rotationId + "|" + offerId + "|" + buyer + "|"
                    + price.serialize() + "|" + timestamp;
        }

        static Sale parse(final String line) {
            final String[] parts = line.split("\\|");
            if (parts.length != 6) {
                throw new IllegalArgumentException("bad sale line '" + line + "'");
            }
            return new Sale(parts[0], parts[1], parts[2], UUID.fromString(parts[3]),
                    MarketCost.parse(parts[4]), Long.parseLong(parts[5]));
        }
    }

    /** One recorded Dark Auction result. */
    public record LotResult(String sessionId, String lotId, UUID winner, long amount,
                            long timestamp) {

        String serialize() {
            return sessionId + "|" + lotId + "|" + (winner == null ? "-" : winner) + "|"
                    + amount + "|" + timestamp;
        }

        static LotResult parse(final String line) {
            final String[] parts = line.split("\\|");
            if (parts.length != 5) {
                throw new IllegalArgumentException("bad lot line '" + line + "'");
            }
            return new LotResult(parts[0], parts[1],
                    "-".equals(parts[2]) ? null : UUID.fromString(parts[2]),
                    Long.parseLong(parts[3]), Long.parseLong(parts[4]));
        }
    }

    private final Path file;
    private final Logger logger;
    private final int historyLimit;

    private String rotationId;
    private long openedAt;
    private long closesAt;
    private final Map<String, ActiveOffer> offers = new LinkedHashMap<>();
    private final Map<String, Map<UUID, Integer>> purchases = new LinkedHashMap<>();
    private final Set<String> previousOfferIds = new LinkedHashSet<>();
    private final List<Sale> sales = new ArrayList<>();
    private final List<LotResult> lots = new ArrayList<>();
    private String lastAuctionSession = "";
    private String suppressedRotation = "";

    public MarketState(final Path file, final int historyLimit, final Logger logger) {
        this.file = file;
        this.historyLimit = Math.max(10, historyLimit);
        this.logger = logger;
    }

    // ------------------------------------------------------------------
    // rotation lifecycle
    // ------------------------------------------------------------------

    /** True when a rotation is open at {@code now}. */
    public boolean isOpen(final long now) {
        return rotationId != null && now < closesAt;
    }

    /** The live rotation id, or null when closed. */
    public String rotationId() {
        return rotationId;
    }

    public long openedAt() {
        return openedAt;
    }

    public long closesAt() {
        return closesAt;
    }

    /** The live offers in display order (empty when closed). */
    public List<ActiveOffer> activeOffers() {
        return List.copyOf(offers.values());
    }

    public ActiveOffer offer(final String offerId) {
        return offers.get(offerId);
    }

    /** Offer ids of the previous rotation (for repeat avoidance). */
    public Set<String> previousOfferIds() {
        return Collections.unmodifiableSet(previousOfferIds);
    }

    /**
     * Opens a rotation. A rotation id that matches the live one is a
     * no-op (that is how a restart inside an open window keeps the
     * same rotation instead of duplicating it).
     */
    public boolean openRotation(final String newRotationId, final long now, final long closes,
                                final List<RotationSelector.Selected> selected) {
        if (newRotationId != null && newRotationId.equals(rotationId)) {
            return false;
        }
        closeRotation();
        this.rotationId = newRotationId;
        this.openedAt = now;
        this.closesAt = closes;
        for (final RotationSelector.Selected pick : selected) {
            offers.put(pick.offer().id(), new ActiveOffer(pick.offer().id(), pick.price(),
                    pick.offer().stock(), pick.offer().stock()));
        }
        persist();
        return true;
    }

    /** Closes the live rotation (its offers become "previous"). */
    public void closeRotation() {
        if (rotationId == null) {
            return;
        }
        previousOfferIds.clear();
        previousOfferIds.addAll(offers.keySet());
        rotationId = null;
        openedAt = 0;
        closesAt = 0;
        offers.clear();
        purchases.clear();
        persist();
    }

    // ------------------------------------------------------------------
    // purchases (atomic stock + per-player limits)
    // ------------------------------------------------------------------

    /** How many units {@code buyer} already bought of {@code offerId}. */
    public int purchased(final String offerId, final UUID buyer) {
        return purchases.getOrDefault(offerId, Map.of()).getOrDefault(buyer, 0);
    }

    /**
     * Atomically reserves one unit: checks rotation, stock and the
     * per-player limit and decrements in one main-thread step. Two
     * callers can never both reserve the last unit. Persists before
     * returning true.
     */
    public boolean reserve(final String expectedRotationId, final String offerId,
                           final UUID buyer, final int perPlayerLimit, final long now) {
        if (rotationId == null || !rotationId.equals(expectedRotationId) || now >= closesAt) {
            return false;
        }
        final ActiveOffer offer = offers.get(offerId);
        if (offer == null || offer.stockLeft <= 0) {
            return false;
        }
        if (purchased(offerId, buyer) >= perPlayerLimit) {
            return false;
        }
        offer.stockLeft--;
        purchases.computeIfAbsent(offerId, key -> new LinkedHashMap<>())
                .merge(buyer, 1, Integer::sum);
        persist();
        return true;
    }

    /** Rolls a reservation back (debit failed): stock + count restored. */
    public void rollbackReservation(final String offerId, final UUID buyer) {
        final ActiveOffer offer = offers.get(offerId);
        if (offer != null && offer.stockLeft < offer.stockTotal) {
            offer.stockLeft++;
        }
        final Map<UUID, Integer> counts = purchases.get(offerId);
        if (counts != null) {
            counts.merge(buyer, -1, Integer::sum);
            if (counts.getOrDefault(buyer, 0) <= 0) {
                counts.remove(buyer);
            }
        }
        persist();
    }

    /** Records a completed sale in the bounded history. */
    public void recordSale(final Sale sale) {
        sales.add(sale);
        while (sales.size() > historyLimit) {
            sales.remove(0);
        }
        persist();
    }

    /** Recent sales, newest last. */
    public List<Sale> sales() {
        return Collections.unmodifiableList(sales);
    }

    /**
     * A grid rotation id an admin manually closed: the scheduler must
     * not reopen that same window (persisted, so a restart cannot
     * resurrect it either).
     */
    public String suppressedRotation() {
        return suppressedRotation;
    }

    public void suppressRotation(final String suppressedId) {
        this.suppressedRotation = suppressedId == null ? "" : suppressedId;
        persist();
    }

    // ------------------------------------------------------------------
    // dark auction bookkeeping
    // ------------------------------------------------------------------

    /** The last auction session key ({@code yyyy-MM-dd HH:mm}). */
    public String lastAuctionSession() {
        return lastAuctionSession;
    }

    public void lastAuctionSession(final String key) {
        this.lastAuctionSession = key == null ? "" : key;
        persist();
    }

    /** Records a settled/cancelled lot in the bounded history. */
    public void recordLot(final LotResult result) {
        lots.add(result);
        while (lots.size() > historyLimit) {
            lots.remove(0);
        }
        persist();
    }

    public List<LotResult> lotHistory() {
        return Collections.unmodifiableList(lots);
    }

    /** Lot ids of the most recent session (for repeat avoidance). */
    public Set<String> recentLotIds(final int lastN) {
        final Set<String> ids = new LinkedHashSet<>();
        for (int index = lots.size() - 1; index >= 0 && ids.size() < lastN; index--) {
            ids.add(lots.get(index).lotId());
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    /** Loads the state file; a corrupt file starts fresh (loudly). */
    public void load() {
        offers.clear();
        purchases.clear();
        previousOfferIds.clear();
        sales.clear();
        lots.clear();
        rotationId = null;
        if (!Files.exists(file)) {
            return;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final Exception exception) {
            logger.severe("black-market-data.yml unreadable — starting fresh: "
                    + exception.getMessage());
            return;
        }
        rotationId = yaml.getString("rotation.id", null);
        openedAt = yaml.getLong("rotation.opened-at", 0);
        closesAt = yaml.getLong("rotation.closes-at", 0);
        final ConfigurationSection offerSection = yaml.getConfigurationSection("rotation.offers");
        if (offerSection != null) {
            for (final String offerId : offerSection.getKeys(false)) {
                try {
                    final ConfigurationSection entry = offerSection.getConfigurationSection(offerId);
                    offers.put(offerId, new ActiveOffer(offerId,
                            MarketCost.parse(entry.getString("price")),
                            entry.getInt("stock-total"), entry.getInt("stock-left")));
                } catch (final RuntimeException exception) {
                    logger.warning("black-market-data.yml: dropping corrupt offer '" + offerId
                            + "': " + exception.getMessage());
                }
            }
        }
        final ConfigurationSection buyerSection = yaml.getConfigurationSection("rotation.purchases");
        if (buyerSection != null) {
            for (final String offerId : buyerSection.getKeys(false)) {
                final ConfigurationSection counts = buyerSection.getConfigurationSection(offerId);
                if (counts == null) {
                    continue;
                }
                final Map<UUID, Integer> map = new LinkedHashMap<>();
                for (final String uuid : counts.getKeys(false)) {
                    try {
                        map.put(UUID.fromString(uuid), Math.max(0, counts.getInt(uuid)));
                    } catch (final IllegalArgumentException exception) {
                        logger.warning("black-market-data.yml: bad buyer uuid '" + uuid + "'");
                    }
                }
                purchases.put(offerId, map);
            }
        }
        previousOfferIds.addAll(yaml.getStringList("previous-offer-ids"));
        for (final String line : yaml.getStringList("sales")) {
            try {
                sales.add(Sale.parse(line));
            } catch (final RuntimeException exception) {
                logger.warning("black-market-data.yml: dropping corrupt sale: " + line);
            }
        }
        for (final String line : yaml.getStringList("lots")) {
            try {
                lots.add(LotResult.parse(line));
            } catch (final RuntimeException exception) {
                logger.warning("black-market-data.yml: dropping corrupt lot: " + line);
            }
        }
        lastAuctionSession = yaml.getString("auction.last-session", "");
        suppressedRotation = yaml.getString("suppressed-rotation", "");
    }

    private void persist() {
        final YamlConfiguration yaml = new YamlConfiguration();
        if (rotationId != null) {
            yaml.set("rotation.id", rotationId);
            yaml.set("rotation.opened-at", openedAt);
            yaml.set("rotation.closes-at", closesAt);
            for (final ActiveOffer offer : offers.values()) {
                final String base = "rotation.offers." + offer.offerId;
                yaml.set(base + ".price", offer.price.serialize());
                yaml.set(base + ".stock-total", offer.stockTotal);
                yaml.set(base + ".stock-left", offer.stockLeft);
            }
            for (final Map.Entry<String, Map<UUID, Integer>> entry : purchases.entrySet()) {
                for (final Map.Entry<UUID, Integer> count : entry.getValue().entrySet()) {
                    yaml.set("rotation.purchases." + entry.getKey() + "." + count.getKey(),
                            count.getValue());
                }
            }
        }
        yaml.set("previous-offer-ids", new ArrayList<>(previousOfferIds));
        final List<String> saleLines = new ArrayList<>(sales.size());
        for (final Sale sale : sales) {
            saleLines.add(sale.serialize());
        }
        yaml.set("sales", saleLines);
        final List<String> lotLines = new ArrayList<>(lots.size());
        for (final LotResult lot : lots) {
            lotLines.add(lot.serialize());
        }
        yaml.set("lots", lotLines);
        yaml.set("auction.last-session", lastAuctionSession);
        yaml.set("suppressed-rotation", suppressedRotation);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, yaml.saveToString(), StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            logger.severe("Could not save black-market-data.yml: " + exception.getMessage());
        }
    }
}
