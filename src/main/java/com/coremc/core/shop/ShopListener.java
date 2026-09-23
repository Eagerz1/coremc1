package com.coremc.core.shop;

import com.coremc.core.config.MessageService;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Click controller for the shop GUIs. Cancels every interaction that
 * would touch a shop window (so items can never be taken out of or
 * moved into the shop), then routes the click:
 *
 * <ul>
 *   <li>Root menu: section slots open the section, close closes.</li>
 *   <li>Section: left-click buys 1, shift-left buys 16, right-click
 *       sells 1, shift-right sells all; nav slots page/close.</li>
 * </ul>
 *
 * All player feedback goes through MessageService and therefore
 * carries the {@code COREMC >>>} prefix.
 */
public final class ShopListener implements Listener {

    private static final int BUY_SHIFT_AMOUNT = 16;

    private final ShopConfig config;
    private final EconomyService economy;
    private final ShopGui gui;
    private final MessageService messages;
    /** Rank sell bonus (Core and up earn a multiple on every sale). */
    private final com.coremc.core.rank.RankService ranks;

    public ShopListener(final ShopConfig config, final EconomyService economy, final ShopGui gui,
                        final MessageService messages,
                        final com.coremc.core.rank.RankService ranks) {
        this.config = config;
        this.economy = economy;
        this.gui = gui;
        this.messages = messages;
        this.ranks = ranks;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopMenu menu)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        final Inventory top = event.getView().getTopInventory();
        final Inventory clicked = event.getClickedInventory();
        if (clicked == top) {
            // Never let shop contents be picked up, swapped or hot-keyed away.
            event.setCancelled(true);
            handleClick(menu, event, player);
        } else if (clicked != null && event.getClick().isShiftClick()) {
            // Never let player items be moved into the shop window.
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopMenu)) {
            return;
        }
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleClick(final ShopMenu menu, final InventoryClickEvent event, final Player player) {
        if (menu.kind() == ShopMenu.Kind.ROOT) {
            handleRootClick(menu, event, player);
        } else {
            handleSectionClick(menu, event, player);
        }
    }

    private void handleRootClick(final ShopMenu menu, final InventoryClickEvent event, final Player player) {
        final int slot = event.getSlot();
        if (slot == ShopLayout.ROOT_CLOSE_SLOT) {
            player.closeInventory();
            clickSound(player);
            return;
        }
        final int ordinal = ShopLayout.sectionOrdinalForSlot(slot);
        if (ordinal >= 0 && ordinal < config.sections().size()) {
            clickSound(player);
            gui.openSection(player, config.sections().get(ordinal), 0);
        }
    }

    private void handleSectionClick(final ShopMenu menu, final InventoryClickEvent event, final Player player) {
        final int slot = event.getSlot();
        final ShopSection section = config.section(menu.sectionId());
        if (section == null) {
            player.closeInventory();
            return;
        }

        if (slot == ShopLayout.SLOT_BACK) {
            clickSound(player);
            gui.openRoot(player);
            return;
        }
        if (slot == ShopLayout.SLOT_CLOSE) {
            player.closeInventory();
            clickSound(player);
            return;
        }
        if (slot == ShopLayout.SLOT_PREVIOUS && menu.page() > 0) {
            clickSound(player);
            gui.openSection(player, section, menu.page() - 1);
            return;
        }
        if (slot == ShopLayout.SLOT_NEXT
                && menu.page() < ShopLayout.pageCount(section.itemCount()) - 1) {
            clickSound(player);
            gui.openSection(player, section, menu.page() + 1);
            return;
        }

        final int index = ShopLayout.itemIndexForSlot(section.itemCount(), menu.page(), slot);
        if (index >= 0) {
            final ShopItem item = section.item(index);
            if (event.getClick().isLeftClick()) {
                buy(player, item, event.getClick().isShiftClick() ? BUY_SHIFT_AMOUNT : 1);
            } else if (event.getClick().isRightClick()) {
                sell(player, item, event.getClick().isShiftClick());
            }
        }
    }

    // ------------------------------------------------------------------
    // Trading
    // ------------------------------------------------------------------

    private void buy(final Player player, final ShopItem item, final int amount) {
        if (!item.buyable()) {
            error(player, messages.get("shop.not-buyable"));
            return;
        }
        final double cost = Money.round(item.buyPrice() * amount);
        final double balance = economy.balance(player.getUniqueId());
        if (!economy.has(player.getUniqueId(), cost)) {
            error(player, messages.get("shop.need-coins", placeholders(item, amount)
                    .put("cost", money(cost))
                    .put("balance", money(balance))
                    .build()));
            return;
        }
        economy.withdraw(player.getUniqueId(), cost);

        final ItemStack stack = new ItemStack(item.material(), amount);
        final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        int dropped = 0;
        for (final ItemStack rest : leftover.values()) {
            dropped += rest.getAmount();
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }

        success(player, messages.get("shop.bought", placeholders(item, amount)
                .put("cost", money(cost))
                .put("balance", money(economy.balance(player.getUniqueId())))
                .build()));
        if (dropped > 0) {
            messages.sendPrefixed(player, "shop.dropped",
                    Map.of("amount", String.valueOf(dropped), "item", item.displayName()));
        }
    }

    private void sell(final Player player, final ShopItem item, final boolean all) {
        if (!item.sellable()) {
            error(player, messages.get("shop.not-sellable"));
            return;
        }
        final ItemStack[] contents = player.getInventory().getStorageContents();
        final int held = ShopTransactions.countSellable(contents, item.material());
        if (held == 0) {
            error(player, messages.get("shop.nothing-to-sell",
                    Map.of("item", item.displayName())));
            return;
        }
        final int amount = all ? held : 1;
        ShopTransactions.removeItems(contents, item.material(), amount);
        player.getInventory().setStorageContents(contents);

        // ranked players earn a multiple on every sale (Core x1.05 and up)
        final double credit = Money.round(
                item.sellPrice() * amount * (ranks == null ? 1.0 : ranks.multiplierOf(player.getUniqueId())));
        economy.deposit(player.getUniqueId(), credit);
        success(player, messages.get("shop.sold", placeholders(item, amount)
                .put("credit", money(credit))
                .put("balance", money(economy.balance(player.getUniqueId())))
                .build()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PlaceholderBuilder placeholders(final ShopItem item, final int amount) {
        return new PlaceholderBuilder()
                .put("item", item.displayName())
                .put("amount", String.valueOf(amount));
    }

    private String money(final double value) {
        return Money.format(value, config.currencySymbol());
    }

    private void success(final Player player, final String message) {
        player.sendMessage(messages.prefix() + message);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
    }

    private void error(final Player player, final String message) {
        player.sendMessage(messages.prefix() + message);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
    }

    private void clickSound(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }

    /** Small mutable builder so transaction messages stay readable. */
    private static final class PlaceholderBuilder {

        private final Map<String, String> values = new HashMap<>();

        PlaceholderBuilder put(final String key, final String value) {
            values.put(key, value);
            return this;
        }

        Map<String, String> build() {
            return values;
        }
    }
}
