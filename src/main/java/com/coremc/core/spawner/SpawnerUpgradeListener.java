package com.coremc.core.spawner;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Click controller for the Spawner Upgrade window. The upgrade button
 * performs the upgrade when every requirement shows a ✔ — otherwise
 * the player gets an error sound plus a message naming exactly what
 * is missing, and the checklist refreshes.
 */
public final class SpawnerUpgradeListener implements Listener {

    private final SpawnerService spawners;
    private final SpawnerUpgradeGui gui;

    public SpawnerUpgradeListener(final SpawnerService spawners, final SpawnerUpgradeGui gui) {
        this.spawners = spawners;
        this.gui = gui;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SpawnerUpgradeHolder holder)) {
            return;
        }
        event.setCancelled(true); // never let upgrade-window items move
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        final int slot = event.getSlot();
        if (slot == SpawnerUpgradeGui.SLOT_CLOSE) {
            player.closeInventory();
            clickSound(player);
            return;
        }
        if (slot == SpawnerUpgradeGui.SLOT_BUTTON) {
            clickSound(player);
            if (spawners.attemptUpgrade(player, holder.context())) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
                player.closeInventory();
            } else {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.9f);
                gui.refresh(player, holder.context());
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SpawnerUpgradeHolder) {
            event.setCancelled(true);
        }
    }

    private void clickSound(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
}
