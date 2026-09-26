package com.coremc.core.gens;

import com.coremc.core.island.IslandGui;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Click controller for the /gens menu: cancels any interaction that
 * would touch the window, then routes clicks to the same purchase
 * flow the command uses ({@link GeneratorService#buy}). Shift-click
 * buys eight at once.
 */
public final class GensMenuListener implements Listener {

    /** How many generators one shift-click buys. */
    private static final int BULK = 8;

    private final GeneratorConfig config;
    private final GeneratorService generators;
    private final GensMenuGui gui;
    private final IslandGui islandGui;

    public GensMenuListener(final GeneratorConfig config, final GeneratorService generators,
                            final GensMenuGui gui, final IslandGui islandGui) {
        this.config = config;
        this.generators = generators;
        this.gui = gui;
        this.islandGui = islandGui;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GensMenuHolder)) {
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
        if (!(event.getView().getTopInventory().getHolder() instanceof GensMenuHolder)) {
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
        if (slot == GensLayout.MENU_CLOSE) {
            clickSound(player);
            player.closeInventory();
            return;
        }
        if (slot == GensLayout.MENU_BACK) {
            clickSound(player);
            if (islandGui != null && generators.islandOf(player) != null) {
                islandGui.openIslandMenu(player);
            } else {
                player.closeInventory();
            }
            return;
        }
        final int index = GensLayout.genIndexAt(slot);
        if (index < 0 || index >= config.all().size()) {
            return;
        }
        final GeneratorTier tier = config.all().get(index);
        clickSound(player);
        final int quantity = event.getClick().isShiftClick() ? BULK : 1;
        if (generators.buy(player, tier.id(), quantity)) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.9f);
        }
        gui.open(player);
    }

    private void clickSound(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
}
