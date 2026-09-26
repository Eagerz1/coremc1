package com.coremc.core.gens;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Click controller for the generator management window. The upgrade
 * and pick-up buttons re-resolve the live registration first (the
 * stack may have changed while the window was open) and every action
 * runs through {@link GeneratorService}, so the rules and the branded
 * messages are identical to the command paths.
 */
public final class GeneratorManageListener implements Listener {

    private final GeneratorService generators;
    private final GeneratorManageGui gui;

    public GeneratorManageListener(final GeneratorService generators,
                                   final GeneratorManageGui gui) {
        this.generators = generators;
        this.gui = gui;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GeneratorManageHolder holder)) {
            return;
        }
        event.setCancelled(true); // window items can never move
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        final int slot = event.getSlot();
        if (slot == GensLayout.MANAGE_CLOSE) {
            clickSound(player);
            player.closeInventory();
            return;
        }
        final GeneratorEntry entry = generators.entryByKey(holder.entryKey());
        if (entry == null) {
            player.closeInventory();
            return;
        }
        if (slot == GensLayout.MANAGE_UPGRADE) {
            clickSound(player);
            if (generators.upgrade(player, entry)) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
                final GeneratorEntry upgraded = generators.entryByKey(holder.entryKey());
                if (upgraded != null) {
                    gui.open(player, upgraded);
                } else {
                    player.closeInventory();
                }
            } else {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.9f);
                gui.open(player, entry);
            }
            return;
        }
        if (slot == GensLayout.MANAGE_PICKUP) {
            clickSound(player);
            if (generators.pickup(player, entry)) {
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
                player.closeInventory();
            } else {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.9f);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GeneratorManageHolder) {
            event.setCancelled(true);
        }
    }

    private void clickSound(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
}
