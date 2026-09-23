package com.coremc.core.island;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Click controller for the island top windows: the category picker
 * opens a leaderboard board, a board's back button returns to the
 * picker, close closes. Everything else is inert — these windows only
 * ever show ranks, nothing can be taken out of them.
 */
public final class IsTopListener implements Listener {

    private final IsTopGui picker;
    private final IsTopBoardGui boards;

    public IsTopListener(final IsTopGui picker, final IsTopBoardGui boards) {
        this.picker = picker;
        this.boards = boards;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof IsTopHolder)
                && !(event.getView().getTopInventory().getHolder() instanceof IsTopBoardHolder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final Inventory top = event.getView().getTopInventory();
        final Inventory clicked = event.getClickedInventory();
        if (clicked == top) {
            event.setCancelled(true);
            final int slot = event.getSlot();
            if (top.getHolder() instanceof IsTopHolder) {
                handlePickerClick(player, slot);
            } else if (top.getHolder() instanceof IsTopBoardHolder) {
                handleBoardClick(player, slot);
            }
        } else if (clicked != null && event.getClick().isShiftClick()) {
            event.setCancelled(true);
        }
    }

    private void handlePickerClick(final Player player, final int slot) {
        final IslandTop.Category category = IsTopLayout.pickerCategoryAt(slot);
        if (category != null) {
            click(player);
            boards.open(player, category);
        } else if (slot == IsTopLayout.PICKER_CLOSE) {
            click(player);
            player.closeInventory();
        }
    }

    private void handleBoardClick(final Player player, final int slot) {
        if (slot == IsTopLayout.BOARD_BACK) {
            click(player);
            picker.open(player);
        } else if (slot == IsTopLayout.BOARD_CLOSE) {
            click(player);
            player.closeInventory();
        }
    }

    private void click(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        final Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof IsTopHolder)
                && !(top.getHolder() instanceof IsTopBoardHolder)) {
            return;
        }
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
