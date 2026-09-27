package com.coremc.core.quest;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Cancels all /quests inventory movement and routes safe clicks. */
public final class QuestMenuListener implements Listener {

    private final QuestGui gui;
    private final QuestService quests;

    public QuestMenuListener(final QuestGui gui, final QuestService quests) {
        this.gui = gui;
        this.quests = quests;
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof QuestMenu menu)) {
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
        if (!(event.getView().getTopInventory().getHolder() instanceof QuestMenu)) {
            return;
        }
        for (final int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handle(final QuestMenu menu, final Player player, final int slot) {
        if (slot == QuestGui.SLOT_CLOSE) {
            player.closeInventory();
            click(player);
            return;
        }
        if (slot == QuestGui.SLOT_HELP) {
            player.closeInventory();
            player.performCommand("help quests");
            click(player);
            return;
        }
        if (slot == QuestGui.SLOT_BACK) {
            gui.openRoot(player);
            click(player);
            return;
        }
        if (menu.kind() == QuestMenu.Kind.ROOT) {
            switch (slot) {
                case QuestGui.SLOT_DAILY -> gui.openDaily(player);
                case QuestGui.SLOT_WEEKLY -> gui.openWeekly(player);
                case QuestGui.SLOT_ISLAND -> gui.openIsland(player);
                case QuestGui.SLOT_COMPLETED -> gui.openCompleted(player);
                case QuestGui.SLOT_JOURNEY -> { player.closeInventory(); player.performCommand("journey"); }
                default -> { return; }
            }
            click(player);
            return;
        }
        final QuestState.Assignment assignment = gui.assignmentAt(player, menu.kind(), slot);
        if (assignment == null) {
            return;
        }
        final QuestConfig.QuestTemplate template = quests.config().template(assignment.templateId());
        if (template == null) {
            return;
        }
        if (!assignment.completed()) {
            click(player);
            return;
        }
        final QuestConfig.Scope scope = template.scope();
        quests.claim(player, scope, assignment.templateId());
        click(player);
        switch (menu.kind()) {
            case DAILY -> gui.openDaily(player);
            case WEEKLY -> gui.openWeekly(player);
            case ISLAND -> gui.openIsland(player);
            case COMPLETED -> gui.openCompleted(player);
            case ROOT -> gui.openRoot(player);
        }
    }

    private void click(final Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
}
