package com.coremc.core.season;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Protects and routes Season Journey GUI clicks. */
public final class SeasonJourneyListener implements Listener {

    private final SeasonJourneyGui gui;
    private final SeasonJourneyService journey;

    public SeasonJourneyListener(final SeasonJourneyGui gui, final SeasonJourneyService journey) {
        this.gui = gui;
        this.journey = journey;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SeasonJourneyMenu menu)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            event.setCancelled(true);
            handle(menu, player, event.getSlot(), event.getClick().isRightClick());
        } else if (event.getClickedInventory() != null && event.getClick().isShiftClick()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SeasonJourneyMenu)) {
            return;
        }
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handle(final SeasonJourneyMenu menu, final Player player, final int slot, final boolean rightClick) {
        if (slot == SeasonJourneyGui.SLOT_CLOSE) {
            player.closeInventory();
            click(player);
            return;
        }
        if (slot == SeasonJourneyGui.SLOT_PREV) {
            gui.open(player, menu.page() - 1);
            click(player);
            return;
        }
        if (slot == SeasonJourneyGui.SLOT_NEXT) {
            gui.open(player, menu.page() + 1);
            click(player);
            return;
        }
        if (slot == SeasonJourneyGui.SLOT_QUESTS) {
            player.closeInventory(); player.performCommand("quests"); click(player); return;
        }
        if (slot == SeasonJourneyGui.SLOT_EVENTS) {
            player.closeInventory(); player.performCommand("coreevent status"); click(player); return;
        }
        if (slot == SeasonJourneyGui.SLOT_ISLAND) {
            player.closeInventory(); player.performCommand("is upgrades"); click(player); return;
        }
        if (slot == SeasonJourneyGui.SLOT_HELP) {
            player.closeInventory(); player.performCommand("help season-journey"); click(player); return;
        }
        final int level = gui.levelAt(menu.page(), slot);
        if (level > 0) {
            journey.claim(player, level, rightClick ? "premium" : "free");
            gui.open(player, menu.page());
            click(player);
        }
    }

    private void click(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
}
