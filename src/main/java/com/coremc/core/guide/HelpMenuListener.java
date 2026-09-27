package com.coremc.core.guide;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Protects and routes /help guide GUI clicks. */
public final class HelpMenuListener implements Listener {

    private final HelpGui gui;
    private final HelpConfig config;

    public HelpMenuListener(final HelpGui gui, final HelpConfig config) {
        this.gui = gui;
        this.config = config;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof HelpMenu menu)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            event.setCancelled(true);
            handle(menu, player, event.getSlot());
        } else if (event.getClickedInventory() != null && event.getClick().isShiftClick()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof HelpMenu)) {
            return;
        }
        for (final int raw : event.getRawSlots()) {
            if (raw < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handle(final HelpMenu menu, final Player player, final int slot) {
        if (slot == HelpGui.SLOT_CLOSE) {
            player.closeInventory();
            click(player);
            return;
        }
        if (slot == HelpGui.SLOT_BACK) {
            gui.openRoot(player);
            click(player);
            return;
        }
        if (menu.kind() == HelpMenu.Kind.ROOT) {
            final String category = gui.categoryAt(slot);
            if (category != null) {
                gui.openCategory(player, category);
                click(player);
            }
            return;
        }
        if (menu.kind() == HelpMenu.Kind.PAGE && slot == HelpGui.SLOT_SHORTCUT) {
            gui.runShortcut(player, config.category(menu.categoryId()));
            click(player);
        }
    }

    private void click(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
}
