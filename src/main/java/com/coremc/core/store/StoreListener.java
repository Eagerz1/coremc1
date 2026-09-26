package com.coremc.core.store;

import com.coremc.core.crate.CrateConfig;
import com.coremc.core.crate.CrateDef;
import com.coremc.core.crate.KeyDef;
import com.coremc.core.lootbox.LootboxConfig;
import com.coremc.core.lootbox.LootboxDef;
import com.coremc.core.reward.RewardGrant;
import com.coremc.core.reward.RewardType;
import java.util.List;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Click controller for the /store menus and the odds previews.
 * Cancels anything that would move items, routes category clicks,
 * and sends purchases through the one {@link PurchaseService} flow.
 * Left-click buys, right-click previews odds.
 */
public final class StoreListener implements Listener {

    private final StoreGui gui;
    private final PreviewGui previews;
    private final PurchaseService purchases;
    private final CrateConfig crates;
    private final LootboxConfig lootboxes;
    private final BundleConfig bundles;

    public StoreListener(final StoreGui gui, final PreviewGui previews,
                         final PurchaseService purchases, final CrateConfig crates,
                         final LootboxConfig lootboxes, final BundleConfig bundles) {
        this.gui = gui;
        this.previews = previews;
        this.purchases = purchases;
        this.crates = crates;
        this.lootboxes = lootboxes;
        this.bundles = bundles;
    }

    // ------------------------------------------------------------------
    // store pages
    // ------------------------------------------------------------------

    @EventHandler
    public void onStoreClick(final InventoryClickEvent event) {
        final Object holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof StoreHolder storeHolder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() != top) {
            if (event.getClick().isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        final int slot = event.getSlot();
        if (slot == StoreLayout.CLOSE_SLOT) {
            clickSound(player);
            player.closeInventory();
            return;
        }
        if (slot == StoreLayout.BACK_SLOT && storeHolder.page() != StoreHolder.Page.ROOT) {
            clickSound(player);
            gui.openRoot(player);
            return;
        }
        if (storeHolder.page() == StoreHolder.Page.ROOT) {
            handleRootClick(player, slot);
            return;
        }
        final StoreHolder.Entry entry = storeHolder.entryAt(slot);
        if (entry == null) {
            return;
        }
        if (event.getClick().isRightClick()) {
            openPreview(player, storeHolder.page(), entry);
            return;
        }
        buy(player, storeHolder.page(), entry);
    }

    @EventHandler
    public void onPreviewClick(final InventoryClickEvent event) {
        final Object holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof PreviewHolder previewHolder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.getClick().isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (event.getSlot() == StoreLayout.CLOSE_SLOT) {
            clickSound(player);
            player.closeInventory();
        } else if (event.getSlot() == StoreLayout.BACK_SLOT) {
            clickSound(player);
            openPage(player, previewHolder.backPage());
        }
    }

    @EventHandler
    public void onDrag(final InventoryDragEvent event) {
        final Object holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof StoreHolder) && !(holder instanceof PreviewHolder)) {
            return;
        }
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    private void handleRootClick(final Player player, final int slot) {
        switch (slot) {
            case StoreLayout.ROOT_KEYS_SLOT -> {
                clickSound(player);
                gui.openKeys(player);
            }
            case StoreLayout.ROOT_LOOTBOXES_SLOT -> {
                clickSound(player);
                gui.openLootboxes(player);
            }
            case StoreLayout.ROOT_BUNDLES_SLOT -> {
                clickSound(player);
                gui.openBundles(player);
            }
            default -> {
                // decorative
            }
        }
    }

    private void openPage(final Player player, final StoreHolder.Page page) {
        switch (page) {
            case KEYS -> gui.openKeys(player);
            case LOOTBOXES -> gui.openLootboxes(player);
            case BUNDLES -> gui.openBundles(player);
            default -> gui.openRoot(player);
        }
    }

    private void openPreview(final Player player, final StoreHolder.Page page,
                             final StoreHolder.Entry entry) {
        clickSound(player);
        if (entry.kind() == StoreHolder.Kind.KEY) {
            final CrateDef crate = crates.crateForKey(entry.id());
            if (crate != null) {
                previews.openCrate(player, crate, page);
            }
        } else if (entry.kind() == StoreHolder.Kind.LOOTBOX) {
            final LootboxDef box = lootboxes.byId(entry.id());
            if (box != null) {
                previews.openLootbox(player, box, page);
            }
        }
    }

    private void buy(final Player player, final StoreHolder.Page page,
                     final StoreHolder.Entry entry) {
        boolean bought = false;
        switch (entry.kind()) {
            case KEY -> {
                final KeyDef key = crates.key(entry.id());
                if (key != null) {
                    bought = purchases.purchase(player, key.price(), plain(key.name()),
                            List.of(new RewardGrant(RewardType.KEY, key.id(), 1, key.name())));
                }
            }
            case LOOTBOX -> {
                final LootboxDef box = lootboxes.byId(entry.id());
                if (box != null) {
                    bought = purchases.purchase(player, box.price(), plain(box.name()),
                            List.of(new RewardGrant(RewardType.LOOTBOX, box.id(), 1, box.name())));
                }
            }
            case BUNDLE -> {
                final BundleDef bundle = bundles.byId(entry.id());
                if (bundle != null) {
                    bought = purchases.purchase(player, bundle.price(), plain(bundle.name()),
                            bundle.expand());
                }
            }
            default -> {
                // nothing
            }
        }
        // re-render so balances and ✔/✖ price markers stay live (the
        // failure sound + shortfall message come from PurchaseService)
        if (bought) {
            openPage(player, page);
        }
    }

    /** Configured display names minus colour codes, for chat lines. */
    private static String plain(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '&' && index + 1 < text.length()) {
                index++;
                continue;
            }
            out.append(text.charAt(index));
        }
        return out.toString();
    }

    private void clickSound(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
}
