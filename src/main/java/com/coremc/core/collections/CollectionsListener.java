package com.coremc.core.collections;

import com.coremc.core.config.MessageService;
import com.coremc.core.island.IslandGui;
import com.coremc.core.progress.reward.RewardService;
import com.coremc.core.util.GuiGrid;
import java.util.List;
import java.util.Map;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Click controller for the Collection menus: cancels every
 * interaction with the window, then routes the click to the right
 * view or to the same claim flow the command uses, so the rules and
 * messages never fork.
 */
public final class CollectionsListener implements Listener {

    private final CollectionConfig config;
    private final CollectionService collections;
    private final CollectionsGui gui;
    private final RewardService rewards;
    private final MessageService messages;
    private final IslandGui islandGui;

    public CollectionsListener(final CollectionConfig config, final CollectionService collections,
                               final CollectionsGui gui, final RewardService rewards,
                               final MessageService messages, final IslandGui islandGui) {
        this.config = config;
        this.collections = collections;
        this.gui = gui;
        this.rewards = rewards;
        this.messages = messages;
        this.islandGui = islandGui;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CollectionsHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final Inventory top = event.getView().getTopInventory();
        final Inventory clicked = event.getClickedInventory();
        if (clicked == top) {
            event.setCancelled(true);
            route(player, holder, event.getRawSlot());
        } else if (clicked != null && event.getClick().isShiftClick()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CollectionsHolder)) {
            return;
        }
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void route(final Player player, final CollectionsHolder holder, final int slot) {
        if (slot == CollectionsLayout.CLOSE) {
            player.closeInventory();
            return;
        }
        switch (holder.view()) {
            case ROOT -> root(player, slot);
            case CATEGORY -> category(player, holder, slot);
            case ENTRY -> entry(player, holder, slot);
            case RECIPES -> paged(player, holder, slot, config.recipes().size(), true);
            case PENDING -> pending(player, holder, slot);
            default -> { }
        }
    }

    private void root(final Player player, final int slot) {
        if (slot == CollectionsLayout.BACK) {
            if (islandGui != null) {
                islandGui.openIslandMenu(player);
            } else {
                player.closeInventory();
            }
            return;
        }
        if (slot == CollectionsLayout.EXTRA) {
            gui.openRecipes(player, 0);
            click(player);
            return;
        }
        if (slot == CollectionsLayout.PENDING) {
            gui.openPending(player, 0);
            click(player);
            return;
        }
        final int index = GuiGrid.indexAt(slot);
        final List<CollectionCategory> categories = config.categories();
        if (index >= 0 && index < categories.size()) {
            gui.openCategory(player, categories.get(index), 0);
            click(player);
        }
    }

    private void category(final Player player, final CollectionsHolder holder, final int slot) {
        if (slot == CollectionsLayout.BACK) {
            gui.openRoot(player);
            return;
        }
        if (slot == CollectionsLayout.EXTRA) {
            gui.openPending(player, 0);
            click(player);
            return;
        }
        final List<CollectionEntry> entries = config.byCategory(holder.category());
        if (slot == CollectionsLayout.PREVIOUS && holder.page() > 0) {
            gui.openCategory(player, holder.category(), holder.page() - 1);
            return;
        }
        if (slot == CollectionsLayout.NEXT
                && holder.page() + 1 < GuiGrid.pages(entries.size())) {
            gui.openCategory(player, holder.category(), holder.page() + 1);
            return;
        }
        final int index = GuiGrid.indexAt(slot);
        if (index < 0) {
            return;
        }
        final int entryIndex = GuiGrid.offset(holder.page(), GuiGrid.PER_PAGE) + index;
        if (entryIndex >= entries.size()) {
            return;
        }
        final CollectionEntry entry = entries.get(entryIndex);
        if (!collections.discovered(player.getUniqueId(), entry)) {
            deny(player);
            return;
        }
        gui.openEntry(player, entry);
        click(player);
    }

    private void entry(final Player player, final CollectionsHolder holder, final int slot) {
        final CollectionEntry entry = config.byId(holder.entryId());
        if (entry == null) {
            gui.openRoot(player);
            return;
        }
        if (slot == CollectionsLayout.BACK) {
            gui.openCategory(player, entry.category(), 0);
            return;
        }
        final int tierIndex = CollectionsLayout.tierIndexAt(slot);
        if (tierIndex < 0 || tierIndex >= entry.tiers()) {
            return;
        }
        final ClaimResult result = collections.claim(player.getUniqueId(), entry.id(),
                tierIndex + 1);
        feedback(player, result, entry, tierIndex + 1);
        gui.openEntry(player, entry);
    }

    private void paged(final Player player, final CollectionsHolder holder, final int slot,
                       final int total, final boolean recipes) {
        if (slot == CollectionsLayout.BACK) {
            gui.openRoot(player);
            return;
        }
        if (slot == CollectionsLayout.PREVIOUS && holder.page() > 0) {
            if (recipes) {
                gui.openRecipes(player, holder.page() - 1);
            }
            return;
        }
        if (slot == CollectionsLayout.NEXT && holder.page() + 1 < GuiGrid.pages(total) && recipes) {
            gui.openRecipes(player, holder.page() + 1);
        }
    }

    private void pending(final Player player, final CollectionsHolder holder, final int slot) {
        if (slot == CollectionsLayout.BACK) {
            gui.openRoot(player);
            return;
        }
        final int held = rewards == null ? 0 : rewards.pending().count(player.getUniqueId());
        if (slot == CollectionsLayout.PREVIOUS && holder.page() > 0) {
            gui.openPending(player, holder.page() - 1);
            return;
        }
        if (slot == CollectionsLayout.NEXT && holder.page() + 1 < GuiGrid.pages(held)) {
            gui.openPending(player, holder.page() + 1);
            return;
        }
        if (GuiGrid.indexAt(slot) < 0) {
            return;
        }
        final int delivered = rewards == null ? 0 : rewards.deliverPending(player);
        if (messages != null) {
            if (delivered > 0) {
                messages.sendPrefixed(player, "collections.pending-collected",
                        Map.of("amount", String.valueOf(delivered)));
            } else {
                messages.sendPrefixed(player, "collections.pending-blocked");
            }
        }
        if (delivered > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f);
        }
        gui.openPending(player, holder.page());
    }

    private void feedback(final Player player, final ClaimResult result,
                          final CollectionEntry entry, final int tier) {
        if (messages == null) {
            return;
        }
        final Map<String, String> placeholders = Map.of(
                "collection", entry.display(),
                "tier", String.valueOf(tier));
        switch (result) {
            case CLAIMED -> {
                messages.sendPrefixed(player, "collections.claimed", placeholders);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            }
            case PENDING -> messages.sendPrefixed(player, "collections.claim-held", placeholders);
            case ALREADY_CLAIMED ->
                    messages.sendPrefixed(player, "collections.claim-already", placeholders);
            case NOT_REACHED -> {
                messages.sendPrefixed(player, "collections.claim-locked", placeholders);
                deny(player);
            }
            case NOTHING_TO_CLAIM -> { /* automatic-only tiers say so in the lore */ }
            default -> messages.sendPrefixed(player, "collections.unknown-collection",
                    Map.of("id", entry.id()));
        }
    }

    private void click(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    private void deny(final Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
    }
}
