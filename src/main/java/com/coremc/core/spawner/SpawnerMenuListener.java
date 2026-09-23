package com.coremc.core.spawner;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Click controller for the spawner menu GUI: cancels any interaction
 * that would touch the menu window, then routes clicks to the same
 * purchase flows the commands use ({@link SpawnerService#buy} and
 * {@link SpawnerService#luckUpgrade}).
 */
public final class SpawnerMenuListener implements Listener {

    private final SpawnerConfig config;
    private final SpawnerService spawners;
    private final SpawnerMenuGui gui;

    public SpawnerMenuListener(final SpawnerConfig config, final SpawnerService spawners,
                               final SpawnerMenuGui gui) {
        this.config = config;
        this.spawners = spawners;
        this.gui = gui;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SpawnerMenuHolder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final Inventory top = event.getView().getTopInventory();
        final Inventory clicked = event.getClickedInventory();
        if (clicked == top) {
            event.setCancelled(true);
            handleClick(event, player);
        } else if (clicked != null && event.getClick().isShiftClick()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SpawnerMenuHolder)) {
            return;
        }
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleClick(final InventoryClickEvent event, final Player player) {
        final int slot = event.getSlot();
        if (slot == SpawnerMenuLayout.CLOSE) {
            clickSound(player);
            player.closeInventory();
            return;
        }
        if (slot == SpawnerMenuLayout.LUCK) {
            clickSound(player);
            spawners.luckUpgrade(player);
            gui.open(player);
            return;
        }
        final SpawnerMob mob = SpawnerMenuLayout.mob(config,
                SpawnerMenuLayout.mobIndexAt(slot));
        if (mob == null) {
            return;
        }
        clickSound(player);
        spawners.buy(player, mob.id());
        gui.open(player);
    }

    private void clickSound(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
}
