package com.coremc.core.market;

import com.coremc.core.reward.RewardDef;
import com.coremc.core.store.StoreLayout;
import com.coremc.core.util.ColorUtil;
import com.coremc.core.util.GuiItems;
import com.coremc.core.util.GuiText;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Renders /blackmarket in the CoreMC design language: smallcaps,
 * {@code &} codes only, rarity colours, live green ✔ / red ✖ price
 * and requirement markers, short lore with clean spacing.
 *
 * <p>Three pages: the CLOSED board (status, next opening, what the
 * market is, recent sales), the OFFERS board (the live rotation) and
 * the AUCTION floor (current lot + bid buttons).</p>
 */
public final class MarketGui {

    private static final String TITLE_CLOSED = "&3&lCOREMC &8— &5Black Market";
    private static final String TITLE_OPEN = "&3&lCOREMC &8— &5Black Market";
    private static final String TITLE_AUCTION = "&3&lCOREMC &8— &5Dark Auction";

    private final MarketService market;
    private final DarkAuctionService auction;

    public MarketGui(final MarketService market, final DarkAuctionService auction) {
        this.market = market;
        this.auction = auction;
    }

    /** Opens whichever board matches the market's current state. */
    public void open(final Player player, final long now) {
        if (market.state().isOpen(now)) {
            openOffers(player, now);
        } else {
            openClosed(player, now);
        }
    }

    // ------------------------------------------------------------------
    // closed board
    // ------------------------------------------------------------------

    public void openClosed(final Player player, final long now) {
        final MarketHolder holder = new MarketHolder(MarketHolder.Page.CLOSED, null);
        final Inventory inventory = Bukkit.createInventory(holder, StoreLayout.SIZE,
                ColorUtil.colorize(TITLE_CLOSED));
        holder.inventory(inventory);

        inventory.setItem(20, GuiItems.item(Material.CLOCK,
                "&c&l" + GuiText.caps("Market Closed"),
                GuiText.value("Next opening", "&e",
                        GuiText.seconds(market.secondsUntilOpen(now))),
                GuiText.blank(),
                "&7" + GuiText.caps("The doors open every")
                        + " &f" + GuiText.seconds(market.config().schedule()
                                .openEveryMillis() / 1000),
                "&7" + GuiText.caps("and stay open for")
                        + " &f" + GuiText.seconds(market.config().schedule()
                                .openForMillis() / 1000) + "&7."));
        inventory.setItem(22, GuiItems.item(Material.SCULK_SHRIEKER,
                "&5&l" + GuiText.caps("The Black Market"),
                "&7" + GuiText.caps("A rotating stall of rare"),
                "&7" + GuiText.caps("progression materials, keys,"),
                "&7" + GuiText.caps("cosmetics and collectibles."),
                GuiText.blank(),
                "&7" + GuiText.caps("Limited GLOBAL stock -"),
                "&7" + GuiText.caps("first come, first served.")));
        inventory.setItem(24, recentSalesItem());
        inventory.setItem(StoreLayout.CLOSE_SLOT, GuiItems.close());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }

    private ItemStack recentSalesItem() {
        final List<String> lore = new ArrayList<>();
        final List<MarketState.Sale> sales = market.state().sales();
        if (sales.isEmpty()) {
            lore.add("&7" + GuiText.caps("Nothing sold yet."));
        } else {
            for (int index = sales.size() - 1;
                    index >= 0 && lore.size() < 5; index--) {
                final MarketState.Sale sale = sales.get(index);
                final MarketOffer offer = market.config().offer(sale.offerId());
                final String item = offer == null ? sale.offerId()
                        : MarketService.plain(offer.reward().display());
                lore.add("&8- &7" + GuiText.caps(item) + " &8(&f"
                        + sale.price().text() + "&8)");
            }
        }
        return GuiItems.item(Material.WRITABLE_BOOK,
                "&e&l" + GuiText.caps("Recent Sales"), lore);
    }

    // ------------------------------------------------------------------
    // offers board
    // ------------------------------------------------------------------

    public void openOffers(final Player player, final long now) {
        final MarketHolder holder = new MarketHolder(MarketHolder.Page.OFFERS,
                market.state().rotationId());
        final Inventory inventory = Bukkit.createInventory(holder, StoreLayout.SIZE,
                ColorUtil.colorize(TITLE_OPEN));
        holder.inventory(inventory);

        inventory.setItem(StoreLayout.CREDITS_SLOT, GuiItems.item(Material.CLOCK,
                "&a&l" + GuiText.caps("Market Open"),
                GuiText.value("Closes in", "&e",
                        GuiText.seconds(market.secondsUntilClose(now))),
                GuiText.blank(),
                "&7" + GuiText.caps("Stock is global - when it is"),
                "&7" + GuiText.caps("gone, it is gone.")));

        final List<MarketState.ActiveOffer> active = market.state().activeOffers();
        final int[] slots = StoreLayout.contentSlots(active.size());
        for (int index = 0; index < slots.length; index++) {
            final MarketState.ActiveOffer entry = active.get(index);
            final MarketOffer offer = market.config().offer(entry.offerId());
            if (offer == null) {
                continue;
            }
            inventory.setItem(slots[index], offerItem(player.getUniqueId(), offer, entry));
            holder.putOffer(slots[index], offer.id());
        }
        inventory.setItem(StoreLayout.INFO_SLOT, recentSalesItem());
        inventory.setItem(StoreLayout.CLOSE_SLOT, GuiItems.close());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }

    /** One offer entry — the spec's exact lore shape. */
    private ItemStack offerItem(final UUID viewer, final MarketOffer offer,
                                final MarketState.ActiveOffer entry) {
        final boolean affordable = market.bank().canAfford(viewer, entry.price());
        final boolean unlocked = market.requirements().allMet(viewer, offer);
        final int bought = market.state().purchased(offer.id(), viewer);
        final boolean limitLeft = bought < offer.perPlayer();
        final boolean soldOut = entry.stockLeft() <= 0;

        final List<String> lore = new ArrayList<>();
        for (final String line : offer.description()) {
            lore.add("&7" + GuiText.caps(line));
        }
        lore.add(GuiText.blank());
        lore.add(GuiText.line("Rarity", offer.pool().color(), offer.pool().display()));
        lore.add(GuiText.value("Stock", soldOut ? "&c" : "&f",
                entry.stockLeft() + "/" + entry.stockTotal()));
        lore.add(GuiText.value("Limit", "&f", offer.perPlayer() + " "
                + GuiText.caps("per player")
                + (bought > 0 ? " &8(" + bought + " " + GuiText.caps("bought") + ")" : "")));
        for (final MarketOffer.Requirement requirement : offer.requirements()) {
            final boolean met = market.requirements().met(viewer, requirement);
            lore.add((met ? "&a" : "&c") + GuiText.mark(met) + " &7"
                    + GuiText.caps(MarketRequirements.describe(requirement)));
        }
        lore.add(GuiText.blank());
        final String priceColor = affordable ? "&a" : "&c";
        lore.add("&7" + GuiText.caps("Price") + ": " + priceColor + entry.price().text()
                + " " + priceColor + GuiText.mark(affordable));
        lore.add(GuiText.blank());
        if (soldOut) {
            lore.add(GuiText.hint("Sold out"));
        } else if (!limitLeft) {
            lore.add(GuiText.hint("Limit reached"));
        } else if (!unlocked) {
            lore.add(GuiText.hint("Locked"));
        } else {
            lore.add(GuiText.click("Click to purchase"));
        }
        final ItemStack item = GuiItems.item(materialFor(offer.reward()),
                offer.pool().color() + "&l" + GuiText.caps(
                        MarketService.plain(offer.reward().display())), lore);
        return affordable && unlocked && limitLeft && !soldOut ? GuiItems.glow(item) : item;
    }

    // ------------------------------------------------------------------
    // auction floor
    // ------------------------------------------------------------------

    public void openAuction(final Player player, final long now) {
        final MarketHolder holder = new MarketHolder(MarketHolder.Page.AUCTION, null);
        final Inventory inventory = Bukkit.createInventory(holder, StoreLayout.SIZE,
                ColorUtil.colorize(TITLE_AUCTION));
        holder.inventory(inventory);

        final AuctionEngine engine = auction.engine();
        if (engine == null) {
            inventory.setItem(22, GuiItems.item(Material.CLOCK,
                    "&c&l" + GuiText.caps("No Auction Running"),
                    "&7" + GuiText.caps("The Dark Auction runs on a"),
                    "&7" + GuiText.caps("schedule - watch the chat"),
                    "&7" + GuiText.caps("for the call."),
                    GuiText.blank(),
                    "&7" + GuiText.caps("Bids are Money only.")));
        } else {
            final AuctionLotDef lot = engine.lot();
            final long current = engine.currentBid();
            final String bidderName = engine.highestBidder() == null ? "nobody"
                    : Bukkit.getOfflinePlayer(engine.highestBidder()).getName();
            inventory.setItem(13, GuiItems.glow(GuiItems.item(materialFor(lot.reward()),
                    lot.rarity().color() + "&l" + GuiText.caps(
                            MarketService.plain(lot.reward().display())),
                    loreForLot(lot, engine, now, bidderName))));
            final List<BlackMarketConfig.BidButton> buttons = market.config().bidButtons();
            final int[] slots = StoreLayout.contentSlots(buttons.size() + 1);
            for (int index = 0; index < buttons.size(); index++) {
                final long next = nextBidFor(buttons.get(index), engine);
                inventory.setItem(slots[index] + 9, GuiItems.item(Material.GOLD_NUGGET,
                        "&e&l" + buttons.get(index).label(),
                        GuiText.value("Bids", "&f", GuiText.money(next)),
                        GuiText.blank(),
                        GuiText.click("Click to bid")));
                holder.putBidButton(slots[index] + 9, index);
            }
            inventory.setItem(slots[buttons.size()] + 9, GuiItems.item(Material.OAK_SIGN,
                    "&e&l" + GuiText.caps("Custom Bid"),
                    "&7" + GuiText.caps("Use the command:"),
                    "&f/blackmarket bid <amount>",
                    GuiText.blank(),
                    GuiText.value("Minimum", "&f", GuiText.money(engine.minNextBid()))));
        }
        inventory.setItem(StoreLayout.CLOSE_SLOT, GuiItems.close());
        GuiItems.fillEmpty(inventory);
        player.openInventory(inventory);
    }

    private List<String> loreForLot(final AuctionLotDef lot, final AuctionEngine engine,
                                    final long now, final String bidderName) {
        final List<String> lore = new ArrayList<>();
        for (final String line : lot.description()) {
            lore.add("&7" + GuiText.caps(line));
        }
        lore.add(GuiText.blank());
        lore.add(GuiText.line("Rarity", lot.rarity().color(), lot.rarity().display()));
        lore.add(GuiText.value("Current bid", "&f", engine.currentBid() == 0
                ? GuiText.money(lot.startingBid()) + " " + GuiText.caps("(starting)")
                : GuiText.money(engine.currentBid())));
        lore.add(GuiText.value("Highest bidder", "&f", bidderName == null ? "?" : bidderName));
        lore.add(GuiText.value("Next valid bid", "&e", GuiText.money(engine.minNextBid())));
        lore.add(GuiText.value("Time left", "&e",
                GuiText.seconds(engine.remainingMillis(now) / 1000)));
        if (auction.lotsRemaining() > 0) {
            lore.add(GuiText.value("Lots after this", "&f",
                    String.valueOf(auction.lotsRemaining())));
        }
        return lore;
    }

    /** The amount a configured button would bid right now. */
    public static long nextBidFor(final BlackMarketConfig.BidButton button,
                                  final AuctionEngine engine) {
        final long base = engine.currentBid() == 0
                ? engine.lot().startingBid() : engine.currentBid();
        final long raised = button.percent()
                ? base + Math.max(1, base * button.value() / 100)
                : base + button.value();
        return Math.max(engine.minNextBid(), raised);
    }

    /** The display material for a reward (PDC item, key, box, ...). */
    private static Material materialFor(final RewardDef reward) {
        return switch (reward.type()) {
            case ITEM -> reward.material() == null ? Material.PAPER : reward.material();
            case KEY -> Material.TRIPWIRE_HOOK;
            case LOOTBOX -> Material.ENDER_CHEST;
            case MONEY -> Material.GOLD_INGOT;
            case SKY_TOKENS -> Material.PRISMARINE_CRYSTALS;
            case CREDITS -> Material.SUNFLOWER;
            case COMMAND -> Material.PAPER;
        };
    }
}
