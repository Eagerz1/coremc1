package com.coremc.core.market;

import com.coremc.core.reward.RewardDef;
import com.coremc.core.reward.RewardType;
import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and validates {@code black-market.yml}: the limited-stock
 * schedule, warning offsets, rotation shape, the offer pools, the
 * Dark Auction schedule/lots/anti-snipe/bid buttons and history
 * limits. Every problem is collected and reported in ONE loud error —
 * a broken file disables the market, never corrupts it.
 *
 * <p>House rules enforced here: Money is the primary currency,
 * Credits may only appear on COSMETIC/SEASONAL offers, no free
 * offers, no negative anything, no schedule where the market never
 * closes.</p>
 */
public final class BlackMarketConfig {

    /** One configured GUI bid button. */
    public record BidButton(boolean percent, long value) {

        /** Button label: {@code +$50,000} or {@code +10%}. */
        public String label() {
            return percent ? "+" + value + "%"
                    : "+$" + String.format(Locale.US, "%,d", value);
        }
    }

    private final JavaPlugin plugin;

    private boolean enabled;
    private long openEveryMillis = 6L * 60 * 60 * 1000;
    private long openForMillis = 30L * 60 * 1000;
    private long anchorOffsetMillis = 15L * 60 * 1000;
    private long openingWarningMillis = 10L * 60 * 1000;
    private List<Long> closingWarningsMillis = List.of(5L * 60 * 1000, 60L * 1000);
    private RotationSelector.Shape shape =
            new RotationSelector.Shape(2, 3, 2, 1, 0.25, 0.5, 0.25);
    private int historyLimit = 200;
    private ZoneId zone = ZoneId.systemDefault();
    private final Map<String, MarketOffer> offers = new LinkedHashMap<>();

    private List<String> auctionTimes = List.of();
    private int lotsMin = 3;
    private int lotsMax = 5;
    private long lotDurationMillis = 60_000;
    private long lotBreakMillis = 10_000;
    private long antiSnipeThresholdMillis = 10_000;
    private long antiSnipeExtensionMillis = 10_000;
    private long antiSnipeCapMillis = 60_000;
    private List<BidButton> bidButtons = List.of();
    private final Map<String, AuctionLotDef> lots = new LinkedHashMap<>();

    public BlackMarketConfig(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** An empty, disabled config (market off). */
    public static BlackMarketConfig disabled() {
        return new BlackMarketConfig(null);
    }

    /** Copies black-market.yml out of the jar on first run, then parses. */
    public void load(final Set<String> keyIds, final Set<String> lootboxIds) {
        final File file = new File(plugin.getDataFolder(), "black-market.yml");
        if (!file.exists()) {
            plugin.saveResource("black-market.yml", false);
        }
        parse(YamlConfiguration.loadConfiguration(file), keyIds, lootboxIds);
    }

    /** Parses + validates; throws ONE error listing every problem. */
    public void parse(final YamlConfiguration yaml, final Set<String> keyIds,
                      final Set<String> lootboxIds) {
        final List<String> problems = new ArrayList<>();
        offers.clear();
        lots.clear();
        enabled = false;

        parseSchedule(yaml, problems);
        parseSelection(yaml, problems);
        historyLimit = yaml.getInt("history-limit", 200);
        if (historyLimit < 10) {
            problems.add("history-limit: must be at least 10");
        }
        parseOffers(yaml.getConfigurationSection("offers"), keyIds, lootboxIds, problems);
        parseAuction(yaml.getConfigurationSection("auction"), keyIds, lootboxIds, problems);
        crossCheckPools(problems);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("broken black-market.yml:\n  - "
                    + String.join("\n  - ", problems));
        }
        enabled = true;
    }

    private void parseSchedule(final YamlConfiguration yaml, final List<String> problems) {
        final long every = yaml.getLong("schedule.open-every-minutes", 360);
        final long duration = yaml.getLong("schedule.open-for-minutes", 30);
        final long offset = yaml.getLong("schedule.anchor-offset-minutes", 15);
        if (every <= 0 || duration <= 0 || duration >= every) {
            problems.add("schedule: 0 < open-for-minutes < open-every-minutes required (got "
                    + duration + " / " + every + ")");
        } else {
            openEveryMillis = every * 60_000;
            openForMillis = duration * 60_000;
            anchorOffsetMillis = Math.max(0, offset) * 60_000;
        }
        final long openingWarn = yaml.getLong("warnings.opening-minutes", 10);
        if (openingWarn < 0) {
            problems.add("warnings.opening-minutes: negative");
        } else {
            openingWarningMillis = openingWarn * 60_000;
        }
        final List<Long> closing = new ArrayList<>();
        for (final int minutes : yaml.getIntegerList("warnings.closing-minutes")) {
            if (minutes <= 0 || minutes * 60_000L >= openForMillis) {
                problems.add("warnings.closing-minutes: " + minutes
                        + " outside the open window");
            } else {
                closing.add(minutes * 60_000L);
            }
        }
        if (!closing.isEmpty()) {
            closing.sort((a, b) -> Long.compare(b, a));
            closingWarningsMillis = List.copyOf(closing);
        }
        final String zoneName = yaml.getString("schedule.timezone", "");
        if (zoneName != null && !zoneName.isBlank()) {
            try {
                zone = ZoneId.of(zoneName);
            } catch (final RuntimeException exception) {
                problems.add("schedule.timezone: unknown zone '" + zoneName + "'");
            }
        }
    }

    private void parseSelection(final YamlConfiguration yaml, final List<String> problems) {
        try {
            shape = new RotationSelector.Shape(
                    yaml.getInt("selection.common-min", 2),
                    yaml.getInt("selection.common-max", 3),
                    yaml.getInt("selection.rare", 2),
                    yaml.getInt("selection.epic", 1),
                    yaml.getDouble("selection.legendary-chance", 0.25),
                    yaml.getDouble("selection.cosmetic-chance", 0.5),
                    yaml.getDouble("selection.seasonal-chance", 0.25));
        } catch (final IllegalArgumentException exception) {
            problems.add("selection: " + exception.getMessage());
        }
    }

    private void parseOffers(final ConfigurationSection section, final Set<String> keyIds,
                             final Set<String> lootboxIds, final List<String> problems) {
        if (section == null || section.getKeys(false).isEmpty()) {
            problems.add("offers: missing or empty");
            return;
        }
        final Set<String> rewardIds = new LinkedHashSet<>();
        for (final String offerId : section.getKeys(false)) {
            final ConfigurationSection entry = section.getConfigurationSection(offerId);
            if (entry == null) {
                problems.add("offers." + offerId + ": not a section");
                continue;
            }
            final MarketRarity pool = MarketRarity.parse(entry.getString("pool"));
            if (pool == null) {
                problems.add("offers." + offerId + ": unknown pool '"
                        + entry.getString("pool") + "'");
                continue;
            }
            final RewardDef reward = parseReward(entry.getConfigurationSection("reward"),
                    "offers." + offerId, keyIds, lootboxIds, problems);
            final CostBand cost = parseCost(entry.getConfigurationSection("price"),
                    "offers." + offerId, problems);
            if (reward == null || cost == null) {
                continue;
            }
            if (cost.chargesCredits()
                    && pool != MarketRarity.COSMETIC && pool != MarketRarity.SEASONAL) {
                problems.add("offers." + offerId + ": Credits are cosmetics-only — a "
                        + pool.display() + " offer cannot charge Credits");
                continue;
            }
            final List<MarketOffer.Requirement> requirements = new ArrayList<>();
            boolean requirementsOk = true;
            for (final String raw : entry.getStringList("requirements")) {
                final int colon = raw.indexOf(':');
                final String type = colon < 0 ? raw : raw.substring(0, colon);
                final String value = colon < 0 ? "" : raw.substring(colon + 1);
                if (type.isBlank()) {
                    problems.add("offers." + offerId + ": malformed requirement '" + raw + "'");
                    requirementsOk = false;
                    continue;
                }
                requirements.add(new MarketOffer.Requirement(
                        type.trim().toLowerCase(Locale.ROOT), value.trim()));
            }
            if (!requirementsOk) {
                continue;
            }
            final int stock = entry.getInt("stock", 0);
            final int perPlayer = entry.getInt("per-player", 1);
            try {
                offers.put(offerId, new MarketOffer(offerId, reward,
                        entry.getStringList("description"), pool, cost, stock, perPlayer,
                        requirements, entry.getBoolean("discovery", false)));
            } catch (final IllegalArgumentException exception) {
                problems.add("offers." + offerId + ": " + exception.getMessage());
            }
            if (!rewardIds.add(reward.type() + ":" + reward.id())) {
                problems.add("offers." + offerId + ": duplicate reward id '" + reward.id()
                        + "' — offer ids must map to distinct rewards");
            }
        }
    }

    private void parseAuction(final ConfigurationSection section, final Set<String> keyIds,
                              final Set<String> lootboxIds, final List<String> problems) {
        if (section == null) {
            problems.add("auction: missing section");
            return;
        }
        final List<String> times = new ArrayList<>();
        for (final String time : section.getStringList("times")) {
            if (!time.matches("([01]?\\d|2[0-3]):[0-5]\\d")) {
                problems.add("auction.times: '" + time + "' is not HH:mm");
            } else {
                times.add(time);
            }
        }
        auctionTimes = List.copyOf(times);
        lotsMin = section.getInt("lots-per-session-min", 3);
        lotsMax = section.getInt("lots-per-session-max", 5);
        if (lotsMin <= 0 || lotsMax < lotsMin) {
            problems.add("auction: 0 < lots-per-session-min <= lots-per-session-max required");
        }
        final long duration = section.getLong("lot-duration-seconds", 60);
        final long lotBreak = section.getLong("break-seconds", 10);
        if (duration <= 5 || lotBreak < 0) {
            problems.add("auction: lot-duration-seconds must exceed 5, break-seconds >= 0");
        } else {
            lotDurationMillis = duration * 1000;
            lotBreakMillis = lotBreak * 1000;
        }
        final long threshold = section.getLong("anti-snipe.threshold-seconds", 10);
        final long extension = section.getLong("anti-snipe.extension-seconds", 10);
        final long cap = section.getLong("anti-snipe.max-extension-seconds", 60);
        if (threshold < 0 || extension < 0 || cap < extension) {
            problems.add("auction.anti-snipe: threshold/extension >= 0 and cap >= extension");
        } else {
            antiSnipeThresholdMillis = threshold * 1000;
            antiSnipeExtensionMillis = extension * 1000;
            antiSnipeCapMillis = cap * 1000;
        }
        final List<BidButton> buttons = new ArrayList<>();
        for (final String raw : section.getStringList("bid-buttons")) {
            final String[] parts = raw.split(":");
            final long value = parts.length == 2 ? parseLong(parts[1]) : -1;
            if (parts.length != 2 || value <= 0
                    || !("add".equals(parts[0]) || "percent".equals(parts[0]))
                    || ("percent".equals(parts[0]) && value > 100)) {
                problems.add("auction.bid-buttons: bad button '" + raw
                        + "' (use add:<amount> or percent:<1-100>)");
                continue;
            }
            buttons.add(new BidButton("percent".equals(parts[0]), value));
        }
        bidButtons = List.copyOf(buttons);
        final ConfigurationSection lotSection = section.getConfigurationSection("lots");
        if (lotSection == null || lotSection.getKeys(false).isEmpty()) {
            problems.add("auction.lots: missing or empty");
            return;
        }
        for (final String lotId : lotSection.getKeys(false)) {
            final ConfigurationSection entry = lotSection.getConfigurationSection(lotId);
            if (entry == null) {
                problems.add("auction.lots." + lotId + ": not a section");
                continue;
            }
            final MarketRarity rarity = MarketRarity.parse(entry.getString("rarity"));
            if (rarity == null) {
                problems.add("auction.lots." + lotId + ": unknown rarity '"
                        + entry.getString("rarity") + "'");
                continue;
            }
            final RewardDef reward = parseReward(entry.getConfigurationSection("reward"),
                    "auction.lots." + lotId, keyIds, lootboxIds, problems);
            if (reward == null) {
                continue;
            }
            try {
                lots.put(lotId, new AuctionLotDef(lotId, reward,
                        entry.getStringList("description"), rarity,
                        entry.getLong("starting-bid", 0), entry.getLong("min-increment", 0)));
            } catch (final IllegalArgumentException exception) {
                problems.add("auction.lots." + lotId + ": " + exception.getMessage());
            }
        }
        if (!lots.isEmpty() && lotsMin > lots.size()) {
            problems.add("auction: lots-per-session-min " + lotsMin + " exceeds the "
                    + lots.size() + " configured lot(s)");
        }
    }

    /** Selection counts must be satisfiable by the configured pools. */
    private void crossCheckPools(final List<String> problems) {
        final Map<MarketRarity, Integer> sizes = new LinkedHashMap<>();
        for (final MarketOffer offer : offers.values()) {
            sizes.merge(offer.pool(), 1, Integer::sum);
        }
        check(problems, MarketRarity.COMMON, shape.commonMin(), sizes);
        check(problems, MarketRarity.RARE, shape.rare(), sizes);
        check(problems, MarketRarity.EPIC, shape.epic(), sizes);
        if (shape.legendaryChance() > 0 && sizes.getOrDefault(MarketRarity.LEGENDARY, 0) == 0) {
            problems.add("selection: legendary-chance > 0 but no LEGENDARY offers exist");
        }
        if (shape.cosmeticChance() > 0 && sizes.getOrDefault(MarketRarity.COSMETIC, 0) == 0) {
            problems.add("selection: cosmetic-chance > 0 but no COSMETIC offers exist");
        }
        if (shape.seasonalChance() > 0 && sizes.getOrDefault(MarketRarity.SEASONAL, 0) == 0) {
            problems.add("selection: seasonal-chance > 0 but no SEASONAL offers exist");
        }
    }

    private static void check(final List<String> problems, final MarketRarity pool,
                              final int needed, final Map<MarketRarity, Integer> sizes) {
        final int available = sizes.getOrDefault(pool, 0);
        if (needed > available) {
            problems.add("selection: needs " + needed + " " + pool.display()
                    + " offer(s) but only " + available + " configured");
        }
    }

    /** Parses one reward section (the store's reward shape, no weight). */
    private static RewardDef parseReward(final ConfigurationSection entry, final String context,
                                         final Set<String> keyIds, final Set<String> lootboxIds,
                                         final List<String> problems) {
        if (entry == null) {
            problems.add(context + ": missing reward section");
            return null;
        }
        final RewardType type = RewardType.parse(entry.getString("type"));
        if (type == null) {
            problems.add(context + ": unknown reward type '" + entry.getString("type") + "'");
            return null;
        }
        final String display = entry.getString("display", "");
        if (display.isBlank()) {
            problems.add(context + ": reward needs a display name");
            return null;
        }
        final String id = entry.getString("id", "");
        Material material = null;
        List<String> commands = null;
        switch (type) {
            case KEY -> {
                if (keyIds != null && !keyIds.contains(id)) {
                    problems.add(context + ": unknown key '" + id + "'");
                    return null;
                }
            }
            case LOOTBOX -> {
                if (lootboxIds != null && !lootboxIds.contains(id)) {
                    problems.add(context + ": unknown lootbox '" + id + "'");
                    return null;
                }
            }
            case ITEM -> {
                if (id.isBlank()) {
                    problems.add(context + ": item rewards need a PDC id");
                    return null;
                }
                material = Material.matchMaterial(entry.getString("material", ""));
                if (material == null || material == Material.AIR) {
                    problems.add(context + ": unknown material '"
                            + entry.getString("material") + "'");
                    return null;
                }
            }
            case COMMAND -> {
                commands = entry.getStringList("commands");
                if (commands.isEmpty()) {
                    problems.add(context + ": command rewards need commands");
                    return null;
                }
            }
            case MONEY, SKY_TOKENS, CREDITS -> {
                // amount-only rewards
            }
            default -> {
            }
        }
        final long amount = entry.getLong("amount", 1);
        final long min = entry.getLong("min", amount);
        final long max = entry.getLong("max", amount);
        if (min <= 0 || max < min) {
            problems.add(context + ": bad amount range " + min + ".." + max);
            return null;
        }
        return new RewardDef(id.isBlank() ? context : id, type, display,
                entry.getString("rarity", "common"), min, max, 1, material, commands);
    }

    private static CostBand parseCost(final ConfigurationSection entry, final String context,
                                      final List<String> problems) {
        if (entry == null) {
            problems.add(context + ": missing price section");
            return null;
        }
        try {
            return new CostBand(
                    entry.getLong("money-min", entry.getLong("money", 0)),
                    entry.getLong("money-max", entry.getLong("money", 0)),
                    entry.getLong("tokens-min", entry.getLong("tokens", 0)),
                    entry.getLong("tokens-max", entry.getLong("tokens", 0)),
                    entry.getLong("credits-min", entry.getLong("credits", 0)),
                    entry.getLong("credits-max", entry.getLong("credits", 0)));
        } catch (final IllegalArgumentException exception) {
            problems.add(context + ": " + exception.getMessage());
            return null;
        }
    }

    private static long parseLong(final String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (final NumberFormatException exception) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // accessors
    // ------------------------------------------------------------------

    public boolean enabled() {
        return enabled;
    }

    public MarketSchedule schedule() {
        return new MarketSchedule(openEveryMillis, openForMillis, anchorOffsetMillis);
    }

    public long openingWarningMillis() {
        return openingWarningMillis;
    }

    /** Closing warning offsets, largest first. */
    public List<Long> closingWarningsMillis() {
        return closingWarningsMillis;
    }

    public RotationSelector.Shape shape() {
        return shape;
    }

    public int historyLimit() {
        return historyLimit;
    }

    public ZoneId zone() {
        return zone;
    }

    public List<MarketOffer> offers() {
        return List.copyOf(offers.values());
    }

    public MarketOffer offer(final String id) {
        return offers.get(id);
    }

    public List<String> auctionTimes() {
        return auctionTimes;
    }

    public int lotsMin() {
        return lotsMin;
    }

    public int lotsMax() {
        return lotsMax;
    }

    public long lotDurationMillis() {
        return lotDurationMillis;
    }

    public long lotBreakMillis() {
        return lotBreakMillis;
    }

    public long antiSnipeThresholdMillis() {
        return antiSnipeThresholdMillis;
    }

    public long antiSnipeExtensionMillis() {
        return antiSnipeExtensionMillis;
    }

    public long antiSnipeCapMillis() {
        return antiSnipeCapMillis;
    }

    public List<BidButton> bidButtons() {
        return bidButtons;
    }

    public List<AuctionLotDef> lots() {
        return List.copyOf(lots.values());
    }

    public AuctionLotDef lot(final String id) {
        return lots.get(id);
    }
}
