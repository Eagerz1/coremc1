package com.coremc.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coremc.core.reward.RewardType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * black-market.yml validation: the SHIPPED file must always parse
 * (so a typo fails the build, not a live server), and every class of
 * broken config must be refused loudly with a clear message.
 */
class BlackMarketConfigTest {

    private static final Set<String> KEYS = Set.of("vote", "river", "sky", "crimson", "boost");
    private static final Set<String> BOXES = Set.of("core", "monthly", "seasonal");

    private static BlackMarketConfig parse(final String yaml) {
        final BlackMarketConfig config = new BlackMarketConfig(null);
        final YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (final org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
        config.parse(parsed, KEYS, BOXES);
        return config;
    }

    private static BlackMarketConfig bundled() throws IOException {
        return parse(Files.readString(Path.of("src/main/resources/black-market.yml"),
                StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // the shipped file
    // ------------------------------------------------------------------

    @Test
    void bundledConfigParsesWithTheSpecDefaults() throws IOException {
        final BlackMarketConfig config = bundled();
        assertTrue(config.enabled());
        assertEquals(6 * 60 * 60_000L, config.schedule().openEveryMillis(), "opens every 6h");
        assertEquals(30 * 60_000L, config.schedule().openForMillis(), "open for 30m");
        assertEquals(10 * 60_000L, config.openingWarningMillis());
        assertEquals(List.of(5 * 60_000L, 60_000L), config.closingWarningsMillis());
        assertEquals(2, config.shape().commonMin());
        assertEquals(3, config.shape().commonMax());
        assertEquals(2, config.shape().rare());
        assertEquals(1, config.shape().epic());
        assertTrue(config.shape().legendaryChance() > 0 && config.shape().legendaryChance() < 1);
    }

    @Test
    void bundledPoolsSatisfyTheSelectionShape() throws IOException {
        final BlackMarketConfig config = bundled();
        long commons = config.offers().stream()
                .filter(offer -> offer.pool() == MarketRarity.COMMON).count();
        long rares = config.offers().stream()
                .filter(offer -> offer.pool() == MarketRarity.RARE).count();
        assertTrue(commons >= config.shape().commonMax(), "commons cover the max draw");
        assertTrue(rares >= config.shape().rare());
        // the 5-7 offer promise: min shape 2+2+1 = 5, max 3+2+1+1+1+1 = 7-ish
        assertTrue(config.shape().commonMin() + config.shape().rare()
                + config.shape().epic() >= 5);
    }

    @Test
    void bundledCreditPricesSitOnCosmeticPoolsOnly() throws IOException {
        for (final MarketOffer offer : bundled().offers()) {
            if (offer.cost().chargesCredits()) {
                assertTrue(offer.pool() == MarketRarity.COSMETIC
                        || offer.pool() == MarketRarity.SEASONAL,
                        offer.id() + " charges Credits outside the cosmetic pools");
            }
        }
    }

    @Test
    void bundledOffersNeverSellRawMoney() throws IOException {
        for (final MarketOffer offer : bundled().offers()) {
            assertTrue(offer.reward().type() != RewardType.MONEY,
                    offer.id() + ": the market is a sink, it never sells raw money");
            assertTrue(offer.stock() > 0 && offer.perPlayer() > 0);
        }
        for (final AuctionLotDef lot : bundled().lots()) {
            assertTrue(lot.reward().type() != RewardType.MONEY, lot.id());
        }
    }

    @Test
    void bundledDiscoveryIsExplicitAndRare() throws IOException {
        final List<MarketOffer> discoveries = bundled().offers().stream()
                .filter(MarketOffer::discovery).toList();
        assertEquals(1, discoveries.size(), "only the seasonal collectible counts as discovery");
        assertEquals("harvest-collectible", discoveries.get(0).id());
    }

    @Test
    void bundledAuctionIsFullyConfigured() throws IOException {
        final BlackMarketConfig config = bundled();
        assertEquals(List.of("13:00", "19:30"), config.auctionTimes());
        assertTrue(config.lots().size() >= config.lotsMin());
        assertEquals(10_000, config.antiSnipeThresholdMillis());
        assertEquals(10_000, config.antiSnipeExtensionMillis());
        assertEquals(60_000, config.antiSnipeCapMillis());
        assertEquals(4, config.bidButtons().size());
        assertEquals("+$10,000", config.bidButtons().get(0).label());
        assertEquals("+10%", config.bidButtons().get(3).label());
        for (final AuctionLotDef lot : config.lots()) {
            assertTrue(lot.startingBid() > 0 && lot.minIncrement() > 0, lot.id());
            assertNotNull(lot.rarity(), lot.id());
        }
    }

    // ------------------------------------------------------------------
    // validation is loud
    // ------------------------------------------------------------------

    private static final String VALID = """
            schedule:
              open-every-minutes: 360
              open-for-minutes: 30
            selection:
              common-min: 1
              common-max: 1
              rare: 0
              epic: 0
              legendary-chance: 0
              cosmetic-chance: 0
              seasonal-chance: 0
            offers:
              bait:
                pool: common
                reward:
                  type: item
                  id: special_bait
                  material: TROPICAL_FISH
                  display: "&bSpecial Bait"
                  amount: 2
                price:
                  money-min: 50000
                  money-max: 90000
                stock: 8
                per-player: 2
            auction:
              times: ["19:30"]
              lots-per-session-min: 1
              lots-per-session-max: 1
              lot-duration-seconds: 60
              bid-buttons: ["add:10000", "percent:10"]
              lots:
                core-lot:
                  rarity: epic
                  reward:
                    type: item
                    id: generator_core
                    material: LODESTONE
                    display: "&6Generator Core"
                  starting-bid: 250000
                  min-increment: 25000
            """;

    private static void refuses(final String yaml, final String needle) {
        final IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> parse(yaml));
        assertTrue(error.getMessage().contains(needle),
                "expected '" + needle + "' in: " + error.getMessage());
    }

    @Test
    void minimalValidConfigParses() {
        final BlackMarketConfig config = parse(VALID);
        assertTrue(config.enabled());
        assertEquals(1, config.offers().size());
        assertEquals(1, config.lots().size());
    }

    @Test
    void unsafeSchedulesAreRefused() {
        refuses(VALID.replace("open-for-minutes: 30", "open-for-minutes: 360"), "schedule");
        refuses(VALID.replace("open-every-minutes: 360", "open-every-minutes: 0"), "schedule");
    }

    @Test
    void badPricesStockAndLimitsAreRefused() {
        refuses(VALID.replace("money-min: 50000", "money-min: 90001"), "band");
        refuses(VALID.replace("money-min: 50000", "money-min: -5"), "negative");
        refuses(VALID.replace("stock: 8", "stock: 0"), "stock");
        refuses(VALID.replace("per-player: 2", "per-player: -1"), "per-player");
        refuses(VALID.replace("money-min: 50000\n      money-max: 90000", "{}"), "price");
    }

    @Test
    void creditsOutsideCosmeticPoolsAreRefused() {
        refuses(VALID.replace("money-min: 50000\n      money-max: 90000",
                "credits: 100"), "cosmetics-only");
    }

    @Test
    void badRewardsAreRefused() {
        refuses(VALID.replace("material: TROPICAL_FISH", "material: NOT_A_THING"), "material");
        refuses(VALID.replace("type: item\n      id: special_bait",
                "type: key\n      id: nokey"), "unknown key");
        refuses(VALID.replace("pool: common", "pool: shiny"), "pool");
    }

    @Test
    void impossiblePoolCountsAreRefused() {
        refuses(VALID.replace("common-min: 1\n  common-max: 1",
                "common-min: 2\n  common-max: 2"), "only 1 configured");
        refuses(VALID.replace("legendary-chance: 0", "legendary-chance: 0.5"),
                "no LEGENDARY offers");
    }

    @Test
    void malformedRequirementsAreRefused() {
        refuses(VALID.replace("stock: 8", "stock: 8\n    requirements: [\":oops\"]"),
                "requirement");
    }

    @Test
    void brokenAuctionSectionsAreRefused() {
        refuses(VALID.replace("times: [\"19:30\"]", "times: [\"25:99\"]"), "HH:mm");
        refuses(VALID.replace("starting-bid: 250000", "starting-bid: 0"), "starting bid");
        refuses(VALID.replace("lots-per-session-min: 1", "lots-per-session-min: 5"),
                "exceeds");
        refuses(VALID.replace("bid-buttons: [\"add:10000\", \"percent:10\"]",
                "bid-buttons: [\"percent:500\"]"), "button");
        refuses(VALID.replace("lot-duration-seconds: 60", "lot-duration-seconds: 2"),
                "lot-duration");
    }

    @Test
    void badTimezonesAreRefused() {
        refuses(VALID.replace("schedule:", "schedule:\n  timezone: Mars/Olympus"),
                "timezone");
    }

    @Test
    void disabledConfigIsSafe() {
        final BlackMarketConfig config = BlackMarketConfig.disabled();
        assertFalse(config.enabled());
        assertTrue(config.offers().isEmpty());
        assertTrue(config.lots().isEmpty());
    }
}
